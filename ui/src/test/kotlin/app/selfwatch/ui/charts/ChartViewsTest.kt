/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.charts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Parcelable
import android.os.SystemClock
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.time.TimeRange
import app.selfwatch.ui.R
import app.selfwatch.ui.format.Units
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChartViewsTest {
    private val ctx: Context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_SelfWatch)
    private val start = 1_763_852_400L
    private val end = start + 8 * 3600L

    private fun layers(): List<ChartLayer> {
        val hrT = LongArray(8 * 3600) { start + it }.filter { it !in (start + 3600)..(start + 4800) }.toLongArray()
        val hr = IntArray(hrT.size) { 50 + (it % 10) }
        return listOf(
            LineLayer("hr", "Heart rate", 0xFFFF8A1F.toInt(), Axis.LEFT, hrT, hr, unit = "bpm"),
            StageLayer(
                "stages", "Stages", 0xFF4DABF7.toInt(),
                listOf(StageSpan(start, start + 1800, SleepStage.AWAKE), StageSpan(start + 1800, end, SleepStage.DEEP)),
            ),
            LineLayer("br", "Breathing", 0xFF5BC08A.toInt(), Axis.RIGHT, longArrayOf(start, start + 60), intArrayOf(140, 152), scale = 10, unit = "br/min"),
        )
    }

    private fun chart(): OverlayChartView = OverlayChartView(ctx).apply {
        id = View.generateViewId()
        range = TimeRange(start, end)
        layers = layers()
        layoutAt(this)
    }

    private fun layoutAt(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1080, 600)
    }

    private fun draw(v: View) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
    }

    @Test
    fun valuesAtReadsEveryVisibleLayerInDrawOrder() {
        val c = chart()
        val values = c.valuesAt(start + 60)
        assertEquals(listOf("stages", "hr", "br"), values.map { it.layerId })
        assertEquals(SleepStage.AWAKE, values[0].stage)
        assertEquals("Awake", values[0].text)
        assertEquals(50.0 + 60 % 10, values[1].value!!, 0.0)
        assertTrue(values[1].text.endsWith("bpm"))
        assertEquals(15.2, values[2].value!!, 1e-9)
    }

    @Test
    fun valuesInsideGapAreEmpty() {
        val c = chart()
        val hr = c.valuesAt(start + 4200).first { it.layerId == "hr" }
        assertNull(hr.value)
        assertEquals(ctx.getString(R.string.sw_chart_no_value), hr.text)
    }

    @Test
    fun hiddenLayersDropOut() {
        val c = chart()
        c.setVisible("hr", false)
        assertEquals(listOf("stages", "br"), c.valuesAt(start).map { it.layerId })
        c.setVisible("hr", true)
        assertTrue(c.isLayerVisible("hr"))
        draw(c)
    }

    @Test
    fun touchScrubsAndReportsValues() {
        val c = chart()
        var got: Long? = null
        c.onScrub = { t, values ->
            got = t
            assertEquals(3, values.size)
        }
        draw(c) // lays out the plot
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 540f, 300f, 0)
        c.onTouchEvent(down)
        assertNotNull(got)
        assertTrue(got!! in start..end)
        assertEquals(got, c.scrubTime)
        draw(c)
        c.onTouchEvent(MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_CANCEL, 540f, 300f, 0))
        assertNull(c.scrubTime)
    }

    @Test
    fun zoomAndVisibilitySurviveSaveAndRestore() {
        val c = chart()
        c.zoomBy(2.0, start + 3600)
        c.setVisible("br", false)
        val state = SparseArray<Parcelable>()
        c.saveHierarchyState(state)

        // Restored before data arrives (pending) ...
        val d = OverlayChartView(ctx)
        d.id = c.id
        d.restoreHierarchyState(state)
        d.range = TimeRange(start, end)
        d.layers = layers()
        assertEquals(c.visibleWindow, d.visibleWindow)
        assertTrue(!d.isLayerVisible("br"))

        // ... and after.
        val e = chart()
        e.id = c.id
        e.restoreHierarchyState(state)
        assertEquals(c.visibleWindow, e.visibleWindow)
        e.resetZoom()
        assertEquals(TimeRange(start, end), e.visibleWindow)
    }

    @Test
    fun zoomStaysInsideRange() {
        val c = chart()
        repeat(30) { c.zoomBy(2.0, end) }
        val w = c.visibleWindow
        assertEquals(end, w.endSec)
        assertEquals(300L, w.durationSec)
    }

    @Test
    fun tableRowsCoverWindow() {
        val rows = chart().tableRows()
        assertTrue(rows.size in 8..48)
        assertTrue(rows.first().contains("Awake"))
        assertTrue(rows.last().contains("Deep"))
    }

    @Test
    fun emptyAndSinglePointDrawWithoutCrashing() {
        val empty = OverlayChartView(ctx)
        layoutAt(empty)
        draw(empty)
        assertEquals(ctx.getString(R.string.sw_chart_no_data), empty.contentDescription)

        val one = OverlayChartView(ctx).apply {
            range = TimeRange(start, end)
            layers = listOf(LineLayer("x", "X", 0xFF000000.toInt(), Axis.LEFT, longArrayOf(start + 10), intArrayOf(5)))
        }
        layoutAt(one)
        draw(one)
        assertEquals(5.0, one.valuesAt(start + 10).single().value!!, 0.0)

        val bars = OverlayChartView(ctx).apply {
            range = TimeRange(start, end)
            layers = listOf(
                BarLayer("b", "B", 0xFF000000.toInt(), Axis.LEFT, start, 3600, intArrayOf(1, -1, 3)),
                BandLayer("band", "Band", 0xFF000000.toInt(), Axis.RIGHT, longArrayOf(start, start + 3600), intArrayOf(40, 45), intArrayOf(80, 90)),
            )
        }
        layoutAt(bars)
        draw(bars)
        val v = bars.valuesAt(start + 3600 + 5)
        assertNull(v.first { it.layerId == "b" }.value)
        assertEquals(45.0, v.first { it.layerId == "band" }.low!!, 0.0)
    }

    @Test
    fun dotTrendAndSignedBarsDrawAndRead() {
        val day = 86_400L
        val from = start
        val to = start + 10 * day
        val weight = DotTrendLayer(
            "w", "Weight", 0xFF3D4A55.toInt(), Axis.LEFT,
            longArrayOf(from + 7 * 3600, from + 2 * day + 7 * 3600, from + 5 * day + 7 * 3600),
            intArrayOf(1816, 1810, 1801), trend = intArrayOf(1816, 1814, 1810), goal = 1750, scale = 10, unit = "lb", lockedOn = true,
        )
        val net = BarLayer(
            "net", "Net", 0xFF6B5BD6.toInt(), Axis.HIDDEN, from, day,
            intArrayOf(-390, 250, BarLayer.NO_DATA, 0, -120, 80, -60, -10, 300, -200), unit = "kcal", signed = true, heightFraction = 0.4f,
        )
        val c = OverlayChartView(ctx).apply {
            range = TimeRange(from, to)
            layers = listOf(net, weight)
        }
        layoutAt(c)
        draw(c)
        val v = c.valuesAt(from + 2 * day + 9 * 3600)
        assertEquals(listOf("net", "w"), v.map { it.layerId }) // bars under the weight
        assertEquals(181.0, v.first { it.layerId == "w" }.value!!, 1e-9)
        assertTrue(v.first { it.layerId == "w" }.text.endsWith("lb"))
        assertNull(v.first { it.layerId == "net" }.value) // NO_DATA
        val dayFour = c.valuesAt(from + 4 * day + 3600)
        assertEquals(-120.0, dayFour.first { it.layerId == "net" }.value!!, 0.0)
        assertNull(dayFour.first { it.layerId == "w" }.value) // the nearest weigh-in is over 12 h away
        assertTrue(c.valuesAt(from + day + 3600).first { it.layerId == "net" }.text.startsWith("+"))
        c.scrubTo(from + 2 * day + 7 * 3600)
        draw(c)
    }

    @Test
    fun minSecLayerReadsAsMinutesAndSeconds() {
        val day = 86_400L
        // Pace in seconds per km with scale 60: drawn as minutes, written m:ss (on a left axis here too).
        val pace = DotTrendLayer(
            "pace", "Pace", 0xFF3D4A55.toInt(), Axis.LEFT, longArrayOf(start, start + day, start + 2 * day),
            intArrayOf(312, 298, 305), trend = intArrayOf(310, 305, 303), scale = 60, unit = "/km", style = ValueStyle.MIN_SEC,
        )
        val c = OverlayChartView(ctx).apply {
            range = TimeRange(start - day, start + 3 * day)
            layers = listOf(pace)
        }
        layoutAt(c)
        draw(c)
        val v = c.valuesAt(start + day).single()
        assertEquals(298 / 60.0, v.value!!, 1e-9)
        assertTrue(v.text, v.text.startsWith("4:58"))
        assertTrue(v.text, v.text.endsWith("/km"))
        assertTrue(c.valuesAt(start).single().text.startsWith("5:12"))
    }

    @Test
    fun lockedLayerChipStaysOn() {
        val c = OverlayChartView(ctx).apply {
            range = TimeRange(start, end)
            layers = listOf(
                DotTrendLayer("w", "Weight", 0xFF3D4A55.toInt(), Axis.LEFT, longArrayOf(start + 60), intArrayOf(800), lockedOn = true),
                LineLayer("x", "X", 0xFF000000.toInt(), Axis.RIGHT, longArrayOf(start, start + 60), intArrayOf(1, 2)),
            )
        }
        val chips = app.selfwatch.ui.components.ToggleChipGroup(ctx)
        c.setVisible("w", false)
        c.bindChips(chips)
        assertTrue(c.isLayerVisible("w"))
        chips.setOn("w", false)
        chips.setOn("x", false)
        assertEquals(setOf("w"), chips.onIds)
        assertTrue(!c.isLayerVisible("x"))
    }

    @Test
    fun trendChartDrawsAndDescribes() {
        val t = TrendChartView(ctx).apply {
            values = intArrayOf(10, -1, 30)
            labels = listOf("a", "b", "c")
            goal = 25
        }
        layoutAt(t)
        draw(t)
        t.selectedIndex = 2
        draw(t)
        assertEquals(2, t.selectedIndex)
        t.values = intArrayOf(1)
        assertEquals(-1, t.selectedIndex)
        assertTrue(t.contentDescription.isNotEmpty())
    }

    @Test
    fun weightTrendSortsEntriesAndSmooths() {
        val day = 86_400L
        val w = WeightTrendView(ctx).apply {
            units = Units.METRIC
            goalKgX100 = 7800
            setEntries(longArrayOf(start + 2 * day, start, start + day), intArrayOf(8200, 8400, 8300))
        }
        layoutAt(w)
        draw(w)
        assertArrayEquals(longArrayOf(start, start + day, start + 2 * day), w.entryTimes)
        val expected = ChartMath.emaTrend(longArrayOf(start, start + day, start + 2 * day), intArrayOf(8400, 8300, 8200), 10 * 86_400.0)
        assertArrayEquals(expected, w.trendKgX100, 1e-9)
        w.selectedIndex = 1
        draw(w)
        w.units = Units.IMPERIAL
        draw(w)
    }
}
