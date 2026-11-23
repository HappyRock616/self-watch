/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.charts

import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.health.SleepStageSample
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

class ChartMathTest {
    private val eps = 1e-6

    // ---- Ticks ----

    @Test
    fun niceTicksCoverRangeWithNiceSteps() {
        val t = ChartMath.niceTicks(43.0, 118.0, 5)
        assertEquals(20.0, t.step, eps)
        assertEquals(40.0, t.lo, eps)
        assertEquals(120.0, t.hi, eps)
        assertEquals(5, t.count)
        assertArrayEquals(doubleArrayOf(40.0, 60.0, 80.0, 100.0, 120.0), t.values(), eps)
    }

    @Test
    fun niceTicksAlwaysWithinLimitAndCoverData() {
        val r = Random(7)
        repeat(2000) {
            val a = r.nextDouble(-1000.0, 1000.0)
            val b = a + r.nextDouble(0.001, 5000.0)
            val max = 3 + r.nextInt(3)
            val t = ChartMath.niceTicks(a, b, max)
            assertTrue("count ${t.count} for $a..$b", t.count in 2..max)
            assertTrue(t.lo <= a + 1e-9 && t.hi >= b - 1e-9)
            val mant = t.step / 10.0.pow(kotlin.math.floor(kotlin.math.log10(t.step)))
            assertTrue("step ${t.step}", listOf(1.0, 2.0, 2.5, 5.0).any { abs(it - mant) < 1e-6 })
        }
    }

    @Test
    fun niceTicksHandleFlatReversedAndBadInput() {
        val flat = ChartMath.niceTicks(60.0, 60.0, 5)
        assertTrue(flat.lo < 60.0 && flat.hi > 60.0)
        val zero = ChartMath.niceTicks(0.0, 0.0, 5)
        assertTrue(zero.lo < 0.0 + eps && zero.hi >= 1.0 - eps)
        val rev = ChartMath.niceTicks(10.0, 0.0, 5)
        assertEquals(0.0, rev.lo, eps)
        assertEquals(10.0, rev.hi, eps)
        val nan = ChartMath.niceTicks(Double.NaN, 1.0, 5)
        assertEquals(Ticks(0.0, 1.0, 0.5), nan)
    }

    @Test
    fun ticksDecimals() {
        assertEquals(0, ChartMath.niceTicks(0.0, 100.0, 5).decimals)
        assertEquals(1, ChartMath.niceTicks(12.0, 14.0, 5).decimals)
        assertEquals(2, decimalsFor(0.25))
        assertEquals(0, decimalsFor(0.0))
    }

    @Test
    fun timeTicksLandOnLocalHours() {
        // 23:10 to 06:40 UTC+1 on some night.
        val offset = 3600
        val start = 1_763_850_000L // arbitrary
        val end = start + 7 * 3600L + 30 * 60L
        val ticks = ChartMath.timeTicks(start, end, 5, offset)
        assertTrue(ticks.size in 2..5)
        for (t in ticks) {
            assertEquals(0L, Math.floorMod(t + offset, 7200L))
            assertTrue(t in start..end)
        }
        assertTrue(ticks[0] - start < 7200L)
        assertEquals(2 * 3600L, ChartMath.timeStep(start, end, 5, offset))
    }

    @Test
    fun timeTicksUseDaysForLongRanges() {
        val start = 1_760_000_000L
        val end = start + 84 * 86_400L
        val step = ChartMath.timeStep(start, end, 6, 0)
        assertTrue(step >= 14 * 86_400L)
        val ticks = ChartMath.timeTicks(start, end, 6, 0)
        assertTrue(ticks.size <= 6)
        for (t in ticks) assertEquals(0L, Math.floorMod(t, 86_400L))
    }

    @Test
    fun timeTicksEmptyForBadWindow() {
        assertEquals(0, ChartMath.timeTicks(10, 10, 5, 0).size)
        assertEquals(0, ChartMath.timeTicks(10, 20, 0, 0).size)
    }

