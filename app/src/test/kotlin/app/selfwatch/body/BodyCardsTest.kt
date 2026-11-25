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
import app.selfwatch.body.cards.IntensityCard
import app.selfwatch.body.cards.Spo2Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.food.cards.MacrosTodayCard
import app.selfwatch.model.fake.FakeClock
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.Goal
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.time.Day
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

/** The Body cards' numbers, read by each card's own loader. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BodyCardsTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()
    private val clock = FakeClock()
    private val today: Day get() = clock.today()

    private fun env(db: SelfWatchDb, day: Day = today) = CardEnv(db, clock, day, Units.METRIC)

    @Test
    fun cardNumbers() = runBlocking {
        val db = TestDb.fresh(ctx)
        val zone = clock.zone()
        val start = today.startSec(zone)
        // Macros: 92.4 g protein, 180 g carbs, 61 g fat; no protein goal, 80 kg → 128 g.
        db.diary.addAll(listOf(meal(today, protein = 924, carb = 1_800, fat = 610)))
        db.body.insert(BodyMeasure(0, start - 86_400, BodyKind.WEIGHT, null, 80_00, DataSource.MANUAL, null, null))
        db.goals.set(Goal(GoalKind.CARB_G, 250, today))
        // Activity: 20 vigorous minutes today, 40 three days ago, 30 moderate.
        val minutes = (0 until 20).map { minute(start + 8 * 3_600 + it * 60L, 3) } +
            (0 until 40).map { minute(today.plusDays(-3).startSec(zone) + 18 * 3_600 + it * 60L, 3) } +
            (0 until 30).map { minute(start + 12 * 3_600 + it * 60L, 2) } +
            (0 until 10).map { minute(today.plusDays(-8).startSec(zone) + 9 * 3_600 + it * 60L, 3) }
        db.activity.insert(minutes)
        db.goals.set(Goal(GoalKind.STEPS, 10_000, today))
        // SpO2: two nights; last night 23:00 and 04:00 readings.
        val lastNight = start - 3_600
        db.metrics.insert(
            listOf(
                MetricSample(1, MetricKind.SPO2, lastNight, 95),
                MetricSample(1, MetricKind.SPO2, start + 4 * 3_600, 98),
                MetricSample(1, MetricKind.SPO2, lastNight - 86_400, 93),
            ),
        )
        val m = MacrosTodayCard.load(env(db))
        assertEquals(92, m.proteinG)
        assertEquals(180, m.carbG)
        assertEquals(61, m.fatG)
        assertEquals(128, m.proteinGoalG)
        assertTrue(m.proteinGoalAuto)
        assertEquals(250, m.carbGoalG)
        assertNull(m.fatGoalG)
        assertFalse(m.isEmpty)
        assertEquals(IntensityInfo(todayMin = 20, weekMin = 60, weekGoalMin = 75), IntensityCard.load(env(db)))
        val s = BodyLoader(db, clock).steps()
        assertEquals(500, s.steps)
        assertEquals(10_000, s.stepsGoal)
        assertEquals(50, s.activeMin)
        val o = Spo2Card.load(env(db)).spo2 ?: error("no spo2")
        assertEquals(97, o.lastPct)
        assertEquals(today, o.lastDay)
        assertEquals(listOf(93, 97), o.nights)

        // A set protein goal wins over body weight.
        db.goals.set(Goal(GoalKind.PROTEIN_G, 150, today))
        val withGoal = MacrosTodayCard.load(env(db))
        assertEquals(150, withGoal.proteinGoalG)
        assertFalse(withGoal.proteinGoalAuto)

        // Food's macros card reads the diary day shown.
        assertEquals(withGoal, MacrosTodayCard.load(env(db)))
        assertTrue(MacrosTodayCard.load(env(db, today.plusDays(-1))).isEmpty)
    }

    @Test
    fun emptyCardsSayNothing() = runBlocking {
        val db = TestDb.fresh(ctx)
        val m = MacrosTodayCard.load(env(db))
        assertTrue(m.isEmpty)
        assertNull("no weight, no goal: no protein target", m.proteinGoalG)
        assertNull(IntensityCard.load(env(db)))
        assertNull(Spo2Card.load(env(db)).spo2)
        assertTrue(BodyLoader(db, clock).steps().isEmpty)
    }

    @Test
    fun pureHelpers() {
        assertEquals(128, BodyLoader.proteinFromWeight(80_00))
        assertEquals(112, BodyLoader.proteinFromWeight(70_00))
        assertEquals(92, BodyLoader.grams(924))
        assertEquals(93, BodyLoader.grams(925))
        assertNull(BodyLoader.grams(null))
        val zone = clock.zone()
        val d = today.startSec(zone)
        val nights = BodyLoader.nightlyAverages(
            listOf(
                MetricSample(1, MetricKind.SPO2, d - 7 * 3_600, 90), // 17:00 yesterday: the night before
                MetricSample(1, MetricKind.SPO2, d - 5 * 3_600, 96), // 19:00 yesterday: last night
                MetricSample(1, MetricKind.SPO2, d + 3 * 3_600, 97),
            ),
            zone,
        )
        assertEquals(listOf(today.plusDays(-1) to 90, today to 97), nights)
    }

    private fun minute(t: Long, intensity: Int) = ActivityMinute(1, t, if (intensity >= 2) 10 else 0, 10, 100, intensity, null)

    private fun meal(day: Day, protein: Int, carb: Int, fat: Int) = DiaryEntry(
        0, day, 2L, day.startSec(clock.zone()) + 12 * 3_600L, null, "Lunch", null, 100, "1 serving", null,
        Nutrients(6_000, protein, fat, null, carb, null, null, null, null), null,
    )
}
