/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body

import app.selfwatch.cards.MainGoal
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** The pure arithmetic behind the phase-2 Body cards and the O3, O6 and O7 overlay series. */
class BodyBaselinesTest {
    private val zone: ZoneId = ZoneId.of("UTC")
    private val d0 = Day(20261001)

    @Test
    fun baselineMeanAndSpread() {
        val b = Baseline.of(listOf(40, 50, 60)) ?: error("no baseline")
        assertEquals(50.0, b.mean, 1e-9)
        assertEquals(10.0, b.sd, 1e-9)
        assertNull(Baseline.of(listOf(1, 2), minCount = 3))
        assertNull(Baseline.of(emptyList()))
        assertEquals(0.0, Baseline.of(listOf(7))?.sd ?: -1.0, 1e-9)
    }

    @Test
    fun hrvNeedsTwoWeeksThenReadsAgainstTheUsualBand() {
        // 13 nights: still learning.
        val learning = HrvMath.evaluate(List(13) { 50 })
        assertNull(learning.result)
        assertEquals(13, learning.nights)
        assertEquals(HrvMath.BASELINE_MIN, learning.needed)
        // 40 nights alternating 45/55 (sd ≈ 5.06, band ≈ ±2.5 around 50), last week at 50: within.
        val steady = List(40) { if (it % 2 == 0) 45 else 55 }.toMutableList<Int?>()
        for (i in 33 until 40) steady[i] = 50
        val within = HrvMath.evaluate(steady).result ?: error("no result")
        assertEquals(RangeStatus.WITHIN, within.status)
        assertEquals(50, within.sevenDayMs)
        assertTrue(within.lowMs < 50 && within.highMs > 50)
        // Last week low (40): lower than usual. Gaps (null) are skipped.
        val low = steady.toMutableList()
        for (i in 33 until 40) low[i] = if (i % 2 == 0) null else 40
        assertEquals(RangeStatus.BELOW, HrvMath.evaluate(low).result?.status)
        // Last week high: higher than usual.
        val high = steady.toMutableList()
        for (i in 33 until 40) high[i] = 60
        assertEquals(RangeStatus.ABOVE, HrvMath.evaluate(high).result?.status)
        // Too few nights in the last week: no result even with a long history.
        val sparse = steady.toMutableList()
        for (i in 34 until 40) sparse[i] = null
        assertNull(HrvMath.evaluate(sparse).result)
        // The sparkline is the rolling 7-night average, at most 30 points.
        assertTrue(within.rolling.size <= HrvMath.SPARK_DAYS)
        assertEquals(50, within.rolling.last())
    }

    @Test
    fun restingHeartRateFourWeekTrend() {
        assertNull(RestingHrMath.evaluate(List(6) { 55 }))
        // Only the current window: a value, no baseline yet.
        val young = RestingHrMath.evaluate(List(20) { 55 }) ?: error("no trend")
        assertEquals(55, young.currentBpm)
        assertNull(young.baselineBpm)
        assertNull(young.status)
        // 28 days at 60, then 28 at 56: lower by 4.
        val lower = RestingHrMath.evaluate(List(28) { 60 } + List(28) { 56 }) ?: error("no trend")
        assertEquals(56, lower.currentBpm)
        assertEquals(60, lower.baselineBpm)
        assertEquals(-4, lower.deltaBpm)
        assertEquals(RangeStatus.BELOW, lower.status)
        // Within 2 bpm is steady; more is higher.
        assertEquals(RangeStatus.WITHIN, RestingHrMath.evaluate(List(28) { 60 } + List(28) { 62 })?.status)
        assertEquals(RangeStatus.ABOVE, RestingHrMath.evaluate(List(28) { 60 } + List(28) { 64 })?.status)
        // The rolling line covers the last 90 days at most.
        assertEquals(RestingHrMath.SPARK_DAYS, RestingHrMath.evaluate(List(120) { 58 })?.rolling?.size)
    }

