/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import android.app.Application
import app.selfwatch.activity.cards.CaloriesTodayCard
import app.selfwatch.activity.cards.DistanceTodayCard
import app.selfwatch.activity.cards.RecoveryHrCard
import app.selfwatch.activity.cards.StepsTodayCard
import app.selfwatch.activity.cards.TodayTimelineCard
import app.selfwatch.body.TestDb
import app.selfwatch.cards.CardEnv
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.model.fake.FakeClock
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.DaySummary
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.Day
import app.selfwatch.service.live.LiveReading
import app.selfwatch.ui.format.Units
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Heart-rate recovery arithmetic, and the steps, Today, recovery, distance and calories Activity cards. */
class RecoveryHrMathTest {
    private val end = 10_000L

    @Test
    fun dropsAtOneAndTwoMinutes() {
        val s = listOf(end - 120 to 150, end - 30 to 152, end + 60 to 128, end + 120 to 112, end + 180 to 105)
        assertEquals(HrRecovery(152, 24, 40), RecoveryHrMath.evaluate(end, s))
    }

    @Test
    fun theEndIsTheReadingClosestToTheStop() {
        // 50 s before and 10 s after: the one after is closer.
        val s = listOf(end - 50 to 140, end + 10 to 150, end + 70 to 130)
        assertEquals(150, RecoveryHrMath.evaluate(end, s)?.endBpm)
        // Nothing within the last minute or the 15 s after: no end, no recovery.
        assertNull(RecoveryHrMath.evaluate(end, listOf(end - 90 to 150, end + 60 to 130)))
        assertNull(RecoveryHrMath.evaluate(end, listOf(end + 20 to 150, end + 60 to 130)))
    }

    @Test
    fun marksAllowHalfAMinuteEitherSide() {
        val s = listOf(end to 150, end + 85 to 125, end + 150 to 110)
        // 1 minute: 85 s is 25 s off, the closest; 2 minutes: 150 s is exactly 30 s off.
        assertEquals(HrRecovery(150, 25, 40), RecoveryHrMath.evaluate(end, s))
    }

    @Test
    fun missingSamples() {
        // No reading near one minute: no recovery at all.
        assertNull(RecoveryHrMath.evaluate(end, listOf(end to 150, end + 100 to 120, end + 120 to 115)))
        // No reading near two minutes: the 1-minute drop alone.
        assertEquals(HrRecovery(150, 20, null), RecoveryHrMath.evaluate(end, listOf(end to 150, end + 60 to 130)))
        // Readings of 0 (no contact) do not count.
        assertNull(RecoveryHrMath.evaluate(end, listOf(end to 150, end + 60 to 0)))
        assertNull(RecoveryHrMath.evaluate(end, emptyList()))
    }

