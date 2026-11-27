/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import android.app.Application
import app.selfwatch.R
import app.selfwatch.activity.cards.IntensityMinutesCard
import app.selfwatch.body.BodyLoader
import app.selfwatch.body.TestDb
import app.selfwatch.cards.CardEnv
import app.selfwatch.me.GoalField
import app.selfwatch.me.ProfileForm
import app.selfwatch.model.fake.FakeClock
import app.selfwatch.model.food.Goal
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.health.DaySummary
import app.selfwatch.model.time.Day
import app.selfwatch.ui.format.Units
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * Active minutes are a weekly goal (default 300) everywhere, the one-time move of daily goals to weekly,
 * the 10,000-step default, and the unit conversions on Me › Goals and Me › Profile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WeeklyGoalsTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()
    private val zone: ZoneId = ZoneId.of("America/Detroit")

    /** Wednesday 2026-11-25; the week started Monday 23. */
    private val today = Day(20261125)
    private val monday = Day(20261123)
    private val clock = FakeClock(today.startSec(zone) + 20 * 3_600, zone)

    private fun summary(day: Day, steps: Int? = null, activeMin: Int? = null) =
        DaySummary(day, 0, steps, null, null, null, activeMin, null, null, null, null, null, null, null, null, null)

    @Test
    fun migrationMultipliesLegacyDailyGoalsOnce() = runBlocking {
        val db = TestDb.fresh(ctx)
        db.goals.set(Goal(GoalKind.ACTIVE_MIN, 30, Day(20260101)))
        db.goals.set(Goal(GoalKind.ACTIVE_MIN, 180, Day(20260301)))
        db.goals.set(Goal(GoalKind.ACTIVE_MIN, 250, Day(20260601)))
        db.goals.set(Goal(GoalKind.STEPS, 50, Day(20260101)))
        assertEquals(2, ActiveGoalMigration.run(db))
        assertEquals(listOf(210, 1_260, 250), db.goals.history(GoalKind.ACTIVE_MIN).map { it.value })
        assertEquals(50, db.goals.history(GoalKind.STEPS).single().value)
        assertEquals("1", db.settings.get(ActiveGoalMigration.DONE_KEY))
        // Idempotent: a second run (and a weekly goal of 150 typed later) is left alone.
        db.goals.set(Goal(GoalKind.ACTIVE_MIN, 150, today))
        assertEquals(0, ActiveGoalMigration.run(db))
        assertEquals(listOf(210, 1_260, 250, 150), db.goals.history(GoalKind.ACTIVE_MIN).map { it.value })
        assertEquals(7, ActiveGoalMigration.weekly(1))
        assertEquals(181, ActiveGoalMigration.weekly(181))
    }

    @Test
    fun migrationWithNoGoalsOnlySetsTheFlag() = runBlocking {
        val db = TestDb.fresh(ctx)
        assertEquals(0, ActiveGoalMigration.run(db))
        assertEquals("1", db.settings.get(ActiveGoalMigration.DONE_KEY))
        assertEquals(150, ActivityGoals.activeMinWeek(db, today))
        assertEquals(7_500, ActivityGoals.steps(db, today))
    }

    @Test
    fun ringShowsThisWeeksMinutesAgainstTheWeeklyGoal() = runBlocking {
        val db = TestDb.fresh(ctx)
        db.summaries.upsertDay(summary(monday.plusDays(-1), activeMin = 99), clock.nowSec()) // last week
        db.summaries.upsertDay(summary(monday, activeMin = 40), clock.nowSec())
        db.summaries.upsertDay(summary(monday.plusDays(1), activeMin = 25), clock.nowSec())
        db.summaries.upsertDay(summary(today, steps = 4_000, activeMin = 15), clock.nowSec())
        val loader = ActivityLoader(db, clock)
        val r = loader.rings()
        assertEquals(80, r.activeMin)
        assertEquals(150, r.activeMinGoal)
        assertEquals(4_000, r.steps)
        assertEquals(7_500, r.stepGoal)
        db.goals.set(Goal(GoalKind.ACTIVE_MIN, 160, monday))
        assertEquals(160, loader.rings().activeMinGoal)
        assertEquals(0.5f, loader.rings().let { it.progress(it.activeMin, it.activeMinGoal) }, 1e-4f)
    }

    @Test
    fun trendGoalLineIsASeventhOfTheWeeklyGoal() = runBlocking {
        val db = TestDb.fresh(ctx)
        val loader = ActivityLoader(db, clock)
        assertEquals(21, loader.trend(TrendRange.WEEK).goals[TrendMetric.ACTIVE_MIN])
        assertEquals(21, loader.trend(TrendRange.YEAR).goals[TrendMetric.ACTIVE_MIN])
        db.goals.set(Goal(GoalKind.ACTIVE_MIN, 210, monday))
        assertEquals(30, loader.trend(TrendRange.MONTH).goals[TrendMetric.ACTIVE_MIN])
        assertEquals(7_500, loader.trend(TrendRange.MONTH).goals[TrendMetric.STEPS])
    }

    @Test
    fun intensityCardTargetIsTheWeeklyGoal() = runBlocking {
        val db = TestDb.fresh(ctx)
        val env = CardEnv(db, clock, today, Units.METRIC)
        assertEquals(150, IntensityMinutesCard.load(env).target)
        db.goals.set(Goal(GoalKind.ACTIVE_MIN, 420, monday))
        assertEquals(420, IntensityMinutesCard.load(env).target)
    }

    @Test
    fun bodyStepsCardUsesWeeklyActiveMinutesAndDefaults() = runBlocking {
        val db = TestDb.fresh(ctx)
        db.summaries.upsertDay(summary(monday, activeMin = 50), clock.nowSec())
        db.summaries.upsertDay(summary(today, steps = 6_000, activeMin = 20), clock.nowSec())
        val s = BodyLoader(db, clock).steps()
        assertEquals(6_000, s.steps)
        assertEquals(7_500, s.stepsGoal)
        assertEquals(70, s.activeMin)
        assertEquals(150, s.activeMinGoal)
    }

    @Test
    fun goalsFieldsFollowTheUnits() {
        assertEquals(R.string.goal_unit_min_week, GoalField.ACTIVE_MIN.unit(Units.METRIC))
        assertEquals(10_080, GoalField.ACTIVE_MIN.parse("10080", Units.METRIC))
        assertNull(GoalField.ACTIVE_MIN.parse("10081", Units.METRIC))
        assertEquals(150, GoalField.ACTIVE_MIN.defaultValue(Units.METRIC))
        assertEquals(7_500, GoalField.STEPS.defaultValue(Units.IMPERIAL))
        assertNull(GoalField.DISTANCE.defaultValue(Units.METRIC))
        // Water: 8 glasses, 96 fl oz or 2.8 L.
        assertEquals("96", GoalField.WATER.toText(GoalField.WATER.defaultValue(Units.IMPERIAL)!!, Units.IMPERIAL))
        assertEquals(2_800, GoalField.WATER.defaultValue(Units.METRIC))
        // Water: ml, or US fl oz in imperial units; stored in ml.
        assertEquals(R.string.goal_unit_ml, GoalField.WATER.unit(Units.METRIC))
        assertEquals(R.string.goal_unit_fl_oz, GoalField.WATER.unit(Units.IMPERIAL))
        assertEquals(2_000, GoalField.WATER.parse("2000", Units.METRIC))
        assertEquals(1_893, GoalField.WATER.parse("64", Units.IMPERIAL))
        assertEquals("64", GoalField.WATER.toText(1_893, Units.IMPERIAL))
        for (oz in listOf(8, 64, 100, 128)) {
            val ml = GoalField.WATER.parse(oz.toString(), Units.IMPERIAL) ?: error("parse $oz")
            assertEquals(oz.toString(), GoalField.WATER.toText(ml, Units.IMPERIAL))
        }
        // Weight: kg or lb.
        assertEquals(R.string.goal_unit_lb, GoalField.WEIGHT.unit(Units.IMPERIAL))
        val kgX100 = GoalField.WEIGHT.parse("172", Units.IMPERIAL) ?: error("parse")
        assertEquals("172", GoalField.WEIGHT.toText(kgX100, Units.IMPERIAL))
    }

    @Test
    fun profileHeightInCentimetresOrFeetAndInches() {
        assertEquals("180" to "", ProfileForm.heightFields(180, Units.METRIC))
        assertEquals(180, ProfileForm.heightCm("180", "", Units.METRIC))
        assertEquals("5" to "11", ProfileForm.heightFields(180, Units.IMPERIAL))
        assertEquals(180, ProfileForm.heightCm("5", "11", Units.IMPERIAL))
        assertEquals(175, ProfileForm.heightCm("", "69", Units.IMPERIAL))
        assertEquals(183, ProfileForm.heightCm("6", "", Units.IMPERIAL))
        assertNull(ProfileForm.heightCm("", "", Units.IMPERIAL))
        assertNull(ProfileForm.heightCm("12", "0", Units.IMPERIAL))
        // Round trips: cm → ft/in → cm stays within half an inch.
        for (cm in 120..220) {
            val (ft, inch) = ProfileForm.heightFields(cm, Units.IMPERIAL)
            val back = ProfileForm.heightCm(ft, inch, Units.IMPERIAL) ?: error("$cm")
            assertEquals("$cm", cm.toDouble(), back.toDouble(), 1.5)
            assertEquals(cm, ProfileForm.heightCm(ProfileForm.heightFields(cm, Units.METRIC).first, "", Units.METRIC))
        }
    }
}