    @Test
    fun bodySignalsFlagOnlyWhenTwoRiseTogether() {
        val nights = (0 until 30).map { d0.plusDays(it) }
        fun series(base: Int, spread: Int, last: Int): Map<Day, Int> =
            nights.mapIndexed { i, d -> d to if (i == nights.size - 1) last else base + (if (i % 2 == 0) spread else -spread) }.toMap()
        // Skin +0.5 °C, resting HR +6, breathing +0.2: two up → flagged.
        val two = BodySignalsMath.evaluate(
            nights,
            mapOf(
                SignalKind.SKIN_TEMP to series(0, 10, 50),
                SignalKind.RESTING_HR to series(55, 1, 61),
                SignalKind.RESPIRATION to series(140, 3, 142),
            ),
        ) ?: error("no reading")
        assertEquals(nights.last(), two.day)
        assertEquals(2, two.upCount)
        assertTrue(two.flagged)
        assertEquals(listOf(true, true, false), two.readings.map { it.up })
        assertEquals(50, two.readings.first { it.kind == SignalKind.SKIN_TEMP }.deviation)
        // Only one up: not flagged.
        val one = BodySignalsMath.evaluate(
            nights,
            mapOf(SignalKind.SKIN_TEMP to series(0, 10, 50), SignalKind.RESTING_HR to series(55, 1, 55)),
        ) ?: error("no reading")
        assertEquals(1, one.upCount)
        assertFalse(one.flagged)
        // A rise smaller than the minimum never counts, however steady the history.
        val tiny = BodySignalsMath.reading(SignalKind.SKIN_TEMP, 20, Baseline(0.0, 0.0, 28))
        assertFalse(tiny.up)
        // Too little history or no value last night: nothing to read.
        assertNull(BodySignalsMath.evaluate(nights.takeLast(5), mapOf(SignalKind.RESTING_HR to series(55, 1, 70))))
        assertNull(BodySignalsMath.evaluate(nights, mapOf(SignalKind.RESTING_HR to series(55, 1, 70).filterKeys { it != nights.last() })))
        assertNull(BodySignalsMath.evaluate(emptyList(), emptyMap()))
    }

    @Test
    fun weightBandPerGoal() {
        // Lose weight: 0.5–1 kg a week.
        val loss = WeightPlan.band(MainGoal.WEIGHT_LOSS, 80_00, -70, 82_00, null) ?: error("no band")
        assertEquals(WeightBand.Kind.LOSS_RATE, loss.kind)
        assertEquals(RangeStatus.WITHIN, loss.status)
        assertEquals(-100, loss.lowX100)
        assertEquals(-50, loss.highX100)
        assertEquals(RangeStatus.BELOW, WeightPlan.band(MainGoal.WEIGHT_LOSS, 80_00, -20, null, null)?.status)
        assertEquals(RangeStatus.ABOVE, WeightPlan.band(MainGoal.WEIGHT_LOSS, 80_00, -130, null, null)?.status)
        assertNull("no rate yet", WeightPlan.band(MainGoal.WEIGHT_LOSS, 80_00, null, null, null)?.status)
        // Maintain: ±3% of the goal, else of the starting trend.
        val keep = WeightPlan.band(MainGoal.WEIGHT_MANAGEMENT, 81_00, 0, 79_00, 80_00) ?: error("no band")
        assertEquals(WeightBand.Kind.MAINTAIN, keep.kind)
        assertEquals(77_60, keep.lowX100)
        assertEquals(82_40, keep.highX100)
        assertEquals(RangeStatus.WITHIN, keep.status)
        assertEquals(RangeStatus.ABOVE, WeightPlan.band(MainGoal.WEIGHT_MANAGEMENT, 82_00, 0, 79_00, null)?.status)
        // Build muscle: +0.25–0.5% of body weight a week (80 kg: 0.2–0.4 kg).
        val gain = WeightPlan.band(MainGoal.BUILD_MUSCLE, 80_00, 30, null, null) ?: error("no band")
        assertEquals(20, gain.lowX100)
        assertEquals(40, gain.highX100)
        assertEquals(RangeStatus.WITHIN, gain.status)
        assertEquals(RangeStatus.BELOW, WeightPlan.band(MainGoal.BUILD_MUSCLE, 80_00, -10, null, null)?.status)
        // Stay active: a note only after a 5% fall in 90 days.
        assertNull(WeightPlan.band(MainGoal.ACTIVE_AGING, 78_00, -10, 80_00, null))
        val unplanned = WeightPlan.band(MainGoal.ACTIVE_AGING, 76_00, -40, 80_00, null) ?: error("no note")
        assertEquals(WeightBand.Kind.UNPLANNED_LOSS, unplanned.kind)
        assertEquals(-50, unplanned.changePctX10)
        // Other goals: no band.
        assertNull(WeightPlan.band(MainGoal.GENERAL_HEALTH, 80_00, -50, 81_00, 75_00))
        assertNull(WeightPlan.band(MainGoal.ENDURANCE, 80_00, -50, 81_00, 75_00))
    }