    // ---- Scales ----

    @Test
    fun valueAndTimeMapping() {
        assertEquals(100f, ChartMath.valueToY(0.0, 0.0, 10.0, 0f, 100f), 1e-4f)
        assertEquals(0f, ChartMath.valueToY(10.0, 0.0, 10.0, 0f, 100f), 1e-4f)
        assertEquals(50f, ChartMath.valueToY(5.0, 5.0, 5.0, 0f, 100f), 1e-4f)
        val w = Window(1000, 2000)
        assertEquals(10f, ChartMath.timeToX(1000, w, 10f, 110f), 1e-4f)
        assertEquals(60f, ChartMath.timeToX(1500, w, 10f, 110f), 1e-4f)
        assertEquals(1500L, ChartMath.xToTime(60f, w, 10f, 110f))
        assertEquals(1000L, ChartMath.xToTime(-50f, w, 10f, 110f))
        assertEquals(2000L, ChartMath.xToTime(500f, w, 10f, 110f))
    }

    // ---- Lookups ----

    @Test
    fun nearestAndLineLookup() {
        val t = longArrayOf(10, 20, 30, 100)
        assertEquals(-1, ChartMath.nearestIndex(t, 0, 5))
        assertEquals(0, ChartMath.nearestIndex(t, 4, 0))
        assertEquals(0, ChartMath.nearestIndex(t, 4, 15)) // tie goes earlier
        assertEquals(1, ChartMath.nearestIndex(t, 4, 16))
        assertEquals(3, ChartMath.nearestIndex(t, 4, 1000))
        assertEquals(2, ChartMath.lineIndexAt(t, 4, 50, 25))
        assertEquals(-1, ChartMath.lineIndexAt(t, 4, 65, 25)) // inside a gap
    }

    @Test
    fun bucketAndSpanLookup() {
        assertEquals(-1, ChartMath.bucketIndex(100, 10, 5, 99))
        assertEquals(0, ChartMath.bucketIndex(100, 10, 5, 100))
        assertEquals(4, ChartMath.bucketIndex(100, 10, 5, 149))
        assertEquals(-1, ChartMath.bucketIndex(100, 10, 5, 150))
        assertEquals(-1, ChartMath.bucketIndex(100, 0, 5, 120))
        val starts = longArrayOf(0, 10, 30)
        val ends = longArrayOf(10, 20, 40)
        assertEquals(0, ChartMath.spanIndexAt(starts, ends, 0))
        assertEquals(1, ChartMath.spanIndexAt(starts, ends, 10))
        assertEquals(-1, ChartMath.spanIndexAt(starts, ends, 25))
        assertEquals(2, ChartMath.spanIndexAt(starts, ends, 39))
        assertEquals(-1, ChartMath.spanIndexAt(starts, ends, 40))
        assertEquals(-1, ChartMath.spanIndexAt(starts, ends, -1))
    }

    // ---- Down-sampling ----

    @Test
    fun downsampleKeepsAtMostTwoPointsPerColumnAndExtremes() {
        val n = 8 * 3600
        val t = LongArray(n) { it.toLong() }
        val r = Random(1)
        val v = IntArray(n) { 50 + r.nextInt(40) }
        v[12_345] = 180
        v[23_456] = 30
        val width = 1000
        val s = ChartMath.downsample(t, v, Window(0, n.toLong()), width, 600)
        assertTrue("size ${s.size}", s.size <= 2 * (width + 2))
        var max = Float.NEGATIVE_INFINITY
        var min = Float.POSITIVE_INFINITY
        for (i in 0 until s.size) {
            max = maxOf(max, s.a[i]); min = minOf(min, s.a[i])
        }
        assertEquals(180f, max, 0f)
        assertEquals(30f, min, 0f)
        assertEquals(0, s.breaks.size)
        // X is non-decreasing.
        for (i in 1 until s.size) assertTrue(s.xs[i] >= s.xs[i - 1])
    }

