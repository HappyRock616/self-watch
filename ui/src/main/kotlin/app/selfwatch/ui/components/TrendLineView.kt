/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import app.selfwatch.ui.charts.ChartPalette

/**
 * Trend line (card design standard, block "trend line"): a filled line over [values] with a dot on the
 * latest, optional labels under both ends ([startLabel], [endLabel]), an optional shaded [band] (your usual
 * range) and an optional dashed [goal] line. Replaces the unlabeled sparkline on cards.
 */
class TrendLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2.5f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val goalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.2f)
        pathEffect = DashPathEffect(floatArrayOf(context.dp(4f), context.dp(4f)), 0f)
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.sp(11f) }
    private val line = Path()
    private val fill = Path()
    private val chartH = context.dp(40f)

    /** The values, oldest first. */
    var values: FloatArray = FloatArray(0)
        set(v) {
            field = v
            invalidate()
        }

    /** Line color; defaults to the theme primary. */
    @ColorInt
    var color: Int = ChartPalette.of(context).primary
        set(v) {
            field = v
            invalidate()
        }

    /** Label under the first point ("Sep"); null for none. */
    var startLabel: String? = null
        set(v) {
            field = v
            requestLayout()
            invalidate()
        }

    /** Label under the last point ("Today"); null for none. */
    var endLabel: String? = null
        set(v) {
            field = v
            requestLayout()
            invalidate()
        }

    /** Shaded range (low, high), for example your usual; null for none. */
    var band: Pair<Float, Float>? = null
        set(v) {
            field = v
            invalidate()
        }

    /** Dashed goal line; null for none. */
    var goal: Float? = null
        set(v) {
            field = v
            invalidate()
        }

    init {
        labelPaint.color = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        goalPaint.color = themeColor(com.google.android.material.R.attr.colorOnSurface)
        dotRing.color = themeColor(com.google.android.material.R.attr.colorSurface)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val labelH = if (startLabel == null && endLabel == null) 0f else labelPaint.descent() - labelPaint.ascent() + context.dp(3f)
        setMeasuredDimension(
            resolveSize(context.dp(120f).toInt(), widthMeasureSpec),
            resolveSize((chartH + labelH + context.dp(8f)).toInt() + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val n = values.size
        val pad = context.dp(5f)
        val left = paddingLeft + pad
        val right = width - paddingRight - pad
        val top = paddingTop + pad
        val bottom = top + chartH
        if (n >= 2) {
            var lo = values.min()
            var hi = values.max()
            band?.let { lo = minOf(lo, it.first); hi = maxOf(hi, it.second) }
            goal?.let { lo = minOf(lo, it); hi = maxOf(hi, it) }
            if (hi - lo < 1e-6f) { lo -= 1f; hi += 1f }
            fun y(v: Float) = bottom - (bottom - top) * (v - lo) / (hi - lo)
            fun x(i: Int) = left + (right - left) * i / (n - 1)
            band?.let { (a, b) ->
                bandPaint.color = color
                bandPaint.alpha = BAND_ALPHA
                canvas.drawRect(left, y(b), right, y(a), bandPaint)
            }
            line.reset()
            fill.reset()
            for (i in 0 until n) {
                if (i == 0) line.moveTo(x(i), y(values[i])) else line.lineTo(x(i), y(values[i]))
            }
            fill.addPath(line)
            fill.lineTo(x(n - 1), bottom)
            fill.lineTo(x(0), bottom)
            fill.close()
            fillPaint.color = color
            fillPaint.alpha = FILL_ALPHA
            canvas.drawPath(fill, fillPaint)
            linePaint.color = color
            canvas.drawPath(line, linePaint)
            goal?.let { g -> canvas.drawLine(left, y(g), right, y(g), goalPaint) }
            dotPaint.color = color
            dotRing.strokeWidth = context.dp(2f)
            val ex = x(n - 1)
            val ey = y(values[n - 1])
            canvas.drawCircle(ex, ey, context.dp(4.5f), dotPaint)
            canvas.drawCircle(ex, ey, context.dp(4.5f), dotRing)
        }
        val base = bottom + context.dp(6f) - labelPaint.ascent()
        startLabel?.let {
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(it, paddingLeft.toFloat(), base, labelPaint)
        }
        endLabel?.let {
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(it, (width - paddingRight).toFloat(), base, labelPaint)
        }
    }

    private companion object {
        const val FILL_ALPHA = 32
        const val BAND_ALPHA = 36
    }
}