    @Test
    fun smallHelpers() {
        assertEquals(52, BodyCardMath.waistToHeightX100(92_00, 178))
        assertNull(BodyCardMath.waistToHeightX100(92_00, null))
        assertNull(BodyCardMath.waistToHeightX100(92_00, 0))
        assertEquals(WaistBand.UNDER, BodyCardMath.waistBand(49))
        assertEquals(WaistBand.RAISED, BodyCardMath.waistBand(50))
        assertEquals(WaistBand.RAISED, BodyCardMath.waistBand(59))
        assertEquals(WaistBand.HIGH, BodyCardMath.waistBand(60))
        assertEquals(RangeStatus.BELOW, BodyCardMath.sleepStatus(419))
        assertEquals(RangeStatus.WITHIN, BodyCardMath.sleepStatus(420))
        assertEquals(RangeStatus.WITHIN, BodyCardMath.sleepStatus(540))
        assertEquals(RangeStatus.ABOVE, BodyCardMath.sleepStatus(541))
        // 7-day balance: complete days only (today left out), days with both sides, at least three.
        val week = listOf(
            DayEnergy(1, 2_000, 2_300), DayEnergy(2, 1_900, 2_400), DayEnergy(3, -1, 2_200), DayEnergy(4, 2_100, 2_400),
            DayEnergy(5, 2_000, -1), DayEnergy(6, 2_200, 2_200), DayEnergy(7, 500, 1_200),
        )
        assertEquals(-275, BodyCardMath.weekBalance(week))
        assertNull(BodyCardMath.weekBalance(week.drop(3)))
        // Rest time: readings at or below 25 count until the next one, gaps capped at 10 minutes.
        val t = longArrayOf(0, 180, 360, 540, 3_600)
        assertEquals(6 + 10, BodyCardMath.restMinutes(t, intArrayOf(20, 22, 60, 10, 15)))
        assertEquals(0, BodyCardMath.restMinutes(LongArray(0), IntArray(0)))
    }

    @Test
    fun layerChoiceRemembersHiddenLayers() {
        val c = LayerChoice("k", listOf("a", "b", "c"))
        assertEquals(emptySet<String>(), c.decode(null))
        assertEquals(setOf("a", "c"), c.decode("c, a,zzz"))
        assertEquals("a,c", c.encode(setOf("c", "a", "zzz")))
        assertEquals(emptySet<String>(), c.decode(""))
        assertEquals(setOf("b"), LayerChoice("k", listOf("a", "b"), setOf("b")).decode(null))
        // Every overlay starts with every layer on.
        for (l in listOf(ProteinOverlay.LAYERS, HeartTrendOverlay.LAYERS, SignalsOverlay.LAYERS)) assertTrue(l.defaultHidden.isEmpty())
    }

