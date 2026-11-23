/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity.cards

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import app.selfwatch.cards.CardView
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.components.BarSeries
import app.selfwatch.ui.components.BarsView
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import app.selfwatch.ui.R as UiR

/**
 * The view ids of one stat card layout (each card has its own ids so two cards never share one).
 * [bars] is 0 when the layout has no bar chart.
 */
internal data class StatIds(
    @param:IdRes val tile: Int,
    @param:IdRes val bar: Int,
    @param:IdRes val bars: Int,
    @param:IdRes val caption: Int,
    @param:IdRes val note: Int,
    @param:IdRes val empty: Int,
)

/**
 * Shared view for the phase-2 Activity cards: a big number ([tile]) with a progress bar toward a target,
 * an optional small bar chart, a caption and a note, and an empty state that replaces them all.
 */
internal abstract class StatCardView<T>(parent: ViewGroup, @LayoutRes layout: Int, private val ids: StatIds) :
    CardView<T>(CardView.inflate(parent, layout)) {

    /** The big number. */
    protected val tile: StatTile = find(ids.tile)

    /** Progress toward the target. */
    protected val bar: LinearProgressIndicator = find(ids.bar)

    /** The small chart, if the layout has one. */
    protected val bars: BarsView? = if (ids.bars != 0) find(ids.bars) else null

    /** The line under the number. */
    protected val caption: TextView = find(ids.caption)

    /** The smaller note at the bottom. */
    protected val note: TextView = find(ids.note)

    init {
        tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
    }

    /** Shows the empty state ([isEmpty]) or the content. */
    protected fun showEmpty(isEmpty: Boolean) {
        find<EmptyState>(ids.empty).visibility = if (isEmpty) View.VISIBLE else View.GONE
        val content = if (isEmpty) View.GONE else View.VISIBLE
        for (v in listOfNotNull(tile, bar, bars, caption, note)) v.visibility = content
    }

    /** Sets the progress bar to [value] of [target] (hidden without a target). */
    protected fun progress(value: Int, target: Int?) {
        bar.visibility = if (target == null || target <= 0) View.GONE else View.VISIBLE
        bar.max = maxOf(target ?: 1, 1)
        bar.setProgressCompat(value.coerceIn(0, bar.max), false)
    }

    /** Sets [text] on [view], hiding it when null. */
    protected fun text(view: TextView, text: CharSequence?) {
        view.text = text
        view.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    /** One bar series, in the theme primary, with [labels] and an optional [goal] line. */
    protected fun bars(label: String, values: List<Int>, labels: List<String>, goal: Int?) {
        val chart = bars ?: return
        chart.labels = labels
        chart.series = listOf(BarSeries(label, ChartPalette.of(root.context).primary, values.toIntArray()))
        chart.goalLine = goal?.toFloat()
    }

    /** Helpers for labels. */
    companion object {
        /** Narrow weekday names ("M", "T", …) of the days [yyyymmdd]. */
        fun weekdayLabels(days: List<Int>, locale: Locale = Locale.getDefault()): List<String> =
            days.map { Fmt.dayToDate(it).dayOfWeek.getDisplayName(TextStyle.NARROW, locale) }

        /** Short date labels ("Nov 3" in English) of the week starts [days]. */
        fun weekLabels(days: List<Int>, locale: Locale = Locale.getDefault()): List<String> {
            val f = DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
            return days.map { f.format(Fmt.dayToDate(it)) }
        }
    }
}
