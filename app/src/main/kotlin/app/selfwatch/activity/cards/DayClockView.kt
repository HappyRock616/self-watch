/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity.cards

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import androidx.annotation.ColorInt
import app.selfwatch.model.health.SleepStage
import app.selfwatch.ui.charts.ChartPalette
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The Day clock: a 24-hour circle, midnight at the top, with each 15 minutes colored by what you did
 * (sleep, rest, light movement, active), a mark at the current time, and the active hours in the
 * middle. All colors come from the theme ([ChartPalette]): sleep in the sleep color, active in the
 * primary, light movement in its tint, rest in the track gray. [compact] draws a smaller ring without
 * the hour labels (the small card).
 */
class DayClockView(context: Context, private val compact: Boolean = false) : View(context) {

    private val palette: ChartPalette = ChartPalette.of(context)
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = palette.track
    }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = palette.text
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = palette.muted
        textSize = sp(12f)
    }
    private val bigPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        fontFeatureSettings = "tnum"
        color = palette.text
    }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = palette.muted
    }
    private val oval = RectF()

    /** The day to draw (null: an empty track). */
    var clock: DayClock? = null
        set(v) {
            field = v
            invalidate()
        }

    /** Now, epoch seconds (the mark is drawn only when it falls inside the day). */
    var nowSec: Long = 0
        set(v) {
            field = v
            invalidate()
        }

    /** Big text in the middle (the active hours) and the line under it. */
    var centerText: String? = null
        set(v) {
            field = v
            invalidate()
        }

    /** The line under [centerText]. */
    var subText: String? = null
        set(v) {
            field = v
            invalidate()
        }

    /** The theme color of [part], or null when nothing is drawn for it. */
    @ColorInt
    fun colorOf(part: ClockPart): Int? = Companion.colorOf(palette, part)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wanted = dp(if (compact) COMPACT_DP else FULL_DP).toInt()
        val w = resolveSize(wanted, widthMeasureSpec)
        val side = min(w, wanted)
        setMeasuredDimension(w, resolveSize(side, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val ring = dp(if (compact) 8f else 14f)
        val labelRoom = if (compact) 0f else labelPaint.textSize * 1.6f
        val side = min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()
        if (side <= ring * 2) return
        val cx = paddingLeft + (width - paddingLeft - paddingRight) / 2f
        val cy = paddingTop + (height - paddingTop - paddingBottom) / 2f
        val r = side / 2f - ring / 2f - labelRoom
        if (r <= ring) return
        oval.set(cx - r, cy - r, cx + r, cy + r)
        trackPaint.strokeWidth = ring
        canvas.drawOval(oval, trackPaint)
        val c = clock
        if (c != null && c.parts.isNotEmpty()) {
            arcPaint.strokeWidth = ring
            val len = (c.range.endSec - c.range.startSec).toFloat()
            var i = 0
            while (i < c.parts.size) {
                // Draw runs of the same part as one arc (no seams between stretches).
                var j = i
                while (j + 1 < c.parts.size && c.parts[j + 1] == c.parts[i]) j++
                val color = colorOf(c.parts[i])
                if (color != null) {
                    val start = (i * DayClockMath.SEGMENT_SEC) / len * FULL_TURN
                    val end = min(((j + 1) * DayClockMath.SEGMENT_SEC) / len, 1f) * FULL_TURN
                    arcPaint.color = color
                    canvas.drawArc(oval, TOP + start, end - start, false, arcPaint)
                }
                i = j + 1
            }
            if (nowSec in c.range.startSec until c.range.endSec) {
                val a = Math.toRadians((TOP + (nowSec - c.range.startSec) / len * FULL_TURN).toDouble())
                nowPaint.strokeWidth = dp(if (compact) 2f else 3f)
                val inner = r - ring / 2f - dp(2f)
                val outer = r + ring / 2f + dp(2f)
                canvas.drawLine(
                    cx + (inner * cos(a)).toFloat(), cy + (inner * sin(a)).toFloat(),
                    cx + (outer * cos(a)).toFloat(), cy + (outer * sin(a)).toFloat(),
                    nowPaint,
                )
            }
        }
        if (!compact) {
            // 0, 6, 12 and 18 o'clock around the ring.
            val lr = r + ring / 2f + labelPaint.textSize * 0.9f
            for ((k, text) in HOUR_LABELS.withIndex()) {
                val a = Math.toRadians((TOP + k * QUARTER).toDouble())
                val x = cx + (lr * cos(a)).toFloat()
                val y = cy + (lr * sin(a)).toFloat() - (labelPaint.descent() + labelPaint.ascent()) / 2f
                canvas.drawText(text, x, y, labelPaint)
            }
        }
        // The middle: fit the texts inside the ring at any font size.
        val inner = (r - ring / 2f) * 2f * INNER_TEXT_SHARE
        val big = centerText
        if (!big.isNullOrEmpty() && inner > 0) {
            bigPaint.textSize = min(sp(if (compact) 18f else 28f), inner * 0.45f)
            while (bigPaint.measureText(big) > inner && bigPaint.textSize > dp(8f)) bigPaint.textSize *= 0.9f
            val sub = subText
            val bigY = if (sub.isNullOrEmpty() || compact) cy - (bigPaint.descent() + bigPaint.ascent()) / 2f else cy
            canvas.drawText(big, cx, bigY, bigPaint)
            if (!sub.isNullOrEmpty() && !compact) {
                subPaint.textSize = min(sp(12f), inner * 0.18f)
                while (subPaint.measureText(sub) > inner && subPaint.textSize > dp(7f)) subPaint.textSize *= 0.9f
                canvas.drawText(sub, cx, cy - subPaint.ascent() + dp(4f), subPaint)
            }
        }
    }

    private fun dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    /** Colors and layout constants. */
    companion object {
        private const val FULL_DP = 220f
        private const val COMPACT_DP = 72f
        private const val TOP = -90f
        private const val FULL_TURN = 360f
        private const val QUARTER = 90f
        private const val INNER_TEXT_SHARE = 0.8f
        private val HOUR_LABELS = listOf("0", "6", "12", "18")

        /** The color of [part] in [palette], or null for no data (the track shows through). */
        @ColorInt
        fun colorOf(palette: ChartPalette, part: ClockPart): Int? = when (part) {
            ClockPart.NONE -> null
            ClockPart.SLEEP -> palette.stage(SleepStage.DEEP)
            ClockPart.REST -> palette.muted
            ClockPart.LIGHT -> palette.series(2)
            ClockPart.ACTIVE -> palette.primary
        }
    }
}
