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
import app.selfwatch.model.fake.FakeClock
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.DaySummary
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private const val M = WeightOverlay.MISSING

private fun summary(day: Day, active: Int? = null, resting: Int? = null, activeMin: Int? = null, steps: Int? = null, kcalIn: Int? = null) =
    DaySummary(day, 0, steps, active, resting, null, activeMin, null, null, null, kcalIn, null, null, null, null, null)

/** The pure assembly of the Weight chart's calorie and activity series. */
class WeightOverlayBuildTest {
    private val first = Day(20261101)

    @Test
    fun dailyInAndOutLineUpByDayWithGapsForMissingDays() {
        val last = first.plusDays(4)
        val o = WeightOverlay.build(
            first,
            last,
            mapOf(first to 19_204L, first.plusDays(1) to 21_005L, first.plusDays(3) to 18_000L),
            listOf(
                summary(first, active = 400, resting = 1_700, activeMin = 45, steps = 9_000),
                summary(first.plusDays(2), active = 300, activeMin = 20),
                summary(first.plusDays(3), resting = 1_800, steps = 4_000),
            ),
        )
        assertEquals(1, o.bucketDays)
        assertEquals(5, o.size)
        assertEquals(first.plusDays(3), o.bucketStart(3))
        // Tenths of a kcal round to the nearest kcal; days without diary entries are missing, not zero.
        assertArrayEquals(intArrayOf(1_920, 2_101, M, 1_800, M), o.kcalIn)
        // Out is active plus resting; either alone counts.
        assertArrayEquals(intArrayOf(2_100, M, 300, 1_800, M), o.kcalOut)
        // Two days of balance and activity are too few for a 7-day average.
        assertTrue(o.netKcal.all { it == M })
        assertTrue(o.activeMin.all { it == M })
        assertTrue(o.steps.all { it == M })
        assertTrue(o.hasData)
    }

    @Test
    fun balanceAndActivityAreTrailingSevenDayAverages() {
        val last = first.plusDays(9)
        val diary = HashMap<Day, Long>()
        val summaries = ArrayList<DaySummary>()
        for (i in -6..9) {
            val d = first.plusDays(i)
            // Out is 2,000 every day; in is 2,000 + 100 i, so the daily net is 100 i. Day 5 has no food logged.
            if (i != 5) diary[d] = (2_000L + 100 * i) * 10
            summaries += summary(d, active = 500, resting = 1_500, activeMin = 40 + 5 * i, steps = 8_000)
        }
        val o = WeightOverlay.build(first, last, diary, summaries)
        assertEquals(10, o.size)
        // Day 0 averages days -6..0 (the lead-in before the range counts).
        assertEquals(-300, o.netKcal[0])
        assertEquals(25, o.activeMin[0])
        // Day 5 averages the six days of -1..4 that have a net.
        assertEquals(150, o.netKcal[5])
        assertEquals(617, o.netKcal[9]) // days 3..9 without day 5: 3,700 / 6
        assertEquals(70, o.activeMin[9])
        assertEquals(8_000, o.steps[9])
        // In and out stay as logged that day.
        assertEquals(2_900, o.kcalIn[9])
        assertEquals(M, o.kcalIn[5])
        assertEquals(2_000, o.kcalOut[5])

        // Without the lead-in, a value needs four days with data.
        val noLead = WeightOverlay.build(first, last, diary.filterKeys { it >= first }, summaries.filter { it.day >= first })
        assertArrayEquals(intArrayOf(M, M, M), noLead.activeMin.copyOfRange(0, 3))
        assertEquals(48, noLead.activeMin[3]) // (40 + 45 + 50 + 55) / 4 = 47.5
    }

    @Test
    fun rollingMeanSkipsMissingAndNeedsEnoughDays() {
        val v = intArrayOf(10, M, 30, M, 50)
        assertEquals(30, WeightOverlay.rollingMean(v, 4, 7, 3))
        assertEquals(M, WeightOverlay.rollingMean(v, 4, 7, 4))
        assertEquals(40, WeightOverlay.rollingMean(v, 4, 3, 1))
        assertEquals(10, WeightOverlay.rollingMean(v, 0, 7, 1))
        assertEquals(M, WeightOverlay.rollingMean(intArrayOf(M, M), 1, 7, 1))
    }