    @Test
    fun downsampleBreaksAtGaps() {
        val t = longArrayOf(0, 60, 120, 2000, 2060)
        val v = intArrayOf(1, 2, 3, 4, 5)
        val s = ChartMath.downsample(t, v, Window(0, 2100), 2100, 600)
        assertEquals(5, s.size)
        assertArrayEquals(intArrayOf(3), s.breaks)
    }

    @Test
    fun downsampleKeepsOnePointOutsideEachEdge() {
        val t = LongArray(100) { it * 10L }
        val v = IntArray(100) { it }
        val s = ChartMath.downsample(t, v, Window(205, 405), 200, 600)
        assertTrue(s.xs[0] < 0f)
        assertTrue(s.xs[s.size - 1] > 200f)
        assertEquals(20f, s.a[0], 0f)
        assertEquals(41f, s.a[s.size - 1], 0f)
    }

    @Test
    fun downsampleOrdersMinAndMaxByTime() {
        // All in one column: max first, then min.
        val t = longArrayOf(0, 1, 2, 3)
        val v = intArrayOf(5, 9, 1, 4)
        val s = ChartMath.downsample(t, v, Window(0, 1000), 10, 600)
        assertEquals(2, s.size)
        assertEquals(9f, s.a[0], 0f)
        assertEquals(1f, s.a[1], 0f)
    }

    @Test
    fun downsampleEmptyAndDegenerate() {
        assertTrue(ChartMath.downsample(LongArray(0), IntArray(0), Window(0, 10), 100, 60).isEmpty)
        assertTrue(ChartMath.downsample(longArrayOf(1), intArrayOf(1), Window(0, 10), 0, 60).isEmpty)
        assertTrue(ChartMath.downsample(longArrayOf(1), intArrayOf(1), Window(5, 5), 100, 60).isEmpty)
        val one = ChartMath.downsample(longArrayOf(5), intArrayOf(7), Window(0, 10), 100, 60)
        assertEquals(1, one.size)
        assertEquals(50f, one.xs[0], 1e-4f)
        // Mismatched array sizes use the shorter.
        assertEquals(2, ChartMath.downsample(longArrayOf(1, 5, 9), intArrayOf(1, 2), Window(0, 10), 100, 60).size)
    }

    @Test
    fun downsampleBandTakesColumnExtremes() {
        val t = longArrayOf(0, 1, 2, 500)
        val low = intArrayOf(50, 45, 48, 60)
        val high = intArrayOf(80, 90, 85, 70)
        val s = ChartMath.downsampleBand(t, low, high, Window(0, 1000), 10, 10_000)
        assertEquals(2, s.size)
        assertEquals(45f, s.a[0], 0f)
        assertEquals(90f, s.b!![0], 0f)
        assertEquals(60f, s.a[1], 0f)
        assertEquals(70f, s.b[1], 0f)
    }

    @Test
    fun envelopeCoversLineInEveryColumn() {
        // From (0.5, 10) to (3.5, 40): slope 10 px per px.
        val e = ChartMath.envelope(floatArrayOf(0.5f, 3.5f), floatArrayOf(10f, 40f), 0, 2, -2, 100)
        assertEquals(4, e.size)
        assertArrayEquals(floatArrayOf(0.5f, 1.5f, 2.5f, 3.5f), e.x, 1e-4f)
        assertArrayEquals(floatArrayOf(10f, 15f, 25f, 35f), e.top, 1e-4f)
        assertArrayEquals(floatArrayOf(15f, 25f, 35f, 40f), e.bottom, 1e-4f)
    }

    @Test
    fun envelopeTakesColumnExtremesOfDenseData() {
        val xs = floatArrayOf(0.1f, 0.4f, 0.6f, 0.9f, 1.2f)
        val ys = floatArrayOf(50f, 20f, 80f, 50f, 50f)
        val e = ChartMath.envelope(xs, ys, 0, 5, 0, 10)
        assertEquals(2, e.size)
        assertEquals(20f, e.top[0], 1e-4f)
        assertEquals(80f, e.bottom[0], 1e-4f)
        assertEquals(50f, e.top[1], 1e-4f)
    }

