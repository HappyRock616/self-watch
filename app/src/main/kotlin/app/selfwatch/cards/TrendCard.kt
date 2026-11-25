/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.cards

import android.content.Context
import android.view.View
import android.view.ViewGroup
import app.selfwatch.R
import app.selfwatch.shell.Views
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.Sparkline
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Units
import kotlin.math.roundToInt
import app.selfwatch.ui.R as UiR

/**
 * One number over time: the [latest] value (null: none yet), the [series] behind the sparkline (oldest
 * first), the [change] over the card's comparison window (null: too little history) and an optional
 * [extras] numbers some cards use (a second value, a count). Values are in the card's own unit.
 */
data class TrendData(
    val latest: Double?,
    val series: List<Double> = emptyList(),
    val change: Double? = null,
    val units: Units = Units.METRIC,
    val extras: List<Double?> = emptyList(),
)

/**
 * Base for the simple catalog cards: one number with its unit and label, the change over time and a
 * sparkline in the large form; the number, label and change in the small form. A subclass loads
 * [TrendData] and says how to format it.
 */
abstract class TrendCard(id: String, tab: CardTab, title: Int, summary: Int) : Card<TrendData>(id, tab, title, summary) {

    /** [value] as text (number only, in [units]). */
    abstract fun format(ctx: Context, value: Double, units: Units): String

    /** The unit after the number, or null. */
    abstract fun unit(ctx: Context, units: Units): String?

    /** The label under the number ("latest", "last night"…). */
    abstract fun label(ctx: Context, data: TrendData): String

    /** The line under the number (the change, a range…), or null. */
    open fun note(ctx: Context, data: TrendData): String? = data.change?.let { c ->
        val text = format(ctx, kotlin.math.abs(c), data.units)
        when {
            (c * CHANGE_SCALE).roundToInt() == 0 -> ctx.getString(R.string.trend_note_steady)
            c > 0 -> ctx.getString(R.string.trend_note_up, text)
            else -> ctx.getString(R.string.trend_note_down, text)
        }
    }

    /** How the number stands (no judgment by default). */
    open fun status(data: TrendData): CardStatus = CardStatus.NONE

    /** The message when there is no value yet. */
    open fun emptyMessage(ctx: Context): String = ctx.getString(R.string.trend_empty)

    /** Opens the detail screen for this number; false when there is none. */
    open fun open(host: CardHost): Boolean = false

    override fun openDetail(host: CardHost): Boolean = open(host)

    override fun small(ctx: Context, data: TrendData): SmallFace? {
        val v = data.latest ?: return null
        return SmallFace(format(ctx, v, data.units), label(ctx, data), unit(ctx, data.units), status(data), note(ctx, data))
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<TrendData> = Holder(this, host, parent)

    private class Holder(private val card: TrendCard, host: CardHost, parent: ViewGroup) : CardView<TrendData>(SwCard(parent.context)) {
        private val ctx = parent.context
        private val tile = StatTile(ctx).apply { setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number) }
        private val note = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body)
        private val spark = Sparkline(ctx).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val empty = EmptyState(ctx)

        init {
            val sw = root as SwCard
            sw.title = ctx.getString(card.title)
            sw.addView(tile, Views.matchWrap(ctx))
            sw.addView(note, Views.matchWrap(ctx, 4))
            sw.addView(spark, Views.matchWrap(ctx, 8).apply { height = ctx.resources.getDimensionPixelSize(R.dimen.trend_card_spark) })
            sw.addView(empty, Views.matchWrap(ctx))
            sw.isClickable = true
            sw.isFocusable = true
            sw.setOnClickListener { card.open(host) }
        }

        override fun bind(data: TrendData) {
            val v = data.latest
            for (x in listOf(tile, note, spark)) x.visibility = if (v == null) View.GONE else View.VISIBLE
            empty.visibility = if (v == null) View.VISIBLE else View.GONE
            if (v == null) {
                empty.message = card.emptyMessage(ctx)
                root.contentDescription = listOf(ctx.getString(card.title), empty.message).joinToString(". ")
                return
            }
            tile.value = card.format(ctx, v, data.units)
            tile.unit = card.unit(ctx, data.units)
            tile.label = card.label(ctx, data)
            val n = card.note(ctx, data)
            note.text = n
            note.visibility = if (n == null) View.GONE else View.VISIBLE
            spark.values = data.series.map { (it * SPARK_SCALE).roundToInt() }.toIntArray()
            spark.visibility = if (data.series.size >= 2) View.VISIBLE else View.GONE
            root.contentDescription = listOfNotNull(ctx.getString(card.title), "${tile.value} ${tile.unit ?: ""}", tile.label, n).joinToString(". ")
        }
    }

    private companion object {
        const val CHANGE_SCALE = 10.0
        const val SPARK_SCALE = 100.0
    }
}
