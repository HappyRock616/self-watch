/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body

import app.selfwatch.activity.Sports
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.TimeRange
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Which layers of one overlay chart the user switched off, kept in settings under [key] as a comma list
 * of hidden layer ids. No stored value means [defaultHidden] (every layer on unless a chart says otherwise).
 */
class LayerChoice(
    /** Settings key. */
    val key: String,
    /** Layers that can be switched, in chip order. */
    val toggleable: List<String>,
    /** Hidden until the user turns them on. */
    val defaultHidden: Set<String> = emptySet(),
) {
    /** The hidden ids stored as [stored] (null: never chosen). Unknown ids are dropped. */
    fun decode(stored: String?): Set<String> {
        if (stored == null) return defaultHidden
        return stored.split(',').map { it.trim() }.filter { it in toggleable }.toSet()
    }

    /** [hidden] as stored (in chip order, so equal sets store equal text). */
    fun encode(hidden: Set<String>): String = toggleable.filter { it in hidden }.joinToString(",")

    /** The hidden layer ids. */
    suspend fun load(db: SelfWatchDb): Set<String> = decode(db.settings.get(key))

    /** Saves the hidden layer ids. */
    suspend fun save(db: SelfWatchDb, hidden: Set<String>) {
        db.settings.set(key, encode(hidden))
    }
}

/**
 * O3: weekly protein per kg of body weight (with a 1.6 g/kg reference line) and strength sessions per
 * week, drawn with the body fat or waist trend. One bucket per week from [firstDay]; [MISSING] marks a
 * week with too little logged.
 *
 * @property proteinPerKgX10 average daily protein ÷ body weight, g/kg ×10.
 * @property strength strength sessions that week.
 */
class ProteinOverlay(val firstDay: Day, val proteinPerKgX10: IntArray, val strength: IntArray) {
    /** Number of weeks. */
    val weeks: Int get() = proteinPerKgX10.size

    /** The first day of week [i]. */
    fun weekStart(i: Int): Day = firstDay.plusDays(i * WEEK)

    /** True when any week has protein or a strength session. */
    val hasData: Boolean get() = proteinPerKgX10.any { it != MISSING } || strength.any { it > 0 }

    /** Constants and the pure assembly. */
    companion object {
        /** A week without enough data. */
        const val MISSING: Int = Int.MIN_VALUE

        /** Weeks shown (about six months: body composition moves over weeks to months). */
        const val WEEKS: Int = 26

        /** The reference line, g/kg ×10 (gains level off around 1.6 g/kg). */
        const val REFERENCE_X10: Int = 16

        /** Days with protein logged a week needs before it shows a value. */
        const val MIN_DAYS: Int = 3

        /** Settings for the chart's chips. */
        val LAYERS: LayerChoice = LayerChoice("chart.body_comp.layers", listOf(BodyCompLayers.PROTEIN, BodyCompLayers.STRENGTH))

        /**
         * Builds [weeks] weeks from [first]: [proteinX10ByDay] protein logged per day (tenths of a gram),
         * [weights] the weigh-ins (any order; the latest on or before each week's end is used, else the
         * first after), [strengthDays] the days of strength sessions (one entry per session).
         */
        fun build(first: Day, weeks: Int, proteinX10ByDay: Map<Day, Long>, weights: List<BodyMeasure>, zone: ZoneId, strengthDays: List<Day>): ProteinOverlay {
            val sorted = weights.sortedBy { it.tSec }
            val protein = IntArray(weeks) { w ->
                val start = first.plusDays(w * WEEK)
                val end = start.plusDays(WEEK - 1)
                val logged = (0 until WEEK).mapNotNull { proteinX10ByDay[start.plusDays(it)] }
                if (logged.size < MIN_DAYS) return@IntArray MISSING
                val endSec = end.plusDays(1).startSec(zone)
                val kgX100 = (sorted.lastOrNull { it.tSec < endSec } ?: sorted.firstOrNull())?.valueX100
                if (kgX100 == null || kgX100 <= 0) return@IntArray MISSING
                val gramsPerDay = logged.average() / TENTHS
                (gramsPerDay / (kgX100 / 100.0) * TENTHS).roundToInt()
            }
            val strength = IntArray(weeks) { w ->
                val start = first.plusDays(w * WEEK)
                val end = start.plusDays(WEEK - 1)
                strengthDays.count { it in DayRange(start, end) }
            }
            return ProteinOverlay(first, protein, strength)
        }

        /** True for a strength session (FIT sport "training" or sub-sport "strength training"). */
        fun isStrength(w: Workout): Boolean = w.sport == Sports.TRAINING || w.subSport == STRENGTH_SUB_SPORT

        /** Loads the last [WEEKS] weeks up to [clock]'s today. */
        suspend fun load(db: SelfWatchDb, clock: Clock): ProteinOverlay {
            val today = clock.today()
            val zone = clock.zone()
            val first = today.plusDays(1 - WEEKS * WEEK)
            val range = DayRange(first, today)
            val protein = HashMap<Day, Long>()
            for (e in db.diary.range(range)) {
                val p = e.nutrients.proteinX10 ?: continue
                protein[e.day] = (protein[e.day] ?: 0L) + p
            }
            val weights = db.body.series(BodyKind.WEIGHT, DayRange(first.plusDays(-LOOKBACK_DAYS), today), zone)
            val strength = db.workouts.forExport(range, zone).filter { isStrength(it) }.map { Day.of(it.startSec, zone) }
            return build(first, WEEKS, protein, weights, zone, strength)
        }

        private const val WEEK = 7
        private const val TENTHS = 10.0
        private const val LOOKBACK_DAYS = 90

        // FIT profile `sub_sport` value for strength training (public FIT documentation).
        private const val STRENGTH_SUB_SPORT = 20
    }
}

