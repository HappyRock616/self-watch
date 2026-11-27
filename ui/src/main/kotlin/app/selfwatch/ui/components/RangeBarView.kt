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
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View

/**
 * Range bar (card design standard, block "range bar"): a horizontal bar split into colored [bands] over
 * [min]..[max], a triangle marker above [value] and optional [ticks] (value, label) under it. Shows where a
 * reading sits among named ranges: BMI, blood pressure, body fat, VO2 max rating, HRV baseline.
 */
class RangeBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = context.sp(11f)
        fontFeatureSettings = "tnum"
    }
    private val rect = RectF()
    private val path = Path()
    private val barH = context.dp(10f)
    private val markerH = context.dp(9f)

    /** The colored bands, in order. */
    var bands: List<Band> = emptyList()
        set(v) {
            field = v
            invalidate()
        }

    /** Lower end of the bar. */
    var min: Float = 0f
        set(v) {
            field = v
            invalidate()
        }

    /** Upper end of the bar. */
    var max: Float = 100f
        set(v) {
            field = v
            invalidate()
        }

    /** The reading; null hides the marker. */
    var value: Float? = null
        set(v) {
            field = v
            invalidate()
        }

    /** Labels under the bar at their values ("120", "130"). */
    var ticks: List<Pair<Float, String>> = emptyList()
        set(v) {
            field = v
            requestLayout()
            invalidate()
        }

    init {
        markerPaint.color = themeColor(com.google.android.material.R.attr.colorOnSurface)
        tickPaint.color = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val tickH = if (ticks.isEmpty()) 0f else tickPaint.descent() - tickPaint.ascent() + context.dp(4f)
        val h = (markerH + context.dp(2f) + barH + tickH).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(context.dp(120f).toInt(), widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    private fun x(v: Float, left: Float, w: Float): Float = left + w * ((v.coerceIn(min, max) - min) / (max - min))

    override fun onDraw(canvas: Canvas) {
        if (max <= min) return
        val left = paddingLeft.toFloat()
        val w = (width - paddingLeft - paddingRight).toFloat()
        val top = paddingTop + markerH + context.dp(2f)
        val gap = context.dp(2f)
        var prev = min
        for (b in bands) {
            val x0 = x(prev, left, w)
            val x1 = x(b.upTo, left, w)
            if (x1 - x0 > gap) {
                bandPaint.color = b.color
                rect.set(x0 + gap / 2, top, x1 - gap / 2, top + barH)
                canvas.drawRoundRect(rect, barH / 2, barH / 2, bandPaint)
            }
            prev = b.upTo
        }
        value?.let { v ->
            val vx = x(v, left, w)
            path.reset()
            path.moveTo(vx - markerH * 0.8f, paddingTop.toFloat())
            path.lineTo(vx + markerH * 0.8f, paddingTop.toFloat())
            path.lineTo(vx, paddingTop + markerH)
            path.close()
            canvas.drawPath(path, markerPaint)
        }
        if (ticks.isNotEmpty()) {
            val base = top + barH + context.dp(4f) - tickPaint.ascent()
            for ((v, label) in ticks) canvas.drawText(label, x(v, left, w), base, tickPaint)
        }
    }
}
