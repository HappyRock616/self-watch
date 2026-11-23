/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.health

import app.selfwatch.model.food.Sex
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.health.SleepStageSample

/** Activity totals of one day after device priority. */
public data class ActivityTotals(
    /** Steps. */
    val steps: Int,
    /** Metres. */
    val distanceM: Int,
    /** Active kilocalories. */
    val activeKcal: Int,
    /** Intensity minutes (moderate 1, vigorous 2). */
    val activeMin: Int,
)

/**
 * The pure rules behind each `day_summary` column (WP-07). Kept apart from the database so they can be
 * tested and shown in the UI exactly as computed.
 */
public object SummaryRules {
    /** Length of the resting-HR fallback window: the lowest 30-minute average. */
    public const val RESTING_HR_WINDOW_SEC: Long = 30 * 60L

    /** Fewest heart-rate samples a 30-minute window needs to count. */
    public const val RESTING_HR_MIN_SAMPLES: Int = 5

    /**
     * Intensity-minute weight of one minute: moderate (2) counts 1, vigorous (3 or more) counts 2,
     * sedentary and light count 0.
     */
    public fun intensityWeight(intensity: Int): Int = when {
        intensity >= 3 -> 2
        intensity == 2 -> 1
        else -> 0
    }

    /**
     * Sums [minutes] with device priority: when several devices report the same minute, only the one
     * with the lowest [rank] (ties: lowest device id) counts. Null when there are no minutes.
     */
    public fun activity(minutes: List<ActivityMinute>, rank: (Long) -> Int): ActivityTotals? {
        if (minutes.isEmpty()) return null
        val best = HashMap<Long, ActivityMinute>(minutes.size * 2)
        for (m in minutes) {
            val key = Math.floorDiv(m.tSec, 60L)
            val cur = best[key]
            if (cur == null || better(m.deviceId, cur.deviceId, rank)) best[key] = m
        }
        var steps = 0L
        var distanceCm = 0L
        var kcalX10 = 0L
        var activeMin = 0L
        for (m in best.values) {
            steps += m.steps.coerceAtLeast(0)
            distanceCm += m.distanceCm.coerceAtLeast(0)
            kcalX10 += m.activeKcalX10.coerceAtLeast(0)
            activeMin += intensityWeight(m.intensity)
        }
        return ActivityTotals(
            steps = clampInt(steps),
            distanceM = clampInt(roundDiv(distanceCm, 100)),
            activeKcal = clampInt(roundDiv(kcalX10, 10)),
            activeMin = clampInt(activeMin),
        )
    }

    /** The samples of the best-ranked device that has any (device priority for metrics). */
    public fun topDevice(samples: List<MetricSample>, rank: (Long) -> Int): List<MetricSample> {
        if (samples.isEmpty()) return samples
        var bestId = samples[0].deviceId
        for (s in samples) if (better(s.deviceId, bestId, rank)) bestId = s.deviceId
        return samples.filter { it.deviceId == bestId }
    }

    /**
     * The lowest average heart rate over any 30-minute window that starts at a sample, ends by
     * [endSec] and holds at least [RESTING_HR_MIN_SAMPLES] samples. Zero readings are ignored.
     * Null when no window qualifies.
     */
    public fun lowestWindowAverage(samples: List<MetricSample>, endSec: Long): Int? {
        val s = samples.filter { it.value > 0 }.sortedBy { it.tSec }
        var best: Long? = null
        var bestCount = 1L
        var j = 0
        var sum = 0L
        for (i in s.indices) {
            if (i > 0) sum -= s[i - 1].value
            if (j < i) {
                j = i
                sum = 0
            }
            val windowEnd = s[i].tSec + RESTING_HR_WINDOW_SEC
            if (windowEnd > endSec) break
            while (j < s.size && s[j].tSec < windowEnd) {
                sum += s[j].value
                j++
            }
            val count = (j - i).toLong()
            if (count >= RESTING_HR_MIN_SAMPLES) {
                // Compare sum/count < best/bestCount without division.
                val b = best
                if (b == null || sum * bestCount < b * count) {
                    best = sum
                    bestCount = count
                }
            }
        }
        val b = best ?: return null
        return roundDiv(b, bestCount).toInt()
    }

    /** Average of [samples] rounded half up, or null when empty. */
    public fun average(samples: List<MetricSample>): Int? {
        if (samples.isEmpty()) return null
        return roundDiv(samples.sumOf { it.value.toLong() }, samples.size.toLong()).toInt()
    }

    /**
     * Minutes asleep in [session]: the time from each light, deep or REM stage sample to the next
     * sample (or the session end), clipped to the session. Awake and unmeasurable time is left out;
     * time before the first stage sample is unknown and not counted. With no stages, the whole session.
     */
    public fun sleepMinutes(session: SleepSession, stages: List<SleepStageSample>): Int {
        if (stages.isEmpty()) return ((session.endSec - session.startSec).coerceAtLeast(0) / 60).toInt()
        val sorted = stages.sortedBy { it.tSec }
        var asleepSec = 0L
        for (i in sorted.indices) {
            val st = sorted[i]
            if (st.stage != SleepStage.LIGHT && st.stage != SleepStage.DEEP && st.stage != SleepStage.REM) continue
            val start = st.tSec.coerceIn(session.startSec, session.endSec)
            val end = (if (i + 1 < sorted.size) sorted[i + 1].tSec else session.endSec).coerceIn(session.startSec, session.endSec)
            asleepSec += (end - start).coerceAtLeast(0)
        }
        return (asleepSec / 60).toInt()
    }

    /**
     * Resting energy in kcal per day by the Mifflin-St Jeor formula:
     * `10 × kg + 6.25 × cm − 5 × age + s`, where `s` is +5 for men, −161 for women and −78 (their
     * midpoint) when sex is not given. Null when an input is missing or out of range.
     */
    public fun mifflinStJeor(weightKgX100: Int?, heightCm: Int?, ageYears: Int?, sex: Sex): Int? {
        if (weightKgX100 == null || heightCm == null || ageYears == null) return null
        if (weightKgX100 <= 0 || heightCm !in 50..272 || ageYears !in 1..120) return null
        val s = when (sex) {
            Sex.MALE -> 5
            Sex.FEMALE -> -161
            Sex.UNSPECIFIED -> -78
        }
        val kcalX100 = 10L * weightKgX100 + 625L * heightCm - 500L * ageYears + 100L * s
        if (kcalX100 <= 0) return null
        return roundDiv(kcalX100, 100).toInt()
    }

    /** [perDay] prorated for the elapsed part of a day running from [startSec] to [endSec] at [nowSec]. */
    public fun prorate(perDay: Int, startSec: Long, endSec: Long, nowSec: Long): Int = when {
        nowSec >= endSec -> perDay
        nowSec <= startSec -> 0
        else -> roundDiv(perDay.toLong() * (nowSec - startSec), endSec - startSec).toInt()
    }

    /** `x10` value to whole units, rounded half up (null stays null). */
    public fun fromX10(v: Int?): Int? = v?.let { roundDiv(it.toLong(), 10).toInt() }

    private fun better(candidate: Long, current: Long, rank: (Long) -> Int): Boolean {
        val rc = rank(candidate)
        val ru = rank(current)
        return rc < ru || (rc == ru && candidate < current)
    }

    /** a / b rounded half away from zero (b > 0). */
    internal fun roundDiv(a: Long, b: Long): Long = if (a >= 0) (2 * a + b) / (2 * b) else -((-2 * a + b) / (2 * b))

    private fun clampInt(v: Long): Int = v.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}
