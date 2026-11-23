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
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import app.selfwatch.model.time.TimeRange
import app.selfwatch.ui.R
import app.selfwatch.ui.components.SwContrast
import app.selfwatch.ui.components.dp
import app.selfwatch.ui.components.sp
import app.selfwatch.ui.components.themeColor
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import java.time.ZoneId
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Body weight over time: a dot per weigh-in, a smoothed trend line and an optional dashed goal line.
 *
 * The trend is an exponential moving average with a [halfLifeDays] half-life (10 days by default) that
 * respects the real gaps between weigh-ins (see [ChartMath.emaTrend]), so daily noise from water and food
 * fades while real change shows. Tap near a dot to see its date, weight and trend ([onSelect] fires with
 * its index, -1 when cleared). Weights are stored in hundredths of a kilogram and shown in [units].
 * Time runs left to right in every locale.
 */
class WeightTrendView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Display units for the axis and labels. */
    var units: Units = Units.forLocale(Locale.getDefault())
        set(v) {
            field = v
            changed()
        }

    /** Goal weight in hundredths of a kilogram; null for none. */
    var goalKgX100: Int? = null
        set(v) {
            field = v
            changed()
        }

    /** Trend half-life in days. */
    var halfLifeDays: Double = 10.0
        set(v) {
            field = v
            recomputeTrend()
            changed()
        }

    /** Time span to show; null fits the entries. */
    var range: TimeRange? = null
        set(v) {
            field = v
            changed()
        }

    /** Time zone for date labels. */
    var zone: ZoneId = ZoneId.systemDefault()
        set(v) {
            field = v
            format = ChartFormat(context, v)
            changed()
        }

    /** Colour of the trend line; defaults to the theme primary (ChartPalette series 1). Dots use a muted version of the text colour. */
    @ColorInt
    var trendColor: Int = ChartPalette.of(context).primary
        set(v) {
            field = v
            invalidate()
        }

    /** Called with the index of the tapped weigh-in, or -1 when the selection is cleared. */
    var onSelect: ((index: Int) -> Unit)? = null

    /** Index of the highlighted weigh-in, or -1. */
    var selectedIndex: Int = -1
        set(v) {
            field = if (v in times.indices) v else -1
            invalidate()
        }

    /** Weigh-in times (epoch seconds, ascending) as last set. */
    val entryTimes: LongArray get() = times.copyOf()

    /** The smoothed trend at each weigh-in, in hundredths of a kilogram. */
    val trendKgX100: DoubleArray get() = trend.copyOf()

    /** Sets the weigh-ins: times in epoch seconds and weights in hundredths of a kilogram (sorted here). */
    fun setEntries(tSec: LongArray, kgX100: IntArray) {
        val n = min(tSec.size, kgX100.size)
        val order = (0 until n).sortedBy { tSec[it] }
        times = LongArray(n) { tSec[order[it]] }
        weights = IntArray(n) { kgX100[order[it]] }
        selectedIndex = -1
        recomputeTrend()
        changed()
    }

    private var times = LongArray(0)
    private var weights = IntArray(0)
    private var trend = DoubleArray(0)
    private var format = ChartFormat(context, zone)
    private var dirty = true

    private val plot = RectF()
    private var win = Window(0, 0)
    private var ticks: Ticks? = null
    private var tickLabels: Array<String> = emptyArray()
    private var timeX = FloatArray(0)
    private var timeLabels: Array<String> = emptyArray()
    private val trendPath = Path()
    private var dotXY = FloatArray(0)

    private val muted = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(ChartPalette.of(context).muted, 0x99)
    }
    private val trendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2.5f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val goalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(context.dp(5f), context.dp(4f)), 0f)
        color = ChartPalette.of(context).secondary
    }
    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, context.dp(0.75f))
        color = themeColor(com.google.android.material.R.attr.colorOutlineVariant)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(12f)
        color = muted
    }
    private val bubbleText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(13f)
        color = themeColor(com.google.android.material.R.attr.colorOnSurface)
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(com.google.android.material.R.attr.colorSurfaceVariant)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2f)
        color = themeColor(com.google.android.material.R.attr.colorOnSurface)
    }
    private val rect = RectF()

    init {
        isClickable = true
        changed()
    }

    private fun recomputeTrend() {
        trend = ChartMath.emaTrend(times, weights, ChartMath.halfLifeSec(halfLifeDays))
    }

    /** Hundredths of a kilogram to the display unit. */
    private fun display(kgX100: Double): Double = when (units) {
        Units.METRIC -> kgX100 / 100.0
        Units.IMPERIAL -> kgX100 / 100.0 / KG_PER_LB
    }

    private fun textHeight(): Float = textPaint.descent() - textPaint.ascent()

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

    private fun rebuild() {
        dirty = false
        trendPath.reset()
        val n = times.size
        if (n == 0 || width == 0) return
        val r = range
        win = if (r != null && r.durationSec > 0) {
            Window(r.startSec, r.endSec)
        } else {
            val pad = max(43_200L, (times[n - 1] - times[0]) / 40)
            Window(times[0] - pad, times[n - 1] + pad)
        }
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (i in 0 until n) {
            lo = min(lo, min(display(weights[i].toDouble()), display(trend[i])))
            hi = max(hi, max(display(weights[i].toDouble()), display(trend[i])))
        }
        goalKgX100?.let {
            lo = min(lo, display(it.toDouble()))
            hi = max(hi, display(it.toDouble()))
        }
        val top = paddingTop + textHeight() / 2f + context.dp(4f)
        val bottom = height - paddingBottom - textHeight() - context.dp(6f)
        val maxTicks = (max(0f, bottom - top) / (textHeight() * 1.8f)).toInt().coerceIn(2, 5)
        val t = ChartMath.niceTicks(lo, hi, maxTicks)
        ticks = t
        tickLabels = t.values().map { format.number(it, t.decimals) }.toTypedArray()
        val axisW = tickLabels.maxOf { textPaint.measureText(it) } + context.dp(8f)
        plot.set(paddingLeft + axisW, top, width - paddingRight - context.dp(4f), bottom)

        dotXY = FloatArray(n * 2)
        for (i in 0 until n) {
            val x = ChartMath.timeToX(times[i], win, plot.left, plot.right)
            dotXY[i * 2] = x
            dotXY[i * 2 + 1] = y(display(weights[i].toDouble()))
            val ty = y(display(trend[i]))
            if (i == 0) trendPath.moveTo(x, ty) else trendPath.lineTo(x, ty)
        }

        val sample = textPaint.measureText(format.date(win.start)) * 1.6f
        val maxT = (plot.width() / max(1f, sample)).toInt().coerceIn(2, 6)
        val offset = format.offsetSec(win.start)
        val tt = ChartMath.timeTicks(win.start, win.end, maxT, offset).filter { it >= win.start }
        timeX = FloatArray(tt.size) { ChartMath.timeToX(tt[it], win, plot.left, plot.right) }
        timeLabels = Array(tt.size) { format.date(tt[it]) }
    }

    private fun y(v: Double): Float {
        val t = ticks ?: return plot.bottom
        return ChartMath.valueToY(v, t.lo, t.hi, plot.top, plot.bottom)
    }

    override fun onDraw(canvas: Canvas) {
        if (dirty) rebuild()
        if (times.isEmpty()) {
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
        textPaint.textAlign = Paint.Align.CENTER
        val baseline = plot.bottom + context.dp(4f) - textPaint.ascent()
        for (i in timeX.indices) {
            val w = textPaint.measureText(timeLabels[i]) / 2f
            canvas.drawText(timeLabels[i], timeX[i].coerceIn(plot.left + w, width - paddingRight - w), baseline, textPaint)
        }

        goalKgX100?.let {
            val gy = y(display(it.toDouble()))
            canvas.drawLine(plot.left, gy, plot.right, gy, goalPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.color = SwContrast.ensure(goalPaint.color, themeColor(com.google.android.material.R.attr.colorSurface))
            val label = context.getString(R.string.sw_chart_goal, Fmt.weight(it, units))
            canvas.drawText(label, plot.right, gy - context.dp(4f), textPaint)
            textPaint.color = muted
        }
        textPaint.textAlign = Paint.Align.LEFT

        val r = context.dp(3f)
        for (i in times.indices) canvas.drawCircle(dotXY[i * 2], dotXY[i * 2 + 1], r, dotPaint)
        trendPaint.color = trendColor
        canvas.drawPath(trendPath, trendPaint)

        val sel = selectedIndex
        if (sel in times.indices) {
            canvas.drawCircle(dotXY[sel * 2], dotXY[sel * 2 + 1], context.dp(6f), ringPaint)
            val text = describeEntry(sel)
            val pad = context.dp(8f)
            val bw = bubbleText.measureText(text) + 2 * pad
            val bh = bubbleText.descent() - bubbleText.ascent() + 2 * pad
            val left = ChartMath.bubbleLeft(dotXY[sel * 2], bw, paddingLeft.toFloat(), (width - paddingRight).toFloat(), pad)
            rect.set(left, plot.top, left + bw, plot.top + bh)
            canvas.drawRoundRect(rect, pad, pad, bubblePaint)
            canvas.drawText(text, left + pad, plot.top + pad - bubbleText.ascent(), bubbleText)
        }
    }

    private fun describeEntry(i: Int): String = context.getString(
        R.string.sw_weight_entry,
        format.day(times[i]),
        Fmt.weight(weights[i], units),
        Fmt.weight(trend[i].roundToInt(), units),
    )

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val i = if (times.isEmpty() || e.x < plot.left - context.dp(12f) || e.x > plot.right + context.dp(12f)) {
                -1
            } else {
                ChartMath.nearestIndex(times, times.size, ChartMath.xToTime(e.x, win, plot.left, plot.right))
            }
            val next = if (i == selectedIndex) -1 else i
            selectedIndex = next
            onSelect?.invoke(next)
            // TalkBack reads the selected weigh-in as the view's state.
            ViewCompat.setStateDescription(this@WeightTrendView, if (next >= 0) describeEntry(next) else null)
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
        val n = times.size
        contentDescription = if (n == 0) {
            context.getString(R.string.sw_chart_no_data)
        } else {
            val goalText = goalKgX100?.let { context.getString(R.string.sw_chart_goal, Fmt.weight(it, units)) } ?: ""
            context.getString(
                R.string.sw_weight_description,
                format.day(times[0]),
                format.day(times[n - 1]),
                Fmt.weight(weights[n - 1], units),
                Fmt.weight(trend[n - 1].roundToInt(), units),
                goalText,
            )
        }
        invalidate()
    }

    private companion object {
        const val KG_PER_LB: Double = 0.45359237
    }
}