    @Test
    fun aRiseIsANegativeDrop() {
        assertEquals(HrRecovery(100, -8, null), RecoveryHrMath.evaluate(end, listOf(end to 100, end + 60 to 108)))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ActivityHrCardsTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()
    private val clock = FakeClock()
    private val today: Day get() = clock.today()
    private val zone get() = clock.zone()

    private fun env(db: SelfWatchDb) = CardEnv(db, clock, today, Units.METRIC)

    private fun at(hour: Int, minute: Int = 0, day: Day = today): Long = day.startSec(zone) + hour * 3_600L + minute * 60L

    private fun workout(start: Long, minutes: Int, sport: Int = 1) = Workout(
        0, 1, sport, null, start, start + minutes * 60L, minutes * 60, 5_000, 300, 140, 170, null, null, false, null, null, null, DataSource.WATCH,
    )

    private fun summary(day: Day, steps: Int?, active: Int?, resting: Int?, distanceM: Int?) =
        DaySummary(day, -300, steps, active, resting, distanceM, null, null, null, null, null, null, null, null, null, null)

    @Test
    fun recoveryOfTheLatestWorkoutWithReadings() = runBlocking {
        val db = TestDb.fresh(ctx)
        clock.set(at(20))
        val samples = ArrayList<MetricSample>()
        // Five earlier runs with recoveries of 20, 22, 24, 26, 28 bpm.
        for (i in 0 until 5) {
            val w = workout(at(7, day = today.plusDays(-6 + i)), 30)
            db.workouts.insert(w)
            samples += MetricSample(1, MetricKind.HEART_RATE, w.endSec, 150)
            samples += MetricSample(1, MetricKind.HEART_RATE, w.endSec + 60, 130 - 2 * i)
            samples += MetricSample(1, MetricKind.HEART_RATE, w.endSec + 120, 120 - 2 * i)
        }
        // Today's swim has no readings after it: the card shows yesterday's run instead.
        db.workouts.insert(workout(at(12), 40, sport = 5))
        db.metrics.insert(samples)

        val info = RecoveryHrCard.load(env(db))
        assertEquals(at(7, day = today.plusDays(-2)), info.workout?.startSec)
        assertEquals(HrRecovery(150, 28, 38), info.latest)
        assertEquals(listOf(20, 22, 24, 26, 28), info.trend)
        assertEquals(24, info.trendMean)
    }

    @Test
    fun recoveryWithoutReadingsOrWorkouts() = runBlocking {
        val db = TestDb.fresh(ctx)
        val none = RecoveryHrCard.load(env(db))
        assertNull(none.workout)
        assertNull(none.latest)
        assertTrue(none.trend.isEmpty())
        db.workouts.insert(workout(at(6), 30))
        val noReadings = RecoveryHrCard.load(env(db))
        assertEquals(at(6), noReadings.workout?.startSec)
        assertNull(noReadings.latest)
    }

    @Test
    fun stepsWithDistanceAndCalories() = runBlocking {
        val db = TestDb.fresh(ctx)
        clock.set(at(15))
        db.summaries.upsertDay(summary(today.plusDays(-1), 9_000, 400, 1_600, 7_000), 0)
        db.activity.insert(
            listOf(
                ActivityMinute(1, at(9), 1_000, 500, 80_000, 2, null),
                ActivityMinute(1, at(10), 2_000, 700, 150_000, 3, null),
            ),
        )
        val d = StepsTodayCard.load(env(db))
        assertEquals(3_000, d.steps)
        assertEquals(10_000, d.target)
        assertEquals(2_300, d.distanceM)
        assertEquals(120, d.activeKcal)
        assertEquals(9_000, d.week[5])
        val empty = StepsTodayCard.load(env(TestDb.fresh(ctx)))
        assertTrue(empty.isEmpty)
        assertNull(empty.distanceM)
    }

    @Test
    fun liveStepsReplaceTheLastSync() {
        val start = 1_000_000L
        assertEquals(3_000, StepsTodayCard.stepsNow(3_000, null, start))
        assertEquals(4_200, StepsTodayCard.stepsNow(3_000, LiveReading(1, 60, null, 4_200, 10_000, start + 50), start))
        // Never below the stored count, and never yesterday's live count.
        assertEquals(3_000, StepsTodayCard.stepsNow(3_000, LiveReading(1, 60, null, 2_000, null, start + 50), start))
        assertEquals(3_000, StepsTodayCard.stepsNow(3_000, LiveReading(1, 60, null, 8_000, null, start - 50), start))
        assertEquals(500, StepsTodayCard.stepsNow(null, LiveReading(1, null, null, 500, null, start + 1), start))
        assertNull(StepsTodayCard.stepsNow(null, LiveReading(1, 60, null, null, null, start + 1), start))
    }

    @Test
    fun todayHourByHour() = runBlocking {
        val db = TestDb.fresh(ctx)
        clock.set(at(15, 30))
        val data0 = TodayTimelineCard.load(env(db))
        assertTrue(data0.timeline.isEmpty)
        db.activity.insert(
            listOf(
                ActivityMinute(1, at(9), 100, 50, 7_000, 2, null),
                ActivityMinute(1, at(9, 30), 120, 50, 8_000, 2, null),
                ActivityMinute(1, at(14, 10), 90, 50, 6_000, 2, null),
            ),
        )
        db.metrics.insert(listOf(MetricSample(1, MetricKind.HEART_RATE, at(9), 95), MetricSample(1, MetricKind.HEART_RATE, at(14), 0)))
        val data = TodayTimelineCard.load(env(db))
        val t = data.timeline
        assertEquals(24, t.stepsPerHour.size)
        assertEquals(220, t.stepsPerHour[9])
        assertEquals(90, t.stepsPerHour[14])
        assertEquals(0, t.stepsPerHour[15])
        assertEquals(-1, t.stepsPerHour[16]) // still to come
        assertEquals(310, t.totalSteps)
        assertEquals(9, t.busiestHour)
        assertEquals(listOf(95), t.hrV.toList())
        assertTrue("both layers on by default", data.hidden.isEmpty())
    }

    @Test
    fun distanceAndCaloriesToday() = runBlocking {
        val db = TestDb.fresh(ctx)
        clock.set(at(15))
        assertTrue(DistanceTodayCard.load(env(db)).week.isEmpty)
        assertTrue(CaloriesTodayCard.load(env(db)).week.isEmpty)
        db.summaries.upsertDay(summary(today.plusDays(-1), 9_000, 400, 1_600, 7_000), 0)
        db.summaries.upsertDay(summary(today, 3_000, 150, 900, 2_300), 0)
        val dist = DistanceTodayCard.load(env(db))
        assertEquals(2_300, dist.week.today)
        assertEquals(4_650.0, dist.week.average ?: 0.0, 0.01)
        val kcal = CaloriesTodayCard.load(env(db))
        assertEquals(1_050, kcal.week.today)
        assertEquals(2_000, kcal.week.values[5])
        assertEquals(150, kcal.activeKcal)
        assertEquals(900, kcal.restingKcal)
    }
}