    @Test
    fun theSummarysStaleKcalInIsIgnored() {
        val o = WeightOverlay.build(first, first, emptyMap(), listOf(summary(first, kcalIn = 2_500, active = 100)))
        assertArrayEquals(intArrayOf(M), o.kcalIn)
        assertArrayEquals(intArrayOf(M), o.netKcal)
    }

    @Test
    fun daysOutsideTheRangeAreIgnored() {
        val o = WeightOverlay.build(
            first,
            first.plusDays(1),
            mapOf(first.plusDays(-1) to 50_000L, first.plusDays(2) to 50_000L, first to 10_000L),
            listOf(summary(first.plusDays(-9), activeMin = 99), summary(first.plusDays(5), activeMin = 99)),
        )
        assertArrayEquals(intArrayOf(1_000, M), o.kcalIn)
        assertArrayEquals(intArrayOf(M, M), o.activeMin)
    }

    @Test
    fun longRangesAverageEachWeekOverTheDaysWithData() {
        assertEquals(1, WeightOverlay.bucketDaysFor(30))
        assertEquals(1, WeightOverlay.bucketDaysFor(92))
        assertEquals(7, WeightOverlay.bucketDaysFor(93))
        assertEquals(7, WeightOverlay.bucketDaysFor(365))
        val last = first.plusDays(364)
        val o = WeightOverlay.build(
            first,
            last,
            mapOf(first.plusDays(-2) to 90_000L, first to 20_000L, first.plusDays(1) to 25_000L, first.plusDays(363) to 18_000L),
            listOf(summary(first.plusDays(8), activeMin = 30), summary(first.plusDays(9), activeMin = 61)),
        )
        assertEquals(7, o.bucketDays)
        assertEquals(53, o.size) // 365 days in weeks, the last one partial
        assertEquals(2_250, o.kcalIn[0]) // (2,000 + 2,500) / 2 days that have entries; lead-in days belong to no week
        assertEquals(1_800, o.kcalIn[51])
        assertEquals(46, o.activeMin[1]) // 45.5 rounds up
        assertEquals(M, o.activeMin[0])
        assertEquals(first.plusDays(7), o.bucketStart(1))
    }

    @Test
    fun noDataAtAll() {
        val o = WeightOverlay.build(first, first.plusDays(29), emptyMap(), emptyList())
        assertEquals(30, o.size)
        assertFalse(o.hasData)
        assertTrue(o.kcalIn.all { it == M })
    }

    @Test
    fun layerChoiceEncoding() {
        assertEquals(WeightLayers.DEFAULT_HIDDEN, WeightLayers.decode(null))
        assertEquals(emptySet<String>(), WeightLayers.decode(""))
        // Unknown ids and the always-on weight are dropped.
        assertEquals(setOf(WeightLayers.KCAL_IN, WeightLayers.STEPS), WeightLayers.decode("steps, bogus,weight,kcal_in"))
        assertEquals("kcal_in,steps", WeightLayers.encode(setOf(WeightLayers.STEPS, WeightLayers.KCAL_IN, "bogus")))
        for (set in listOf(emptySet(), WeightLayers.DEFAULT_HIDDEN, WeightLayers.TOGGLEABLE.toSet())) {
            assertEquals(set, WeightLayers.decode(WeightLayers.encode(set)))
        }
    }
}

