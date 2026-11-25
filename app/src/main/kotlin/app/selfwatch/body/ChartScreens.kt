/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.selfwatch.R
import app.selfwatch.model.time.Day
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.ChartLayer
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.charts.OverlayChartView
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.components.ToggleChipGroup
import app.selfwatch.ui.format.Fmt
import com.google.android.material.button.MaterialButton
import java.time.ZoneId
import app.selfwatch.ui.R as UiR

/** "‹  Last night  ›": steps one night or day back or forward. */
class DayStepper(ctx: Context, onPrevious: () -> Unit, onNext: () -> Unit) {
    /** The row to add to the screen. */
    val view: LinearLayout = Views.row(ctx).apply { gravity = Gravity.CENTER_VERTICAL }

    private val prev = MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        text = ctx.getString(R.string.chart_previous_symbol)
        contentDescription = ctx.getString(R.string.chart_previous)
        minWidth = Views.dp(ctx, 48)
        textSize = SYMBOL_SP
        setOnClickListener { onPrevious() }
    }
    private val label = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_BodyLarge).apply { gravity = Gravity.CENTER }
    private val next = MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        text = ctx.getString(R.string.chart_next_symbol)
        contentDescription = ctx.getString(R.string.chart_next)
        minWidth = Views.dp(ctx, 48)
        textSize = SYMBOL_SP
        setOnClickListener { onNext() }
    }

    init {
        view.addView(prev)
        view.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        view.addView(next)
    }

    /** Shows [text]; [canGoNext] enables the forward button. */
    fun bind(text: CharSequence, canGoNext: Boolean) {
        label.text = text
        next.isEnabled = canGoNext
        next.alpha = if (canGoNext) 1f else DISABLED_ALPHA
    }

    private companion object {
        const val DISABLED_ALPHA = 0.3f
        const val SYMBOL_SP = 28f
    }
}

/** An overlay chart card: chart, toggle chips and a scrub read-out, plus an empty state below it. */
class ChartCard(parent: LinearLayout, chartId: Int, emptyMessage: CharSequence) {
    private val ctx: Context = parent.context

    /** The card. */
    val card: SwCard = Views.card(parent, null)

    /** The chart. */
    val chart: OverlayChartView = OverlayChartView(ctx).apply { id = chartId }

    /** Its chips. */
    val chips: ToggleChipGroup = ToggleChipGroup(ctx)

    private val readout: TextView = Views.caption(ctx, ctx.getString(R.string.chart_scrub_hint))

    /** Shown instead of the card when there is no data. */
    val empty: EmptyState = EmptyState(ctx).apply { message = emptyMessage }

    init {
        card.addView(chart, Views.matchWrap(ctx))
        card.addView(chips, Views.matchWrap(ctx, 8))
        card.addView(readout, Views.matchWrap(ctx, 4))
        parent.addView(empty, Views.matchWrap(ctx, 16))
        chart.onScrub = { t, values ->
            readout.text = ctx.getString(
                R.string.chart_scrub_values,
                Fmt.time(ctx, t),
                values.joinToString(" · ") { "${it.label} ${it.text}" },
            )
        }
    }

    /**
     * Shows [layers] over [start]..[end] with chips remembered under [chipsKey]; [hiddenByDefault]
     * layers start switched off. With no data the empty state shows instead.
     */
    fun bind(start: Long, end: Long, layers: List<ChartLayer>, chipsKey: String, hiddenByDefault: Set<String>, hasData: Boolean) {
        card.visibility = if (hasData) View.VISIBLE else View.GONE
        empty.visibility = if (hasData) View.GONE else View.VISIBLE
        if (!hasData) return
        for (id in hiddenByDefault) {
            if (chips.chartId != chipsKey) chart.setVisible(id, false)
        }
        chart.range = app.selfwatch.model.time.TimeRange(start, end)
        chart.layers = layers
        chips.chartId = chipsKey
        chart.bindChips(chips)
    }

    private var appliedChoice: String? = null

    /**
     * Shows [layers] over [start]..[end] with the choice kept in settings: [hidden] is the stored choice
     * among [toggleable] for the view named [view] (applied once each time the view changes; after that
     * the chips drive the chart); a chip change calls [onSave] with the new hidden set.
     */
    fun bind(
        start: Long,
        end: Long,
        layers: List<ChartLayer>,
        view: String,
        hidden: Set<String>,
        toggleable: List<String>,
        hasData: Boolean,
        onSave: (Set<String>) -> Unit,
    ) {
        card.visibility = if (hasData) View.VISIBLE else View.GONE
        empty.visibility = if (hasData) View.GONE else View.VISIBLE
        if (!hasData) return
        if (appliedChoice != view) {
            for (id in toggleable) chart.setVisible(id, id !in hidden)
            appliedChoice = view
        }
        chart.range = app.selfwatch.model.time.TimeRange(start, end)
        chart.layers = layers
        chips.chartId = null
        chart.bindChips(chips)
        chips.onChange = { id, on, _ ->
            chart.setVisible(id, on)
            onSave(toggleable.filterNot { chart.isLayerVisible(it) }.toSet())
        }
    }
}

/** The [SwCard] that holds this view (the view itself when none). */
fun View.parentCard(): View {
    var v: View = this
    while (v !is SwCard) v = (v.parent as? View) ?: return this
    return v
}

/** Small shared helpers for the chart screens. */
object ChartScreens {
    /** The color theme's color for series [index] ([ChartPalette.series]). */
    fun series(ctx: Context, index: Int): Int = ChartPalette.of(ctx).series(index)

    /** The neutral chart color (usual bands). */
    fun neutral(ctx: Context): Int = ChartPalette.of(ctx).muted

    /** A row of [labels.size] stat tiles in a card added to [parent]; returns the tiles. */
    fun tiles(parent: LinearLayout, labels: List<CharSequence>, first: Boolean = false): List<StatTile> {
        val ctx = parent.context
        val card = Views.card(parent, null, first)
        val row = Views.row(ctx)
        val out = labels.mapIndexed { i, l ->
            StatTile(ctx).apply { label = l }.also { row.addView(it, Views.weighted(ctx, if (i == 0) 0 else 8)) }
        }
        card.addView(row, Views.matchWrap(ctx))
        return out
    }

    /** Sets [tile] to [value] (a dash when null) with [unit]. */
    fun set(tile: StatTile, value: String?, unit: String? = null) {
        tile.value = value ?: tile.context.getString(R.string.dash)
        tile.unit = if (value == null) null else unit
    }

    /** Local noon of [day] in [zone], where per-day points sit on period charts. */
    fun noon(day: Day, zone: ZoneId): Long = day.toLocalDate().atTime(NOON_HOUR, 0).atZone(zone).toEpochSecond()

    private const val NOON_HOUR = 12
}
