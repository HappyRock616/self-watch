/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body

import app.selfwatch.db.SelfWatchDb
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.TimeRange
import kotlin.math.roundToInt

/**
 * Heart rate now and today.
 *
 * @property latestBpm the newest stored heart-rate reading (null: none at all).
 * @property latestSec when it was taken.
 * @property lowBpm today's lowest reading; [highBpm] today's highest (null: none today).
 * @property restingBpm today's resting heart rate from the watch (null: not yet).
 * @property spark 15-minute averages over the last 24 hours, oldest first (empty quarters left out).
 */
data class HeartNowInfo(
    val latestBpm: Int?,
    val latestSec: Long?,
    val lowBpm: Int?,
    val highBpm: Int?,
    val restingBpm: Int?,
    val spark: List<Int>,
) {
    /** True with no heart-rate reading at all. */
    val isEmpty: Boolean get() = latestBpm == null
}

/**
 * Resting and daily average heart rate per day over the last [HeartCardMath.TREND_DAYS] days.
 *
 * @property days the days, oldest first (today last).
 * @property resting resting heart rate per day (null: none).
 * @property average average of the day's readings (null: none).
 */
data class HeartTrendInfo(val days: List<Day>, val resting: List<Int?>, val average: List<Int?>) {
    /** True with no value of either kind. */
    val isEmpty: Boolean get() = resting.all { it == null } && average.all { it == null }

    /** Mean of the days with a resting value (null: none). */
    val restingMean: Int? get() = resting.filterNotNull().takeIf { it.isNotEmpty() }?.average()?.roundToInt()

    /** Mean of the days with an average (null: none). */
    val averageMean: Int? get() = average.filterNotNull().takeIf { it.isNotEmpty() }?.average()?.roundToInt()
}

/**
 * Overnight breathing against the user's usual range.
 *
 * @property lastX10 the latest night's average, breaths a minute ×10.
 * @property lastDay the day that night ended.
 * @property lowX10 bottom of the usual range; [highX10] its top (×10).
 * @property nights the last [HeartCardMath.SPARK_NIGHTS] nightly values with a reading, oldest first.
 */
data class BreathingResult(
    val lastX10: Int,
    val lastDay: Day,
    val lowX10: Int,
    val highX10: Int,
    val status: RangeStatus,
    val nights: List<Int>,
)

/** Breathing card data: the result once there is a baseline, else the latest night (if any) and the nights so far. */
data class BreathingInfo(val result: BreathingResult?, val lastX10: Int?, val nights: Int, val needed: Int)

/**
 * The watch's energy level today.
 *
 * @property latest the newest reading today (0..100); [latestSec] when.
 * @property high today's highest; [low] today's lowest.
 * @property spark 15-minute values over today, oldest first.
 */
data class EnergyLevelInfo(val latest: Int, val latestSec: Long, val high: Int, val low: Int, val spark: List<Int>)

/** Pure arithmetic of the heart, breathing and energy cards. */
object HeartCardMath {
    /** Sparkline bucket. */
    const val BUCKET_SEC: Long = 900L

    /** Days in the heart-rate trend. */
    const val TREND_DAYS: Int = 30

    /** Nights the usual breathing range is learned from. */
    const val BASELINE_NIGHTS: Int = 60

    /** Nights with a value before the range means anything (about two weeks). */
    const val BASELINE_MIN: Int = 14

    /** Nights in the breathing sparkline. */
    const val SPARK_NIGHTS: Int = 30

    /** Smallest half-width of the usual breathing range: half a breath a minute (×10). */
    private const val MIN_HALF_X10 = 5.0

    /** Readings below this are no measurement (0 while the sensor has no contact). */
    const val MIN_BPM: Int = 25

    /**
     * Averages of [samples] in [bucketSec] steps from [fromSec] to [toSec], oldest first; buckets without
     * a reading are left out.
     */
    fun buckets(samples: List<MetricSample>, fromSec: Long, toSec: Long, bucketSec: Long = BUCKET_SEC): List<Int> {
        if (toSec <= fromSec) return emptyList()
        val n = ((toSec - fromSec + bucketSec - 1) / bucketSec).toInt()
        val sum = LongArray(n)
        val count = IntArray(n)
        for (s in samples) {
            if (s.tSec < fromSec || s.tSec >= toSec) continue
            val i = ((s.tSec - fromSec) / bucketSec).toInt()
            sum[i] += s.value.toLong()
            count[i]++
        }
        return (0 until n).filter { count[it] > 0 }.map { (sum[it].toDouble() / count[it]).roundToInt() }
    }

