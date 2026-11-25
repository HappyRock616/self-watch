/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.cards

import app.selfwatch.activity.IntensityMath
import app.selfwatch.activity.Sports
import app.selfwatch.activity.cards.RecordKind
import app.selfwatch.activity.cards.RecordMath
import app.selfwatch.activity.cards.SportProgressMath
import app.selfwatch.activity.cards.WeeklyReviewCard
import app.selfwatch.body.cards.BmiCard
import app.selfwatch.body.cards.LeanMassCard
import app.selfwatch.body.cards.NapsCard
import app.selfwatch.body.cards.ReadinessFactor
import app.selfwatch.body.cards.ReadinessMath
import app.selfwatch.food.cards.BurnSource
import app.selfwatch.food.cards.DailyBalanceMath
import app.selfwatch.food.cards.FoodGroup
import app.selfwatch.food.cards.FoodGroupsCard
import app.selfwatch.food.cards.Macro
import app.selfwatch.food.cards.WeightGoalMath
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.Day
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** The arithmetic behind the new cards: goals, intensity minutes, weight goal, readiness, balance, records. */
class CardMathTest {
    private val today = Day(20261124)

    @Test
    fun goalProgressAndStatusFollowTheRule() {
        // More is better.
        val steps = GoalLine(GoalMetric.STEPS, 6_000, 10_000)
        assertEquals(0.6f, steps.progress!!, 0.001f)
        assertEquals(CardStatus.CLOSE, steps.status)
        assertEquals(CardStatus.GOOD, GoalLine(GoalMetric.STEPS, 12_000, 10_000).status)
        assertEquals(1f, GoalLine(GoalMetric.STEPS, 12_000, 10_000).progress!!, 0f)
        assertEquals(CardStatus.OFF, GoalLine(GoalMetric.STEPS, 1_000, 10_000).status)
        assertEquals(CardStatus.NONE, GoalLine(GoalMetric.STEPS, null, 10_000).status)
        assertNull(GoalLine(GoalMetric.DISTANCE, 3_000, null).progress)
        // A budget: under is on track, a little over is close.
        assertEquals(CardStatus.GOOD, GoalLine(GoalMetric.KCAL_EATEN, 1_800, 2_000).status)
        assertEquals(CardStatus.CLOSE, GoalLine(GoalMetric.KCAL_EATEN, 2_150, 2_000).status)
        assertEquals(CardStatus.OFF, GoalLine(GoalMetric.KCAL_EATEN, 2_600, 2_000).status)
        // A target weight: within 1% is there, within 5% is close, no bar.
        assertEquals(CardStatus.GOOD, GoalLine(GoalMetric.WEIGHT, 75_50, 75_00).status)
        assertEquals(CardStatus.CLOSE, GoalLine(GoalMetric.WEIGHT, 78_00, 75_00).status)
        assertEquals(CardStatus.NONE, GoalLine(GoalMetric.WEIGHT, 90_00, 75_00).status)
        assertNull(GoalLine(GoalMetric.WEIGHT, 78_00, 75_00).progress)
        // A level out of 100.
        assertEquals(CardStatus.GOOD, GoalLine(GoalMetric.ENERGY, 70, null).status)
        assertEquals(CardStatus.OFF, GoalLine(GoalMetric.ENERGY, 10, null).status)
        // Counting: judged lines only.
        val lines = listOf(steps, GoalLine(GoalMetric.WATER, 2_000, 2_000), GoalLine(GoalMetric.DISTANCE, 3_000, null), GoalLine(GoalMetric.ENERGY, 80, null))
        assertEquals(2 to 3, GoalMath.onTrack(lines))
    }

    @Test
    fun intensityMinutesCountVigorousTwiceAndOnceAcrossWatches() {
        val zone = ZoneOffset.UTC
        val t = today.startSec(zone) + 8 * 3_600
        val minutes = listOf(
            minute(t, IntensityMath.MODERATE),
            minute(t + 60, IntensityMath.VIGOROUS),
            minute(t + 60, IntensityMath.MODERATE, device = 2), // the same minute from a second watch counts once, at the higher level
            minute(t + 120, 1),
        )
        val byDay = IntensityMath.byDay(minutes, zone)
        assertEquals(1, byDay.getValue(today).moderateMin)
        assertEquals(1, byDay.getValue(today).vigorousMin)
        assertEquals(3, byDay.getValue(today).intensityMin)
        // Days without minutes use the summary's active minutes, once.
        val days = listOf(today.plusDays(-1), today)
        assertEquals(listOf(40, 3), IntensityMath.perDay(days, byDay, mapOf(today.plusDays(-1) to 40)))
        // Active days: at least a seventh of the weekly goal (43 of 300).
        assertEquals(43, IntensityMath.activeDayMin(300))
        assertEquals(2, IntensityMath.activeDays(listOf(50, 43, 42, null), 43))
    }

