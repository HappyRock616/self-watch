/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.charts

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.selfwatch.model.time.TimeRange
import app.selfwatch.ui.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Task C08: draw time of an [OverlayChartView] showing a full night (8 h of 1 Hz heart rate, stages, HRV
 * every 5 minutes, breathing every minute) at phone size, rendered into a Bitmap-backed (software)
 * Canvas. Software drawing is slower than the hardware pipeline the real screen uses, so this is an upper
 * bound for the UI-thread cost. Results go to logcat (tag SwChartBench) and the instrumentation status.
 */
@RunWith(AndroidJUnit4::class)
class ChartBenchmarkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private class Result(val name: String, val medianMs: Double, val p90Ms: Double)

    @Test
    fun fullNightDrawsUnderFourMs() {
        val results = ArrayList<Result>()
        instrumentation.runOnMainSync {
            val ctx = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_SelfWatch)
            val start = 1_763_852_400L
            val night = ChartSamples.night(start)
            val chart = OverlayChartView(ctx).apply {
                range = TimeRange(night.startSec, night.endSec)
                layers = listOf(
                    StageLayer("stages", "Stages", ChartPalette.of(ctx).secondary, night.stages),
                    LineLayer("hr", "Heart rate", ChartPalette.of(ctx).series(0), Axis.LEFT, night.hrT, night.hr, unit = "bpm"),
                    LineLayer("hrv", "HRV", ChartPalette.of(ctx).series(1), Axis.RIGHT, night.hrvT, night.hrv, unit = "ms"),
                    LineLayer("br", "Breathing", ChartPalette.of(ctx).series(2), Axis.HIDDEN, night.breathT, night.breathX10, scale = 10, unit = "br/min"),
                )
            }
            // Pixel 5: 1080 px wide; the chart sits in a card with 16 dp margins, 260 dp tall.
            val density = ctx.resources.displayMetrics.density
            val w = (ctx.resources.displayMetrics.widthPixels - 2 * 32 * density).toInt()
            val h = (260 * density).toInt()
            chart.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            chart.layout(0, 0, w, h)
            val canvas = Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))

            results += measure("static") { chart.draw(canvas) }
            for (only in listOf("stages", "hr", "hrv", "br")) {
                listOf("stages", "hr", "hrv", "br").forEach { chart.setVisible(it, it == only) }
                chart.draw(canvas)
                Log.i(TAG, "only $only: " + measure(only) { chart.draw(canvas) }.medianMs)
            }
            listOf("stages", "hr", "hrv", "br").forEach { chart.setVisible(it, true) }
            val full = Window(night.startSec, night.endSec)
            Log.i(TAG, "downsample hr: " + measure("ds") { ChartMath.downsample(night.hrT, night.hr, full, w, 600) }.medianMs)
            val s = ChartMath.downsample(night.hrT, night.hr, full, w, 600)
            val ys = FloatArray(s.size) { s.a[it] }
            Log.i(TAG, "envelope hr: " + measure("env") { ChartMath.envelope(s.xs, ys, 0, s.size, -1, w) }.medianMs)
            val fmt = ChartFormat(ctx, java.time.ZoneId.systemDefault())
            Log.i(TAG, "5 time labels: " + measure("fmt") { repeat(5) { fmt.time(start + it * 3600L) } }.medianMs)
            var i = 0
            results += measure("scrub") {
                i++
                chart.scrubTo(start + (i * 97L) % (8 * 3600L))
                chart.draw(canvas)
            }
            chart.scrubTo(null)
            var zoomIn = true
            results += measure("zoom (paths rebuilt)") {
                if (zoomIn) chart.zoomBy(1.25, start + 4 * 3600L) else chart.zoomBy(0.8, start + 4 * 3600L)
                zoomIn = !zoomIn
                chart.draw(canvas)
            }
            Log.i(TAG, "device ${Build.MODEL}, chart ${w}x$h px, ${night.hr.size} HR, ${night.stages.size} stage spans, ${night.hrv.size} HRV, ${night.breathX10.size} breathing")
        }
        val status = Bundle()
        for (r in results) {
            val line = "%s: median %.2f ms, p90 %.2f ms".format(r.name, r.medianMs, r.p90Ms)
            Log.i(TAG, line)
            status.putString(r.name, line)
        }
        instrumentation.sendStatus(0, status)
        assertTrue("static median ${results[0].medianMs} ms", results[0].medianMs < 4.0)
        assertTrue("scrub median ${results[1].medianMs} ms", results[1].medianMs < 4.0)
        assertTrue("zoom median ${results[2].medianMs} ms", results[2].medianMs < 4.0)
    }

    private fun measure(name: String, frame: () -> Unit): Result {
        repeat(WARMUP) { frame() }
        val times = DoubleArray(RUNS)
        for (k in 0 until RUNS) {
            val t0 = System.nanoTime()
            frame()
            times[k] = (System.nanoTime() - t0) / 1e6
        }
        times.sort()
        return Result(name, times[RUNS / 2], times[(RUNS * 9) / 10])
    }

    private companion object {
        const val TAG: String = "SwChartBench"
        const val WARMUP: Int = 60
        const val RUNS: Int = 300
    }
}