    /**
     * Evaluates nightly breathing [nightly] (×10 per day, oldest first, the last is today; null for no
     * value): the latest night (if it ended today or yesterday) against a range learned from the
     * [BASELINE_NIGHTS] before it (mean ± one standard deviation, at least ± half a breath).
     */
    fun breathing(days: List<Day>, nightly: List<Int?>): BreathingInfo {
        val lastIdx = nightly.indexOfLast { it != null }.takeIf { it >= 0 && it >= nightly.size - 2 }
        val last = lastIdx?.let { nightly[it] }
        val before = (if (lastIdx == null) nightly else nightly.subList(0, lastIdx)).takeLast(BASELINE_NIGHTS).filterNotNull()
        if (lastIdx == null || last == null || before.size < BASELINE_MIN) return BreathingInfo(null, last, before.size, BASELINE_MIN)
        val base = Baseline.of(before) ?: return BreathingInfo(null, last, before.size, BASELINE_MIN)
        val half = maxOf(base.sd, MIN_HALF_X10)
        val low = base.mean - half
        val high = base.mean + half
        val status = when {
            last < low -> RangeStatus.BELOW
            last > high -> RangeStatus.ABOVE
            else -> RangeStatus.WITHIN
        }
        val spark = nightly.subList(0, lastIdx + 1).filterNotNull().takeLast(SPARK_NIGHTS)
        return BreathingInfo(BreathingResult(last, days[lastIdx], low.roundToInt(), high.roundToInt(), status, spark), last, before.size, BASELINE_MIN)
    }
}

/** Reads the heart, breathing and energy cards' numbers. */
class HeartCardLoader(private val db: SelfWatchDb, private val clock: Clock) {

    /** The latest reading, today's low, high and resting, and the last 24 hours. */
    suspend fun now(): HeartNowInfo {
        val zone = clock.zone()
        val now = clock.nowSec()
        val today = clock.today()
        val start = today.startSec(zone)
        val from = minOf(start, now - DAY_SEC)
        val samples = db.metrics.series(MetricKind.HEART_RATE, TimeRange(from, now + 1)).filter { it.value >= HeartCardMath.MIN_BPM }
        val latest = samples.lastOrNull() ?: db.metrics.latest(MetricKind.HEART_RATE)?.takeIf { it.value >= HeartCardMath.MIN_BPM }
        val todays = samples.filter { it.tSec >= start }
        val resting = BodyTrendLoader(db, clock).restingByDay(DayRange(today, today))[today]
        return HeartNowInfo(
            latestBpm = latest?.value,
            latestSec = latest?.tSec,
            lowBpm = todays.minOfOrNull { it.value },
            highBpm = todays.maxOfOrNull { it.value },
            restingBpm = resting,
            spark = HeartCardMath.buckets(samples, now - DAY_SEC, now + 1),
        )
    }

    /** Resting and average heart rate per day, the last 30 days. */
    suspend fun trend(): HeartTrendInfo {
        val today = clock.today()
        val range = DayRange(today.plusDays(1 - HeartCardMath.TREND_DAYS), today)
        val resting = BodyTrendLoader(db, clock).restingByDay(range)
        val avg = db.metrics.dailyStats(MetricKind.HEART_RATE, range, clock.zone()).associate { it.day to it.avg }
        val days = range.days()
        return HeartTrendInfo(days, days.map { resting[it] }, days.map { avg[it] })
    }

    /** Last night's breathing against the usual range. */
    suspend fun breathing(): BreathingInfo {
        val today = clock.today()
        val range = DayRange(today.plusDays(-HeartCardMath.BASELINE_NIGHTS), today)
        val nights = BodyTrendLoader(db, clock).signalNights(range)[SignalKind.RESPIRATION].orEmpty()
        val days = range.days()
        return HeartCardMath.breathing(days, days.map { nights[it] })
    }

    /** Today's energy level, or null with no reading today. */
    suspend fun energy(): EnergyLevelInfo? {
        val zone = clock.zone()
        val now = clock.nowSec()
        val start = clock.today().startSec(zone)
        val samples = db.metrics.series(MetricKind.ENERGY_LEVEL, TimeRange(start, now + 1)).filter { it.value in 0..MAX_ENERGY }
        val latest = samples.lastOrNull() ?: return null
        return EnergyLevelInfo(
            latest = latest.value,
            latestSec = latest.tSec,
            high = samples.maxOf { it.value },
            low = samples.minOf { it.value },
            spark = HeartCardMath.buckets(samples, start, now + 1),
        )
    }

    private companion object {
        const val DAY_SEC = 86_400L
        const val MAX_ENERGY = 100
    }
}

/** Layer ids of the heart-rate trends chart (stored in `chart.heart_rate_trends.layers`; never change them). */
object HeartTrendLayers {
    /** Resting heart rate per day. */
    const val RESTING: String = "resting"

    /** Average of the day's readings. */
    const val AVERAGE: String = "average"

    /** Chip order. */
    val ALL: List<String> = listOf(RESTING, AVERAGE)
}
