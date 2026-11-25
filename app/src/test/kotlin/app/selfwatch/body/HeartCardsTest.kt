/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body

import android.app.Application
import app.selfwatch.body.cards.BreathingRateCard
import app.selfwatch.body.cards.EnergyLevelCard
import app.selfwatch.body.cards.HeartRateNowCard
import app.selfwatch.body.cards.HeartRateTrendsCard
import app.selfwatch.cards.CardEnv
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.model.fake.FakeClock
import app.selfwatch.model.health.DaySummary
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.SleepSession
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

/** The heart-rate, breathing and energy-level Body cards: loaders with data and empty, and their arithmetic. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HeartCardsTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()
    private val clock = FakeClock()
    private val today: Day get() = clock.today()
    private val zone get() = clock.zone()

    private fun env(db: SelfWatchDb) = CardEnv(db, clock, today, Units.METRIC)

    private fun at(hour: Int, minute: Int = 0): Long = today.startSec(zone) + hour * 3_600L + minute * 60L

    @Test
    fun heartRateNowWithData() = runBlocking {
        val db = TestDb.fresh(ctx)
        clock.set(at(15))
        val samples = ArrayList<MetricSample>()
        // Yesterday evening (inside the last 24 hours) and today 8:00 to 14:57, every 3 minutes.
        samples += MetricSample(1, MetricKind.HEART_RATE, at(15) - 20 * 3_600L, 70)
        var t = at(8)
        while (t < at(15) - 120) {
            samples += MetricSample(1, MetricKind.HEART_RATE, t, if (t < at(9)) 50 else 80)
            t += 180
        }
        samples += MetricSample(1, MetricKind.HEART_RATE, at(14, 59), 132)
        samples += MetricSample(1, MetricKind.HEART_RATE, at(10), 0) // no contact: ignored
        samples += MetricSample(1, MetricKind.RESTING_HR, at(6), 54)
        db.metrics.insert(samples)

        val info = HeartRateNowCard.load(env(db))
        assertEquals(132, info.latestBpm)
        assertEquals(at(14, 59), info.latestSec)
        assertEquals(50, info.lowBpm)
        assertEquals(132, info.highBpm)
        assertEquals(54, info.restingBpm)
        // One quarter yesterday evening, then 28 quarters from 8:00 to 15:00.
        assertEquals(29, info.spark.size)
        assertEquals(70, info.spark.first())
    }

    @Test
    fun heartRateNowShowsAnOldReadingWhenNothingIsRecent() = runBlocking {
        val db = TestDb.fresh(ctx)
        clock.set(at(15))
        db.metrics.insert(listOf(MetricSample(1, MetricKind.HEART_RATE, at(15) - 3 * 86_400L, 61)))
        val info = HeartRateNowCard.load(env(db))
        assertEquals(61, info.latestBpm)
        assertNull(info.lowBpm)
        assertTrue(info.spark.isEmpty())
    }

    @Test
    fun heartRateNowEmpty() = runBlocking {
        val info = HeartRateNowCard.load(env(TestDb.fresh(ctx)))
        assertTrue(info.isEmpty)
        assertNull(info.restingBpm)
    }

    @Test
    fun ageText() {
        val now = 1_000_000L
        assertEquals("Live from your watch", HeartRateNowCard.ageText(ctx, now - 5, now, live = true))
        assertEquals("Just now", HeartRateNowCard.ageText(ctx, now - 30, now, live = false))
        assertEquals("1 minute ago", HeartRateNowCard.ageText(ctx, now - 90, now, live = false))
        assertEquals("3 minutes ago", HeartRateNowCard.ageText(ctx, now - 200, now, live = false))
        assertEquals("2 hours ago", HeartRateNowCard.ageText(ctx, now - 2 * 3_600 - 5, now, live = false))
        assertTrue(HeartRateNowCard.ageText(ctx, now - 30 * 3_600, now, live = false).startsWith("Last reading "))
    }

    @Test
    fun heartRateTrendsWithData() = runBlocking {
        val db = TestDb.fresh(ctx)
        clock.set(at(15))
        for (offset in 40 downTo 0) {
            val d = today.plusDays(-offset)
            db.summaries.upsertDay(DaySummary(d, -300, null, null, null, null, null, 50 + offset % 4, null, null, null, null, null, null, null, null), 0)
        }
        val samples = ArrayList<MetricSample>()
        for (offset in 10 downTo 1) {
            val start = today.plusDays(-offset).startSec(zone)
            samples += MetricSample(1, MetricKind.HEART_RATE, start + 10 * 3_600, 60)
            samples += MetricSample(1, MetricKind.HEART_RATE, start + 11 * 3_600, 80)
        }
        db.metrics.insert(samples)
        val data = HeartRateTrendsCard.load(env(db))
        val t = data.trend
        assertEquals(30, t.days.size)
        assertEquals(today, t.days.last())
        assertEquals(30, t.resting.count { it != null })
        assertEquals(10, t.average.count { it != null })
        assertEquals(70, t.average[28])
        assertNull(t.average.last())
        assertEquals(70, t.averageMean)
        assertTrue(t.restingMean in 50..53)
        assertTrue("both layers on by default", data.hidden.isEmpty())
    }

    @Test
    fun heartRateTrendsEmpty() = runBlocking {
        assertTrue(HeartRateTrendsCard.load(env(TestDb.fresh(ctx))).trend.isEmpty)
    }

    @Test
    fun breathingAgainstTheUsualRange() = runBlocking {
        val db = TestDb.fresh(ctx)
        clock.set(at(15))
        val samples = ArrayList<MetricSample>()
        for (offset in 20 downTo 0) {
            val bed = today.plusDays(-offset).startSec(zone) - 3_600
            db.sleep.insertSession(SleepSession(1, bed, bed + 7 * 3_600L, null))
            val value = if (offset == 0) 170 else if (offset % 2 == 0) 145 else 135
            samples += MetricSample(1, MetricKind.RESPIRATION, bed + 2 * 3_600, value)
            samples += MetricSample(1, MetricKind.RESPIRATION, bed + 3 * 3_600, value)
        }
        db.metrics.insert(samples)
        val info = BreathingRateCard.load(env(db))
        val r = info.result
        requireNotNull(r)
        assertEquals(170, r.lastX10)
        assertEquals(today, r.lastDay)
        assertEquals(RangeStatus.ABOVE, r.status)
        assertTrue(r.lowX10 in 130..135 && r.highX10 in 145..150)
        assertEquals(20, info.nights)
    }

    @Test
    fun breathingLearningAndEmpty() {
        val days = (0 until 10).map { Day(20261101 + it) }
        val few = HeartCardMath.breathing(days, List(9) { 140 } + 150)
        assertNull(few.result)
        assertEquals(150, few.lastX10)
        assertEquals(9, few.nights)
        // A last night older than yesterday is not "last night".
        val stale = HeartCardMath.breathing(days, List(7) { 140 } + listOf(null, null, null))
        assertNull(stale.lastX10)
        val empty = runBlocking { BreathingRateCard.load(env(TestDb.fresh(ctx))) }
        assertNull(empty.result)
        assertNull(empty.lastX10)
        assertEquals(0, empty.nights)
    }

    @Test
    fun breathingRangeHasAMinimumWidth() {
        val days = (0 until 16).map { Day(20261101 + it) }
        val info = HeartCardMath.breathing(days, List(15) { 140 } + 143)
        val r = requireNotNull(info.result)
        // A perfectly steady history still gets ± half a breath: 13.5 to 14.5.
        assertEquals(135, r.lowX10)
        assertEquals(145, r.highX10)
        assertEquals(RangeStatus.WITHIN, r.status)
    }

    @Test
    fun energyLevelWithDataAndEmpty() = runBlocking {
        val db = TestDb.fresh(ctx)
        clock.set(at(15))
        assertNull(EnergyLevelCard.load(env(db)).info)
        db.metrics.insert(
            listOf(
                MetricSample(1, MetricKind.ENERGY_LEVEL, at(15) - 86_400L, 20), // yesterday
                MetricSample(1, MetricKind.ENERGY_LEVEL, at(7), 88),
                MetricSample(1, MetricKind.ENERGY_LEVEL, at(12), 61),
                MetricSample(1, MetricKind.ENERGY_LEVEL, at(14, 45), 55),
            ),
        )
        val e = requireNotNull(EnergyLevelCard.load(env(db)).info)
        assertEquals(55, e.latest)
        assertEquals(at(14, 45), e.latestSec)
        assertEquals(88, e.high)
        assertEquals(55, e.low)
        assertEquals(listOf(88, 61, 55), e.spark)
    }

    @Test
    fun bucketsAverageAndSkipEmptyQuarters() {
        val s = listOf(
            MetricSample(1, MetricKind.HEART_RATE, 0, 60),
            MetricSample(1, MetricKind.HEART_RATE, 899, 70),
            MetricSample(1, MetricKind.HEART_RATE, 2_700, 90),
            MetricSample(1, MetricKind.HEART_RATE, 5_000, 99), // outside
        )
        assertEquals(listOf(65, 90), HeartCardMath.buckets(s, 0, 3_600))
        assertTrue(HeartCardMath.buckets(s, 10, 10).isEmpty())
    }
}