/** Layer ids of the body-composition chart (O3). */
object BodyCompLayers {
    /** The measurement itself (body fat or waist), always shown. */
    const val MEASURE: String = "measure"

    /** Weekly protein g/kg with the 1.6 line. */
    const val PROTEIN: String = "protein"

    /** Strength sessions per week. */
    const val STRENGTH: String = "strength"
}

/**
 * O6: weekly intensity minutes (vigorous count double) against the 4-week rolling resting heart rate,
 * over about six months (resting heart rate answers to training over 4+ weeks).
 *
 * @property intensityMin intensity minutes per week from [firstDay] (-1 for a week without activity data).
 * @property restDays the days that have a rolling resting heart rate, oldest first.
 * @property restBpm the 4-week rolling resting heart rate on each of [restDays].
 */
class HeartTrendOverlay(val firstDay: Day, val intensityMin: IntArray, val restDays: List<Day>, val restBpm: IntArray) {
    /** True when there is anything to draw. */
    val hasData: Boolean get() = intensityMin.any { it >= 0 } || restBpm.isNotEmpty()

    /** Constants and the pure assembly. */
    companion object {
        /** Weeks shown. */
        const val WEEKS: Int = 26

        /** Layer ids. */
        const val INTENSITY: String = "intensity"

        /** The rolling resting heart rate. */
        const val RESTING: String = "resting_hr"

        /** Settings for the chart's chips. */
        val LAYERS: LayerChoice = LayerChoice("chart.heart_trend.layers", listOf(INTENSITY, RESTING))

        /** Intensity of a moderate minute and of a vigorous one ([app.selfwatch.model.health.ActivityMinute.intensity]). */
        private const val MODERATE = 2
        private const val VIGOROUS = 3

        /** Intensity minutes of [intensities] (one per minute): moderate count once, vigorous twice. */
        fun intensityMinutes(intensities: List<Int>): Int =
            intensities.sumOf { if (it >= VIGOROUS) 2 else if (it == MODERATE) 1 else 0 }

        /**
         * Builds the overlay from [first] (the first week's first day): [weekly] intensity minutes per
         * week, and [restByDay] daily resting heart rate (reaching [RestingHrMath.WINDOW_DAYS] − 1 days
         * before [first], for the first rolling values) up to [last].
         */
        fun build(first: Day, last: Day, weekly: IntArray, restByDay: Map<Day, Int>): HeartTrendOverlay {
            val days = DayRange(first, last).days()
            val outDays = ArrayList<Day>()
            val outBpm = ArrayList<Int>()
            for (d in days) {
                val window = (0 until RestingHrMath.WINDOW_DAYS).mapNotNull { restByDay[d.plusDays(-it)] }
                if (window.size >= RestingHrMath.WINDOW_MIN && restByDay.containsKey(d)) {
                    outDays += d
                    outBpm += window.average().roundToInt()
                }
            }
            return HeartTrendOverlay(first, weekly, outDays, outBpm.toIntArray())
        }

        /** Loads the last [WEEKS] weeks up to [clock]'s today. */
        suspend fun load(db: SelfWatchDb, clock: Clock): HeartTrendOverlay {
            val today = clock.today()
            val zone = clock.zone()
            val first = today.plusDays(1 - WEEKS * WEEK)
            val weekly = IntArray(WEEKS) { -1 }
            for (w in 0 until WEEKS) {
                val start = first.plusDays(w * WEEK)
                val minutes = db.activity.minutes(TimeRange(start.startSec(zone), start.plusDays(WEEK).startSec(zone)))
                if (minutes.isNotEmpty()) weekly[w] = intensityMinutes(minutes.map { it.intensity })
            }
            val rest = BodyTrendLoader(db, clock).restingByDay(DayRange(first.plusDays(1 - RestingHrMath.WINDOW_DAYS), today))
            return build(first, today, weekly, rest)
        }

        private const val WEEK = 7
    }
}

