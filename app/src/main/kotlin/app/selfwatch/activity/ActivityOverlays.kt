/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import app.selfwatch.cards.ChartChoice
import app.selfwatch.cards.ChartId
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.model.health.DaySummary
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.TimeRange
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Shared helpers of the Activity overlays. */
object OverlayMath {
    /** A day or week without data. */
    const val MISSING: Int = -1

    /** Whole days from [a] to [b]. */
    fun daysBetween(a: Day, b: Day): Int = ChronoUnit.DAYS.between(a.toLocalDate(), b.toLocalDate()).toInt()

    /**
     * The mean of the present values among the [window] values ending at index [end] (inclusive), rounded,
     * or [MISSING] when fewer than [minCount] are present.
     */
    fun rollingMean(values: IntArray, end: Int, window: Int, minCount: Int): Int {
        var sum = 0L
        var n = 0
        for (i in maxOf(0, end - window + 1)..minOf(end, values.size - 1)) {
            if (values[i] == MISSING) continue
            sum += values[i]
            n++
        }
        return if (n == 0 || n < minCount) MISSING else (sum.toDouble() / n).roundToInt()
    }
}

/**
 * Overlay O4, training and recovery: per day from [firstDay], the training load (heart-rate-zone
 * minutes) and the intensity minutes as bars, and the overnight HRV and resting heart rate as trailing
 * [ROLLING_DAYS]-day averages (each morning's value follows the days before it), with the user's usual
 * HRV band: the mean ± half a standard deviation of the nightly HRV over the [BASELINE_DAYS] nights before
 * each day (at least [BASELINE_MIN] of them), the "smallest worthwhile change" band of the baseline method.
 * [OverlayMath.MISSING] marks no data.
 */
class TrainingOverlay(
    val firstDay: Day,
    val load: IntArray,
    val intensityMin: IntArray,
    val hrv7: IntArray,
    val hrvLow: IntArray,
    val hrvHigh: IntArray,
    val restingHr7: IntArray,
) {
    /** Number of days. */
    val size: Int get() = load.size

    /** True when any series has data. */
    val hasData: Boolean
        get() = load.any { it > 0 } || listOf(intensityMin, hrv7, restingHr7).any { a -> a.any { it != OverlayMath.MISSING } }

    /** Constants and the pure assembly. */
    companion object {
        /** Days shown (12 weeks). */
        const val DAYS: Int = 84

        /** Trailing average length for HRV and resting heart rate. */
        const val ROLLING_DAYS: Int = 7

        /** Nights a trailing average needs. */
        const val ROLLING_MIN: Int = 4

        /** Nights the usual-HRV band looks back over. */
        const val BASELINE_DAYS: Int = 60

        /** Nights the band needs. */
        const val BASELINE_MIN: Int = 14

        /** The band is the mean ± this many standard deviations. */
        const val BAND_SD: Double = 0.5

        /**
         * Builds the overlay for [first]..[last] from [dailyLoad], [dailyIntensity] (days without minutes
         * are missing) and [summaries] reaching [BASELINE_DAYS] days before [first].
         */
        fun build(first: Day, last: Day, dailyLoad: Map<Day, Int>, dailyIntensity: Map<Day, Int>, summaries: List<DaySummary>): TrainingOverlay {
            val n = (OverlayMath.daysBetween(first, last) + 1).coerceAtLeast(0)
            val lead = BASELINE_DAYS
            val hrv = IntArray(n + lead) { OverlayMath.MISSING }
            val rhr = IntArray(n + lead) { OverlayMath.MISSING }
            for (s in summaries) {
                val i = OverlayMath.daysBetween(first, s.day) + lead
                if (i !in hrv.indices) continue
                s.hrvAvgMs?.takeIf { it > 0 }?.let { hrv[i] = it }
                s.restingHr?.takeIf { it > 0 }?.let { rhr[i] = it }
            }
            val low = IntArray(n) { OverlayMath.MISSING }
            val high = IntArray(n) { OverlayMath.MISSING }
            for (d in 0 until n) {
                val nights = (d until d + lead).map { hrv[it] }.filter { it != OverlayMath.MISSING }
                if (nights.size < BASELINE_MIN) continue
                val mean = nights.average()
                val sd = sqrt(nights.sumOf { (it - mean) * (it - mean) } / (nights.size - 1))
                low[d] = (mean - BAND_SD * sd).roundToInt()
                high[d] = (mean + BAND_SD * sd).roundToInt()
            }
            val days = (0 until n).map { first.plusDays(it) }
            return TrainingOverlay(
                first,
                IntArray(n) { dailyLoad[days[it]] ?: 0 },
                IntArray(n) { dailyIntensity[days[it]] ?: OverlayMath.MISSING },
                IntArray(n) { OverlayMath.rollingMean(hrv, it + lead, ROLLING_DAYS, ROLLING_MIN) },
                low,
                high,
                IntArray(n) { OverlayMath.rollingMean(rhr, it + lead, ROLLING_DAYS, ROLLING_MIN) },
            )
        }

        /** Loads the last [DAYS] days up to today. */
        suspend fun load(db: SelfWatchDb, clock: Clock): TrainingOverlay {
            val today = clock.today()
            val first = today.plusDays(-(DAYS - 1))
            val loader = ActivityStatsLoader(db, clock)
            val range = DayRange(first, today)
            val load = loader.dailyLoad(range, loader.maxHr().bpm)
            val zone = clock.zone()
            val minutes = db.activity.minutes(TimeRange(first.startSec(zone), today.plusDays(1).startSec(zone)))
            val intensity = IntensityMath.byDay(minutes, zone).mapValues { it.value.intensityMin }
            val summaries = db.summaries.days(DayRange(first.plusDays(-BASELINE_DAYS), today))
            return build(first, today, load, intensity, summaries)
        }
    }
}

