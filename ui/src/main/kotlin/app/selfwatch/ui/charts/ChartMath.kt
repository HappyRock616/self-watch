/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.charts

import app.selfwatch.model.health.SleepStage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// Pure maths for the charts: ticks, scales, down-sampling, lookups, zoom and smoothing. No Android types,
// so all of it is unit tested on the JVM.

/** Evenly spaced value ticks from [lo] to [hi] (both on the grid) every [step]. */
internal data class Ticks(val lo: Double, val hi: Double, val step: Double) {
    /** Number of ticks including both ends. */
    val count: Int get() = if (step <= 0.0) 1 else ((hi - lo) / step).roundToInt() + 1

    /** The tick values, low to high. */
    fun values(): DoubleArray = DoubleArray(count) { lo + it * step }

    /** Fraction digits needed to print every tick exactly (0 for whole steps, 1 for 0.5, 2 for 0.25...). */
    val decimals: Int get() = decimalsFor(step)
}

/** A time window in epoch seconds, [start] inclusive and [end] exclusive. */
internal data class Window(val start: Long, val end: Long) {
    /** Length in seconds (never negative). */
    val span: Long get() = max(0L, end - start)
}

/**
 * Down-sampled points of one layer: [size] points with pixel x in [xs] and raw values in [a] (and the
 * band's upper bound in [b], else null). A new line segment starts at every index in [breaks].
 */
internal class Sampled(val xs: FloatArray, val a: FloatArray, val b: FloatArray?, val size: Int, val breaks: IntArray) {
    /** True when there is nothing to draw. */
    val isEmpty: Boolean get() = size == 0

    companion object {
        /** No points. */
        val EMPTY: Sampled = Sampled(FloatArray(0), FloatArray(0), null, 0, IntArray(0))
    }
}

/** Per pixel column ([x] = column centre): the line's highest point [top] and lowest point [bottom], in px. */
internal class Envelope(val x: FloatArray, val top: FloatArray, val bottom: FloatArray, val size: Int) {
    companion object {
        /** No columns. */
        val EMPTY: Envelope = Envelope(FloatArray(0), FloatArray(0), FloatArray(0), 0)
    }
}

/** A bar label to draw: the bar's [index] and the label's [left] edge in px. */
internal data class AxisLabelSpot(val index: Int, val left: Float)

internal object ChartMath {
    private val NICE_MANTISSAS: DoubleArray = doubleArrayOf(1.0, 2.0, 2.5, 5.0)

    /** Time-axis steps in seconds, smallest first. */
    val TIME_STEPS: LongArray = longArrayOf(
        60, 120, 300, 600, 900, 1_800, 3_600, 2 * 3_600, 3 * 3_600, 6 * 3_600, 12 * 3_600,
        86_400, 2 * 86_400, 7 * 86_400, 14 * 86_400, 28 * 86_400, 91 * 86_400, 182 * 86_400, 364 * 86_400,
    )

    // ---- Ticks ----

    /**
     * Nice ticks covering [min]..[max] with at most [maxTicks] ticks (at least 2): the smallest step of the
     * form 1, 2, 2.5 or 5 × 10ⁿ that fits. A flat range is widened around its value; NaN or infinite
     * input gives 0..1.
     */
    fun niceTicks(min: Double, max: Double, maxTicks: Int = 5): Ticks {
        var lo = min
        var hi = max
        if (!lo.isFinite() || !hi.isFinite()) return Ticks(0.0, 1.0, 0.5)
        if (lo > hi) lo = hi.also { hi = lo }
        if (hi - lo < 1e-9) {
            val pad = if (abs(lo) < 1e-9) 1.0 else abs(lo) * 0.1
            lo -= pad
            hi += pad
        }
        val n = max(2, maxTicks)
        val rough = (hi - lo) / (n - 1)
        var exp = floor(log10(rough)).toInt() - 1
        while (true) {
            val base = 10.0.pow(exp)
            for (m in NICE_MANTISSAS) {
                val step = m * base
                val tLo = floor(lo / step + 1e-9) * step
                val tHi = ceil(hi / step - 1e-9) * step
                val count = ((tHi - tLo) / step).roundToInt() + 1
                if (count <= n) return Ticks(clean(tLo, step), clean(tHi, step), step)
            }
            exp++
        }
    }

