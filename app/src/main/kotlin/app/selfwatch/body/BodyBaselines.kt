/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body

import app.selfwatch.cards.MainGoal
import app.selfwatch.model.time.Day
import app.selfwatch.ui.format.Units
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Where a reading sits against a range: under it, inside it or over it. */
enum class RangeStatus { BELOW, WITHIN, ABOVE }

/** The mean and spread of a person's own readings over a window. */
data class Baseline(val mean: Double, val sd: Double, val count: Int) {
    /** Helpers. */
    companion object {
        /** The baseline of [values], or null with fewer than [minCount] of them. */
        fun of(values: List<Int>, minCount: Int = 1): Baseline? {
            if (values.isEmpty() || values.size < minCount) return null
            val mean = values.sumOf { it.toLong() }.toDouble() / values.size
            val variance = if (values.size < 2) 0.0 else values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
            return Baseline(mean, sqrt(variance), values.size)
        }
    }
}

/**
 * Overnight HRV against the user's own normal band (the baseline method: a 7-day average read against
 * a band around the longer-term mean). No population targets.
 *
 * @property sevenDayMs the average of the last [RECENT_DAYS] nights that have a value.
 * @property lowMs bottom of the usual band.
 * @property highMs top of the usual band.
 * @property rolling the 7-day average for each of the last [SPARK_DAYS] days (days without enough nights left out).
 */
data class HrvBaseline(val sevenDayMs: Int, val lowMs: Int, val highMs: Int, val status: RangeStatus, val rolling: List<Int>)

/** HRV card data: the result once there is enough history, else how many nights are in so far. */
data class HrvInfo(val result: HrvBaseline?, val nights: Int, val needed: Int, val watch: WatchHrv? = null)

/**
 * The watch's own HRV status summary (FIT `hrv_status_summary`), as Garmin shows it: last night's and
 * the 7-day average, the balanced baseline and the status (1 poor, 2 low, 3 unbalanced, 4 balanced).
 */
data class WatchHrv(val nightMs: Int?, val weekMs: Int?, val lowMs: Int?, val highMs: Int?, val status: Int?)

/**
 * Resting heart rate as a 4-week rolling average against the user's earlier level.
 *
 * @property currentBpm the average of the last [RestingHrMath.WINDOW_DAYS] days.
 * @property baselineBpm the average of the window before that (null until there is enough of it).
 * @property deltaBpm current minus baseline (null without a baseline).
 * @property status lower, steady or higher (null without a baseline).
 * @property rolling the 4-week rolling average for each day of the last 90 (oldest first).
 */
data class RestingHrTrend(
    val currentBpm: Int,
    val baselineBpm: Int?,
    val deltaBpm: Int?,
    val status: RangeStatus?,
    val rolling: List<Int>,
)

/** One body signal on the latest night against the user's own baseline. */
data class SignalReading(
    /** Which signal. */
    val kind: SignalKind,
    /** Last night's value (stored scale of the kind). */
    val value: Int,
    /** Last night minus the baseline mean (stored scale), rounded. */
    val deviation: Int,
    /** True when the signal is clearly above the user's usual. */
    val up: Boolean,
    /** Spread of the baseline nights (stored scale); 0 when not known. */
    val sd: Double = 0.0,
)

/** The body signals read against baseline. [scale] divides the stored value for display. */
enum class SignalKind(
    /** Stored value ÷ scale = display value. */
    val scale: Int,
    /** Smallest rise that counts as "up", stored scale. */
    val minRise: Int,
) {
    /** Skin temperature change, °C ×100 (rises of 0.3 °C or more count). */
    SKIN_TEMP(100, 30),

    /** Resting heart rate, bpm (3 bpm or more). */
    RESTING_HR(1, 3),

    /** Breathing rate while asleep, breaths per minute ×10 (1 breath a minute or more). */
    RESPIRATION(10, 10),
}

/**
 * Last night's body signals. [flagged] when two or three rise together (never diagnostic: the card
 * only says "take it easy").
 */
data class BodySignals(val day: Day, val readings: List<SignalReading>, val flagged: Boolean) {
    /** How many signals are up. */
    val upCount: Int get() = readings.count { it.up }
}

/** Body signals card data: the reading once there is a baseline, else the nights so far, and the display units. */
data class SignalsInfo(val result: BodySignals?, val nights: Int, val needed: Int, val units: Units = Units.METRIC)

