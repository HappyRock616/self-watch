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
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.content.withStyledAttributes
import app.selfwatch.ui.R
import app.selfwatch.ui.format.Fmt
import kotlin.math.max
import kotlin.math.min

/** One series of bars: a value per group (for example kcal eaten per day), drawn in [color]. */
data class BarSeries(val label: String, @param:ColorInt @get:ColorInt val color: Int, val values: IntArray) {
    override fun equals(other: Any?): Boolean =
        other is BarSeries && other.label == label && other.color == color && other.values.contentEquals(values)

    override fun hashCode(): Int = (label.hashCode() * 31 + color) * 31 + values.contentHashCode()
}

/**
 * Small grouped bar chart, e.g. 7 days of calories in vs out: one group per label, one bar per series in
 * each group, an optional dashed goal line and labels under the groups. The first group is at the start
 * edge (right in right-to-left layouts). TalkBack reads every group's values.
 */
class BarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val goalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(context.dp(4f), context.dp(4f)), 0f)
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = context.sp(12f)
    }
    private val rect = RectF()
    private val corner = context.dp(3f)
    private var userDescription: CharSequence? = null
    private var ready: Boolean = false

    /** The series to draw, side by side in each group. */
    var series: List<BarSeries> = emptyList()
        set(v) {
            field = v
            update()
        }

    /** One label per group ("M", "T"...); fewer labels than groups leaves the rest blank. */
    var labels: List<String> = emptyList()
        set(v) {
            field = v
            update()
        }

    /** Goal value drawn as a dashed line; null for none. */
    var goalLine: Float? = null
        set(v) {
            field = v
            update()
        }

    /**
     * Per-bar colour (card design standard: met = primary, short = secondary): given the group and its
     * value, the colour for that bar, or null for the series colour.
     */
    var barColor: ((group: Int, value: Int) -> Int?)? = null
        set(v) {
            field = v
            invalidate()
        }

    /** Colour of the goal line; defaults to the muted text colour. */
    @ColorInt
    var goalColor: Int = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        set(v) {
            field = v
            invalidate()
        }

    init {
        userDescription = contentDescription
        context.withStyledAttributes(attrs, R.styleable.BarsView, defStyleAttr, 0) {
            goalColor = getColor(R.styleable.BarsView_swGoalColor, goalColor)
        }
        labelPaint.color = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        ready = true
        update()
    }

    /** Overrides the automatic TalkBack text. */
    override fun setContentDescription(contentDescription: CharSequence?) {
        userDescription = contentDescription
        // Called from View's constructor (XML attribute) before this class is initialised.
        if (!ready) {
            super.setContentDescription(contentDescription)
            return
        }
        super.setContentDescription(contentDescription ?: describe())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val labelH = if (labels.isEmpty()) 0f else labelPaint.descent() - labelPaint.ascent() + context.dp(4f)
        val desired = resources.getDimensionPixelSize(R.dimen.sw_bars_height) + labelH.toInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val groups = groupCount()
        if (groups == 0) return
        val labelH = if (labels.isEmpty()) 0f else labelPaint.descent() - labelPaint.ascent() + context.dp(4f)
        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val chartW = (width - paddingLeft - paddingRight).toFloat()
        val bottom = height - paddingBottom - labelH
        val scale = BarsMath.scaleMax(series.map { it.values }, goalLine)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val barGap = context.dp(2f)

        series.forEachIndexed { s, ser ->
            for (g in 0 until groups) {
                val v = ser.values.getOrNull(g) ?: continue
                barPaint.color = barColor?.invoke(g, v) ?: ser.color
                val slot = BarsMath.slot(g, s, groups, series.size, chartW, barGap = barGap, rtl = rtl)
                val y = BarsMath.valueToY(v.toFloat(), scale, top, bottom)
                rect.set(left + slot.left, y, left + slot.right, bottom)
                canvas.drawRoundRect(rect, corner, corner, barPaint)
                // Square off the bottom corners.
                if (bottom - y > corner) {
                    rect.top = bottom - corner
                    canvas.drawRect(rect, barPaint)
                }
            }
        }

        goalLine?.let { goal ->
            val y = BarsMath.valueToY(goal, scale, top, bottom)
            goalPaint.color = goalColor
            canvas.drawLine(left, y, left + chartW, y, goalPaint)
        }

        if (labels.isNotEmpty()) {
            val baseline = bottom + context.dp(4f) - labelPaint.ascent()
            for (g in 0 until min(groups, labels.size)) {
                val slot = BarsMath.slot(g, 0, groups, 1, chartW, groupGap = 0f, rtl = rtl)
                val avail = slot.right - slot.left
                val text = labels[g]
                val size = context.sp(12f)
                labelPaint.textSize = size
                labelPaint.textSize = size * fitTextScale(labelPaint.measureText(text), avail)
                canvas.drawText(text, left + (slot.left + slot.right) / 2f, baseline, labelPaint)
            }
            labelPaint.textSize = context.sp(12f)
        }
    }

    private fun groupCount(): Int = max(labels.size, series.maxOfOrNull { it.values.size } ?: 0)

    private fun update() {
        if (!ready) return
        if (userDescription == null) super.setContentDescription(describe())
        requestLayout()
        invalidate()
    }

    private fun describe(): CharSequence {
        val groups = groupCount()
        val parts = ArrayList<String>(groups + 1)
        for (g in 0 until groups) {
            val values = series.mapNotNull { s ->
                s.values.getOrNull(g)?.let { context.getString(R.string.sw_bars_series_value, s.label, Fmt.number(it.toLong())) }
            }.joinToString(", ")
            val label = labels.getOrNull(g) ?: (g + 1).toString()
            parts += context.getString(R.string.sw_bars_label_values, label, values)
        }
        goalLine?.let { parts += context.getString(R.string.sw_bars_goal, Fmt.number(it.toDouble())) }
        return parts.joinToString("; ")
    }
}
