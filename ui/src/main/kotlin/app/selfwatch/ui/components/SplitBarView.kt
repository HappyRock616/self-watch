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
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt

/** One part of a split bar: its size, color and an optional short label ("Deep 1:42"). */
data class SplitPart(val value: Float, @param:ColorInt @get:ColorInt val color: Int, val label: String? = null)

/**
 * Split bar (card design standard, block "split bar"): one bar divided into [parts] by size (sleep
 * stages, time at each stress level, resting vs active calories), each label under its part when it fits.
 * An optional [mark] (fraction 0..1) draws a reference tick (the 75–80 % easy mark).
 */
class SplitBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val partPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = context.dp(2f) }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = context.sp(11f)
        fontFeatureSettings = "tnum"
    }
    private val rect = RectF()
    private val barH = context.dp(16f)

    /** The parts, in order. */
    var parts: List<SplitPart> = emptyList()
        set(v) {
            field = v
            contentDescription = v.mapNotNull { it.label }.joinToString(", ")
            requestLayout()
            invalidate()
        }

    /** A reference tick across the bar at this fraction; null for none. */
    var mark: Float? = null
        set(v) {
            field = v
            invalidate()
        }

    init {
        labelPaint.color = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        markPaint.color = themeColor(com.google.android.material.R.attr.colorOnSurface)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val labelH = if (parts.none { it.label != null }) 0f else labelPaint.descent() - labelPaint.ascent() + context.dp(4f)
        setMeasuredDimension(
            resolveSize(context.dp(120f).toInt(), widthMeasureSpec),
            resolveSize((barH + labelH + context.dp(4f)).toInt() + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val total = parts.sumOf { it.value.coerceAtLeast(0f).toDouble() }.toFloat()
        if (total <= 0f) return
        val left = paddingLeft.toFloat()
        val w = (width - paddingLeft - paddingRight).toFloat()
        val top = paddingTop + context.dp(2f)
        val gap = context.dp(2f)
        val corner = context.dp(4f)
        val base = top + barH + context.dp(4f) - labelPaint.ascent()
        var x = left
        for (p in parts) {
            val pw = w * p.value.coerceAtLeast(0f) / total
            if (pw <= 0f) continue
            partPaint.color = p.color
            rect.set(x, top, x + (pw - gap).coerceAtLeast(1f), top + barH)
            canvas.drawRoundRect(rect, corner, corner, partPaint)
            p.label?.let { label ->
                if (labelPaint.measureText(label) <= pw - gap) canvas.drawText(label, x + pw / 2, base, labelPaint)
            }
            x += pw
        }
        mark?.let { f ->
            val mx = left + w * f.coerceIn(0f, 1f)
            canvas.drawLine(mx, top - context.dp(2f), mx, top + barH + context.dp(2f), markPaint)
        }
    }
}
