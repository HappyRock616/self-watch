/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import app.selfwatch.db.dao.PeriodSummary
import app.selfwatch.db.dao.PeriodValues
import app.selfwatch.me.GoalField
import app.selfwatch.model.food.Goal
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.health.DaySummary
import app.selfwatch.model.health.TrackPoint
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.ui.format.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ActivityRulesTest {
    private val today = Day(20261123)

    private fun summary(day: Day, steps: Int?, distance: Int? = null) =
        DaySummary(day, 0, steps, 300, 1500, distance, 40, null, null, null, null, null, null, null, null, null)

    @Test
    fun streakCountsYesterdayBackAndTodayOnceMet() {
        val goal = { _: Day -> 10_000 }
        val steps = mapOf(
            today to 2_000, // today not done yet: does not break the streak
            today.plusDays(-1) to 12_000,
            today.plusDays(-2) to 10_000,
            today.plusDays(-3) to 9_999,
            today.plusDays(-4) to 15_000,
        )
        assertEquals(2, ActivityRules.streak(today, steps, goal))
        assertEquals(3, ActivityRules.streak(today, steps + (today to 10_500), goal))
        // A day with no data ends the streak.
        assertEquals(0, ActivityRules.streak(today, mapOf(today.plusDays(-2) to 20_000), goal))
    }

    @Test
    fun streakUsesTheGoalOfEachDay() {
        val history = listOf(Goal(GoalKind.STEPS, 8_000, Day(20260101)), Goal(GoalKind.STEPS, 12_000, today.plusDays(-1)))
        val steps = mapOf(today.plusDays(-1) to 11_000, today.plusDays(-2) to 9_000)
        // Yesterday's goal was 12,000 (missed); before that 8,000.
        assertEquals(0, ActivityRules.streak(today, steps) { ActivityRules.goalOn(history, it, 10_000) })
        assertEquals(8_000, ActivityRules.goalOn(history, today.plusDays(-2), 10_000))
        assertEquals(10_000, ActivityRules.goalOn(history, Day(20251231), 10_000))
    }

    @Test
    fun ringsProgressAgainstGoals() {
        val r = Rings(steps = 7_842, stepGoal = 10_000, activeMin = 45, activeMinGoal = 30, activeKcal = 0, activeKcalGoal = 0)
        assertEquals(0.7842f, r.progress(r.steps, r.stepGoal), 1e-4f)
        assertEquals(1.5f, r.progress(r.activeMin, r.activeMinGoal), 1e-4f)
        assertEquals(0f, r.progress(r.activeKcal, r.activeKcalGoal), 0f)
    }

    @Test
    fun dailyTrendMarksMissingDays() {
        val days = DayRange(today.plusDays(-2), today)
        val t = ActivityRules.daily(TrendRange.WEEK, days, listOf(summary(today, 5_000, 3_200)), mapOf(TrendMetric.STEPS to 10_000))
        assertEquals(listOf(-1, -1, 5_000), t.values[TrendMetric.STEPS])
        assertEquals(listOf(-1, -1, 3_200), t.values[TrendMetric.DISTANCE])
        assertEquals(10_000, t.goals[TrendMetric.STEPS])
        assertTrue(!t.isEmpty)
    }

    @Test
    fun monthlyTrendUsesDailyAverages() {
        fun pv(steps: Long?) = PeriodValues(steps, null, null, null, null, null, null, null, null, null, null, null, null, null)
        val months = listOf(PeriodSummary(Day(20261001), 20, pv(200_000), pv(10_000)))
        val t = ActivityRules.monthly(TrendRange.YEAR, Day(20260915), today, months, emptyMap())
        assertEquals(listOf(Day(20260901), Day(20261001), Day(20261101)), t.starts)
        assertEquals(listOf(-1, 10_000, -1), t.values[TrendMetric.STEPS])
    }

    @Test
    fun workoutMarkersEveryKilometre() {
        // 0.001° of latitude is about 111 m: 30 steps north is about 3.3 km.
        val route = (0..30).map { GeoPoint(470_000_000 + it * 10_000, 85_000_000) }
        val markers = WorkoutMath.markers(route, 1000.0)
        assertEquals(3, markers.size)
        assertEquals(9, markers[0])
        assertEquals(3_336.0, WorkoutMath.lengthM(route), 10.0)
        assertTrue(WorkoutMath.markers(route.take(1), 1000.0).isEmpty())
    }

    @Test
    fun workoutTrackSeriesAndSmoothing() {
        val pts = (0 until 5).map { TrackPoint(100L + it, 470_000_000, 85_000_000 + it * 100, null, 120 + it, null, null, if (it == 2) 9_000 else 3_000) }
        val t = WorkoutMath.build(pts, 1000.0)
        assertEquals(5, t.route.size)
        assertEquals(5, t.hr.size)
        assertEquals(0, t.altitude.size)
        assertTrue("spike smoothed: ${t.speed.v.toList()}", t.speed.v.max() < 9_000)
    }

    @Test
    fun paceAndSpeed() {
        Locale.setDefault(Locale.US)
        assertEquals("5:36", Sports.pace(28 * 60, 5_000, Units.METRIC))
        assertNull(Sports.pace(0, 5_000, Units.METRIC))
        assertEquals("19.6", Sports.speed(55 * 60, 18_000, Units.METRIC))
    }

    @Test
    fun goalFieldsParseAndFormat() {
        Locale.setDefault(Locale.US)
        assertEquals(16 * 60, GoalField.FAST.parse("16", Units.METRIC))
        assertEquals(78_00, GoalField.WEIGHT.parse("78", Units.METRIC))
        assertEquals(78_02, GoalField.WEIGHT.parse("172", Units.IMPERIAL))
        assertEquals("7.5", GoalField.SLEEP.toText(450, Units.METRIC))
        assertNull(GoalField.STEPS.parse("0", Units.METRIC))
        assertNull(GoalField.KCAL.parse("99999", Units.METRIC))
    }
}