/**
 * Overlay O5, exercise and sleep by week: per Monday-to-Sunday week from [firstWeek], the active minutes
 * (moderate and vigorous, from the day summaries) and the average night's sleep in minutes. Weeks
 * without data (sleep: fewer than [MIN_NIGHTS] nights) are [OverlayMath.MISSING]. Weekly on purpose:
 * nightly overlays mostly show noise (WP-15, O5).
 */
class ExerciseSleepOverlay(val firstWeek: Day, val exerciseMin: IntArray, val sleepMin: IntArray) {
    /** Number of weeks. */
    val size: Int get() = exerciseMin.size

    /** True when any week has data. */
    val hasData: Boolean get() = (exerciseMin.asList() + sleepMin.asList()).any { it != OverlayMath.MISSING }

    /** Constants and the pure assembly. */
    companion object {
        /** Weeks shown. */
        const val WEEKS: Int = 26

        /** Nights a week needs for its sleep average. */
        const val MIN_NIGHTS: Int = 3

        /** Builds [weeks] weeks from the Monday [firstWeek] from [summaries]. */
        fun build(firstWeek: Day, weeks: Int, summaries: List<DaySummary>): ExerciseSleepOverlay {
            val ex = LongArray(weeks)
            val exDays = IntArray(weeks)
            val sl = LongArray(weeks)
            val slDays = IntArray(weeks)
            for (s in summaries) {
                val d = OverlayMath.daysBetween(firstWeek, s.day)
                if (d < 0) continue
                val w = d / ActivityWeek.DAYS
                if (w >= weeks) continue
                s.activeMin?.let {
                    ex[w] += it
                    exDays[w]++
                }
                s.sleepMin?.takeIf { it > 0 }?.let {
                    sl[w] += it
                    slDays[w]++
                }
            }
            return ExerciseSleepOverlay(
                firstWeek,
                IntArray(weeks) { if (exDays[it] == 0) OverlayMath.MISSING else ex[it].toInt() },
                IntArray(weeks) { if (slDays[it] < MIN_NIGHTS) OverlayMath.MISSING else (sl[it].toDouble() / slDays[it]).roundToInt() },
            )
        }

        /** Loads the last [WEEKS] weeks up to this one. */
        suspend fun load(db: SelfWatchDb, clock: Clock): ExerciseSleepOverlay {
            val today = clock.today()
            val first = ActivityWeek.start(today).plusDays(-ActivityWeek.DAYS * (WEEKS - 1))
            return build(first, WEEKS, db.summaries.days(DayRange(first, today)))
        }
    }
}

/** The two Activity overlays' layer ids and their stored choices. */
object OverlayLayers {
    /** O4 layers. */
    const val LOAD: String = "load"
    const val INTENSITY: String = "intensity"
    const val HRV: String = "hrv"
    const val HRV_BAND: String = "hrv_band"
    const val RESTING_HR: String = "resting_hr"

    /** O5 layers. */
    const val EXERCISE: String = "exercise"
    const val SLEEP: String = "sleep"

    /** O4, training and recovery. */
    val TRAINING: ChartChoice = ChartChoice(ChartId.TRAINING)

    /** O5, exercise and sleep. */
    val EXERCISE_SLEEP: ChartChoice = ChartChoice(ChartId.EXERCISE_SLEEP)
}
