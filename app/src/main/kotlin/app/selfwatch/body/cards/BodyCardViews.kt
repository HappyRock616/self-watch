/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body.cards

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import app.selfwatch.model.health.SleepStage
import app.selfwatch.ui.charts.ChartPalette

private fun View.dp(v: Float): Float = v * resources.displayMetrics.density

private fun View.sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

/**
 * Sleep stage timeline (card design standard): one block per stage change across the night, awake on the
 * top row, then REM, light and deep at the bottom, each in its stage color, with the bedtime and wake time
 * under the ends.
 */
class SleepStagesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        color = ChartPalette.of(context).muted
    }
    private val rect = RectF()
    private val rowH = dp(13f)
    private val rowGap = dp(3f)

    /** Stage changes, oldest first: when each stage started. */
    var stages: List<Pair<Long, SleepStage>> = emptyList()
        set(v) {
            field = v
            invalidate()
        }

    /** When the night started and ended (seconds). */
    var startSec: Long = 0L
        set(v) {
            field = v
            invalidate()
        }

    /** End of the night (seconds). */
    var endSec: Long = 0L
        set(v) {
            field = v
            invalidate()
        }

    /** Label under the start ("11:02 PM"); null for none. */
    var startLabel: String? = null
        set(v) {
            field = v
            requestLayout()
            invalidate()
        }

    /** Label under the end ("6:41 AM"); null for none. */
    var endLabel: String? = null
        set(v) {
            field = v
            requestLayout()
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val labelH = if (startLabel == null && endLabel == null) 0f else labelPaint.descent() - labelPaint.ascent() + dp(4f)
        val h = ROWS * rowH + (ROWS - 1) * rowGap + labelH
        setMeasuredDimension(
            resolveSize(dp(160f).toInt(), widthMeasureSpec),
            resolveSize(h.toInt() + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val w = (width - paddingLeft - paddingRight).toFloat()
        val span = (endSec - startSec).toFloat()
        val palette = ChartPalette.of(context)
        if (span > 0 && stages.isNotEmpty()) {
            val corner = dp(2f)
            for (i in stages.indices) {
                val (t, stage) = stages[i]
                val row = row(stage) ?: continue
                val end = if (i + 1 < stages.size) stages[i + 1].first else endSec
                val x0 = left + w * ((t - startSec).coerceIn(0L, endSec - startSec) / span)
                val x1 = left + w * ((end - startSec).coerceIn(0L, endSec - startSec) / span)
                if (x1 <= x0) continue
                val top = paddingTop + row * (rowH + rowGap)
                rect.set(x0, top, maxOf(x1, x0 + dp(1f)), top + rowH)
                blockPaint.color = palette.stage(stage)
                canvas.drawRoundRect(rect, corner, corner, blockPaint)
            }
        }
        val base = paddingTop + ROWS * rowH + (ROWS - 1) * rowGap + dp(4f) - labelPaint.ascent()
        startLabel?.let {
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(it, left, base, labelPaint)
        }
        endLabel?.let {
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(it, left + w, base, labelPaint)
        }
    }

    private fun row(stage: SleepStage): Int? = when (stage) {
        SleepStage.AWAKE -> 0
        SleepStage.REM -> 1
        SleepStage.LIGHT -> 2
        SleepStage.DEEP -> 3
        SleepStage.UNMEASURABLE -> null
    }

    private companion object {
        const val ROWS = 4
    }
}

/**
 * Daily range bars (card design standard): one floating bar per day from the day's low to its high, the
 * latest day highlighted, with labels under both ends. Days without a value are left empty.
 */
class DayRangeBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        color = ChartPalette.of(context).muted
    }
    private val rect = RectF()

    /** Chart height in pixels (without the labels). */
    var chartHeight: Float = dp(64f)
        set(v) {
            field = v
            requestLayout()
        }

    /** Each day's low, oldest first (null: none). */
    var lows: List<Int?> = emptyList()
        set(v) {
            field = v
            invalidate()
        }

    /** Each day's high, same length as [lows]. */
    var highs: List<Int?> = emptyList()
        set(v) {
            field = v
            invalidate()
        }

    /** Label under the first day; null for none. */
    var startLabel: String? = null

    /** Label under the last day; null for none. */
    var endLabel: String? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val labelH = if (startLabel == null && endLabel == null) 0f else labelPaint.descent() - labelPaint.ascent() + dp(4f)
        setMeasuredDimension(
            resolveSize(dp(160f).toInt(), widthMeasureSpec),
            resolveSize((chartHeight + labelH).toInt() + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val n = minOf(lows.size, highs.size)
        val left = paddingLeft.toFloat()
        val w = (width - paddingLeft - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = top + chartHeight
        val values = (lows + highs).filterNotNull()
        val palette = ChartPalette.of(context)
        if (n > 0 && values.isNotEmpty()) {
            var lo = values.min().toFloat()
            var hi = values.max().toFloat()
            if (hi - lo < 1f) { lo -= 1f; hi += 1f }
            fun y(v: Int) = bottom - (bottom - top) * (v - lo) / (hi - lo)
            val slot = w / n
            val bw = (slot * BAR_SHARE).coerceAtLeast(dp(2f))
            val corner = bw / 2f
            val latest = (0 until n).lastOrNull { lows[it] != null && highs[it] != null }
            for (i in 0 until n) {
                val a = lows[i] ?: continue
                val b = highs[i] ?: continue
                val cx = left + slot * (i + 0.5f)
                rect.set(cx - bw / 2f, y(maxOf(a, b)), cx + bw / 2f, maxOf(y(minOf(a, b)), y(maxOf(a, b)) + dp(3f)))
                barPaint.color = if (i == latest) palette.primary else palette.series(2)
                barPaint.alpha = if (i == latest) OPAQUE else DIM
                canvas.drawRoundRect(rect, corner, corner, barPaint)
            }
        }
        val base = bottom + dp(4f) - labelPaint.ascent()
        startLabel?.let {
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(it, left, base, labelPaint)
        }
        endLabel?.let {
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(it, left + w, base, labelPaint)
        }
    }

    private companion object {
        const val BAR_SHARE = 0.6f
        const val OPAQUE = 255
        const val DIM = 170
    }
}