    /** Fraction digits for printing multiples of [step]. */
    fun decimalsForStep(step: Double): Int = decimalsFor(step)

    private fun clean(v: Double, step: Double): Double {
        val d = decimalsFor(step)
        val f = 10.0.pow(d)
        val r = kotlin.math.round(v * f) / f
        return if (r == 0.0) 0.0 else r
    }

    /**
     * Time ticks inside `[start, end]`: the smallest step from [TIME_STEPS] giving at most [maxTicks] ticks,
     * aligned to local time ([offsetSec] is the zone's UTC offset, e.g. 3600 for UTC+1), so hourly ticks land
     * on the hour and daily ticks on midnight.
     */
    fun timeTicks(start: Long, end: Long, maxTicks: Int, offsetSec: Int): LongArray {
        if (end <= start || maxTicks <= 0) return LongArray(0)
        val step = timeStep(start, end, maxTicks, offsetSec)
        val first = Math.floorDiv(start + offsetSec + step - 1, step) * step - offsetSec
        val out = ArrayList<Long>()
        var t = first
        while (t <= end) {
            out += t
            t += step
        }
        return out.toLongArray()
    }

    /** The step [timeTicks] uses for this window. */
    fun timeStep(start: Long, end: Long, maxTicks: Int, offsetSec: Int): Long {
        for (step in TIME_STEPS) {
            val first = Math.floorDiv(start + offsetSec + step - 1, step) * step - offsetSec
            val count = if (first > end) 0 else (end - first) / step + 1
            if (count <= maxTicks) return step
        }
        return TIME_STEPS.last()
    }

    // ---- Scales ----

    /** Y pixel of [value] on a [lo]..[hi] scale drawn from [bottom] (lo) up to [top] (hi). Not clamped. */
    fun valueToY(value: Double, lo: Double, hi: Double, top: Float, bottom: Float): Float {
        if (hi <= lo) return (top + bottom) / 2f
        return (bottom - (value - lo) / (hi - lo) * (bottom - top)).toFloat()
    }

    /** X pixel of time [t] in window [w] drawn from [left] to [right]. Not clamped. */
    fun timeToX(t: Long, w: Window, left: Float, right: Float): Float {
        if (w.span <= 0) return left
        return left + ((t - w.start).toDouble() / w.span * (right - left)).toFloat()
    }

    /** Time at pixel [x] in window [w] drawn from [left] to [right], clamped to the window. */
    fun xToTime(x: Float, w: Window, left: Float, right: Float): Long {
        if (right <= left) return w.start
        val f = ((x - left) / (right - left)).toDouble().coerceIn(0.0, 1.0)
        return w.start + (f * w.span).toLong()
    }

    // ---- Lookups ----

