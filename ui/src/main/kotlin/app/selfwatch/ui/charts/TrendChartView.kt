/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.charts

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.text.TextPaint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import app.selfwatch.ui.R
import app.selfwatch.ui.components.SwContrast
import app.selfwatch.ui.components.dp
import app.selfwatch.ui.components.sp
import app.selfwatch.ui.components.themeColor
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Bars per day, week or month (steps, distance, active minutes...) with an optional dashed goal line and
 * an average line. Axis labels under the bars are thinned so they never overlap. Tap a bar to select it:
 * it stays bright, the others dim, a bubble shows its value and [onSelect] fires; tap it again to clear.
 * Time runs left to right in every locale, like [OverlayChartView]. TalkBack reads each bar.
 *
 * Values are raw integers shown as `value / scale`; a negative value means "no data" (no bar, and it is
 * left out of the average).
 */
class TrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** One raw value per bar, oldest first; negative for no data. */
    var values: IntArray = IntArray(0)
        set(v) {
            field = v
            if (selectedIndex >= v.size) selectedIndex = -1
            changed()
        }

    /** Short axis label per bar ("3 Nov", "Sep"); thinned to fit. */
    var labels: List<String> = emptyList()
        set(v) {
            field = v
            changed()
        }

    /** Long label per bar for the bubble and TalkBack ("Week of 3 Nov"); falls back to [labels]. */
    var longLabels: List<String> = emptyList()
        set(v) {
            field = v
            changed()
        }

    /** Goal (raw units) drawn as a dashed line; null for none. */
    var goal: Int? = null
        set(v) {
            field = v
            changed()
        }

    /** Draws the average of the present values as a thin line. */
    var showAverage: Boolean = true
        set(v) {
            field = v
            changed()
        }

    /** Display divisor for the raw values (10 for tenths). */
    var scale: Int = 1
        set(v) {
            field = max(1, v)
            changed()
        }

    /** Unit shown after values in the bubble and to TalkBack ("km"); empty for none. */
    var unit: String = ""
        set(v) {
            field = v
            changed()
        }

    /** Bar colour; defaults to the theme primary (ChartPalette series 1). */
    @ColorInt
    var barColor: Int = ChartPalette.of(context).primary
        set(v) {
            field = v
            invalidate()
        }

    /** Index of the selected bar, or -1. Setting it does not fire [onSelect]. */
    var selectedIndex: Int = -1
        set(v) {
            field = if (v in values.indices) v else -1
            invalidate()
        }

    /** Called when the user taps a bar (index) or clears the selection (-1). */
    var onSelect: ((index: Int) -> Unit)? = null

    private val format = ChartFormat(context, ZoneId.systemDefault())
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, context.dp(0.75f))
        color = themeColor(com.google.android.material.R.attr.colorOutlineVariant)
    }
    private val goalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(context.dp(5f), context.dp(4f)), 0f)
        color = themeColor(com.google.android.material.R.attr.colorOnSurface)
    }
    private val avgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.5f)
        color = ChartPalette.of(context).secondary
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(12f)
        color = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
    }
    private val bubbleText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(13f)
        color = themeColor(com.google.android.material.R.attr.colorOnSurface)
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(com.google.android.material.R.attr.colorSurfaceVariant)
    }
    private val rect = RectF()
    private val goalTag = RectF()
    private val plot = RectF()
    private val goalPath = Path()
    private var ticks: Ticks? = null
    private var tickLabels: Array<String> = emptyArray()
    private var dirty = true

    init {
        isFocusable = true
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = context.dp(180f).toInt() + textHeight().toInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        dirty = true
    }

    private fun textHeight(): Float = textPaint.descent() - textPaint.ascent()

    private fun maxValue(): Double {
        var m = 0.0
        for (v in values) if (v >= 0) m = max(m, v.toDouble() / scale)
        goal?.let { m = max(m, it.toDouble() / scale) }
        return m
    }

    private fun rebuild() {
        dirty = false
        val top = paddingTop + textHeight() / 2f + context.dp(4f)
        val bottom = height - paddingBottom - textHeight() - context.dp(6f)
        val maxTicks = (max(0f, bottom - top) / (textHeight() * 1.8f)).toInt().coerceIn(2, 5)
        val t = ChartMath.niceTicks(0.0, max(maxValue(), 1.0 / scale), maxTicks)
        ticks = t
        tickLabels = t.values().map { format.number(it, t.decimals) }.toTypedArray()
        val axisW = tickLabels.maxOf { textPaint.measureText(it) } + context.dp(8f)
        val left = paddingLeft + axisW
        val right = (width - paddingRight).toFloat()
        // Leave just enough room at the end for the newest bar's label to sit centred under it.
        val lastLabel = labels.getOrNull(values.size - 1)
        val inset = if (lastLabel == null) 0f else ChartMath.endInsetForCentredLabel(right - left, values.size, textPaint.measureText(lastLabel))
        plot.set(left, top, right - inset, bottom)
        goalPath.reset()
        goal?.let {
            val y = y(it.toDouble() / scale)
            goalPath.moveTo(plot.left, y)
            goalPath.lineTo(plot.right, y)
        }
    }

    private fun y(v: Double): Float {
        val t = ticks ?: return plot.bottom
        return ChartMath.valueToY(v, t.lo, t.hi, plot.top, plot.bottom)
    }

    private fun slotLeft(i: Int): Float = plot.left + plot.width() * i / max(1, values.size)

    override fun onDraw(canvas: Canvas) {
        if (dirty) rebuild()
        val n = values.size
        if (n == 0 || values.none { it >= 0 }) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(context.getString(R.string.sw_chart_no_data), width / 2f, height / 2f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
            return
        }
        val t = ticks ?: return
        val half = (textPaint.ascent() + textPaint.descent()) / 2f
        textPaint.textAlign = Paint.Align.RIGHT
        t.values().forEachIndexed { i, v ->
            val gy = y(v)
            canvas.drawLine(plot.left, gy, plot.right, gy, gridPaint)
            canvas.drawText(tickLabels[i], plot.left - context.dp(4f), gy - half, textPaint)
        }

        val slotW = plot.width() / n
        val gap = min(slotW * 0.25f, context.dp(6f))
        val corner = min(context.dp(3f), (slotW - gap) / 2f)
        val base = y(0.0)
        for (i in 0 until n) {
            val v = values[i]
            if (v < 0) continue
            val dim = selectedIndex >= 0 && i != selectedIndex
            barPaint.color = if (dim) ColorUtils.setAlphaComponent(barColor, 0x66) else barColor
            val l = slotLeft(i) + gap / 2f
            val top = min(y(v.toDouble() / scale), base - 1f)
            // Rounded top, square bottom: draw past the baseline and clip, so translucent bars stay even.
            rect.set(l, top, l + slotW - gap, base + corner)
            canvas.withClip(l, top, l + slotW - gap, base) { drawRoundRect(rect, corner, corner, barPaint) }
        }

        // Goal and average, each with a pill label placed clear of the bars.
        goalTag.setEmpty()
        goal?.let {
            canvas.drawPath(goalPath, goalPaint)
            val text = context.getString(R.string.sw_chart_goal, format.number(it.toDouble() / scale, decimalsForScale(scale)))
            drawTag(canvas, text, y(it.toDouble() / scale), goalPaint.color, goalTag)
        }
        val avg = if (showAverage) ChartMath.averageOfPresent(values) else null
        if (avg != null) {
            val ay = y(avg / scale)
            canvas.drawLine(plot.left, ay, plot.right, ay, avgPaint)
            val text = context.getString(R.string.sw_chart_average, format.number(avg / scale, decimalsForScale(scale)))
            drawTag(canvas, text, ay, avgPaint.color, null)
        }
        textPaint.color = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)

        // Axis labels, thinned and centred under their bars; the newest bar's label is always kept.
        if (labels.isNotEmpty()) {
            val count = min(n, labels.size)
            val widths = FloatArray(count) { textPaint.measureText(labels[it]) }
            val baseline = plot.bottom + context.dp(4f) - textPaint.ascent()
            textPaint.textAlign = Paint.Align.LEFT
            val spots = ChartMath.axisLabelSpots(
                n, plot.left, plot.right, widths, paddingLeft.toFloat(), (width - paddingRight).toFloat(), context.dp(4f),
            )
            for (spot in spots) canvas.drawText(labels[spot.index], spot.left, baseline, textPaint)
        }
        textPaint.textAlign = Paint.Align.LEFT

        if (selectedIndex in 0 until n) drawBubble(canvas, selectedIndex, slotW)
    }

    /**
     * A label on a pill for the line at [lineY]: at the end or the start of the plot, above or below the
     * line, wherever it is clear of the bars and of [goalTag], preferring the spot closest to its line. With no clear spot it
     * sits on the line at the end, where the pill keeps it readable. The text colour is [color] adjusted
     * to 4.5:1 on the pill. [out], when given, receives the pill's bounds.
     */
    private fun drawTag(canvas: Canvas, text: String, lineY: Float, @ColorInt color: Int, out: RectF?) {
        val pad = context.dp(4f)
        val gap = context.dp(3f)
        val w = textPaint.measureText(text) + 2 * pad
        val h = textPaint.descent() - textPaint.ascent() + pad
        var bestLeft = plot.right - w
        var bestTop = Float.NaN
        for (left in floatArrayOf(plot.right - w, plot.left)) {
            for (top in ChartMath.tagTopsClearOfBars(lineY, h, gap, highestBarTop(left, left + w), plot.top)) {
                if (!goalTag.isEmpty && goalTag.intersects(left, top, left + w, top + h)) continue
                if (bestTop.isNaN() || abs(top + h / 2f - lineY) < abs(bestTop + h / 2f - lineY)) {
                    bestTop = top
                    bestLeft = left
                }
            }
        }
        if (bestTop.isNaN()) bestTop = (lineY - gap - h).coerceAtLeast(plot.top)
        rect.set(bestLeft, bestTop, bestLeft + w, bestTop + h)
        out?.set(rect)
        canvas.drawRoundRect(rect, pad, pad, bubblePaint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = SwContrast.ensure(color, bubblePaint.color)
        canvas.drawText(text, bestLeft + pad, bestTop + pad / 2f - textPaint.ascent(), textPaint)
    }

    /** Smallest y (tallest bar) among the bars under x0..x1, or the baseline when there are none. */
    private fun highestBarTop(x0: Float, x1: Float): Float {
        val base = y(0.0)
        var top = base
        for (i in ChartMath.slotsUnder(x0, x1, plot.left, plot.right, values.size)) {
            val v = values[i]
            if (v >= 0) top = min(top, min(y(v.toDouble() / scale), base - 1f))
        }
        return top
    }

    private fun drawBubble(canvas: Canvas, i: Int, slotW: Float) {
        val text = describeBar(i)
        val pad = context.dp(8f)
        val bw = bubbleText.measureText(text) + 2 * pad
        val bh = bubbleText.descent() - bubbleText.ascent() + 2 * pad
        val cx = slotLeft(i) + slotW / 2f
        val left = (cx - bw / 2f).coerceIn(paddingLeft.toFloat(), max(paddingLeft.toFloat(), width - paddingRight - bw))
        rect.set(left, plot.top, left + bw, plot.top + bh)
        canvas.drawRoundRect(rect, pad, pad, bubblePaint)
        canvas.drawText(text, left + pad, plot.top + pad - bubbleText.ascent(), bubbleText)
    }

    private fun describeBar(i: Int): String {
        val label = longLabels.getOrNull(i) ?: labels.getOrNull(i) ?: (i + 1).toString()
        val v = values.getOrNull(i) ?: -1
        val text = if (v < 0) {
            context.getString(R.string.sw_chart_no_value)
        } else {
            val n = format.number(v.toDouble() / scale, decimalsForScale(scale))
            if (unit.isEmpty()) n else context.getString(R.string.sw_chart_value_unit, n, unit)
        }
        return context.getString(R.string.sw_chart_bar, label, text)
    }

    private fun barAt(x: Float, y: Float): Int {
        if (values.isEmpty() || x < plot.left || x > plot.right || y < plot.top || y > plot.bottom + textHeight()) return -1
        return ChartMath.sliceAt(x, plot.left, plot.right, values.size)
    }

    private fun select(i: Int) {
        val next = if (i == selectedIndex) -1 else i
        selectedIndex = next
        onSelect?.invoke(next)
        a11y.invalidateRoot()
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            select(barAt(e.x, e.y))
            tapped = true
            return true
        }
    })

    private var tapped = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val handled = gestures.onTouchEvent(event)
        if (tapped) {
            tapped = false
            performClick()
        }
        return handled
    }

    override fun performClick(): Boolean = super.performClick()

    private fun changed() {
        dirty = true
        val n = values.size
        contentDescription = if (n == 0) {
            context.getString(R.string.sw_chart_no_data)
        } else {
            val first = longLabels.firstOrNull() ?: labels.firstOrNull() ?: ""
            val last = longLabels.getOrNull(n - 1) ?: labels.getOrNull(n - 1) ?: ""
            val parts = ArrayList<String>()
            ChartMath.averageOfPresent(values)?.let {
                parts += context.getString(R.string.sw_chart_average, format.number(it / scale, decimalsForScale(scale)))
            }
            goal?.let { parts += context.getString(R.string.sw_chart_goal, format.number(it.toDouble() / scale, decimalsForScale(scale))) }
            context.getString(R.string.sw_trend_description, first, last, parts.joinToString(", "))
        }
        a11y.invalidateRoot()
        requestLayout()
        invalidate()
    }

    private val a11y = object : ExploreByTouchHelper(this) {
        private val bounds = Rect()

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val i = barAt(x, y)
            return if (i < 0) INVALID_ID else i
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            for (i in values.indices) virtualViewIds += i
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            node.contentDescription = describeBar(virtualViewId)
            val slotW = plot.width() / max(1, values.size)
            val l = slotLeft(virtualViewId)
            bounds.set(l.toInt(), plot.top.toInt(), max(l.toInt() + 1, (l + slotW).toInt()), max(plot.top.toInt() + 1, plot.bottom.toInt()))
            @Suppress("DEPRECATION")
            node.setBoundsInParent(bounds)
            node.isSelected = virtualViewId == selectedIndex
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            select(virtualViewId)
            return true
        }
    }

    init {
        ViewCompat.setAccessibilityDelegate(this, a11y)
        changed()
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = a11y.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = a11y.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        a11y.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }
}