/** The Weight screen's loader and the remembered layer choice against the real database. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WeightOverlayLoadTest {
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

    private fun meal(day: Day, kcal: Int, hour: Int = 12): DiaryEntry = DiaryEntry(
        0, day, 2, day.startSec(clock.zone()) + hour * 3600L, null, "Lunch", null, 100, "1 serving", null,
        Nutrients(kcal * 10, null, null, null, null, null, null, null, null), null,
    )

    @Test
    fun loadAlignsWeightCaloriesAndActivityOverTheRange() = runBlocking {
        val db = TestDb.fresh(ctx)
        val zone = clock.zone()
        for (i in listOf(0, 3, 10, 29, 40)) {
            db.body.insert(BodyMeasure(0, today.plusDays(-i).startSec(zone) + 7 * 3600, BodyKind.WEIGHT, null, 82_00 + i * 10, DataSource.SCALE, null, null))
        }
        // Two meals on one day sum; a day with only a summary has no food.
        db.diary.addAll(listOf(meal(today, 600), meal(today, 1_300, hour = 19), meal(today.plusDays(-2), 2_000), meal(today.plusDays(-35), 2_400)))
        db.diary.addAll((3..6).map { meal(today.plusDays(-it), 2_100) })
        db.summaries.upsertDay(summary(today, active = 200, resting = 900, activeMin = 25, steps = 5_000, kcalIn = 100), 0)
        db.summaries.upsertDay(summary(today.plusDays(-1), active = 500, resting = 1_750, activeMin = 70), 0)
        for (i in 2..6) db.summaries.upsertDay(summary(today.plusDays(-i), active = 300, resting = 1_700, activeMin = 40), 0)
        val s = WeightViewModel(db, clock).load(WeightRange.D30, Units.IMPERIAL)
        assertEquals(today.plusDays(-29), s.firstDay)
        assertEquals(4, s.entries.size)
        assertEquals(s.entries.size, s.trend.size)
        val o = s.overlay ?: error("no overlay")
        assertEquals(today.plusDays(-29), o.firstDay)
        assertEquals(1, o.bucketDays)
        assertEquals(30, o.size)
        // The last bucket is today: food from the diary (the summary's kcal_in of 100 is stale).
        assertEquals(1_900, o.kcalIn[29])
        assertEquals(1_100, o.kcalOut[29])
        // Net over the last 7 days that have both sides: 800 today, 0 two days ago, 100 on four days.
        assertEquals(200, o.netKcal[29])
        assertEquals(42, o.activeMin[29]) // (25 + 70 + 5 x 40) / 7
        assertEquals(M, o.kcalIn[28])
        assertEquals(2_250, o.kcalOut[28])
        assertEquals(2_000, o.kcalIn[27])
        assertEquals(2_000, o.kcalOut[27])
        assertTrue((0..22).all { o.kcalIn[it] == M }) // the meal 35 days ago lies outside

        // "All" starts at the first weigh-in's day.
        val all = WeightViewModel(db, clock).load(WeightRange.ALL, Units.METRIC)
        assertEquals(today.plusDays(-40), all.firstDay)
        assertEquals(1, all.overlay?.bucketDays)
        assertEquals(41, all.overlay?.size)
        assertEquals(2_400, all.overlay?.kcalIn?.get(5))
    }

    @Test
    fun noWeighInsMeansNoOverlay() = runBlocking {
        val db = TestDb.fresh(ctx)
        db.diary.add(meal(today, 500))
        val s = WeightViewModel(db, clock).load(WeightRange.D90, Units.METRIC)
        assertNull(s.firstDay)
        assertNull(s.overlay)
    }

    @Test
    fun layerChoiceRoundTripsThroughSettings() = runBlocking {
        val db = TestDb.fresh(ctx)
        assertEquals(WeightLayers.DEFAULT_HIDDEN, WeightLayers.load(db))
        WeightLayers.save(db, emptySet())
        assertEquals(emptySet<String>(), WeightLayers.load(db))
        assertEquals("", db.settings.get(WeightLayers.KEY))
        WeightLayers.save(db, setOf(WeightLayers.KCAL_OUT, WeightLayers.ACTIVE_MIN))
        assertEquals(setOf(WeightLayers.KCAL_OUT, WeightLayers.ACTIVE_MIN), WeightLayers.load(db))
        // The view model saves the choice and its state picks it up.
        db.body.insert(BodyMeasure(0, clock.nowSec() - 3600, BodyKind.WEIGHT, null, 80_00, DataSource.MANUAL, null, null))
        val vm = WeightViewModel(db, clock)
        vm.saveHiddenLayers(setOf(WeightLayers.NET))
        val s = withTimeout(5_000) { vm.state.first { !it.loading && it.hiddenLayers == setOf(WeightLayers.NET) } }
        assertNotNull(s.overlay)
    }
}