    @Test
    fun envelopeClipsAndHandlesDegenerateInput() {
        val e = ChartMath.envelope(floatArrayOf(-50f, 5.5f), floatArrayOf(0f, 0f), 0, 2, -2, 3)
        assertEquals(-1.5f, e.x[0], 1e-4f)
        assertEquals(3.5f, e.x[e.size - 1], 1e-4f)
        assertEquals(0, ChartMath.envelope(floatArrayOf(1f), floatArrayOf(1f), 0, 0, 0, 10).size)
        assertEquals(1, ChartMath.envelope(floatArrayOf(1f), floatArrayOf(1f), 0, 1, 0, 10).size)
        assertEquals(0, ChartMath.envelope(floatArrayOf(50f, 60f), floatArrayOf(1f, 1f), 0, 2, 0, 10).size)
        val vertical = ChartMath.envelope(floatArrayOf(2f, 2f), floatArrayOf(1f, 9f), 0, 2, 0, 10)
        assertEquals(1, vertical.size)
        assertEquals(1f, vertical.top[0], 0f)
        assertEquals(9f, vertical.bottom[0], 0f)
    }

    // ---- Zoom ----

    @Test
    fun zoomKeepsAnchorAndStaysInside() {
        val full = Window(0, 28_800)
        val z = ChartMath.zoom(full, full, 2.0, 14_400)
        assertEquals(Window(7_200, 21_600), z)
        val edge = ChartMath.zoom(full, full, 2.0, 0)
        assertEquals(Window(0, 14_400), edge)
        val out = ChartMath.zoom(z, full, 0.1, 14_400)
        assertEquals(full, out)
        // Never narrower than the minimum span.
        var w = full
        repeat(20) { w = ChartMath.zoom(w, full, 2.0, 10_000) }
        assertEquals(ChartMath.minSpan(full), w.span)
        assertTrue(w.start >= full.start && w.end <= full.end)
        assertEquals(full, ChartMath.zoom(z, full, Double.NaN, 0))
    }

    @Test
    fun panClamps() {
        val full = Window(0, 1000)
        val w = Window(200, 400)
        assertEquals(Window(300, 500), ChartMath.pan(w, full, 100))
        assertEquals(Window(0, 200), ChartMath.pan(w, full, -500))
        assertEquals(Window(800, 1000), ChartMath.pan(w, full, 5000))
        assertTrue(ChartMath.isZoomed(w, full))
        assertFalse(ChartMath.isZoomed(full, full))
    }

    @Test
    fun minSpan() {
        assertEquals(300L, ChartMath.minSpan(Window(0, 28_800)))
        assertEquals(30 * 86_400L / 96, ChartMath.minSpan(Window(0, 30 * 86_400L)))
        assertEquals(100L, ChartMath.minSpan(Window(0, 100)))
    }

    // ---- Accessibility and layout ----

    @Test
    fun slices() {
        assertEquals(0, ChartMath.sliceAt(-5f, 0f, 120f, 12))
        assertEquals(5, ChartMath.sliceAt(55f, 0f, 120f, 12))
        assertEquals(11, ChartMath.sliceAt(500f, 0f, 120f, 12))
        assertEquals(50L, ChartMath.sliceCentre(Window(0, 1200), 0, 12))
        assertEquals(1150L, ChartMath.sliceCentre(Window(0, 1200), 11, 12))
    }

    @Test
    fun stageRows() {
        assertEquals(0, ChartMath.stageRow(SleepStage.AWAKE))
        assertEquals(1, ChartMath.stageRow(SleepStage.REM))
        assertEquals(2, ChartMath.stageRow(SleepStage.LIGHT))
        assertEquals(3, ChartMath.stageRow(SleepStage.DEEP))
        assertEquals(-1, ChartMath.stageRow(SleepStage.UNMEASURABLE))
    }