/**
 * O7: skin temperature change, resting heart rate and breathing, each as its distance from the user's own
 * baseline (the 28 nights before each night) in standard deviations ×10, with the usual band (±1).
 *
 * @property days the nights shown, oldest first.
 * @property z per signal, the deviation in standard deviations ×10 ([BodySignalsMath.MISSING] for none).
 * @property deviation per signal, the deviation in the signal's stored scale (for the read-out).
 */
class SignalsOverlay(val days: List<Day>, val z: Map<SignalKind, IntArray>, val deviation: Map<SignalKind, IntArray>) {
    /** True when any signal has a value. */
    val hasData: Boolean get() = z.values.any { a -> a.any { it != BodySignalsMath.MISSING } }

    /** Constants and the pure assembly. */
    companion object {
        /** Nights shown. */
        const val NIGHTS: Int = 60

        /** The usual band (±1 standard deviation). */
        const val BAND: String = "band"

        /** Layer id of each signal. */
        fun layerId(kind: SignalKind): String = when (kind) {
            SignalKind.SKIN_TEMP -> "skin_temp"
            SignalKind.RESTING_HR -> "resting_hr"
            SignalKind.RESPIRATION -> "respiration"
        }

        /** Settings for the chart's chips. */
        val LAYERS: LayerChoice = LayerChoice("chart.body_signals.layers", SignalKind.entries.map { layerId(it) } + BAND)

        /** Builds the overlay for [days] from [values] covering [allDays] (which reach a baseline further back). */
        fun build(days: List<Day>, allDays: List<Day>, values: Map<SignalKind, Map<Day, Int>>): SignalsOverlay {
            val z = SignalKind.entries.associateWith { BodySignalsMath.zSeries(days, values[it].orEmpty(), allDays) }
            val dev = SignalKind.entries.associateWith { BodySignalsMath.deviationSeries(days, values[it].orEmpty(), allDays) }
            return SignalsOverlay(days, z, dev)
        }

        /** Loads the last [NIGHTS] nights up to [clock]'s today. */
        suspend fun load(db: SelfWatchDb, clock: Clock): SignalsOverlay {
            val today = clock.today()
            val all = DayRange(today.plusDays(1 - NIGHTS - BodySignalsMath.BASELINE_NIGHTS), today)
            val values = BodyTrendLoader(db, clock).signalNights(all)
            val allDays = all.days()
            return build(allDays.takeLast(NIGHTS), allDays, values)
        }
    }
}