/** Pure arithmetic of the HRV baseline. */
object HrvMath {
    /** Nights in the short average. */
    const val RECENT_DAYS: Int = 7

    /** Nights with a value the short average needs. */
    const val RECENT_MIN: Int = 3

    /** Days the usual band is learned from. */
    const val BASELINE_DAYS: Int = 60

    /** Nights with a value before the band means anything (about two weeks). */
    const val BASELINE_MIN: Int = 14

    /** Days in the sparkline. */
    const val SPARK_DAYS: Int = 30

    /** Half-width of the usual band, in standard deviations of the nightly values. */
    private const val BAND_SD = 0.5

    /** Smallest half-width (ms), so a very steady history still has a band. */
    private const val MIN_HALF_MS = 1.0

    /**
     * Evaluates [daily] HRV (ms per day, oldest first, the last entry is today; null for no value).
     * Only the last [BASELINE_DAYS] are used.
     */
    fun evaluate(daily: List<Int?>): HrvInfo {
        val window = daily.takeLast(BASELINE_DAYS)
        val values = window.filterNotNull()
        val recent = window.takeLast(RECENT_DAYS).filterNotNull()
        if (values.size < BASELINE_MIN || recent.size < RECENT_MIN) return HrvInfo(null, values.size, BASELINE_MIN)
        val base = Baseline.of(values) ?: return HrvInfo(null, values.size, BASELINE_MIN)
        val half = maxOf(base.sd * BAND_SD, MIN_HALF_MS)
        val avg = recent.average()
        val low = base.mean - half
        val high = base.mean + half
        val status = when {
            avg < low -> RangeStatus.BELOW
            avg > high -> RangeStatus.ABOVE
            else -> RangeStatus.WITHIN
        }
        val rolling = window.indices.toList().takeLast(SPARK_DAYS).mapNotNull { end ->
            val part = window.subList(maxOf(0, end - RECENT_DAYS + 1), end + 1).filterNotNull()
            if (part.size >= RECENT_MIN) part.average().roundToInt() else null
        }
        return HrvInfo(HrvBaseline(avg.roundToInt(), low.roundToInt(), high.roundToInt(), status, rolling), values.size, BASELINE_MIN)
    }
}

/** Pure arithmetic of the resting heart-rate trend. */
object RestingHrMath {
    /** Length of the rolling window (4 weeks). */
    const val WINDOW_DAYS: Int = 28

    /** Days with a value a window needs. */
    const val WINDOW_MIN: Int = 7

    /** Days with a value the baseline window needs. */
    const val BASELINE_MIN: Int = 14

    /** Days of history read (the current window, the baseline window and a margin). */
    const val HISTORY_DAYS: Int = 120

    /** Days in the sparkline. */
    const val SPARK_DAYS: Int = 90

    /** A change this small (bpm) reads as steady. */
    const val STEADY_BPM: Int = 2

    private fun mean(values: List<Int?>, min: Int): Double? {
        val v = values.filterNotNull()
        return if (v.size >= min) v.average() else null
    }

    /**
     * Evaluates [daily] resting heart rate (bpm per day, oldest first, the last is today; null for no
     * value): the last [WINDOW_DAYS] against the [WINDOW_DAYS] before them (when there is a baseline),
     * or null when the current window has fewer than [WINDOW_MIN] days.
     */
    fun evaluate(daily: List<Int?>): RestingHrTrend? {
        val n = daily.size
        val current = mean(daily.subList(maxOf(0, n - WINDOW_DAYS), n), WINDOW_MIN) ?: return null
        val baseFrom = maxOf(0, n - 2 * WINDOW_DAYS)
        val baseTo = maxOf(0, n - WINDOW_DAYS)
        val baseline = mean(daily.subList(baseFrom, baseTo), BASELINE_MIN)
        val cur = current.roundToInt()
        val base = baseline?.roundToInt()
        val delta = base?.let { cur - it }
        val status = delta?.let {
            when {
                abs(it) <= STEADY_BPM -> RangeStatus.WITHIN
                it < 0 -> RangeStatus.BELOW
                else -> RangeStatus.ABOVE
            }
        }
        val rolling = (maxOf(0, n - SPARK_DAYS) until n).mapNotNull { end ->
            mean(daily.subList(maxOf(0, end - WINDOW_DAYS + 1), end + 1), WINDOW_MIN)?.roundToInt()
        }
        return RestingHrTrend(cur, base, delta, status, rolling)
    }
}

