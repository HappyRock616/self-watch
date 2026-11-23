/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db.dao

import android.database.Cursor
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.db.args
import app.selfwatch.db.bindIntOrNull
import app.selfwatch.db.intOrNull
import app.selfwatch.db.longOrNull
import app.selfwatch.db.queryList
import app.selfwatch.db.queryOne
import app.selfwatch.db.read
import app.selfwatch.db.withStatement
import app.selfwatch.db.write
import app.selfwatch.model.health.DaySummary
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import java.time.DayOfWeek

/** One value per [DaySummary] field, used for period sums and averages. Null: no day had a value. */
public data class PeriodValues(
    val steps: Long?,
    val activeKcal: Long?,
    val restingKcal: Long?,
    val distanceM: Long?,
    val activeMin: Long?,
    val restingHr: Long?,
    val hrvAvgMs: Long?,
    val sleepMin: Long?,
    val kcalIn: Long?,
    val proteinG: Long?,
    val fatG: Long?,
    val carbG: Long?,
    val weightKgX100: Long?,
    val waterMl: Long?,
)

/**
 * A week or month of day summaries.
 *
 * @property first the week's first day (or the 1st of the month); it may lie before the requested range.
 * @property dayCount summary rows in the period (within the requested range).
 * @property sum each field summed over the days that have it.
 * @property avg each field's average over the days that have it, rounded half away from zero.
 */
public data class PeriodSummary(val first: Day, val dayCount: Int, val sum: PeriodValues, val avg: PeriodValues)

/** Day summaries (table `day_summary`) and week and month aggregates computed in SQL. */
public class SummaryDao internal constructor(private val db: SelfWatchDb) {

    /** Stores [s] (replacing the row for its day), stamped [updatedSec]. */
    public suspend fun upsertDay(s: DaySummary, updatedSec: Long) {
        db.write(setOf(Table.DAY_SUMMARY)) { d ->
            d.withStatement("INSERT OR REPLACE INTO day_summary (day, tz_offset_min, $FIELDS, updated_sec) VALUES (${"?, ".repeat(16)}?)") { st ->
                st.bindLong(1, s.day.yyyymmdd.toLong())
                st.bindLong(2, s.tzOffsetMin.toLong())
                val v = listOf(
                    s.steps, s.activeKcal, s.restingKcal, s.distanceM, s.activeMin, s.restingHr, s.hrvAvgMs,
                    s.sleepMin, s.kcalIn, s.proteinG, s.fatG, s.carbG, s.weightKgX100, s.waterMl,
                )
                v.forEachIndexed { i, x -> st.bindIntOrNull(3 + i, x) }
                st.bindLong(17, updatedSec)
                st.executeInsert()
            }
        }
    }

    /** The summary of [day], or null. */
    public suspend fun day(day: Day): DaySummary? = db.read {
        it.queryOne("SELECT day, tz_offset_min, $FIELDS FROM day_summary WHERE day = ?", args(day.yyyymmdd), ::mapDay)
    }

    /** Summaries of the days in [days] that have one, oldest first. */
    public suspend fun days(days: DayRange): List<DaySummary> = db.read {
        it.queryList(
            "SELECT day, tz_offset_min, $FIELDS FROM day_summary WHERE day >= ? AND day <= ? ORDER BY day",
            args(days.first.yyyymmdd, days.last.yyyymmdd),
            ::mapDay,
        )
    }

    /** Week aggregates of the days in [days], grouped in SQL by the week starting on [weekStart]. Oldest first. */
    public suspend fun weeks(days: DayRange, weekStart: DayOfWeek = DayOfWeek.MONDAY): List<PeriodSummary> {
        // SQLite weekday numbers: 0 = Sunday … 6 = Saturday. '-6 days' then 'weekday N' finds the
        // N-day on or before the date.
        val n = weekStart.value % 7
        val dateText = "printf('%04d-%02d-%02d', day / 10000, day / 100 % 100, day % 100)"
        return period("CAST(strftime('%Y%m%d', date($dateText, '-6 days', 'weekday $n')) AS INTEGER)", days)
    }

    /** Month aggregates of the days in [days], grouped in SQL by calendar month. Oldest first. */
    public suspend fun months(days: DayRange): List<PeriodSummary> = period("(day / 100) * 100 + 1", days)

    private suspend fun period(keyExpr: String, days: DayRange): List<PeriodSummary> = db.read { d ->
        val cols = FIELD_LIST.joinToString(", ") { "SUM($it), CAST(ROUND(AVG($it)) AS INTEGER)" }
        d.queryList(
            "SELECT $keyExpr AS k, COUNT(*), $cols FROM day_summary WHERE day >= ? AND day <= ? GROUP BY k ORDER BY k",
            args(days.first.yyyymmdd, days.last.yyyymmdd),
        ) { c ->
            PeriodSummary(
                first = Day(c.getInt(0)),
                dayCount = c.getInt(1),
                sum = values(c) { i -> 2 + 2 * i },
                avg = values(c) { i -> 3 + 2 * i },
            )
        }
    }

    private companion object {
        val FIELD_LIST = listOf(
            "steps", "active_kcal", "resting_kcal", "distance_m", "active_min", "resting_hr", "hrv_avg_ms", "sleep_min",
            "kcal_in", "protein_g", "fat_g", "carb_g", "weight_kg_x100", "water_ml",
        )
        val FIELDS = FIELD_LIST.joinToString(", ")

        inline fun values(c: Cursor, col: (Int) -> Int): PeriodValues = PeriodValues(
            c.longOrNull(col(0)), c.longOrNull(col(1)), c.longOrNull(col(2)), c.longOrNull(col(3)), c.longOrNull(col(4)),
            c.longOrNull(col(5)), c.longOrNull(col(6)), c.longOrNull(col(7)), c.longOrNull(col(8)), c.longOrNull(col(9)),
            c.longOrNull(col(10)), c.longOrNull(col(11)), c.longOrNull(col(12)), c.longOrNull(col(13)),
        )

        fun mapDay(c: Cursor): DaySummary = DaySummary(
            day = Day(c.getInt(0)),
            tzOffsetMin = c.getInt(1),
            steps = c.intOrNull(2),
            activeKcal = c.intOrNull(3),
            restingKcal = c.intOrNull(4),
            distanceM = c.intOrNull(5),
            activeMin = c.intOrNull(6),
            restingHr = c.intOrNull(7),
            hrvAvgMs = c.intOrNull(8),
            sleepMin = c.intOrNull(9),
            kcalIn = c.intOrNull(10),
            proteinG = c.intOrNull(11),
            fatG = c.intOrNull(12),
            carbG = c.intOrNull(13),
            weightKgX100 = c.intOrNull(14),
            waterMl = c.intOrNull(15),
        )
    }
}