    @Test
    fun weightGoalRateIsCappedAndTheTargetNeverBelowTheFloor() {
        val date = today.plusDays(10 * 7) // 10 weeks
        // 80 → 76 kg in 10 weeks: 0.4 kg a week, under the 0.8 kg cap.
        val (rate, capped) = WeightGoalMath.rate(80_00, 76_00, today, date)
        assertEquals(-40, rate)
        assertFalse(capped)
        // 80 → 70 kg in 10 weeks would need 1 kg a week: capped at 1% (0.8 kg).
        val fast = WeightGoalMath.rate(80_00, 70_00, today, date)
        assertEquals(-80 to true, fast)
        // Gaining is capped at 0.5% a week; no date: a gentle 0.5% a week.
        assertEquals(40 to true, WeightGoalMath.rate(80_00, 90_00, today, date))
        assertEquals(-40 to false, WeightGoalMath.rate(80_00, 70_00, today, null))
        // Within half a kilo: keep steady.
        assertEquals(0 to false, WeightGoalMath.rate(75_30, 75_00, today, date))

        // The plan: burn 2,400, lose 0.4 kg a week = 440 kcal a day less.
        val plan = WeightGoalMath.plan(80_00, 76_00, today, date, 2_400, BurnSource.MEASURED, 1_500)
        assertEquals(1_960, plan.targetKcal)
        assertEquals(-40, plan.ratePerWeekX100)
        assertFalse(plan.floorApplied)
        assertEquals(today.plusDays(70), plan.reachDay)
        assertTrue(plan.onTime(date))
        // A small burn hits the safe minimum: the rate slows and the date slips.
        val low = WeightGoalMath.plan(60_00, 54_00, today, today.plusDays(70), 1_600, BurnSource.ESTIMATED, 1_200)
        assertEquals(1_200, low.targetKcal)
        assertTrue(low.floorApplied)
        assertEquals(-36, low.ratePerWeekX100) // 400 kcal a day = 0.36 kg a week
        assertFalse(low.onTime(today.plusDays(70)))
        // Keeping steady eats the burn.
        assertEquals(2_400, WeightGoalMath.plan(75_20, 75_00, today, null, 2_400, BurnSource.ADAPTIVE, 1_500).targetKcal)
    }

    @Test
    fun adaptiveBurnFollowsWhatReallyHappened() {
        // Ate 2,000 a day for 21 days and lost 1.05 kg: burned 2,000 + 1.05 × 7,700 / 21 = 2,385.
        assertEquals(2_385, WeightGoalMath.adaptiveBurn(2_000.0, -105, 21))
        // Gained instead: burn is lower than intake.
        assertEquals(1_633, WeightGoalMath.adaptiveBurn(2_000.0, 100, 21))
        assertEquals(listOf(80_00, 79_60, 79_20, 79_00), WeightGoalMath.projection(80_00, -40, 79_00, 10))
        assertEquals(listOf(80_00), WeightGoalMath.projection(80_00, 0, 79_00, 10))
        assertEquals(24.7, WeightGoalMath.bmi(80_00, 180)!!, 0.05)
        assertNull(WeightGoalMath.bmi(80_00, null))
    }

    @Test
    fun readinessWeighsSleepHrvRestingHrAndLoad() {
        assertEquals(100.0, ReadinessMath.sleep(480, 480), 0.0)
        assertEquals(75.0, ReadinessMath.sleep(360, 480), 0.0)
        assertEquals(100.0, ReadinessMath.hrv(60, 50), 0.0)
        assertEquals(0.0, ReadinessMath.hrv(35, 50), 0.0)
        assertEquals(50.0, ReadinessMath.restingHr(60, 55), 0.0)
        assertEquals(100.0, ReadinessMath.load(300, 400), 0.0)
        assertEquals(50.0, ReadinessMath.load(600, 400), 0.0)
        assertEquals(100 to ReadinessFactor.SLEEP, ReadinessMath.score(100.0, 100.0, 100.0, 100.0))
        // Short sleep pulls it down and is named.
        val (score, weakest) = ReadinessMath.score(50.0, 100.0, null, null)!!
        assertEquals(75, score)
        assertEquals(ReadinessFactor.SLEEP, weakest)
        assertNull("no sleep and no HRV: no score", ReadinessMath.score(null, null, 100.0, 100.0))
    }

