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
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt

/**
 * Day strip (card design standard, block "day strip"): one column per slot across a day (for example 96
 * slots of 15 minutes), its height the value over [max] and its color from [colorFor]. Slots marked in
 * [dim] (asleep) are drawn faded; a slot with a negative value is left empty. Thin guides and [marks]
 * (fraction of the day, label: 0.25 to "6") under the strip.
 */
class DayStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = context.dp(0.75f) }
    private val markPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = context.sp(11f)
    }
    private val stripH = context.dp(44f)

    /** One value per slot; negative for no reading. */
    var values: IntArray = IntArray(0)
        set(v) {
            field = v
            invalidate()
        }

    /** Slots to fade (asleep), same length as [values] or empty. */
    var dim: BooleanArray = BooleanArray(0)
        set(v) {
            field = v
            invalidate()
        }

    /** The value at full height. */
    var max: Int = 100
        set(v) {
            field = v
            invalidate()
        }

    /** The color of a slot's value. */
    var colorFor: (Int) -> Int = { app.selfwatch.ui.charts.ChartPalette.of(context).primary }
        set(v) {
            field = v
            invalidate()
        }

    /** Guides with labels under the strip, at fractions of the day. */
    var marks: List<Pair<Float, String>> = listOf(0.25f to "6", 0.5f to "12", 0.75f to "18")
        set(v) {
            field = v
            requestLayout()
            invalidate()
        }

    /** Minimum column height so a low value still shows its color. */
    var minBar: Float = context.dp(3f)

    @ColorInt
    private val muted: Int = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)

    init {
        markPaint.color = muted
        guidePaint.color = muted
        guidePaint.alpha = GUIDE_ALPHA
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val markH = if (marks.isEmpty()) 0f else markPaint.descent() - markPaint.ascent() + context.dp(3f)
        setMeasuredDimension(
            resolveSize(context.dp(160f).toInt(), widthMeasureSpec),
            resolveSize((stripH + markH).toInt() + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val w = (width - paddingLeft - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = top + stripH
        val n = values.size
        if (n > 0 && max > 0) {
            val bw = w / n
            val gap = if (bw > context.dp(3f)) context.dp(0.75f) else 0f
            for (i in 0 until n) {
                val v = values[i]
                if (v < 0) continue
                val h = (stripH * v.coerceAtMost(max) / max).coerceAtLeast(minBar)
                barPaint.color = colorFor(v)
                barPaint.alpha = if (dim.getOrNull(i) == true) DIM_ALPHA else FULL_ALPHA
                canvas.drawRect(left + i * bw, bottom - h, left + (i + 1) * bw - gap, bottom, barPaint)
            }
        }
        val base = bottom + context.dp(3f) - markPaint.ascent()
        for ((f, label) in marks) {
            val x = left + w * f
            canvas.drawLine(x, top, x, bottom, guidePaint)
            canvas.drawText(label, x, base, markPaint)
        }
    }

    private companion object {
        const val DIM_ALPHA = 110
        const val FULL_ALPHA = 255
        const val GUIDE_ALPHA = 90
    }
}
