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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** One band of a gauge or range bar: values up to [upTo] are drawn in [color]. */
data class Band(val upTo: Float, @param:ColorInt @get:ColorInt val color: Int)

/**
 * Arc gauge (card design standard, block "arc gauge"): a 240° arc split into colored [bands] over
 * [min]..[max], a marker at [value], the number in the middle ([centerText]) and a short tag under it
 * ([subText], e.g. "Low"). For 0–100 scores: stress, energy level, sleep score, readiness.
 */
class ArcGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val centerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        fontFeatureSettings = "tnum"
    }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val oval = RectF()

    /** The colored bands, in order. */
    var bands: List<Band> = emptyList()
        set(v) {
            field = v
            invalidate()
        }

    /** Lower end of the scale. */
    var min: Float = 0f
        set(v) {
            field = v
            invalidate()
        }

    /** Upper end of the scale. */
    var max: Float = 100f
        set(v) {
            field = v
            invalidate()
        }

    /** Where the marker sits; null hides it. */
    var value: Float? = null
        set(v) {
            field = v
            invalidate()
        }

    /** The big number in the middle. */
    var centerText: CharSequence? = null
        set(v) {
            field = v
            updateDescription()
            invalidate()
        }

    /** The short tag under the number ("Low"). */
    var subText: CharSequence? = null
        set(v) {
            field = v
            updateDescription()
            invalidate()
        }

    /** Arc thickness in pixels. */
    var arcWidth: Float = context.dp(12f)
        set(v) {
            field = v
            invalidate()
        }

    init {
        centerPaint.color = themeColor(com.google.android.material.R.attr.colorOnSurface)
        subPaint.color = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        markerPaint.color = themeColor(com.google.android.material.R.attr.colorOnSurface)
        markerRing.color = themeColor(com.google.android.material.R.attr.colorSurface)
    }

    private fun updateDescription() {
        contentDescription = listOfNotNull(centerText, subText).joinToString(" ")
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(context.dp(160f).toInt(), widthMeasureSpec)
        // The arc spans 240°: its height is about 0.8 of its width.
        val h = resolveSize((min(w.toFloat(), context.dp(260f)) * HEIGHT_RATIO).toInt() + paddingTop + paddingBottom, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val availW = (width - paddingLeft - paddingRight).toFloat()
        val availH = (height - paddingTop - paddingBottom).toFloat()
        val r = min(availW / 2f, availH / HEIGHT_RATIO / 2f) - arcWidth
        if (r <= 0f) return
        val cx = paddingLeft + availW / 2f
        val cy = paddingTop + r + arcWidth
        oval.set(cx - r, cy - r, cx + r, cy + r)
        arcPaint.strokeWidth = arcWidth
        val span = max - min
        if (span <= 0f) return
        var prev = min
        val gap = GAP_DEG
        for (b in bands) {
            val from = START_DEG + SWEEP_DEG * ((prev - min) / span)
            val to = START_DEG + SWEEP_DEG * ((b.upTo.coerceAtMost(max) - min) / span)
            if (to - from > gap) {
                arcPaint.color = b.color
                canvas.drawArc(oval, from + gap / 2, to - from - gap, false, arcPaint)
            }
            prev = b.upTo
        }
        value?.let { v ->
            val a = Math.toRadians((START_DEG + SWEEP_DEG * ((v.coerceIn(min, max) - min) / span)).toDouble())
            val mx = cx + r * cos(a).toFloat()
            val my = cy + r * sin(a).toFloat()
            markerRing.strokeWidth = context.dp(3f)
            canvas.drawCircle(mx, my, arcWidth * MARKER, markerPaint)
            canvas.drawCircle(mx, my, arcWidth * MARKER, markerRing)
        }
        centerText?.toString()?.let { t ->
            centerPaint.textSize = r * CENTER_SIZE
            centerPaint.textSize *= fitTextScale(centerPaint.measureText(t), r * 1.5f)
            canvas.drawText(t, cx, cy + centerPaint.textSize * 0.35f, centerPaint)
        }
        subText?.toString()?.let { t ->
            subPaint.textSize = r * SUB_SIZE
            subPaint.textSize *= fitTextScale(subPaint.measureText(t), r * 1.6f)
            canvas.drawText(t, cx, cy + r * SUB_Y, subPaint)
        }
    }

    private companion object {
        const val START_DEG = 150f
        const val SWEEP_DEG = 240f
        const val GAP_DEG = 2.5f
        const val HEIGHT_RATIO = 0.82f
        const val MARKER = 0.75f
        const val CENTER_SIZE = 0.62f
        const val SUB_SIZE = 0.24f
        const val SUB_Y = 0.55f
    }
}