    @Test
    fun dailyBalanceTargetsSharesAndLimits() {
        assertEquals(100.0, DailyBalanceMath.target(Macro.PROTEIN, 2_000, null), 0.01) // 20% of 2,000 kcal / 4
        assertEquals(250.0, DailyBalanceMath.target(Macro.CARBS, 2_000, null), 0.01)
        assertEquals(66.7, DailyBalanceMath.target(Macro.FAT, 2_000, null), 0.1)
        assertEquals(140.0, DailyBalanceMath.target(Macro.PROTEIN, 2_000, 140), 0.0)
        val shares = DailyBalanceMath.shares(100.0, 250.0, 66.7)
        assertEquals(mapOf(Macro.PROTEIN to 20, Macro.CARBS to 50, Macro.FAT to 30), shares)
        assertTrue(DailyBalanceMath.balanced(Macro.FAT, 30))
        assertFalse(DailyBalanceMath.balanced(Macro.CARBS, 30))
        assertEquals(emptyMap<Macro, Int>(), DailyBalanceMath.shares(0.0, 0.0, 0.0))
        assertEquals(22.2, DailyBalanceMath.satFatLimitG(2_000), 0.1)
        assertEquals(50.0, DailyBalanceMath.addedSugarLimitG(2_000), 0.01)
        assertEquals(28.0, DailyBalanceMath.fiberTargetG(2_000), 0.01)
        assertEquals(0, FoodGroupsCard.groupsMet(emptyMap()))
        assertEquals(2, FoodGroupsCard.groupsMet(mapOf(FoodGroup.FRUITS to 2, FoodGroup.DAIRY to 3, FoodGroup.GRAINS to 1)))
    }

    @Test
    fun sportProgressAndRecords() {
        val zone = ZoneOffset.UTC
        val base = today.plusDays(-60).startSec(zone)
        val runs = (0 until 8).map { i -> workout(base + i * 7 * 86_400L, Sports.RUNNING, 1_800, 6_000, avgHr = 150 - i) }
        val ride = workout(base + 3 * 86_400L, Sports.CYCLING, 3_600, 30_000, avgHr = 130, ascent = 400)
        val all = runs + ride
        assertEquals(Sports.RUNNING, SportProgressMath.mainSport(all))
        assertEquals(300.0, SportProgressMath.secPerKm(runs[0])!!, 0.01)
        // Same pace, heart rate dropping over time: fitter.
        val hr = SportProgressMath.hrAtSamePace(runs)!!
        assertTrue(hr.second < hr.first)
        val p = SportProgressMath.progress(Sports.RUNNING, all, today, zone)
        assertEquals(SportProgressMath.WEEKS, p.weeks.size)
        assertEquals(8, p.weeks.sumOf { it.sessions })
        assertEquals(48_000, p.distanceM)
        assertEquals(setOf(Sports.RUNNING, Sports.CYCLING), p.sports.toSet())
        // Records.
        val records = RecordMath.records(all).associate { it.kind to it.workout }
        assertEquals(ride, records[RecordKind.LONGEST_DISTANCE])
        assertEquals(ride, records[RecordKind.LONGEST_TIME])
        assertEquals(runs[0], records[RecordKind.FASTEST_RUN_5K])
        assertEquals(ride, records[RecordKind.BIGGEST_CLIMB])
        assertEquals(0.08, WeeklyReviewCard.share(10_800.0, 10_000.0)!!, 0.0001)
        assertNull(WeeklyReviewCard.share(10_800.0, null))
    }

    @Test
    fun bodyNumbers() {
        assertEquals(64.0, LeanMassCard.leanKg(80_00, 20_00), 0.001)
        assertEquals(24.69, BmiCard.bmi(80.0, 180), 0.01)
        val zone = ZoneOffset.UTC
        val start = today.startSec(zone)
        // A 40-minute nap after lunch counts; the night (started 23:00 the day before) does not.
        val sessions = listOf((start - 3_600) to (start + 7 * 3_600), (start + 13 * 3_600) to (start + 13 * 3_600 + 2_400))
        assertEquals(40, NapsCard.napMinutes(today, sessions, zone))
    }

    private fun minute(t: Long, intensity: Int, device: Long = 1) = ActivityMinute(device, t, 100, 50, 7_000, intensity, null)

    private fun workout(start: Long, sport: Int, durationSec: Int, distanceM: Int?, avgHr: Int? = null, ascent: Int? = null) = Workout(
        start, 1, sport, null, start, start + durationSec, durationSec, distanceM, null, avgHr, null, ascent, null, false, null, null, null, DataSource.WATCH,
    )
}
