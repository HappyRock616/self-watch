/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.selfwatch.body

import android.app.Application
import app.selfwatch.activity.ActivityLoader
import app.selfwatch.activity.TrendRange
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.me.GoalField
import app.selfwatch.me.GoalsViewModel
import app.selfwatch.model.fake.FakeClock
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.Goal
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.DaySummary
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.health.SleepStageSample
import app.selfwatch.model.time.Day
import app.selfwatch.ui.format.Units
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Empties the health, body, goal and settings tables of the shared Robolectric database. */
object TestDb {
    private val TABLES = listOf(
        "activity_minute", "metric", "sleep_stage", "sleep_session", "workout", "body_measure", "measure_kind",
        "goal", "setting", "day_summary", "diary_entry", "import_batch", "device",
    )

    /** The database, emptied. */
    fun fresh(ctx: Application): SelfWatchDb {
        val db = SelfWatchDb.get(ctx)
        for (t in TABLES) db.writableDatabase.execSQL("DELETE FROM $t")
        return db
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BodyViewModelsTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()
    private val clock = FakeClock()
    private val today: Day get() = clock.today()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun summary(day: Day, steps: Int? = null, active: Int? = null, resting: Int? = null, kcalIn: Int? = null, sleep: Int? = null, rhr: Int? = null) =
        DaySummary(day, 0, steps, active, resting, steps?.let { it * 3 / 4 }, 40, rhr, 50, sleep, kcalIn, null, null, null, null, null)

    @Test
    fun energyCardNumbers() = runBlocking {
        val db = TestDb.fresh(ctx)
        db.summaries.upsertDay(summary(today, active = 420, resting = 1_000, kcalIn = 1_180, sleep = 432, rhr = 54), 0)
        db.summaries.upsertDay(summary(today.plusDays(-1), active = 300, resting = 1_700, kcalIn = 2_100), 0)
        db.goals.set(Goal(GoalKind.KCAL_IN, 2_000, today.plusDays(-30)))
        // Food comes from the diary (as on the Food screen), not the summary's copy.
        db.diary.addAll(listOf(lunch(today, 1_180), lunch(today.plusDays(-1), 2_100)))
        val loader = BodyLoader(db, clock)
        val s = loader.energy()
        assertEquals(2_000, s.energy.goalKcal)
        assertEquals(1_420, s.energy.outKcal)
        assertEquals(1_240, s.energy.remainingKcal)
        assertEquals(7, s.week.size)
        assertEquals(2_000, s.week[5].outKcal)
        assertEquals(2_100, s.week[5].inKcal)
        assertEquals(-1, s.week[0].inKcal)
        assertEquals(Recovery(432, 54, 50), loader.recovery())
        assertNull(loader.weight())
    }

    @Test
    fun weightCardAndScreenState() = runBlocking {
        val db = TestDb.fresh(ctx)
        val vm = BodyViewModel.withoutWatch(db, clock)
        val first = withTimeout(5_000) { vm.state.first { !it.loading } }
        assertEquals(today, first.today)
        assertNull(first.watch)
        val start = today.plusDays(-20).startSec(clock.zone()) + 7 * 3600
        for (i in 0..20) {
            db.body.insert(BodyMeasure(0, start + i * 86_400L, BodyKind.WEIGHT, null, 84_00 - i * 10, DataSource.MANUAL, null, null))
        }
        db.goals.set(Goal(GoalKind.WEIGHT_KG_X100, 78_00, today))
        val w = BodyLoader(db, clock).weight() ?: error("no weight")
        assertEquals(82_00, w.latestKgX100)
        assertEquals(78_00, w.goalKgX100)
        assertTrue("trend fell this week: ${w.weekChangeX100}", (w.weekChangeX100 ?: 0) < 0)
        assertTrue(w.spark.size >= 2)
    }

    @Test
    fun weightScreenRangeTrendAndGoal() = runBlocking {
        val db = TestDb.fresh(ctx)
        val start = today.plusDays(-100).startSec(clock.zone()) + 7 * 3600
        for (i in 0..100 step 2) db.body.insert(BodyMeasure(0, start + i * 86_400L, BodyKind.WEIGHT, null, 90_00 - i * 10, DataSource.MANUAL, null, null))
        db.settings.setProfile(db.settings.profile().copy(heightCm = 180))
        val vm = WeightViewModel(db, clock)
        val d30 = vm.load(WeightRange.D30, Units.METRIC)
        val all = vm.load(WeightRange.ALL, Units.METRIC)
        assertTrue(d30.entries.size in 14..16)
        assertEquals(51, all.entries.size)
        // The trend lags a falling weight, so it sits above the latest weigh-in.
        assertTrue((all.trendKgX100 ?: 0) > all.entries.last().valueX100)
        assertEquals(80_00 / 1.8 / 1.8 / 100.0, all.bmi ?: 0.0, 0.1)
        vm.setGoal(75_00)
        assertEquals(75_00, withTimeout(5_000) { vm.state.first { it.goal != null } }.goal?.value)
    }

    @Test
    fun sleepNightAssemblyFromTheDatabase() = runBlocking {
        val db = TestDb.fresh(ctx)
        val bed = today.plusDays(-1).startSec(clock.zone()) + 23 * 3600
        val up = bed + 7 * 3600
        db.sleep.insertSession(SleepSession(1, bed, up, 80))
        db.sleep.insertStages(
            listOf(
                SleepStageSample(1, bed, SleepStage.AWAKE),
                SleepStageSample(1, bed + 600, SleepStage.LIGHT),
                SleepStageSample(1, bed + 3_600, SleepStage.DEEP),
                SleepStageSample(1, bed + 3 * 3_600, SleepStage.REM),
                SleepStageSample(1, up - 600, SleepStage.AWAKE),
            ),
        )
        // Heart rate inside the night, one sample before it (left out).
        db.metrics.insert(
            listOf(
                MetricSample(1, MetricKind.HEART_RATE, bed - 600, 90),
                MetricSample(1, MetricKind.HEART_RATE, bed + 1_200, 56),
                MetricSample(1, MetricKind.HEART_RATE, bed + 7_200, 50),
                MetricSample(1, MetricKind.RESPIRATION, bed + 7_200, 142),
            ),
        )
        val vm = SleepViewModel(db, clock)
        val s = withTimeout(5_000) { vm.state.first { !it.loading } }
        val n = s.night ?: error("no night")
        assertEquals(2, n.hr.size)
        assertEquals(53, n.hr.average())
        assertEquals(1, n.breathing.size)
        assertEquals(10 + 10, n.stageMin[SleepStage.AWAKE])
        assertEquals(n.stageMin[SleepStage.LIGHT]!! + n.stageMin[SleepStage.DEEP]!! + n.stageMin[SleepStage.REM]!!, n.asleepMin)
        assertEquals(400, n.asleepMin)
        // The week view reads day summaries.
        db.summaries.upsertDay(summary(today, sleep = 400, rhr = 52), 0)
        vm.setRange(PeriodRange.WEEK)
        val w = withTimeout(5_000) { vm.state.first { it.range == PeriodRange.WEEK && it.days.isNotEmpty() } }
        assertEquals(7, w.days.size)
        assertEquals(400, w.days.last().sleepMin)
        vm.previous()
        vm.setRange(PeriodRange.SINGLE)
        val earlier = withTimeout(5_000) { vm.state.first { it.range == PeriodRange.SINGLE && it.nightDay == today.plusDays(-1) } }
        assertNull(earlier.night)
        assertTrue(earlier.canGoNext)
    }

    @Test
    fun heartDayAndPeriod() = runBlocking {
        val db = TestDb.fresh(ctx)
        val noon = today.startSec(clock.zone()) + 12 * 3600
        db.metrics.insert(listOf(MetricSample(1, MetricKind.HEART_RATE, noon, 70), MetricSample(1, MetricKind.HEART_RATE, noon + 60, 90)))
        val vm = HeartViewModel(db, clock)
        val day = withTimeout(5_000) { vm.state.first { !it.loading } }
        assertEquals(2, day.hr.size)
        vm.setRange(PeriodRange.MONTH)
        val month = withTimeout(5_000) { vm.state.first { it.range == PeriodRange.MONTH && !it.loading } }
        assertEquals(30, month.days.size)
        assertEquals(70, month.days.last().minHr)
        assertEquals(90, month.days.last().maxHr)
    }

    @Test
    fun activityRingsAgainstGoalsAndStreak() = runBlocking {
        val db = TestDb.fresh(ctx)
        for (i in 0..5) db.summaries.upsertDay(summary(today.plusDays(-i), steps = if (i == 0) 4_000 else 11_000, active = 350), 0)
        db.goals.set(Goal(GoalKind.STEPS, 10_000, today.plusDays(-60)))
        db.settings.setInt(GoalField.ACTIVE_KCAL_KEY, 500)
        val loader = ActivityLoader(db, clock)
        val rings = loader.rings()
        assertEquals(4_000, rings.steps)
        assertEquals(10_000, rings.stepGoal)
        assertEquals(500, rings.activeKcalGoal)
        assertEquals(350, rings.activeKcal)
        assertEquals(5, loader.streak())
        assertEquals(7, loader.trend(TrendRange.WEEK).starts.size)
        val year = loader.trend(TrendRange.YEAR)
        assertEquals(12, year.starts.size)
        assertTrue(!year.daily)
        // The Trends card's range is a setting (month until chosen).
        assertEquals(TrendRange.MONTH, loader.trendRange())
        db.settings.set(TrendRange.KEY, TrendRange.YEAR.id)
        assertEquals(TrendRange.YEAR, loader.trendRange())
        assertEquals(12, app.selfwatch.activity.cards.TrendsCard.load(app.selfwatch.cards.CardEnv(db, clock, today, Units.METRIC)).trend.starts.size)
        val vm = app.selfwatch.activity.ActivityViewModel(db, clock)
        assertEquals(5, withTimeout(5_000) { vm.state.first { !it.loading } }.streak)
    }

    @Test
    fun goalsAreSavedAsDatedRows() = runBlocking {
        val db = TestDb.fresh(ctx)
        db.goals.set(Goal(GoalKind.STEPS, 8_000, today.plusDays(-10)))
        val vm = GoalsViewModel(db, clock)
        vm.saveNow(mapOf(GoalField.STEPS to 12_000, GoalField.ACTIVE_KCAL to 450))
        val history = db.goals.history(GoalKind.STEPS)
        assertEquals(listOf(8_000, 12_000), history.map { it.value })
        assertEquals(today, history.last().fromDay)
        // The past keeps its goal.
        assertEquals(8_000, db.goals.goalOn(GoalKind.STEPS, today.plusDays(-1))?.value)
        assertEquals(450, db.settings.getInt(GoalField.ACTIVE_KCAL_KEY))
        val loaded = vm.load(Units.METRIC)
        assertNotNull(loaded.values[GoalField.STEPS])
        assertEquals(12_000, loaded.values[GoalField.STEPS]?.value)
    }

    private fun lunch(day: Day, kcal: Int) = DiaryEntry(
        0, day, 2L, day.startSec(java.time.ZoneOffset.UTC) + 12 * 3_600L, null, "Lunch", null, 100, "1 serving", null,
        Nutrients(kcal * 10, null, null, null, null, null, null, null, null), null,
    )
}