    @Test
    fun bubblePlacement() {
        assertEquals(110f, ChartMath.bubbleLeft(100f, 50f, 0f, 300f, 10f), 0f)
        assertEquals(240f, ChartMath.bubbleLeft(290f, 40f, 0f, 300f, 10f), 0f)
        assertEquals(0f, ChartMath.bubbleLeft(20f, 290f, 0f, 300f, 10f), 0f)
        assertEquals(0f, ChartMath.bubbleLeft(20f, 400f, 0f, 300f, 10f), 0f)
    }

    @Test
    fun labelStride() {
        assertEquals(1, ChartMath.labelStride(40f, 20f))
        assertEquals(3, ChartMath.labelStride(10f, 20f))
        assertEquals(1, ChartMath.labelStride(0f, 20f))
    }

    @Test
    fun scaleDecimals() {
        assertEquals(0, decimalsForScale(1))
        assertEquals(1, decimalsForScale(10))
        assertEquals(2, decimalsForScale(100))
    }

    @Test
    fun stageSpansFromSamples() {
        val s = listOf(
            SleepStageSample(1, 100, SleepStage.LIGHT),
            SleepStageSample(1, 0, SleepStage.AWAKE),
            SleepStageSample(1, 100, SleepStage.DEEP),
            SleepStageSample(1, 300, SleepStage.REM),
        )
        val spans = StageSpan.fromSamples(s, 400)
        assertEquals(
            listOf(StageSpan(0, 100, SleepStage.AWAKE), StageSpan(100, 300, SleepStage.DEEP), StageSpan(300, 400, SleepStage.REM)),
            spans,
        )
        assertTrue(StageSpan.fromSamples(emptyList(), 10).isEmpty())
    }

    // ---- Smoothing ----

    @Test
    fun emaHalfLifeHalvesTheGap() {
        val day = 86_400L
        val t = longArrayOf(0, 10 * day)
        val v = intArrayOf(8000, 9000)
        val trend = ChartMath.emaTrend(t, v, ChartMath.halfLifeSec(10.0))
        assertEquals(8000.0, trend[0], eps)
        assertEquals(8500.0, trend[1], eps) // one half-life: halfway
    }

    @Test
    fun emaIrregularGapsMatchDailySteps() {
        // One reading after 2 days equals two daily readings of the same value.
        val day = 86_400L
        val h = ChartMath.halfLifeSec(10.0)
        val a = ChartMath.emaTrend(longArrayOf(0, 2 * day), intArrayOf(8000, 7000), h)
        val b = ChartMath.emaTrend(longArrayOf(0, day, 2 * day), intArrayOf(8000, 7000, 7000), h)
        assertEquals(b[2], a[1], 1e-6)
    }

    @Test
    fun emaSameInstantAveragesAndEmpty() {
        val t = ChartMath.emaTrend(longArrayOf(0, 0, 0), intArrayOf(100, 200, 300), 1000.0)
        assertEquals(200.0, t[2], eps)
        assertEquals(0, ChartMath.emaTrend(LongArray(0), IntArray(0), 1.0).size)
        val instant = ChartMath.emaTrend(longArrayOf(0, 5), intArrayOf(1, 9), 0.0)
        assertEquals(9.0, instant[1], eps)
    }

    @Test
    fun emaConvergesToConstant() {
        val n = 200
        val t = LongArray(n) { it * 86_400L }
        val v = IntArray(n) { if (it == 0) 9000 else 8000 }
        val trend = ChartMath.emaTrend(t, v, ChartMath.halfLifeSec(10.0))
        assertEquals(8000.0, trend.last(), 1.0)
        for (i in 1 until n) assertTrue(trend[i] <= trend[i - 1])
    }