    @Test
    fun proteinOverlaySeries() {
        val weights = listOf(
            BodyMeasure(1, d0.plusDays(-3).startSec(zone), BodyKind.WEIGHT, null, 80_00, DataSource.SCALE, null, null),
            BodyMeasure(2, d0.plusDays(8).startSec(zone), BodyKind.WEIGHT, null, 75_00, DataSource.SCALE, null, null),
        )
        // Week 0: 4 days at 128 g → 1.6 g/kg at 80 kg. Week 1: 3 days at 120 g → 1.6 at 75 kg. Week 2: 2 days only.
        val protein = HashMap<Day, Long>()
        for (i in 0 until 4) protein[d0.plusDays(i)] = 1_280
        for (i in 7 until 10) protein[d0.plusDays(i)] = 1_200
        for (i in 14 until 16) protein[d0.plusDays(i)] = 2_000
        val strength = listOf(d0.plusDays(1), d0.plusDays(4), d0.plusDays(9), d0.plusDays(40))
        val o = ProteinOverlay.build(d0, 3, protein, weights, zone, strength)
        assertArrayEquals(intArrayOf(16, 16, ProteinOverlay.MISSING), o.proteinPerKgX10)
        assertArrayEquals(intArrayOf(2, 1, 0), o.strength)
        assertEquals(d0.plusDays(14), o.weekStart(2))
        assertTrue(o.hasData)
        // No weigh-in at all: no g/kg.
        assertArrayEquals(intArrayOf(ProteinOverlay.MISSING), ProteinOverlay.build(d0, 1, protein, emptyList(), zone, emptyList()).proteinPerKgX10)
        assertFalse(ProteinOverlay.build(d0, 1, emptyMap(), weights, zone, emptyList()).hasData)
    }

    @Test
    fun heartTrendOverlaySeries() {
        assertEquals(1 + 2 + 2 + 0 + 0, HeartTrendOverlay.intensityMinutes(listOf(2, 3, 4, 1, 0)))
        // Resting HR every day from 27 days before the first: a rolling value on every day.
        val first = d0
        val last = d0.plusDays(13)
        val rest = DayRange(first.plusDays(-27), last).days().associateWith { 60 }
        val o = HeartTrendOverlay.build(first, last, intArrayOf(150, -1), rest)
        assertEquals(14, o.restDays.size)
        assertTrue(o.restBpm.all { it == 60 })
        assertTrue(o.hasData)
        // Too few days in a window: left out.
        val sparse = mapOf(d0 to 60, d0.plusDays(1) to 61)
        val none = HeartTrendOverlay.build(first, last, intArrayOf(-1, -1), sparse)
        assertTrue(none.restDays.isEmpty())
        assertFalse(none.hasData)
    }

    @Test
    fun signalsOverlaySeries() {
        val all = (0 until 40).map { d0.plusDays(it) }
        val skin = all.mapIndexed { i, d -> d to if (i == 39) 60 else (if (i % 2 == 0) 10 else -10) }.toMap()
        val o = SignalsOverlay.build(all.takeLast(10), all, mapOf(SignalKind.SKIN_TEMP to skin))
        val z = o.z.getValue(SignalKind.SKIN_TEMP)
        assertEquals(10, z.size)
        // The last night is about 6 sd above its baseline (capped at 4 sd ×10).
        assertEquals(40, z.last())
        assertEquals(60, o.deviation.getValue(SignalKind.SKIN_TEMP).last())
        assertTrue(o.z.getValue(SignalKind.RESTING_HR).all { it == BodySignalsMath.MISSING })
        assertTrue(o.hasData)
        // The first nights have no baseline yet.
        val early = SignalsOverlay.build(all.take(5), all, mapOf(SignalKind.SKIN_TEMP to skin))
        assertFalse(early.hasData)
        assertNotNull(SignalsOverlay.LAYERS.toggleable.firstOrNull { it == SignalsOverlay.BAND })
    }
}
