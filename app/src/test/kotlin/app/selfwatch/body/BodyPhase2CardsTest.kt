/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body

import android.app.Application
import app.selfwatch.body.cards.BodyFatCard
import app.selfwatch.body.cards.BodySignalsCard
import app.selfwatch.body.cards.BodyStressCard
import app.selfwatch.body.cards.EnergyCard
import app.selfwatch.body.cards.HrvBaselineCard
import app.selfwatch.body.cards.RestingHrCard
import app.selfwatch.body.cards.SleepCard
import app.selfwatch.body.cards.WaistHeightCard
import app.selfwatch.body.cards.WeightCard
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.MainGoal
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.model.fake.FakeClock
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.DaySummary
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.health.SleepStageSample
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.Day
import app.selfwatch.ui.format.Units
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The phase-2 Body cards, each read by its own loader, with data and with an empty database; and the overlay loaders. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BodyPhase2CardsTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()
    private val clock = FakeClock()
    private val today: Day get() = clock.today()
    private val zone get() = clock.zone()

    private fun env(db: SelfWatchDb) = CardEnv(db, clock, today, Units.METRIC)

    private fun summary(day: Day, restingHr: Int? = null, hrv: Int? = null, sleep: Int? = null, active: Int? = null, resting: Int? = null) =
        DaySummary(day, -300, null, active, resting, null, null, restingHr, hrv, sleep, null, null, null, null, null, null)

    /** Resting HR: 60 for days 55–28 ago, 55/57 for 27–1 ago, 61 today. */
    private fun restingFor(offset: Int): Int = when {
        offset == 0 -> 61
        offset <= 27 -> if (offset % 2 == 0) 55 else 57
        else -> 60
    }

    private suspend fun seed(db: SelfWatchDb) {
        for (offset in 55 downTo 0) {
            val d = today.plusDays(-offset)
            val hrv = when {
                offset > 39 -> null
                offset < 7 -> 50
                else -> if (offset % 2 == 0) 45 else 55
            }
            db.summaries.upsertDay(summary(d, restingFor(offset), hrv, if (offset < 14) 450 else null, 500, 1_700), 0)
        }
        // Last night's stages: light 60, deep 90, REM 290, awake 10 minutes.
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
        // 20 earlier nights for the body signals; skin temperature and breathing inside each night.
        val samples = ArrayList<MetricSample>()
        for (offset in 20 downTo 0) {
            val nightBed = today.plusDays(-offset).startSec(zone) - 3_600
            if (offset > 0) db.sleep.insertSession(SleepSession(1, nightBed, nightBed + 450 * 60L, null))
            val skin = if (offset == 0) 50 else if (offset % 2 == 0) 10 else -10
            val resp = if (offset == 0) 141 else if (offset % 2 == 0) 143 else 137
            samples += MetricSample(1, MetricKind.SKIN_TEMP_DELTA, nightBed + 4 * 3_600, skin)
            samples += MetricSample(1, MetricKind.RESPIRATION, nightBed + 2 * 3_600, resp)
        }
        // Body stress today: 20 (rest) for an hour, then 80 for an hour.
        val morning = today.startSec(zone) + 8 * 3_600
        for (i in 0 until 40) samples += MetricSample(1, MetricKind.STRESS, morning + i * 180L, if (i < 20) 20 else 80)
        db.metrics.insert(samples)
        // Waist 90 cm, height 180 cm; weight 81 kg; body fat 22 → 20 % over 60 days.
        db.settings.setProfile(db.settings.profile().copy(heightCm = 180))
        db.body.insert(BodyMeasure(0, today.startSec(zone) - 40 * 86_400L, BodyKind.WAIST, null, 92_00, DataSource.MANUAL, null, null))
        db.body.insert(BodyMeasure(0, today.startSec(zone) - 86_400L, BodyKind.WAIST, null, 90_00, DataSource.MANUAL, null, null))
        for (offset in 60 downTo 0 step 5) {
            val t = today.plusDays(-offset).startSec(zone) + 7 * 3_600
            db.body.insert(BodyMeasure(0, t, BodyKind.BODY_FAT, null, 2_000 + offset * 200 / 60, DataSource.SCALE, null, null))
        }
        for (offset in 35 downTo 0) {
            val t = today.plusDays(-offset).startSec(zone) + 7 * 3_600 - 60
            db.body.insert(BodyMeasure(0, t, BodyKind.WEIGHT, null, 79_00 + offset * 15, DataSource.SCALE, null, null))
        }
        // Food: 2,000 kcal on each of the last 6 days (out is 2,200).
        db.diary.addAll(
            (1..6).map { o ->
                val d = today.plusDays(-o)
                DiaryEntry(0, d, 2L, d.startSec(zone) + 12 * 3_600L, null, "Lunch", null, 100, "1 serving", null, Nutrients(20_000, 1_280, null, null, null, null, null, null, null), null)
            },
        )
    }

    @Test
    fun cardsReadTheirNumbers() = runBlocking {
        val db = TestDb.fresh(ctx)
        seed(db)

        val sleep = SleepCard.load(env(db))
        assertEquals(450, sleep.lastMin)
        assertEquals(today, sleep.lastDay)
        assertEquals(450, sleep.avgMin)
        assertEquals(90, sleep.stageMin[SleepStage.DEEP])
        assertEquals(290, sleep.stageMin[SleepStage.REM])
        assertEquals(10, sleep.stageMin[SleepStage.AWAKE])
        assertEquals(14, sleep.nights.size)
        assertFalse(sleep.isEmpty)

        val wh = WaistHeightCard.load(env(db)).info
        assertEquals(180, wh.heightCm)
        assertEquals(90_00, wh.waistX100)
        assertEquals(50, wh.ratioX100)
        assertEquals(WaistBand.RAISED, BodyCardMath.waistBand(wh.ratioX100 ?: 0))
        assertEquals(-2_00, wh.changeX100)
        assertNotNull(wh.bmi)

        val fat = BodyFatCard.load(env(db)).fat ?: error("no body fat")
        assertEquals(2_000, fat.latestX100)
        assertTrue("trend lags behind the falling readings", fat.trendX100 in 2_001..2_200)
        assertTrue((fat.change90X100 ?: 0) < 0)

        val hrv = HrvBaselineCard.load(env(db))
        val h = hrv.result ?: error("no hrv")
        assertEquals(50, h.sevenDayMs)
        assertEquals(RangeStatus.WITHIN, h.status)
        assertEquals(40, hrv.nights)

        val rhr = RestingHrCard.load(env(db)).trend ?: error("no resting hr")
        assertEquals(56, rhr.currentBpm)
        assertEquals(60, rhr.baselineBpm)
        assertEquals(RangeStatus.BELOW, rhr.status)

        val stress = BodyStressCard.load(env(db)).stress ?: error("no stress")
        assertEquals(today, stress.day)
        assertEquals(50, stress.avg)
        assertEquals(60, stress.restMin)
        assertEquals(7, stress.daily.size)

        val signals = BodySignalsCard.load(env(db))
        val s = signals.result ?: error("no signals")
        assertEquals(today, s.day)
        assertEquals(setOf(SignalKind.SKIN_TEMP, SignalKind.RESTING_HR), s.readings.filter { it.up }.map { it.kind }.toSet())
        assertTrue("two signals up together", s.flagged)

        // Weight: trend first, and the band for the main goal.
        db.settings.set(SettingKeys.MAIN_GOAL, MainGoal.WEIGHT_LOSS.id)
        val w = WeightCard.load(env(db))
        val band = w.band ?: error("no band")
        assertEquals(WeightBand.Kind.LOSS_RATE, band.kind)
        assertTrue((band.ratePerWeekX100 ?: 0) < 0)
        assertNotNull(band.status)
        db.settings.set(SettingKeys.MAIN_GOAL, MainGoal.GENERAL_HEALTH.id)
        assertNull(WeightCard.load(env(db)).band)

        // Energy: 2,000 in and 2,200 out on six complete days → about −200 a day.
        assertEquals(-200, EnergyCard.load(env(db)).balanceKcal)
    }

    @Test
    fun emptyDatabaseShowsEmptyStates() = runBlocking {
        val db = TestDb.fresh(ctx)
        assertTrue(SleepCard.load(env(db)).isEmpty)
        val wh = WaistHeightCard.load(env(db)).info
        assertNull(wh.heightCm)
        assertNull(wh.ratioX100)
        assertNull(BodyFatCard.load(env(db)).fat)
        val hrv = HrvBaselineCard.load(env(db))
        assertNull(hrv.result)
        assertEquals(0, hrv.nights)
        assertNull(RestingHrCard.load(env(db)).trend)
        assertNull(BodyStressCard.load(env(db)).stress)
        val sig = BodySignalsCard.load(env(db))
        assertNull(sig.result)
        assertEquals(0, sig.nights)
        assertNull(WeightCard.load(env(db)).band)
        assertNull(EnergyCard.load(env(db)).balanceKcal)
        // A waist without a height still asks for the height.
        db.body.insert(BodyMeasure(0, clock.nowSec() - 3_600, BodyKind.WAIST, null, 90_00, DataSource.MANUAL, null, null))
        val noHeight = WaistHeightCard.load(env(db)).info
        assertEquals(90_00, noHeight.waistX100)
        assertNull(noHeight.ratioX100)
    }

    @Test
    fun overlayLoaders() = runBlocking {
        val db = TestDb.fresh(ctx)
        assertFalse(ProteinOverlay.load(db, clock).hasData)
        assertFalse(HeartTrendOverlay.load(db, clock).hasData)
        assertFalse(SignalsOverlay.load(db, clock).hasData)
        seed(db)
        // O3: protein logged on six days (128 g at about 79 kg → 1.6 g/kg) and one strength session this week.
        val start = today.plusDays(-2).startSec(zone) + 17 * 3_600
        db.workouts.insert(Workout(0, 1, 10, 20, start, start + 2_700, 2_700, null, 200, null, null, null, null, false, null, null, null, DataSource.WATCH))
        val p = ProteinOverlay.load(db, clock)
        assertEquals(ProteinOverlay.WEEKS, p.weeks)
        assertEquals(16, p.proteinPerKgX10.last())
        assertEquals(1, p.strength.sum())
        // O6: activity this week (10 vigorous minutes → 20) and the rolling resting heart rate.
        db.activity.insert((0 until 10).map { ActivityMinute(1, today.startSec(zone) + 6 * 3_600 + it * 60L, 0, 10, 0, 3, null) })
        val h = HeartTrendOverlay.load(db, clock)
        assertEquals(20, h.intensityMin.last())
        assertTrue(h.restBpm.isNotEmpty())
        assertEquals(today, h.restDays.last())
        // O7: the last night's skin temperature is well above its baseline.
        val s = SignalsOverlay.load(db, clock)
        assertEquals(SignalsOverlay.NIGHTS, s.days.size)
        assertTrue(s.z.getValue(SignalKind.SKIN_TEMP).last() > 20)
        assertEquals(50, s.deviation.getValue(SignalKind.SKIN_TEMP).last())
        assertTrue(s.hasData)
    }
}
