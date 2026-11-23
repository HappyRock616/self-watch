/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import android.app.Application
import app.selfwatch.activity.cards.ActiveDaysCard
import app.selfwatch.activity.cards.ActiveKcalCard
import app.selfwatch.activity.cards.EasyHardCard
import app.selfwatch.activity.cards.IntensityMinutesCard
import app.selfwatch.activity.cards.StepsTodayCard
import app.selfwatch.activity.cards.StrengthSessionsCard
import app.selfwatch.activity.cards.TrainingLoadCard
import app.selfwatch.activity.cards.WeeklyVolumeCard
import app.selfwatch.body.TestDb
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.MainGoal
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.me.GoalField
import app.selfwatch.model.fake.FakeClock
import app.selfwatch.model.food.Goal
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.DaySummary
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.Day
import app.selfwatch.ui.charts.BandLayer
import app.selfwatch.ui.charts.BarLayer
import app.selfwatch.ui.charts.LineLayer
import app.selfwatch.ui.format.Units
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * The phase-2 Activity cards' loaders against a seeded database: week boundaries, vigorous minutes
 * counting double, zone math, the long-session flag, targets by age and goal, and the empty cases; the
 * overlay series and layers; and the Activity presets per goal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ActivityCardsTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()
    private val zone: ZoneId = ZoneId.of("America/Detroit")

    /** Wednesday 2026-11-25, 20:00: this week is Monday 23 to today. */
    private val today = Day(20261125)
    private val clock = FakeClock(today.startSec(zone) + 20 * 3_600, zone)
    private val monday = Day(20261123)

    private fun env(db: SelfWatchDb) = CardEnv(db, clock, today, Units.METRIC)

    private fun at(day: Day, hour: Int): Long = day.startSec(zone) + hour * 3_600L

    @Test
    fun intensityMinutesAndActiveDays() = runBlocking {
        val db = TestDb.fresh(ctx)
        db.activity.insert(
            // Monday: 30 moderate. Tuesday: 10 vigorous (= 20). Sunday (last week): 30 vigorous, not counted.
            (0 until 30).map { minute(at(monday, 8) + it * 60L, 2) } +
                (0 until 10).map { minute(at(monday.plusDays(1), 18) + it * 60L, 3) } +
                (0 until 30).map { minute(at(monday.plusDays(-1), 9) + it * 60L, 3) },
        )
        // Wednesday has no minutes, only an imported summary: 15 active minutes, counted once.
        db.summaries.upsertDay(summary(today, activeMin = 15), clock.nowSec())
        val d = IntensityMinutesCard.load(env(db))
        assertEquals(ActivityWeek.days(today), d.days)
        assertEquals(listOf(30, 20, 15, null, null, null, null), d.perDay)
        assertEquals(65, d.weekMin)
        assertEquals(150, d.target)
        assertFalse(d.extraBenefit)
        assertTrue(d.hasData)
        db.settings.set(SettingKeys.MAIN_GOAL, MainGoal.WEIGHT_LOSS.id)
        val loss = IntensityMinutesCard.load(env(db))
        assertEquals(250, loss.target)
        assertTrue(loss.extraBenefit)

        val a = ActiveDaysCard.load(env(db))
        assertEquals(1, a.activeDays)
        assertTrue(a.hasData)
    }

    @Test
    fun stepsAndActiveKcal() = runBlocking {
        val db = TestDb.fresh(ctx)
        db.summaries.upsertDay(summary(today, steps = 5_000, activeKcal = 320), clock.nowSec())
        db.summaries.upsertDay(summary(today.plusDays(-2), steps = 9_000, activeKcal = 480), clock.nowSec())
        val s = StepsTodayCard.load(env(db))
        assertEquals(5_000, s.steps)
        assertEquals(7_000, s.target)
        assertNull(s.rangeHigh)
        assertFalse(s.ownGoal)
        assertEquals(listOf(null, null, null, null, 9_000, null, 5_000), s.week)
        // 60 and over: 6,000 to 8,000.
        db.settings.setInt(SettingKeys.PROFILE_BIRTH_YEAR, 2026 - 62)
        val older = StepsTodayCard.load(env(db))
        assertEquals(6_000, older.target)
        assertEquals(8_000, older.rangeHigh)
        // The user's own goal wins.
        db.goals.set(Goal(GoalKind.STEPS, 10_000, today.plusDays(-10)))
        val own = StepsTodayCard.load(env(db))
        assertEquals(10_000, own.target)
        assertTrue(own.ownGoal)
        assertNull(own.rangeHigh)

        val k = ActiveKcalCard.load(env(db))
        assertEquals(320, k.kcal)
        assertEquals(400, k.target)
        assertFalse(k.ownGoal)
        db.settings.setInt(GoalField.ACTIVE_KCAL_KEY, 500)
        assertEquals(500, ActiveKcalCard.load(env(db)).target)
    }

    @Test
    fun strengthDaysThisWeek() = runBlocking {
        val db = TestDb.fresh(ctx)
        db.settings.setInt(SettingKeys.PROFILE_BIRTH_YEAR, 2026 - 41)
        for (w in listOf(
            workout(at(monday, 17), 10, 20),
            workout(at(monday, 19), 10, 20),
            workout(at(monday.plusDays(1), 17), 10, 20),
            workout(at(monday.plusDays(-1), 17), 10, 20), // last week
            workout(at(today, 7), 10, 43), // yoga
        )) {
            db.workouts.insert(w)
        }
        val d = StrengthSessionsCard.load(env(db))
        assertEquals(2, d.days)
        assertEquals(2, d.target)
        assertFalse(d.withBalance)
        assertEquals(monday.plusDays(1), d.lastDay)
        // From 65 balance sessions count and the aim is 3.
        db.settings.setInt(SettingKeys.PROFILE_BIRTH_YEAR, 2026 - 66)
        val older = StrengthSessionsCard.load(env(db))
        assertEquals(3, older.days)
        assertEquals(3, older.target)
        assertTrue(older.withBalance)
        assertEquals(today, older.lastDay)
    }

    @Test
    fun trainingLoadAndEasyHardFromZones() = runBlocking {
        val db = TestDb.fresh(ctx)
        db.settings.setInt(SettingKeys.PROFILE_BIRTH_YEAR, 2026 - 40) // max 180
        // Tuesday: 30 minutes with a reading a minute at 150 bpm (83%, zone 4) → load 120, all hard.
        val tue = at(monday.plusDays(1), 18)
        db.workouts.insert(workout(tue, Sports.RUNNING, null, durationSec = 1_800, avgHr = 140, distanceM = 5_000))
        db.metrics.insert((0 until 30).map { MetricSample(1, MetricKind.HEART_RATE, tue + it * 60L, 150) })
        // Last week: 40 minutes without readings, average 126 (70%, zone 3) → load 120, all easy.
        db.workouts.insert(workout(at(monday.plusDays(-4), 7), Sports.RUNNING, null, durationSec = 2_400, avgHr = 126, distanceM = 6_000))
        // A workout without any heart rate counts nowhere.
        db.workouts.insert(workout(at(monday.plusDays(-3), 7), Sports.CYCLING, null, durationSec = 3_600))
        val t = TrainingLoadCard.load(env(db))
        assertEquals(MaxHr(180, MaxHrSource.AGE), t.maxHr)
        assertEquals(6, t.loads.size)
        assertEquals(monday, t.weekStarts.last())
        assertEquals(listOf(0, 0, 0, 0, 120, 120), t.loads)
        assertEquals(120, t.thisWeek)
        assertEquals(30, t.recentAverage)
        assertTrue(t.hasData)

        val e = EasyHardCard.load(env(db))
        assertEquals(2_400, e.easySec)
        assertEquals(1_800, e.hardSec)
        assertEquals(2, e.workouts)
        assertEquals(57, e.easyPct)
        assertEquals(144, e.easyBelowBpm)

        // A stored maximum heart rate wins: at 200, 150 bpm is 75% (zone 3, easy).
        db.settings.setInt(HrZones.MAX_HR_KEY, 200)
        val stored = EasyHardCard.load(env(db))
        assertEquals(100, stored.easyPct)
        assertEquals(MaxHrSource.STORED, TrainingLoadCard.load(env(db)).maxHr.source)
    }

    @Test
    fun weeklyVolumeAndLongSessionFlag() = runBlocking {
        val db = TestDb.fresh(ctx)
        db.workouts.insert(workout(at(today.plusDays(-25), 7), Sports.RUNNING, null, durationSec = 2_700, distanceM = 8_000))
        db.workouts.insert(workout(at(today.plusDays(-12), 7), Sports.RUNNING, null, durationSec = 2_400, distanceM = 7_000))
        // Monday: 9.2 km, 15% longer than the longest run of the 30 days before.
        val long = workout(at(monday, 7), Sports.RUNNING, null, durationSec = 3_000, distanceM = 9_200)
        db.workouts.insert(long)
        db.workouts.insert(workout(at(today, 7), 10, 20, durationSec = 1_800))
        val v = WeeklyVolumeCard.load(env(db))
        assertEquals(4_800, v.durationsSec.last())
        assertEquals(9_200, v.distancesM.last())
        assertEquals(2_400, v.durationsSec[3])
        assertTrue(v.checked)
        val flag = v.longSession ?: error("not flagged")
        assertEquals(9_200, flag.value)
        assertEquals(8_000, flag.previousLongest)
        assertEquals(15, flag.pctLonger)
        assertFalse(v.isEmpty)
    }

    @Test
    fun emptyDatabaseShowsEmptyStates() = runBlocking {
        val db = TestDb.fresh(ctx)
        assertFalse(IntensityMinutesCard.load(env(db)).hasData)
        assertFalse(ActiveDaysCard.load(env(db)).hasData)
        assertTrue(StepsTodayCard.load(env(db)).isEmpty)
        assertTrue(ActiveKcalCard.load(env(db)).isEmpty)
        assertNull(StrengthSessionsCard.load(env(db)).lastDay)
        assertFalse(TrainingLoadCard.load(env(db)).hasData)
        assertNull(EasyHardCard.load(env(db)).easyPct)
        val v = WeeklyVolumeCard.load(env(db))
        assertTrue(v.isEmpty)
        assertNull(v.longSession)
        assertFalse(TrainingOverlay.load(db, clock).hasData)
        assertFalse(ExerciseSleepOverlay.load(db, clock).hasData)
    }

    @Test
    fun overlaySeriesAndLayers() = runBlocking {
        val db = TestDb.fresh(ctx)
        db.settings.setInt(SettingKeys.PROFILE_BIRTH_YEAR, 2026 - 40)
        val tue = at(monday.plusDays(1), 18)
        db.workouts.insert(workout(tue, Sports.RUNNING, null, durationSec = 1_800, avgHr = 150))
        db.activity.insert((0 until 10).map { minute(tue + it * 60L, 3) })
        for (i in 0 until 40) {
            db.summaries.upsertDay(summary(today.plusDays(-i), hrv = 50 + i % 3, restingHr = 55, activeMin = 30, sleepMin = 420), clock.nowSec())
        }
        val o = TrainingOverlay.load(db, clock)
        assertEquals(TrainingOverlay.DAYS, o.size)
        assertEquals(today.plusDays(-(TrainingOverlay.DAYS - 1)), o.firstDay)
        val tueIndex = TrainingOverlay.DAYS - 2
        assertEquals(120, o.load[tueIndex])
        assertEquals(20, o.intensityMin[tueIndex])
        assertEquals(55, o.restingHr7.last())
        assertTrue(o.hrvLow.last() != OverlayMath.MISSING && o.hrvLow.last() <= o.hrv7.last() + 2)
        val layers = TrainingDetailFragment.layersOf(ctx, o, zone)
        assertEquals(OverlayLayers.TRAINING.toggleable, layers.map { it.id })
        assertTrue(layers[0] is BarLayer && layers[1] is BarLayer && layers[2] is LineLayer && layers[3] is BandLayer && layers[4] is LineLayer)

        val w = ExerciseSleepOverlay.load(db, clock)
        assertEquals(ExerciseSleepOverlay.WEEKS, w.size)
        assertEquals(ActivityWeek.start(today), w.firstWeek.plusDays(7 * (ExerciseSleepOverlay.WEEKS - 1)))
        // This week so far: Monday to Wednesday, 3 × 30 minutes.
        assertEquals(90, w.exerciseMin.last())
        assertEquals(420, w.sleepMin.last())
        assertEquals(OverlayLayers.EXERCISE_SLEEP.toggleable, ExerciseSleepDetailFragment.layersOf(ctx, w, zone).map { it.id })

        // The chips' choice is remembered in settings.
        OverlayLayers.TRAINING.save(db, setOf(OverlayLayers.HRV_BAND))
        assertEquals(setOf(OverlayLayers.HRV_BAND), OverlayLayers.TRAINING.load(db))
        assertEquals(emptySet<String>(), OverlayLayers.EXERCISE_SLEEP.load(db))
    }

    @Test
    fun activityPresetsFollowTheDefaultStacks() {
        val expected = mapOf(
            MainGoal.WEIGHT_LOSS to listOf("intensity_minutes", "steps_today", "today_rings", "trends", "workouts"),
            MainGoal.WEIGHT_MANAGEMENT to listOf("intensity_minutes", "active_kcal", "steps_today", "today_rings", "trends", "workouts"),
            MainGoal.BUILD_MUSCLE to listOf("strength_sessions", "workouts", "intensity_minutes", "trends"),
            MainGoal.ATHLETIC to listOf("training_load", "workouts", "easy_hard", "trends"),
            MainGoal.ENDURANCE to listOf("weekly_volume", "easy_hard", "training_load", "workouts", "trends"),
            MainGoal.HEART_HEALTH to listOf("intensity_minutes", "steps_today", "today_rings", "trends"),
            MainGoal.STRESS_WELLBEING to listOf("steps_today", "intensity_minutes", "today_rings"),
            MainGoal.ACTIVE_AGING to listOf("steps_today", "intensity_minutes", "strength_sessions", "active_days", "today_rings"),
            MainGoal.GENERAL_HEALTH to listOf("intensity_minutes", "steps_today", "strength_sessions", "today_rings", "trends", "workouts"),
        )
        assertEquals(MainGoal.entries.toSet(), expected.keys)
        for ((goal, ids) in expected) assertEquals("$goal", ids, goal.cards(CardTab.ACTIVITY).map { it.id })
    }

    private fun minute(t: Long, intensity: Int) = ActivityMinute(1, t, 100, 50, 7_000, intensity, null)

    private fun workout(start: Long, sport: Int, sub: Int?, durationSec: Int = 2_700, avgHr: Int? = null, distanceM: Int? = null) = Workout(
        0, 1, sport, sub, start, start + durationSec, durationSec, distanceM, null, avgHr, null, null, null, false, null, null, null, DataSource.WATCH,
    )

    private fun summary(
        day: Day,
        steps: Int? = null,
        activeKcal: Int? = null,
        activeMin: Int? = null,
        restingHr: Int? = null,
        hrv: Int? = null,
        sleepMin: Int? = null,
    ) = DaySummary(day, 0, steps, activeKcal, null, null, activeMin, restingHr, hrv, sleepMin, null, null, null, null, null, null)
}