    /** First index in sorted [t] (first [size] items) whose value is ≥ [target]; [size] if none. */
    fun lowerBound(t: LongArray, size: Int, target: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (t[mid] < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Index of the sample nearest [target] in sorted [t] (first [size] items), or -1 when empty. */
    fun nearestIndex(t: LongArray, size: Int, target: Long): Int {
        if (size <= 0) return -1
        val i = lowerBound(t, size, target)
        if (i == 0) return 0
        if (i == size) return size - 1
        return if (target - t[i - 1] <= t[i] - target) i - 1 else i
    }

    /**
     * Index of the line sample to show at [target]: the nearest one, or -1 when there is none within
     * [maxDistSec] (the layer's gap), so the bubble shows "no data" inside a gap.
     */
    fun lineIndexAt(t: LongArray, size: Int, target: Long, maxDistSec: Long): Int {
        val i = nearestIndex(t, size, target)
        if (i < 0) return -1
        return if (abs(t[i] - target) <= maxDistSec) i else -1
    }

    /** Bucket index containing [target], or -1 outside the [count] buckets. */
    fun bucketIndex(startSec: Long, bucketSec: Long, count: Int, target: Long): Int {
        if (bucketSec <= 0 || target < startSec) return -1
        val i = (target - startSec) / bucketSec
        return if (i < count) i.toInt() else -1
    }

    /** Index of the span containing [target] in sorted, non-overlapping spans, or -1. */
    fun spanIndexAt(starts: LongArray, ends: LongArray, target: Long): Int {
        val i = lowerBound(starts, starts.size, target + 1) - 1
        if (i < 0) return -1
        return if (target < ends[i]) i else -1
    }

    // ---- Down-sampling ----

    /**
     * Down-samples a line to at most two points (the minimum and the maximum, in time order) per pixel
     * column of a [widthPx]-wide plot showing [w]. One sample either side of the window is kept so the
     * line runs to the edges. A gap longer than [gapSec] between consecutive samples starts a new segment.
     * X values are pixels from the plot's left edge; values stay raw.
     */
    fun downsample(t: LongArray, v: IntArray, w: Window, widthPx: Int, gapSec: Long): Sampled =
        sample(t, v, null, w, widthPx, gapSec)

    /**
     * Down-samples a band: per pixel column the lowest [low] and the highest [high], at the column's first
     * sample time. Gaps longer than [gapSec] split the band.
     */
    fun downsampleBand(t: LongArray, low: IntArray, high: IntArray, w: Window, widthPx: Int, gapSec: Long): Sampled =
        sample(t, low, high, w, widthPx, gapSec)

    private fun sample(t: LongArray, a: IntArray, b: IntArray?, w: Window, widthPx: Int, gapSec: Long): Sampled {
        val n = if (b == null) min(t.size, a.size) else min(t.size, min(a.size, b.size))
        val span = w.span
        if (n == 0 || widthPx <= 0 || span <= 0) return Sampled.EMPTY
        val first = max(0, lowerBound(t, n, w.start) - 1)
        val last = min(n - 1, lowerBound(t, n, w.end))
        if (first > last) return Sampled.EMPTY
        val cap = min((last - first + 1) * 2, (widthPx + 4) * 2 * 4)
        val out = SampleBuffer(cap, b != null, w.start, widthPx.toDouble() / span)
        val start = w.start
        val wide = widthPx.toLong()

        // A tight loop over plain locals: this runs over every sample in view (28,800 for a night of 1 Hz
        // heart rate) whenever the zoom window changes.
        var col = 0L
        var hasCol = false
        var minV = 0
        var maxV = 0
        var minT = 0L
        var maxT = 0L
        var colT = 0L
        var prevT = 0L
        for (i in first..last) {
            val ti = t[i]
            val lowV = a[i]
            val highV = if (b == null) lowV else b[i]
            val gap = i > first && ti - prevT > gapSec
            val c = Math.floorDiv((ti - start) * wide, span)
            if (hasCol && (gap || c != col)) {
                out.emit(colT, minT, minV, maxT, maxV)
                hasCol = false
            }
            if (gap) out.markBreak()
            if (!hasCol) {
                col = c
                hasCol = true
                colT = ti
                minV = lowV
                minT = ti
                maxV = highV
                maxT = ti
            } else {
                if (lowV < minV) {
                    minV = lowV
                    minT = ti
                }
                if (highV > maxV) {
                    maxV = highV
                    maxT = ti
                }
            }
            prevT = ti
        }
        if (hasCol) out.emit(colT, minT, minV, maxT, maxV)
        return out.toSampled()
    }

    /** Output of [sample]: grows nothing, drops points beyond its capacity (only possible with many gaps). */
    private class SampleBuffer(cap: Int, band: Boolean, private val start: Long, private val pxPerSec: Double) {
        val xs = FloatArray(cap)
        val a = FloatArray(cap)
        val b: FloatArray? = if (band) FloatArray(cap) else null
        var size = 0
        private var breaks = IntArray(8)
        private var breakCount = 0

        private fun x(time: Long): Float = ((time - start) * pxPerSec).toFloat()

        fun emit(colT: Long, minT: Long, minV: Int, maxT: Long, maxV: Int) {
            val bb = b
            if (bb != null) {
                if (size + 1 > xs.size) return
                xs[size] = x(colT); a[size] = minV.toFloat(); bb[size] = maxV.toFloat(); size++
            } else if (minT == maxT || minV == maxV) {
                if (size + 1 > xs.size) return
                xs[size] = x(minT); a[size] = minV.toFloat(); size++
            } else {
                if (size + 2 > xs.size) return
                if (minT < maxT) {
                    xs[size] = x(minT); a[size] = minV.toFloat(); size++
                    xs[size] = x(maxT); a[size] = maxV.toFloat(); size++
                } else {
                    xs[size] = x(maxT); a[size] = maxV.toFloat(); size++
                    xs[size] = x(minT); a[size] = minV.toFloat(); size++
                }
            }
        }

        fun markBreak() {
            if (size == 0 || (breakCount > 0 && breaks[breakCount - 1] == size)) return
            if (breakCount == breaks.size) breaks = breaks.copyOf(breaks.size * 2)
            breaks[breakCount++] = size
        }

        fun toSampled(): Sampled {
            val br = breaks.copyOf(breakCount).filter { it < size }.toIntArray()
            return Sampled(xs, a, b, size, br)
        }
    }

    /**
     * The column envelope of one polyline segment (points `from until to` of [xs], [ys], in pixels, x
     * ascending): for every whole pixel column the line passes through, the smallest and largest y the line
     * reaches inside that column, including where it crosses the column's edges. Painting each column from
     * `top − w/2` to `bottom + w/2` draws the line `w` px thick without stroking thousands of tiny segments,
     * which is several times cheaper. Columns outside [minCol]..[maxCol] are skipped.
     */
    fun envelope(xs: FloatArray, ys: FloatArray, from: Int, to: Int, minCol: Int, maxCol: Int): Envelope {
        if (to <= from) return Envelope.EMPTY
        val c0 = max(minCol, floor(xs[from]).toInt())
        val c1 = min(maxCol, floor(xs[to - 1]).toInt())
        if (c1 < c0) return Envelope.EMPTY
        val n = c1 - c0 + 1
        val top = FloatArray(n) { Float.POSITIVE_INFINITY }
        val bottom = FloatArray(n) { Float.NEGATIVE_INFINITY }
        fun put(col: Int, y: Float) {
            if (col < c0 || col > c1) return
            val k = col - c0
            if (y < top[k]) top[k] = y
            if (y > bottom[k]) bottom[k] = y
        }
        if (to - from == 1) put(floor(xs[from]).toInt(), ys[from])
        for (i in from until to - 1) {
            val xa = xs[i]
            val ya = ys[i]
            val xb = xs[i + 1]
            val yb = ys[i + 1]
            val ca = max(c0, floor(xa).toInt())
            val cb = min(c1, floor(xb).toInt())
            if (xb <= xa) {
                put(floor(xa).toInt(), ya)
                put(floor(xa).toInt(), yb)
                continue
            }
            val slope = (yb - ya) / (xb - xa)
            for (c in ca..cb) {
                val l = max(xa, c.toFloat())
                val r = min(xb, (c + 1).toFloat())
                put(c, ya + (l - xa) * slope)
                put(c, ya + (r - xa) * slope)
            }
        }
        // Columns the segment skipped over entirely cannot exist (consecutive pairs cover every column), but
        // clipping can leave the ends empty: trim them.
        var s = 0
        var e = n - 1
        while (s <= e && top[s] == Float.POSITIVE_INFINITY) s++
        while (e >= s && top[e] == Float.POSITIVE_INFINITY) e--
        if (s > e) return Envelope.EMPTY
        val size = e - s + 1
        return Envelope(
            FloatArray(size) { c0 + s + it + 0.5f },
            top.copyOfRange(s, e + 1),
            bottom.copyOfRange(s, e + 1),
            size,
        )
    }

    // ---- Zoom and pan ----

    /** Smallest window span allowed when zooming into [full]: 5 minutes, or 1/96 of a long range. */
    fun minSpan(full: Window): Long = min(full.span, max(300L, full.span / 96))

    /**
     * Zooms [cur] by [factor] (2 = twice as close) keeping [anchor] at the same place on screen, then keeps
     * the result inside [full] and no narrower than [minSpan].
     */
    fun zoom(cur: Window, full: Window, factor: Double, anchor: Long): Window {
        if (full.span <= 0 || factor <= 0.0 || !factor.isFinite()) return full
        val span = (cur.span / factor).toLong().coerceIn(minSpan(full), full.span)
        val frac = if (cur.span <= 0) 0.5 else ((anchor - cur.start).toDouble() / cur.span).coerceIn(0.0, 1.0)
        val start = anchor - (frac * span).toLong()
        return clampWindow(start, span, full)
    }

    /** Moves [cur] by [deltaSec] (positive = later), staying inside [full]. */
    fun pan(cur: Window, full: Window, deltaSec: Long): Window = clampWindow(cur.start + deltaSec, cur.span, full)

    /** A window of [span] starting at [start], shifted to lie inside [full]. */
    fun clampWindow(start: Long, span: Long, full: Window): Window {
        val s = min(span, full.span)
        val st = start.coerceIn(full.start, full.end - s)
        return Window(st, st + s)
    }

    /** True when [cur] shows less than all of [full]. */
    fun isZoomed(cur: Window, full: Window): Boolean = cur.span < full.span

    // ---- Accessibility ----

    /** Index of the slice (of [count] equal slices across [left]..[right]) containing x, clamped. */
    fun sliceAt(x: Float, left: Float, right: Float, count: Int): Int {
        if (count <= 0 || right <= left) return 0
        return floor((x - left) / (right - left) * count).toInt().coerceIn(0, count - 1)
    }

    /** Centre time of slice [i] of [count] equal slices of [w]. */
    fun sliceCentre(w: Window, i: Int, count: Int): Long =
        w.start + ((i + 0.5) * w.span / max(1, count)).toLong()

    // ---- Layout helpers ----

    /** Row of a sleep stage in a hypnogram (0 = top): awake, REM, light, deep; -1 for unmeasurable. */
    fun stageRow(stage: SleepStage): Int = when (stage) {
        SleepStage.AWAKE -> 0
        SleepStage.REM -> 1
        SleepStage.LIGHT -> 2
        SleepStage.DEEP -> 3
        SleepStage.UNMEASURABLE -> -1
    }

    /** Number of hypnogram rows. */
    const val STAGE_ROWS: Int = 4

    /**
     * Left edge of a bubble [bubbleW] wide next to a scrub line at [lineX]: to the right of the line by
     * [gap], or to its left when that would cross [right]; always kept within [left]..[right].
     */
    fun bubbleLeft(lineX: Float, bubbleW: Float, left: Float, right: Float, gap: Float): Float {
        val r = lineX + gap
        val x = if (r + bubbleW <= right) r else lineX - gap - bubbleW
        return x.coerceIn(left, max(left, right - bubbleW))
    }

    /** Every [labelStride]-th label fits: labels [labelW] px wide on slots [slotW] px apart, with 25% air. */
    fun labelStride(slotW: Float, labelW: Float): Int {
        if (slotW <= 0f) return 1
        return max(1, ceil(labelW * 1.25f / slotW).toInt())
    }

    /**
     * Space to keep free at the end of a bar plot [avail] px wide with [n] bars so a label [labelW] px wide
     * can sit centred under the last bar without running off the edge: solves
     * `inset = labelW / 2 − slotW / 2` with `slotW = (avail − inset) / n`. 0 when it already fits.
     */
    fun endInsetForCentredLabel(avail: Float, n: Int, labelW: Float): Float {
        if (n <= 0 || avail <= 0f) return 0f
        val inset = (n * labelW - avail) / (2f * n - 1f)
        return inset.coerceIn(0f, avail / 2f)
    }

    /**
     * Which bar labels to draw under a bar plot and where. Bars are [n] equal slots from [plotLeft] to
     * [plotRight]; [widths] holds each label's width. Labels are thinned with [labelStride] counting back
     * from the last bar (so the newest bar is always labelled), centred under their bar and only nudged
     * inwards to stay within [minX]..[maxX]. A label that would then come closer than [air] px to the
     * label after it is dropped. Returns pairs of (bar index, label left edge), last bar first.
     */
    fun axisLabelSpots(
        n: Int,
        plotLeft: Float,
        plotRight: Float,
        widths: FloatArray,
        minX: Float,
        maxX: Float,
        air: Float,
    ): List<AxisLabelSpot> {
        val count = min(n, widths.size)
        if (count <= 0 || n <= 0) return emptyList()
        val slotW = (plotRight - plotLeft) / n
        var maxW = 0f
        for (i in 0 until count) maxW = max(maxW, widths[i])
        val stride = labelStride(slotW, maxW)
        val out = ArrayList<AxisLabelSpot>()
        var nextLeft = Float.POSITIVE_INFINITY
        var i = n - 1
        while (i >= 0) {
            if (i < count) {
                val w = widths[i]
                val centre = plotLeft + slotW * (i + 0.5f)
                val left = (centre - w / 2f).coerceIn(minX, max(minX, maxX - w))
                if (left + w + air <= nextLeft) {
                    out += AxisLabelSpot(i, left)
                    nextLeft = left
                }
            }
            i -= stride
        }
        return out
    }

    /** The bar slots (of [n] from [plotLeft] to [plotRight]) that horizontal span [x0]..[x1] touches. */
    fun slotsUnder(x0: Float, x1: Float, plotLeft: Float, plotRight: Float, n: Int): IntRange {
        if (n <= 0 || plotRight <= plotLeft || x1 < plotLeft || x0 > plotRight) return IntRange.EMPTY
        val slotW = (plotRight - plotLeft) / n
        val first = floor((max(x0, plotLeft) - plotLeft) / slotW).toInt().coerceIn(0, n - 1)
        val last = (ceil((min(x1, plotRight) - plotLeft) / slotW).toInt() - 1).coerceIn(first, n - 1)
        return first..last
    }

    /**
     * Top edges where a label pill [tagH] px tall that belongs to a horizontal line at [lineY] can sit clear
     * of the bars beneath it: [highestBarTop] is the smallest y (tallest bar) among the bars the pill spans
     * (the plot bottom when there are none). In order of preference: above the line ([gap] px), or above
     * the tallest bar when that bar reaches past the line; then just below the line. Never above
     * [plotTop]. Empty when no spot is clear.
     */
    fun tagTopsClearOfBars(lineY: Float, tagH: Float, gap: Float, highestBarTop: Float, plotTop: Float): FloatArray {
        val clearBottom = highestBarTop - gap
        val above = min(lineY - gap, clearBottom) - tagH
        val below = lineY + gap
        val okAbove = above >= plotTop
        val okBelow = below >= plotTop && below + tagH <= clearBottom
        return when {
            okAbove && okBelow -> floatArrayOf(above, below)
            okAbove -> floatArrayOf(above)
            okBelow -> floatArrayOf(below)
            else -> FloatArray(0)
        }
    }

    // ---- Smoothing ----

    /**
     * Exponential moving average on irregular times: each new reading pulls the trend towards it by
     * `1 − 2^(−Δt / halfLife)`, so a reading after a long break counts for more than one taken an hour
     * after the last. Times must be ascending; readings at the same second are averaged into the trend
     * equally. Returns one trend value per reading.
     */
    fun emaTrend(tSec: LongArray, values: IntArray, halfLifeSec: Double): DoubleArray {
        val n = min(tSec.size, values.size)
        val out = DoubleArray(n)
        if (n == 0) return out
        var trend = values[0].toDouble()
        var sameRun = 1
        out[0] = trend
        for (i in 1 until n) {
            val dt = (tSec[i] - tSec[i - 1]).toDouble()
            val v = values[i].toDouble()
            if (dt <= 0.0) {
                // Same instant: running mean of the readings at this instant.
                sameRun++
                trend += (v - trend) / sameRun
            } else {
                sameRun = 1
                val alpha = if (halfLifeSec <= 0.0) 1.0 else 1.0 - 2.0.pow(-dt / halfLifeSec)
                trend += alpha * (v - trend)
            }
            out[i] = trend
        }
        return out
    }

    /** Half-life in seconds for [days]. */
    fun halfLifeSec(days: Double): Double = days * 86_400.0

    /** Mean of the values that are not missing (negative), or null when all are missing. */
    fun averageOfPresent(values: IntArray): Double? {
        var sum = 0.0
        var n = 0
        for (v in values) if (v >= 0) {
            sum += v
            n++
        }
        return if (n == 0) null else sum / n
    }

    /**
     * Display-unit min and max of a bar layer (zero always included, so bars grow from a visible base),
     * or null when no bar has data.
     */
    fun barDomain(l: BarLayer): Pair<Double, Double>? {
        var lo = 0.0
        var hi = 0.0
        var any = false
        for (i in l.values.indices) if (l.hasValue(i)) {
            val v = l.values[i].toDouble() / l.scale
            lo = min(lo, v)
            hi = max(hi, v)
            any = true
        }
        return if (any) lo to hi else null
    }

    /** Display-unit min and max over the readings, the trend and the goal of [l], or null with no readings. */
    fun dotTrendDomain(l: DotTrendLayer): Pair<Double, Double>? {
        val n = l.size
        if (n == 0) return null
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (i in 0 until n) {
            lo = min(lo, l.values[i].toDouble())
            hi = max(hi, l.values[i].toDouble())
        }
        for (i in 0 until min(n, l.trend.size)) {
            lo = min(lo, l.trend[i].toDouble())
            hi = max(hi, l.trend[i].toDouble())
        }
        l.goal?.let {
            lo = min(lo, it.toDouble())
            hi = max(hi, it.toDouble())
        }
        return lo / l.scale to hi / l.scale
    }

    /**
     * Stretches the top of [lo]..[hi] so that the data fills only the bottom [fraction] of the plot
     * (clamped to 0.1..1): returns the new top value.
     */
    fun stretchTop(lo: Double, hi: Double, fraction: Float): Double {
        val f = fraction.coerceIn(MIN_FRACTION, 1f).toDouble()
        return lo + (hi - lo) / f
    }

    private const val MIN_FRACTION = 0.1f
}

/** Fraction digits needed to print multiples of [step] exactly (capped at 4). */
internal fun decimalsFor(step: Double): Int {
    if (step <= 0.0 || !step.isFinite()) return 0
    var d = 0
    var s = step
    while (d < 4 && abs(s - kotlin.math.round(s)) > 1e-6 * max(1.0, abs(s))) {
        s *= 10.0
        d++
    }
    return d
}