    @Test
    fun averageOfPresentSkipsMissing() {
        assertEquals(20.0, ChartMath.averageOfPresent(intArrayOf(10, -1, 30))!!, eps)
        assertEquals(null, ChartMath.averageOfPresent(intArrayOf(-1, -1)))
        assertEquals(null, ChartMath.averageOfPresent(IntArray(0)))
    }

    // ---- Bar labels and tags (TrendChartView) ----

    @Test
    fun endInsetCentresTheLastLabelUnderTheLastBar() {
        // 31 days in 300 px with a 30 px "31" label: the label overhangs half a slot, so keep some room.
        val avail = 300f
        val n = 31
        val w = 30f
        val inset = ChartMath.endInsetForCentredLabel(avail, n, w)
        assertTrue(inset > 0f)
        val slotW = (avail - inset) / n
        val lastCentre = slotW * (n - 0.5f)
        assertEquals(avail, lastCentre + w / 2f, 1e-3f)
        // A label narrower than its slot needs no room.
        assertEquals(0f, ChartMath.endInsetForCentredLabel(300f, 7, 20f), 0f)
        assertEquals(0f, ChartMath.endInsetForCentredLabel(300f, 0, 20f), 0f)
    }

    @Test
    fun axisLabelsKeepTheLastBarAndSitCentred() {
        // Month view: 30 bars, 10 px slots, labels 16 px wide, so every 2nd label from the end.
        val n = 30
        val widths = FloatArray(n) { 16f }
        val spots = ChartMath.axisLabelSpots(n, 40f, 340f, widths, 0f, 360f, 4f)
        assertEquals(n - 1, spots.first().index)
        for (s in spots) {
            val centre = 40f + 10f * (s.index + 0.5f)
            assertEquals("label undefined centred on its bar", centre, s.left + 8f, 1e-3f)
            assertEquals(0, (n - 1 - s.index) % 2)
        }
        // Never overlapping, with air between neighbours.
        for (k in 1 until spots.size) assertTrue(spots[k].left + 16f + 4f <= spots[k - 1].left)
    }

    @Test
    fun axisLabelNudgedAtTheEdgeDropsAColliderInsteadOfOverlapping() {
        // The plot runs to the edge (no end inset) and labels are 4 slots wide (stride 5): the last label
        // is nudged left 15 px, so its neighbour five slots back would touch it and is dropped.
        val n = 20
        val widths = FloatArray(n) { 40f }
        val spots = ChartMath.axisLabelSpots(n, 0f, 200f, widths, 0f, 200f, 4f)
        assertEquals(listOf(19, 9, 4), spots.map { it.index })
        assertEquals(160f, spots[0].left, 1e-3f)
        assertEquals(75f, spots[1].left, 1e-3f)
        for (k in 1 until spots.size) assertTrue(spots[k].left + 40f + 4f <= spots[k - 1].left)
        assertTrue(ChartMath.axisLabelSpots(0, 0f, 100f, FloatArray(0), 0f, 100f, 4f).isEmpty())
    }

    @Test
    fun slotsUnderSpan() {
        assertEquals(7..9, ChartMath.slotsUnder(75f, 100f, 0f, 100f, 10))
        assertEquals(0..1, ChartMath.slotsUnder(-5f, 20f, 0f, 100f, 10))
        assertEquals(3..3, ChartMath.slotsUnder(31f, 39f, 0f, 100f, 10))
        assertTrue(ChartMath.slotsUnder(120f, 140f, 0f, 100f, 10).isEmpty())
    }

    @Test
    fun tagSitsAboveItsLineWhenTheBarsAreLower() {
        // Line at 100, bars beneath reach up to 150 only: just above the line, or just below it.
        assertArrayEquals(floatArrayOf(100f - 3f - 20f, 103f), ChartMath.tagTopsClearOfBars(100f, 20f, 3f, 150f, 0f), 1e-3f)
    }

