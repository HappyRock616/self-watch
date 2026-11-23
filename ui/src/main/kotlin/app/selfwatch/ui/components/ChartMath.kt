/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.components

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// Pure geometry and scale maths for the custom views, kept free of Android types so it is unit tested
// on the JVM.

/** An axis-aligned rectangle in pixels. */
internal data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Shrinks text to fit: the scale (at most 1, at least [minScale]) that makes [textWidth] fit [available]. */
internal fun fitTextScale(textWidth: Float, available: Float, minScale: Float = 0.4f): Float {
    if (textWidth <= 0f || available <= 0f) return 1f
    return min(1f, max(minScale, available / textWidth))
}

internal object RingMath {
    /** Sweep angle in degrees for a progress value; clamped to 0..360, NaN counts as 0. */
    fun sweepDegrees(progress: Float): Float {
        if (progress.isNaN()) return 0f
        return progress.coerceIn(0f, 1f) * 360f
    }

    /**
     * The square the ring's stroke centre runs on: the largest square that fits inside the content area
     * (view size minus padding), centred, and inset by half the stroke so the stroke isn't clipped.
     */
    fun arcBox(
        width: Float,
        height: Float,
        stroke: Float,
        padStart: Float = 0f,
        padTop: Float = 0f,
        padEnd: Float = 0f,
        padBottom: Float = 0f,
    ): Box {
        val cw = max(0f, width - padStart - padEnd)
        val ch = max(0f, height - padTop - padBottom)
        val size = min(cw, ch)
        val left = padStart + (cw - size) / 2f + stroke / 2f
        val top = padTop + (ch - size) / 2f + stroke / 2f
        val right = left + max(0f, size - stroke)
        val bottom = top + max(0f, size - stroke)
        return Box(left, top, right, bottom)
    }

    /** Width available for centre text inside the ring: 80% of the inner diameter. */
    fun innerTextWidth(box: Box, stroke: Float): Float = max(0f, (box.width - stroke) * 0.8f)
}

internal object BarsMath {
    /** One bar's horizontal extent in pixels. */
    data class Slot(val left: Float, val right: Float)

    /** The smallest "nice" number (1, 2, 2.5 or 5 times a power of ten) that is at least [value]. */
    fun niceCeil(value: Float): Float {
        if (value.isNaN() || value <= 0f) return 1f
        val exp = floor(log10(value.toDouble()))
        val base = 10.0.pow(exp)
        val f = value / base
        val nice = when {
            f <= 1.0 -> 1.0
            f <= 2.0 -> 2.0
            f <= 2.5 -> 2.5
            f <= 5.0 -> 5.0
            else -> 10.0
        }
        return (nice * base).toFloat()
    }

    /** Scale top for the chart: a nice number covering every value and the goal line. */
    fun scaleMax(series: List<IntArray>, goal: Float?): Float {
        var m = goal ?: 0f
        for (values in series) for (v in values) m = max(m, v.toFloat())
        return niceCeil(m)
    }

    /** Y pixel for [value] on a 0..[max] scale drawn between [top] and [bottom]; clamped to the area. */
    fun valueToY(value: Float, max: Float, top: Float, bottom: Float): Float {
        if (max <= 0f) return bottom
        val frac = (value / max).coerceIn(0f, 1f)
        return bottom - frac * (bottom - top)
    }

    /**
     * Horizontal slot of bar [series] in group [group]. Groups split [width] evenly; [groupGap] (0..1) of
     * each group is empty space shared on both sides; bars in a group are [barGap] px apart. With [rtl]
     * the first group is on the right.
     */
    fun slot(
        group: Int,
        series: Int,
        groupCount: Int,
        seriesCount: Int,
        width: Float,
        groupGap: Float = 0.3f,
        barGap: Float = 0f,
        rtl: Boolean = false,
    ): Slot {
        if (groupCount <= 0 || seriesCount <= 0 || width <= 0f) return Slot(0f, 0f)
        val groupWidth = width / groupCount
        val inner = groupWidth * (1f - groupGap.coerceIn(0f, 0.9f))
        val barWidth = max(0f, (inner - (seriesCount - 1) * barGap) / seriesCount)
        val left = group * groupWidth + (groupWidth - inner) / 2f + series * (barWidth + barGap)
        val right = left + barWidth
        return if (rtl) Slot(width - right, width - left) else Slot(left, right)
    }
}

internal object SparkMath {
    /**
     * Polyline points (x0, y0, x1, y1, ...) for [values] spread across [width] and scaled to [height],
     * inset by [pad] on every side. A flat series sits in the middle; one value is a single centred point.
     */
    fun points(values: IntArray, width: Float, height: Float, pad: Float = 0f): FloatArray {
        val n = values.size
        if (n == 0) return FloatArray(0)
        val w = max(0f, width - 2 * pad)
        val h = max(0f, height - 2 * pad)
        var lo = values[0]
        var hi = values[0]
        for (v in values) {
            lo = min(lo, v)
            hi = max(hi, v)
        }
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val x = if (n == 1) pad + w / 2f else pad + w * i / (n - 1)
            val y = if (hi == lo) pad + h / 2f else pad + h * (1f - (values[i] - lo).toFloat() / (hi - lo))
            out[i * 2] = x
            out[i * 2 + 1] = y
        }
        return out
    }
}

internal object StepperMath {
    /**
     * Steps [valueX100] by one [stepX100] in [direction] (+1 or -1), snapping to the step grid (12.5 with a
     * step of 1 goes to 13 or 12), then clamps to [minX100]..[maxX100].
     */
    fun step(valueX100: Long, stepX100: Long, direction: Int, minX100: Long, maxX100: Long): Long {
        if (stepX100 <= 0L) return valueX100.coerceIn(minX100, maxX100)
        val next = if (direction >= 0) {
            Math.floorDiv(valueX100, stepX100) * stepX100 + stepX100
        } else {
            -Math.floorDiv(-valueX100, stepX100) * stepX100 - stepX100
        }
        return next.coerceIn(minX100, maxX100)
    }
}