/** Pure arithmetic of the body signals (skin temperature, resting HR and breathing against baseline). */
object BodySignalsMath {
    /** Nights before the latest that form the baseline. */
    const val BASELINE_NIGHTS: Int = 28

    /** Nights with a value a signal's baseline needs. */
    const val BASELINE_MIN: Int = 7

    /** Signals that must rise together for the card to say "take it easy". */
    const val FLAG_COUNT: Int = 2

    /** A rise counts when it is at least this many standard deviations (and [SignalKind.minRise]). */
    private const val RISE_SD = 1.0

    /** The deviation of [value] from [base] (stored scale) and whether it counts as up for [kind]. */
    fun reading(kind: SignalKind, value: Int, base: Baseline): SignalReading {
        val dev = value - base.mean
        val up = dev >= kind.minRise && dev >= RISE_SD * base.sd
        return SignalReading(kind, value, dev.roundToInt(), up, base.sd)
    }

    /**
     * Last night's reading: [nights] are the night days (oldest first, the last is the night read), and
     * [values] each signal's value per night. A signal without a value on the last night or without
     * [BASELINE_MIN] earlier nights is left out. Null when no signal can be read.
     */
    fun evaluate(nights: List<Day>, values: Map<SignalKind, Map<Day, Int>>): BodySignals? {
        val last = nights.lastOrNull() ?: return null
        val before = nights.dropLast(1).takeLast(BASELINE_NIGHTS)
        val readings = SignalKind.entries.mapNotNull { kind ->
            val byDay = values[kind] ?: return@mapNotNull null
            val v = byDay[last] ?: return@mapNotNull null
            val base = Baseline.of(before.mapNotNull { byDay[it] }, BASELINE_MIN) ?: return@mapNotNull null
            reading(kind, v, base)
        }
        if (readings.isEmpty()) return null
        return BodySignals(last, readings, readings.count { it.up } >= FLAG_COUNT)
    }

    /**
     * For the body-signals chart: each night of [days] (oldest first) as the deviation of [byDay] from the
     * baseline of the [BASELINE_NIGHTS] nights before it, in standard deviations ×10; [MISSING] where the
     * night has no value or too little history.
     */
    fun zSeries(days: List<Day>, byDay: Map<Day, Int>, allDays: List<Day>): IntArray =
        IntArray(days.size) { i ->
            val d = days[i]
            val v = byDay[d] ?: return@IntArray MISSING
            val idx = allDays.indexOf(d)
            if (idx < 0) return@IntArray MISSING
            val base = Baseline.of(allDays.subList(maxOf(0, idx - BASELINE_NIGHTS), idx).mapNotNull { byDay[it] }, BASELINE_MIN)
                ?: return@IntArray MISSING
            val sd = maxOf(base.sd, MIN_SD)
            ((v - base.mean) / sd * Z_SCALE).roundToInt().coerceIn(-Z_CAP, Z_CAP)
        }

    /**
     * For the chart read-out: each night's deviation (stored scale) from the baseline before it, or
     * [MISSING].
     */
    fun deviationSeries(days: List<Day>, byDay: Map<Day, Int>, allDays: List<Day>): IntArray =
        IntArray(days.size) { i ->
            val d = days[i]
            val v = byDay[d] ?: return@IntArray MISSING
            val idx = allDays.indexOf(d)
            val base = Baseline.of(allDays.subList(maxOf(0, idx - BASELINE_NIGHTS), maxOf(0, idx)).mapNotNull { byDay[it] }, BASELINE_MIN)
                ?: return@IntArray MISSING
            (v - base.mean).roundToInt()
        }

    /** No value. */
    const val MISSING: Int = Int.MIN_VALUE

    /** Standard deviations are shown ×10 on the chart. */
    const val Z_SCALE: Int = 10

    private const val Z_CAP = 40
    private const val MIN_SD = 0.5
}

