/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity.cards

import app.selfwatch.activity.IntensityMath
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.time.TimeRange

/** What one stretch of the Day clock shows. */
enum class ClockPart {
    /** No data (not worn, or still to come). */
    NONE,

    /** Asleep. */
    SLEEP,

    /** Awake and still (data, but no steps to speak of). */
    REST,

    /** Light movement (steps, or light intensity). */
    LIGHT,

    /** Moderate or vigorous minutes. */
    ACTIVE,
}

/**
 * The day shown as a 24-hour circle: [parts] has one entry per [SEGMENT_SEC] from [range]'s start (96 on
 * a normal day; 92 or 100 on daylight-saving days). [activeHours] counts the clock hours with at least
 * [ACTIVE_HOUR_STEPS] steps or a moderate or vigorous minute; the minute totals sum each part.
 */
data class DayClock(
    val range: TimeRange,
    val parts: List<ClockPart>,
    val activeHours: Int,
    val sleepMin: Int,
    val restMin: Int,
    val lightMin: Int,
    val activeMin: Int,
) {
    /** True when nothing was recorded for the day. */
    val isEmpty: Boolean get() = parts.all { it == ClockPart.NONE }
}

/** Builds the [DayClock] from the day's minutes and sleep. */
object DayClockMath {
    /** Length of one stretch of the circle (15 minutes). */
    const val SEGMENT_SEC: Long = 900L

    /** Steps in a clock hour that make it an active hour. */
    const val ACTIVE_HOUR_STEPS: Int = 250

    /** Steps in a 15-minute stretch that count as light movement. */
    const val LIGHT_SEGMENT_STEPS: Int = 60

    private const val MINUTE = 60L
    private const val HOUR = 3_600L

    /**
     * The clock of the day [range] from its activity [minutes] (any devices; a minute recorded twice
     * counts once, at the higher values) and [sleep] sessions, up to [nowSec] (later stretches stay
     * [ClockPart.NONE]). A stretch is sleep when at least half of it is inside a session.
     */
    fun build(range: TimeRange, minutes: List<ActivityMinute>, sleep: List<SleepSession>, nowSec: Long): DayClock {
        val len = (range.endSec - range.startSec).coerceAtLeast(0)
        val count = ((len + SEGMENT_SEC - 1) / SEGMENT_SEC).toInt()
        val steps = IntArray(count)
        val maxIntensity = IntArray(count) { -1 }
        val hourSteps = HashMap<Long, Int>()
        val hourActive = HashSet<Long>()
        // One value per minute: the highest steps and intensity any device recorded.
        val byMinute = HashMap<Long, ActivityMinute>()
        for (m in minutes) {
            if (m.tSec !in range) continue
            val key = m.tSec / MINUTE
            val seen = byMinute[key]
            byMinute[key] = if (seen == null) m else seen.copy(steps = maxOf(seen.steps, m.steps), intensity = maxOf(seen.intensity, m.intensity))
        }
        for (m in byMinute.values) {
            val i = ((m.tSec - range.startSec) / SEGMENT_SEC).toInt().coerceIn(0, count - 1)
            steps[i] += m.steps
            maxIntensity[i] = maxOf(maxIntensity[i], m.intensity)
            val hour = (m.tSec - range.startSec) / HOUR
            hourSteps[hour] = (hourSteps[hour] ?: 0) + m.steps
            if (m.intensity >= IntensityMath.MODERATE) hourActive += hour
        }
        val parts = ArrayList<ClockPart>(count)
        val sums = IntArray(ClockPart.entries.size)
        for (i in 0 until count) {
            val start = range.startSec + i * SEGMENT_SEC
            val end = minOf(start + SEGMENT_SEC, range.endSec)
            val asleep = sleep.sumOf { s -> (minOf(end, s.endSec) - maxOf(start, s.startSec)).coerceAtLeast(0) }
            val part = when {
                start >= nowSec -> ClockPart.NONE
                asleep * 2 >= end - start -> ClockPart.SLEEP
                maxIntensity[i] >= IntensityMath.MODERATE -> ClockPart.ACTIVE
                maxIntensity[i] >= 0 && (steps[i] >= LIGHT_SEGMENT_STEPS || maxIntensity[i] == 1) -> ClockPart.LIGHT
                maxIntensity[i] >= 0 -> ClockPart.REST
                else -> ClockPart.NONE
            }
            parts += part
            sums[part.ordinal] += ((end - start) / MINUTE).toInt()
        }
        val hours = hourSteps.filterValues { it >= ACTIVE_HOUR_STEPS }.keys + hourActive
        return DayClock(
            range = range,
            parts = parts,
            activeHours = hours.size,
            sleepMin = sums[ClockPart.SLEEP.ordinal],
            restMin = sums[ClockPart.REST.ordinal],
            lightMin = sums[ClockPart.LIGHT.ordinal],
            activeMin = sums[ClockPart.ACTIVE.ordinal],
        )
    }
}
