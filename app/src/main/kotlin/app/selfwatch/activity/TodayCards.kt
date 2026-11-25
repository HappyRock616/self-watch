/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.dao.ActivityDayTotals
import app.selfwatch.model.health.DaySummary
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.TimeRange

/** Layer ids of the Today (hour by hour) chart (stored in `chart.today_timeline.layers`; never change them). */
object TimelineLayers {
    /** Steps per hour (bars). */
    const val STEPS: String = "steps"

    /** Heart rate (line). */
    const val HR: String = "hr"

    /** Chip order. */
    val ALL: List<String> = listOf(STEPS, HR)
}

/**
 * One metric for the last 7 days, today last.
 *
 * @property days the days; [values] their values (null: none).
 */
data class WeekValues(val days: List<Day>, val values: List<Int?>) {
    /** Today's value. */
    val today: Int? get() = values.last()

    /** True with no value in the 7 days. */
    val isEmpty: Boolean get() = values.all { it == null }

    /** Average of the days with a value (null: none). */
    val average: Double? get() = values.filterNotNull().takeIf { it.isNotEmpty() }?.average()
}

/**
 * Today hour by hour.
 *
 * @property dayStartSec the start of today.
 * @property stepsPerHour steps in each hour of today so far (24 entries, -1 for hours still to come).
 * @property hrT heart-rate reading times today; [hrV] their values.
 */
data class TimelineInfo(val dayStartSec: Long, val stepsPerHour: IntArray, val hrT: LongArray, val hrV: IntArray) {
    /** True with no steps and no heart rate today. */
    val isEmpty: Boolean get() = stepsPerHour.none { it > 0 } && hrT.isEmpty()

    /** Steps so far today. */
    val totalSteps: Int get() = stepsPerHour.filter { it > 0 }.sum()

    /** The hour (0..23) with the most steps, or null with none. */
    val busiestHour: Int? get() = stepsPerHour.indices.filter { stepsPerHour[it] > 0 }.maxByOrNull { stepsPerHour[it] }

    override fun equals(other: Any?): Boolean =
        other is TimelineInfo && other.dayStartSec == dayStartSec && other.stepsPerHour.contentEquals(stepsPerHour) &&
            other.hrT.contentEquals(hrT) && other.hrV.contentEquals(hrV)

    override fun hashCode(): Int = (dayStartSec.hashCode() * 31 + stepsPerHour.contentHashCode()) * 31 + hrV.contentHashCode()
}

/** Reads today's Activity numbers: the day summary first, else the minute data. */
class TodayLoader(private val db: SelfWatchDb, private val clock: Clock) {

    private suspend fun week(day: Day, fromSummary: (DaySummary) -> Int?, fromMinutes: (ActivityDayTotals) -> Int?): WeekValues {
        val days = (WEEK - 1 downTo 0).map { day.plusDays(-it) }
        val range = DayRange(days.first(), days.last())
        val summary = db.summaries.days(range).mapNotNull { s -> fromSummary(s)?.let { s.day to it } }.toMap()
        val minutes = db.activity.totalsByDay(range, clock.zone()).mapNotNull { t -> fromMinutes(t)?.let { t.day to it } }.toMap()
        return WeekValues(days, days.map { summary[it] ?: minutes[it] })
    }

    /** Steps, the last 7 days ending [day]. */
    suspend fun steps(day: Day): WeekValues = week(day, { it.steps }, { t -> t.steps.toInt().takeIf { t.minutes > 0 } })

    /** Distance in metres, the last 7 days ending [day]. */
    suspend fun distanceM(day: Day): WeekValues = week(day, { it.distanceM }, { t -> (t.distanceCm / CM_PER_M).toInt().takeIf { t.minutes > 0 } })

    /** Active kcal, the last 7 days ending [day]. */
    suspend fun activeKcal(day: Day): WeekValues =
        week(day, { it.activeKcal }, { t -> ((t.activeKcalX10 + HALF) / TENTHS).toInt().takeIf { t.minutes > 0 } })

    /** Total kcal (active + resting), the last 7 days ending [day]; null for a day with neither. */
    suspend fun totalKcal(day: Day): Pair<WeekValues, Pair<Int?, Int?>> {
        val active = activeKcal(day)
        val resting = week(day, { it.restingKcal }, { null })
        val total = active.values.zip(resting.values) { a, r -> if (a == null && r == null) null else (a ?: 0) + (r ?: 0) }
        return WeekValues(active.days, total) to (active.today to resting.today)
    }

    /** Today hour by hour: steps per hour and every heart-rate reading. */
    suspend fun timeline(day: Day): TimelineInfo {
        val zone = clock.zone()
        val start = day.startSec(zone)
        val end = day.plusDays(1).startSec(zone)
        val now = clock.nowSec()
        val steps = IntArray(HOURS) { h -> if (start + h * HOUR_SEC <= now) 0 else -1 }
        for (m in db.activity.minutes(TimeRange(start, end))) {
            val h = ((m.tSec - start) / HOUR_SEC).toInt()
            if (h in 0 until HOURS) steps[h] = maxOf(steps[h], 0) + m.steps
        }
        val hr = db.metrics.series(MetricKind.HEART_RATE, TimeRange(start, end)).filter { it.value >= MIN_BPM }
        return TimelineInfo(start, steps, LongArray(hr.size) { hr[it].tSec }, IntArray(hr.size) { hr[it].value })
    }

    private companion object {
        const val WEEK = 7
        const val CM_PER_M = 100L
        const val HALF = 5L
        const val TENTHS = 10L
        const val HOURS = 24
        const val HOUR_SEC = 3_600L
        const val MIN_BPM = 25
    }
}
