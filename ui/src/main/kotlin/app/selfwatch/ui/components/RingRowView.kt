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
import app.selfwatch.ui.charts.ChartPalette
import kotlin.math.min

/** One ring in a [RingRowView]: progress toward a goal, its color, the value inside and a label under it. */
data class RingItem(val progress: Float, @param:ColorInt @get:ColorInt val color: Int, val value: String, val label: String)

/**
 * A row of small goal rings (card design standard: Body today, Activity goals). Each ring shows its value
 * inside and a one-word label under it; up to six fit a phone-width card.
 */
class RingRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        fontFeatureSettings = "tnum"
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = context.sp(11f)
    }
    private val oval = RectF()

    /** The rings, left to right. */
    var items: List<RingItem> = emptyList()
        set(v) {
            field = v
            contentDescription = v.joinToString(", ") { "${it.label} ${it.value}" }
            // The ring size depends on how many there are: measure again.
            requestLayout()
            invalidate()
        }

    init {
        trackPaint.color = ChartPalette.of(context).track
        valuePaint.color = themeColor(com.google.android.material.R.attr.colorOnSurface)
        labelPaint.color = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(context.dp(300f).toInt(), widthMeasureSpec)
        val n = items.size.coerceAtLeast(1)
        val ring = min((w - paddingLeft - paddingRight) / n.toFloat() * RING_SHARE, context.dp(64f))
        val labelH = labelPaint.descent() - labelPaint.ascent()
        setMeasuredDimension(w, resolveSize((ring + labelH + context.dp(8f)).toInt() + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val n = items.size
        if (n == 0) return
        val slot = (width - paddingLeft - paddingRight) / n.toFloat()
        val d = min(slot * RING_SHARE, context.dp(64f))
        val stroke = d * STROKE_SHARE
        trackPaint.strokeWidth = stroke
        arcPaint.strokeWidth = stroke
        val r = (d - stroke) / 2f
        val cy = paddingTop + d / 2f
        items.forEachIndexed { i, it ->
            val cx = paddingLeft + slot * (i + 0.5f)
            oval.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(oval, 0f, 360f, false, trackPaint)
            arcPaint.color = it.color
            val sweep = 360f * it.progress.coerceIn(0f, 1f)
            if (sweep > 0f) canvas.drawArc(oval, -90f, sweep, false, arcPaint)
            valuePaint.textSize = r * VALUE_SIZE
            valuePaint.textSize *= fitTextScale(valuePaint.measureText(it.value), r * 1.6f)
            canvas.drawText(it.value, cx, cy + valuePaint.textSize * 0.35f, valuePaint)
            labelPaint.textSize = context.sp(11f)
            labelPaint.textSize *= fitTextScale(labelPaint.measureText(it.label), slot - context.dp(2f))
            canvas.drawText(it.label, cx, paddingTop + d + context.dp(4f) - labelPaint.ascent(), labelPaint)
        }
    }

    private companion object {
        const val RING_SHARE = 0.82f
        const val STROKE_SHARE = 0.13f
        const val VALUE_SIZE = 0.62f
    }
}
