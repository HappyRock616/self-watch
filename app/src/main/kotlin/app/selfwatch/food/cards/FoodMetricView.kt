/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.cards

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.IdRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import app.selfwatch.R
import app.selfwatch.cards.CardView
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.components.SwCard
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import app.selfwatch.ui.R as UiR

/**
 * A slim bar for one number against a target: a track up to [max], the target band shaded (from
 * [low] to [high]; to the end of the track when [high] is null), the value filled in its colour and a
 * tick at a limit. Drawn only; the card's text says the same for TalkBack.
 */
class RangeBarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ChartPalette.of(context).track }
    private val band = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ChartPalette.of(context).muted
        strokeWidth = 2 * density
    }
    private val rect = RectF()
    private var value = 0f
    private var low: Float? = null
    private var high: Float? = null
    private var limit: Float? = null
    private var max = 1f

    /** Shows [value] on a track to [max], with an optional band [low]..[high] and a [limit] tick, the fill in [color]. */
    fun set(value: Float, max: Float, @ColorInt color: Int, low: Float? = null, high: Float? = null, limit: Float? = null) {
        this.value = value
        this.max = if (max > 0f) max else 1f
        this.low = low
        this.high = high
        this.limit = limit
        fill.color = color
        band.color = ColorUtils.setAlphaComponent(ChartPalette.of(context).primary, BAND_ALPHA)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (HEIGHT_DP * density).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val w = right - left
        if (w <= 0f) return
        val barTop = paddingTop + (height - paddingTop - paddingBottom) * BAR_TOP
        val barBottom = paddingTop + (height - paddingTop - paddingBottom) * BAR_BOTTOM
        val r = (barBottom - barTop) / 2
        fun x(v: Float): Float = left + w * (v / max).coerceIn(0f, 1f)
        rect.set(left, barTop, right, barBottom)
        canvas.drawRoundRect(rect, r, r, track)
        low?.let { lo ->
            // The band is drawn a little taller than the track, so it reads as the place to land.
            rect.set(x(lo), paddingTop.toFloat(), x(high ?: max), (height - paddingBottom).toFloat())
            canvas.drawRoundRect(rect, r, r, band)
        }
        if (value > 0f) {
            rect.set(left, barTop, maxOf(x(value), left + 2 * r), barBottom)
            canvas.drawRoundRect(rect, r, r, fill)
        }
        limit?.let { canvas.drawLine(x(it), paddingTop.toFloat(), x(it), (height - paddingBottom).toFloat(), tick) }
    }

    private companion object {
        const val HEIGHT_DP = 16
        const val BAR_TOP = 0.25f
        const val BAR_BOTTOM = 0.75f
        const val BAND_ALPHA = 90
    }
}

/**
 * The shared view of the Food number cards (layout `card_food_metric`): a stat tile, an optional
 * [RangeBarView], an optional row of dots, a note, a data-coverage line, a link and an empty state.
 * Each card gives the root its own id ([rootId]) and fills the parts it uses in [bind].
 */
abstract class FoodMetricView<T>(parent: ViewGroup, @IdRes rootId: Int, title: Int) :
    CardView<T>(CardView.inflate(parent, R.layout.card_food_metric)) {

    /** The card. */
    protected val card: SwCard = root as SwCard

    /** The main number. */
    protected val tile: StatTile = find(R.id.metric_tile)

    /** The bar under the number. */
    protected val bar: RangeBarView = find(R.id.metric_bar)

    /** The dots row. */
    protected val dots: ChipGroup = find(R.id.metric_dots)

    /** The line under the number. */
    protected val note: TextView = find(R.id.metric_note)

    /** "4 of 6 foods have sodium data". */
    protected val coverage: TextView = find(R.id.metric_coverage)

    /** A text link at the bottom. */
    protected val link: MaterialButton = find(R.id.metric_link)

    /** Shown instead of the number when there is nothing yet. */
    protected val empty: EmptyState = find(R.id.metric_empty)

    /** The card's context. */
    protected val ctx: Context get() = root.context

    init {
        card.id = rootId
        card.title = parent.context.getString(title)
        tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
    }

    /** The color theme's chart colors: primary = on target, secondary = not yet, alarm = over a limit. */
    protected val palette: ChartPalette get() = ChartPalette.of(ctx)

    /** Shows [message] instead of the number (the rest hidden); [description] is read by TalkBack. */
    protected fun showEmpty(message: CharSequence, description: CharSequence = message) {
        empty.message = message
        show(empty, true)
        for (v in listOf(tile, bar, dots, note, coverage)) show(v, false)
        card.contentDescription = description
    }

    /** Shows the number parts (and hides the empty state). */
    protected fun showContent() {
        show(empty, false)
        show(tile, true)
    }

    /** Sets [v]'s text, hiding it when [text] is null. */
    protected fun text(v: TextView, text: CharSequence?) {
        v.text = text
        show(v, text != null)
    }

    /** Shows or hides [v]. */
    protected fun show(v: View, visible: Boolean) {
        v.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Replaces the dots with one per entry of [filled] (true: filled; [faded]: drawn lighter). */
    protected fun setDots(filled: List<Boolean>, faded: List<Boolean> = filled.map { false }) {
        dots.removeAllViews()
        val size = ctx.resources.getDimensionPixelSize(R.dimen.food_dot_size)
        val cell = ctx.resources.getDimensionPixelSize(R.dimen.food_dot_cell)
        for ((i, on) in filled.withIndex()) {
            val dot = View(ctx).apply {
                setBackgroundResource(R.drawable.food_dot)
                isSelected = on
                alpha = if (faded.getOrElse(i) { false }) FADED_ALPHA else 1f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val frame = FrameLayout(ctx).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                tag = "dot_$i"
            }
            frame.addView(dot, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            dots.addView(frame, ViewGroup.LayoutParams(cell, cell))
        }
        show(dots, filled.isNotEmpty())
    }

    private companion object {
        const val FADED_ALPHA = 0.35f
    }
}
