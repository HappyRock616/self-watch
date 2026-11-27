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
import app.selfwatch.ui.charts.ChartPalette
import kotlin.math.min

/**
 * A row of day dots (card design standard: active days, strength sessions, streaks): filled dots for
 * the [filled] days, empty rings for the rest, an optional label under each ("M", "T").
 */
class DotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2f)
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = context.sp(11f)
    }

    /** One entry per dot: true when filled. */
    var filled: BooleanArray = BooleanArray(0)
        set(v) {
            field = v
            requestLayout()
            invalidate()
        }

    /** Labels under the dots; empty for none. */
    var labels: List<String> = emptyList()
        set(v) {
            field = v
            requestLayout()
            invalidate()
        }

    /** Color of a filled dot. */
    @ColorInt
    var color: Int = ChartPalette.of(context).primary
        set(v) {
            field = v
            invalidate()
        }

    init {
        ringPaint.color = ChartPalette.of(context).track
        labelPaint.color = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val labelH = if (labels.isEmpty()) 0f else labelPaint.descent() - labelPaint.ascent() + context.dp(4f)
        setMeasuredDimension(
            resolveSize(context.dp(160f).toInt(), widthMeasureSpec),
            resolveSize((context.dp(22f) + labelH).toInt() + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val n = filled.size
        if (n == 0) return
        val slot = (width - paddingLeft - paddingRight) / n.toFloat()
        val r = min(slot * 0.32f, context.dp(10f))
        val cy = paddingTop + context.dp(11f)
        fillPaint.color = color
        for (i in 0 until n) {
            val cx = paddingLeft + slot * (i + 0.5f)
            if (filled[i]) canvas.drawCircle(cx, cy, r, fillPaint) else canvas.drawCircle(cx, cy, r - context.dp(1f), ringPaint)
            labels.getOrNull(i)?.let { canvas.drawText(it, cx, cy + context.dp(11f) + context.dp(4f) - labelPaint.ascent(), labelPaint) }
        }
    }
}