/** The weight card's goal-based band: a weekly rate (loss or gain), a ±3% band, or a note on unplanned loss. */
data class WeightBand(
    /** What kind of band. */
    val kind: Kind,
    /** Where the trend sits against it; null when there are not yet enough weigh-ins to tell. */
    val status: RangeStatus?,
    /** The trend's change per week (hundredths of a kg); null when not known. */
    val ratePerWeekX100: Int?,
    /** Band bottom: kg/week ×100 for rates, kg ×100 for [Kind.MAINTAIN]. */
    val lowX100: Int,
    /** Band top, same units as [lowX100]. */
    val highX100: Int,
    /** The 90-day change in percent ×10 (for [Kind.UNPLANNED_LOSS]). */
    val changePctX10: Int? = null,
) {
    /** The bands. */
    enum class Kind {
        /** Lose weight: 0.5–1 kg a week. */
        LOSS_RATE,

        /** Weight management: within ±3% of the goal (or the starting trend). */
        MAINTAIN,

        /** Build muscle: +0.25–0.5% of body weight a week. */
        GAIN_RATE,

        /** Stay active: the trend fell 5% or more in 90 days (worth a look if it was not planned). */
        UNPLANNED_LOSS,
    }
}

/** The weight card's band per main goal, from the smoothed trend. */
object WeightPlan {
    /** Loss pace, kg ×100 per week (0.5–1 kg). */
    const val LOSS_MIN_X100: Int = 50
    const val LOSS_MAX_X100: Int = 100

    /** Maintenance band, percent either side. */
    const val MAINTAIN_PCT: Double = 3.0

    /** Gain pace, percent of body weight per week. */
    const val GAIN_MIN_PCT: Double = 0.25
    const val GAIN_MAX_PCT: Double = 0.5

    /** A 90-day fall of this many percent (or more) gets the unplanned-loss note. */
    const val UNPLANNED_PCT: Double = 5.0

    /** Weeks the weekly rate is measured over (one week of trend is too noisy). */
    const val RATE_WEEKS: Int = 4

    /**
     * The band for [goal]: [trendX100] the trend now, [ratePerWeekX100] its change per week (null with too
     * few weigh-ins), [startTrendX100] the trend at the oldest weigh-in of the last 90 days, [goalX100] the
     * weight goal. Null for goals without a weight band.
     */
    fun band(goal: MainGoal, trendX100: Int, ratePerWeekX100: Int?, startTrendX100: Int?, goalX100: Int?): WeightBand? = when (goal) {
        MainGoal.WEIGHT_LOSS -> {
            val status = ratePerWeekX100?.let {
                when {
                    -it < LOSS_MIN_X100 -> RangeStatus.BELOW
                    -it > LOSS_MAX_X100 -> RangeStatus.ABOVE
                    else -> RangeStatus.WITHIN
                }
            }
            WeightBand(WeightBand.Kind.LOSS_RATE, status, ratePerWeekX100, -LOSS_MAX_X100, -LOSS_MIN_X100)
        }
        MainGoal.WEIGHT_MANAGEMENT -> {
            val ref = goalX100 ?: startTrendX100 ?: trendX100
            val low = (ref * (1 - MAINTAIN_PCT / 100)).roundToInt()
            val high = (ref * (1 + MAINTAIN_PCT / 100)).roundToInt()
            val status = when {
                trendX100 < low -> RangeStatus.BELOW
                trendX100 > high -> RangeStatus.ABOVE
                else -> RangeStatus.WITHIN
            }
            WeightBand(WeightBand.Kind.MAINTAIN, status, ratePerWeekX100, low, high)
        }
        MainGoal.BUILD_MUSCLE -> {
            val low = (trendX100 * GAIN_MIN_PCT / 100).roundToInt()
            val high = (trendX100 * GAIN_MAX_PCT / 100).roundToInt()
            val status = ratePerWeekX100?.let {
                when {
                    it < low -> RangeStatus.BELOW
                    it > high -> RangeStatus.ABOVE
                    else -> RangeStatus.WITHIN
                }
            }
            WeightBand(WeightBand.Kind.GAIN_RATE, status, ratePerWeekX100, low, high)
        }
        MainGoal.ACTIVE_AGING -> {
            val start = startTrendX100
            val pctX10 = if (start == null || start <= 0) null else ((trendX100 - start) * 1000.0 / start).roundToInt()
            if (pctX10 != null && pctX10 <= -(UNPLANNED_PCT * 10).roundToInt()) {
                WeightBand(WeightBand.Kind.UNPLANNED_LOSS, RangeStatus.BELOW, ratePerWeekX100, 0, 0, pctX10)
            } else {
                null
            }
        }
        else -> null
    }
}

/** Waist-to-height bands (NICE): under 0.5, 0.5–0.59, 0.6 and over. */
enum class WaistBand { UNDER, RAISED, HIGH }