    @Test
    fun tagClimbsAboveTheTallestBarItWouldCover() {
        // Average line at 100 but a bar under the tag reaches 60: the tag goes above that bar.
        val tops = ChartMath.tagTopsClearOfBars(100f, 20f, 3f, 60f, 0f)
        assertArrayEquals(floatArrayOf(60f - 3f - 20f), tops, 1e-3f)
        assertTrue(tops[0] + 20f <= 60f)
    }

    @Test
    fun tagGoesBelowItsLineAtTheTopAndGivesUpWhenNothingIsClear() {
        // Goal line near the top, bars well below: no room above, so below the line.
        assertArrayEquals(floatArrayOf(13f), ChartMath.tagTopsClearOfBars(10f, 20f, 3f, 150f, 0f), 1e-3f)
        // A bar reaching the top under the tag: nowhere clear.
        assertEquals(0, ChartMath.tagTopsClearOfBars(100f, 20f, 3f, 5f, 0f).size)
    }

    // ---- Layer domains ----

    @Test
    fun barDomainIncludesZeroAndSkipsMissing() {
        val plain = BarLayer("a", "A", 0, Axis.LEFT, 0, 60, intArrayOf(-1, 30, 120, -1), scale = 10)
        assertEquals(0.0 to 12.0, ChartMath.barDomain(plain))
        assertTrue(plain.hasValue(1))
        assertFalse(plain.hasValue(0))
        val signed = BarLayer("n", "N", 0, Axis.HIDDEN, 0, 60, intArrayOf(-400, BarLayer.NO_DATA, 250), signed = true)
        assertEquals(-400.0 to 250.0, ChartMath.barDomain(signed))
        assertFalse(signed.hasValue(1))
        assertTrue(signed.hasValue(0))
        val allNegative = BarLayer("n", "N", 0, Axis.HIDDEN, 0, 60, intArrayOf(-50, -20), signed = true)
        assertEquals(-50.0 to 0.0, ChartMath.barDomain(allNegative))
        assertEquals(null, ChartMath.barDomain(BarLayer("e", "E", 0, Axis.LEFT, 0, 60, intArrayOf(-1, -5))))
        assertEquals(null, ChartMath.barDomain(BarLayer("e", "E", 0, Axis.LEFT, 0, 60, intArrayOf(BarLayer.NO_DATA), signed = true)))
    }

    @Test
    fun dotTrendDomainCoversReadingsTrendAndGoal() {
        val l = DotTrendLayer("w", "W", 0, Axis.LEFT, longArrayOf(1, 2, 3), intArrayOf(1820, 1790, 1805), trend = intArrayOf(1830, 1810, 1808), goal = 1750, scale = 10)
        val d = ChartMath.dotTrendDomain(l) ?: error("no domain")
        assertEquals(175.0, d.first, eps)
        assertEquals(183.0, d.second, eps)
        // Values without a time are ignored; no readings means no domain.
        val ragged = DotTrendLayer("w", "W", 0, Axis.LEFT, longArrayOf(1), intArrayOf(500, 900))
        assertEquals(500.0 to 500.0, ChartMath.dotTrendDomain(ragged))
        assertEquals(null, ChartMath.dotTrendDomain(DotTrendLayer("w", "W", 0, Axis.LEFT, LongArray(0), IntArray(0), goal = 7)))
    }

    @Test
    fun stretchTopKeepsDataInTheBottomFraction() {
        assertEquals(250.0, ChartMath.stretchTop(0.0, 100.0, 0.4f), 1e-4)
        assertEquals(100.0, ChartMath.stretchTop(0.0, 100.0, 1f), 1e-4)
        assertEquals(100.0, ChartMath.stretchTop(0.0, 100.0, 3f), 1e-4) // clamped to 1
        assertEquals(990.0, ChartMath.stretchTop(-10.0, 90.0, 0f), 1e-4) // clamped to 0.1
        // The data's top lands at the requested fraction of the stretched scale.
        val top = ChartMath.stretchTop(-400.0, 300.0, 0.35f)
        assertEquals(0.35, (300.0 + 400.0) / (top + 400.0), 1e-4)
    }
}
