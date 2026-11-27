/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body

import android.app.Application
import app.selfwatch.activity.cards.ActivityChartCard
import app.selfwatch.body.cards.HeartChartCard
import app.selfwatch.body.cards.SleepChartCard
import app.selfwatch.body.cards.WeightChartCard
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardPresets
import app.selfwatch.cards.CardRegistry
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.MainGoal
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.model.fake.FakeClock
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.health.SleepStageSample
import app.selfwatch.model.time.Day
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

/** The chart cards read the same data as their detail screens and build the same layers with the same stored choice. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ChartCardsTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()
    private val clock = FakeClock()
    private val today: Day get() = clock.today()
    private val zone get() = clock.zone()

    private fun env(db: SelfWatchDb) = CardEnv(db, clock, today, Units.METRIC)

    private suspend fun seedNight(db: SelfWatchDb) {
        val bed = today.startSec(zone) - 3_600
        db.sleep.insertSession(SleepSession(1, bed, bed + 450 * 60L, 80))
        db.sleep.insertStages(
            listOf(
                SleepStageSample(1, bed, SleepStage.LIGHT),
                SleepStageSample(1, bed + 60 * 60, SleepStage.DEEP),
                SleepStageSample(1, bed + 150 * 60, SleepStage.REM),
                SleepStageSample(1, bed + 440 * 60, SleepStage.AWAKE),
            ),
        )
        db.metrics.insert(
            (0 until 10).map { MetricSample(1, MetricKind.HEART_RATE, bed + it * 1_800L, 52 + it) } +
                MetricSample(1, MetricKind.HRV_RMSSD, bed + 2 * 3_600, 48),
        )
    }

    @Test
    fun sleepChartCardLoadsTheNightViewWithItsStoredChoice() = runBlocking {
        val db = TestDb.fresh(ctx)
        seedNight(db)
        db.settings.set(SettingKeys.MAIN_GOAL, MainGoal.GENERAL_HEALTH.id)

        val data = SleepChartCard.load(env(db))
        val night = checkNotNull(data.night)
        // The same night the Sleep screen's Night view shows.
        assertEquals(SleepViewModel.loadNight(db, today, zone), night)
        assertEquals(10, night.hr.size)
        // Never chosen: the main goal's defaults, the same as the detail screen reads.
        assertEquals(SleepLayers.NIGHT_CHOICE.load(db), data.hidden)
        // The card and the screen build the same layers, in chip order; series without samples are left out.
        val layers = SleepChart.nightLayers(ctx, night, data.units).map { it.id }
        assertEquals(listOf(SleepLayers.STAGES, SleepLayers.HR, SleepLayers.HRV), layers)
        assertTrue(SleepLayers.NIGHT.containsAll(layers))
        // A chip change on the detail screen reaches the card's next load.
        SleepLayers.NIGHT_CHOICE.save(db, setOf(SleepLayers.HRV))
        assertEquals(setOf(SleepLayers.HRV), SleepChartCard.load(env(db)).hidden)
        // Small form: time asleep (light + deep + REM).
        val small = checkNotNull(SleepChartCard.small(ctx, data))
        assertTrue(small.value.isNotEmpty())
    }

    @Test
    fun chartCardsAreEmptyWithoutData() = runBlocking {
        val db = TestDb.fresh(ctx)
        val sleep = SleepChartCard.load(env(db))
        assertNull(sleep.night)
        assertNull(SleepChartCard.small(ctx, sleep))
        val heart = HeartChartCard.load(env(db))
        assertTrue(heart.isEmpty)
        assertNull(HeartChartCard.small(ctx, heart))
        assertTrue(HeartChart.dayLayers(ctx, heart).isEmpty())
        val weight = WeightChartCard.load(env(db))
        assertTrue(weight.entries.isEmpty())
        assertNull(WeightChartCard.small(ctx, weight))
        val activity = ActivityChartCard.load(env(db))
        assertNull(ActivityChartCard.small(ctx, activity))
    }

    @Test
    fun weightAndHeartChartCardsMatchTheirScreens() = runBlocking {
        val db = TestDb.fresh(ctx)
        for (offset in 20 downTo 0) {
            val t = today.plusDays(-offset).startSec(zone) + 7 * 3_600
            db.body.insert(BodyMeasure(0, t, BodyKind.WEIGHT, null, 80_00 - offset * 10, DataSource.SCALE, null, null))
        }
        db.metrics.insert((0 until 6).map { MetricSample(1, MetricKind.HEART_RATE, today.startSec(zone) + 8 * 3_600 + it * 600L, 70 + it) })

        val weight = WeightChartCard.load(env(db))
        val screen = WeightViewModel.load(db, clock, WeightRange.D90, Units.METRIC)
        assertEquals(screen.copy(overlay = null), weight.copy(overlay = null))
        assertEquals(WeightLayers.WEIGHT, WeightChart.layers(ctx, weight, zone).first().id)
        assertTrue(checkNotNull(WeightChartCard.small(ctx, weight)).value.startsWith("80"))

        val heart = HeartChartCard.load(env(db))
        assertEquals(HeartViewModel.loadDay(db, today, zone), heart)
        assertEquals(listOf("hr"), HeartChart.dayLayers(ctx, heart).map { it.id })
        assertEquals("73", checkNotNull(HeartChartCard.small(ctx, heart)).value)
    }

    @Test
    fun chartCardsAreRegisteredLargeAndSleepChartIsABodyDefault() {
        for (c in listOf(SleepChartCard, HeartChartCard, WeightChartCard, ActivityChartCard)) {
            assertEquals(c, CardRegistry.byId(c.id))
            assertEquals(CardSize.LARGE, c.defaultSize)
        }
        val body = CardPresets.layout(CardTab.BODY)
        assertEquals(SleepChartCard, body[1].card)
        assertEquals(CardSize.LARGE, body[1].size)
    }
}