/** Small pure helpers for the Body cards. */
object BodyCardMath {
    /** Waist ÷ height ×100 (52 = 0.52), from a waist in cm ×100 and a height in cm; null without a height. */
    fun waistToHeightX100(waistCmX100: Int, heightCm: Int?): Int? {
        if (heightCm == null || heightCm <= 0) return null
        return (waistCmX100.toDouble() / heightCm).roundToInt()
    }

    /** The band of a waist-to-height ratio ×100. */
    fun waistBand(ratioX100: Int): WaistBand = when {
        ratioX100 < RAISED_FROM -> WaistBand.UNDER
        ratioX100 < HIGH_FROM -> WaistBand.RAISED
        else -> WaistBand.HIGH
    }

    /** 0.50 as ×100. */
    const val RAISED_FROM: Int = 50

    /** 0.60 as ×100. */
    const val HIGH_FROM: Int = 60

    /** Sleep guide, minutes: 7–9 hours. */
    const val SLEEP_MIN: Int = 7 * 60
    const val SLEEP_MAX: Int = 9 * 60

    /** Where [minutes] of sleep sit against 7–9 h. */
    fun sleepStatus(minutes: Int): RangeStatus = when {
        minutes < SLEEP_MIN -> RangeStatus.BELOW
        minutes > SLEEP_MAX -> RangeStatus.ABOVE
        else -> RangeStatus.WITHIN
    }

    /** Days with both food and energy out the 7-day balance needs. */
    const val BALANCE_MIN_DAYS: Int = 3

    /**
     * The average daily energy balance (in − out, kcal) over the complete days of [week] (today, the
     * last entry, is left out because it is not over), using only days with both sides; null with fewer
     * than [BALANCE_MIN_DAYS] such days.
     */
    fun weekBalance(week: List<DayEnergy>): Int? {
        val days = week.dropLast(1).filter { it.inKcal >= 0 && it.outKcal >= 0 }
        if (days.size < BALANCE_MIN_DAYS) return null
        return days.map { it.inKcal - it.outKcal }.average().roundToInt()
    }

    /** Stress at or below this is rest (0–25 on the watch's scale). */
    const val REST_MAX: Int = 25

    /** The longest gap between two readings that still counts as continuous, seconds. */
    const val MAX_GAP_SEC: Long = 10 * 60L

    /**
     * Rest minutes from stress readings ([tSec] ascending, values 0–100): each reading at or below
     * [REST_MAX] counts until the next reading (at most [MAX_GAP_SEC]).
     */
    /**
     * Minutes at each stress level (0 rest 0–25, 1 low 26–50, 2 medium 51–75, 3 high 76–100), each reading
     * counting until the next one, at most [MAX_GAP_SEC] (as [restMinutes]).
     */
    fun levelMinutes(tSec: LongArray, values: IntArray): IntArray {
        val sec = LongArray(4)
        for (i in 0 until minOf(tSec.size, values.size) - 1) {
            val v = values[i]
            if (v !in 0..100) continue
            val level = when {
                v <= 25 -> 0
                v <= 50 -> 1
                v <= 75 -> 2
                else -> 3
            }
            sec[level] += (tSec[i + 1] - tSec[i]).coerceIn(0, MAX_GAP_SEC)
        }
        return IntArray(4) { (sec[it] / 60).toInt() }
    }

    /** The average of [values] in each of [slots] equal parts of [startSec]..[endSec]; −1 where none. */
    fun slots(tSec: LongArray, values: IntArray, startSec: Long, endSec: Long, slots: Int): IntArray {
        val sum = LongArray(slots)
        val count = IntArray(slots)
        val span = (endSec - startSec).coerceAtLeast(1)
        for (i in tSec.indices) {
            if (tSec[i] < startSec || tSec[i] >= endSec) continue
            val s = ((tSec[i] - startSec) * slots / span).toInt().coerceIn(0, slots - 1)
            sum[s] += values[i]
            count[s]++
        }
        return IntArray(slots) { if (count[it] == 0) -1 else ((sum[it] * 2 + count[it]) / (2L * count[it])).toInt() }
    }

    fun restMinutes(tSec: LongArray, values: IntArray): Int {
        var sec = 0L
        for (i in 0 until minOf(tSec.size, values.size) - 1) {
            if (values[i] in 0..REST_MAX) sec += (tSec[i + 1] - tSec[i]).coerceIn(0, MAX_GAP_SEC)
        }
        return (sec / 60).toInt()
    }
}
