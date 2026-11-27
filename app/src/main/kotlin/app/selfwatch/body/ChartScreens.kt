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
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.selfwatch.R
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.TimeRange
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.ChartLayer
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.charts.LayerValue
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

/**
 * An overlay chart card: chart, toggle chips, a scrub read-out and an optional [note]. On a detail screen
 * (the public constructor) the empty state sits below the card and replaces it when there is no data; as a
 * card on a tab ([onTab]) the card has a tappable title row and the empty state shows inside it.
 */
class ChartCard private constructor(
    private val ctx: Context,
    /** The card. */
    val card: SwCard,
    chartId: Int,
    emptyMessage: CharSequence,
    /** The title row of a card on a tab, or null on a detail screen. */
    private val header: ChartCardHeader?,
) {
    /** A chart card added to [parent] on a detail screen, the empty state below it. */
    constructor(parent: LinearLayout, chartId: Int, emptyMessage: CharSequence) :
        this(parent.context, Views.card(parent, null), chartId, emptyMessage, null) {
        parent.addView(empty, Views.matchWrap(ctx, 16))
    }

    /** The chart. */
    val chart: OverlayChartView = OverlayChartView(ctx).apply { id = chartId }

    /** Its chips. */
    val chips: ToggleChipGroup = ToggleChipGroup(ctx)

    private val readout: TextView = Views.caption(ctx, ctx.getString(R.string.chart_scrub_hint))

    /** A caption under the read-out (how the series are smoothed); hidden until given text. */
    val note: TextView = Views.caption(ctx).apply { visibility = View.GONE }

    /** Shown instead of the card (on a tab: instead of the chart) when there is no data. */
    val empty: EmptyState = EmptyState(ctx).apply { message = emptyMessage }

    /** The read-out text for the time under the finger and the values there. */
    var readoutText: (tSec: Long, values: List<LayerValue>) -> String = { t, values ->
        ctx.getString(R.string.chart_scrub_values, Fmt.time(ctx, t), values.joinToString(" · ") { "${it.label} ${it.text}" })
    }

    // On a tab, everything but the title row and the empty state, hidden together when there is no data.
    private val plot: LinearLayout? = header?.let { LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL } }

    init {
        val box: ViewGroup = plot ?: card
        header?.let { card.addView(it.view, Views.matchWrap(ctx)) }
        plot?.let { card.addView(it, Views.matchWrap(ctx)) }
        box.addView(chart, Views.matchWrap(ctx))
        box.addView(chips, Views.matchWrap(ctx, 8))
        box.addView(readout, Views.matchWrap(ctx, 4))
        box.addView(note, Views.matchWrap(ctx, 4))
        if (header != null) card.addView(empty, Views.matchWrap(ctx, 8))
        // On a tab the how-to hint is left out (card design standard: no sentences); the read-out shows once the chart is touched.
        if (header != null) readout.visibility = View.GONE
        chart.onScrub = { t, values ->
            readout.text = readoutText(t, values)
            readout.visibility = View.VISIBLE
        }
    }

    /** The short tag in the title row of a card on a tab ("7 h 40 min"); null hides it. */
    fun setTag(text: CharSequence?) {
        header?.tag = text
    }

    private fun show(hasData: Boolean) {
        val shown = plot ?: card
        shown.visibility = if (hasData) View.VISIBLE else View.GONE
        empty.visibility = if (hasData) View.GONE else View.VISIBLE
    }

    /**
     * Shows [layers] over [start]..[end] with chips remembered under [chipsKey]; [hiddenByDefault]
     * layers start switched off. With no data the empty state shows instead.
     */
    fun bind(start: Long, end: Long, layers: List<ChartLayer>, chipsKey: String, hiddenByDefault: Set<String>, hasData: Boolean) {
        show(hasData)
        if (!hasData) return
        for (id in hiddenByDefault) {
            if (chips.chartId != chipsKey) chart.setVisible(id, false)
        }
        chart.range = TimeRange(start, end)
        chart.layers = layers
        chips.chartId = chipsKey
        chart.bindChips(chips)
    }

    // The view name and the hidden set last applied to the chart (stored or chosen with the chips).
    private var applied: Pair<String, Set<String>>? = null

    /**
     * Shows [layers] over [start]..[end] with the choice kept in settings: [hidden] is the stored choice
     * among [toggleable] for the view named [view] (applied when the view changes or the stored choice
     * changes elsewhere, e.g. on the detail screen while this card waits on its tab; otherwise the chips
     * drive the chart); a chip change calls [onSave] with the new hidden set. The zoom is kept when only
     * the data changed.
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
        show(hasData)
        if (!hasData) return
        if (applied != view to hidden) {
            for (id in toggleable) chart.setVisible(id, id !in hidden)
            applied = view to hidden
        }
        val range = TimeRange(start, end)
        if (chart.range != range) chart.range = range
        chart.layers = layers
        chips.chartId = null
        chart.bindChips(chips)
        chips.onChange = { id, on, _ ->
            chart.setVisible(id, on)
            val now = toggleable.filterNot { chart.isLayerVisible(it) }.toSet()
            applied = view to now
            onSave(now)
        }
    }

    /** Chart cards on the main tabs. */
    companion object {
        /**
         * A chart card for a tab (not attached): [title] in a row that calls [onOpen] when tapped (the chart
         * itself scrubs and zooms), then the chart, chips and read-out, and [emptyMessage] inside the card
         * when there is no data. The chart has no view id ([View.NO_ID]): the detail screen's chart may be
         * shown above the tab.
         */
        fun onTab(ctx: Context, title: CharSequence, emptyMessage: CharSequence, onOpen: () -> Unit): ChartCard =
            ChartCard(ctx, SwCard(ctx), View.NO_ID, emptyMessage, ChartCardHeader(ctx, title, onOpen))
    }
}

/** The title row of a chart card on a tab: title, an optional short tag and a chevron; tapping it opens the detail. */
class ChartCardHeader(ctx: Context, title: CharSequence, onOpen: () -> Unit) {
    /** The row. */
    val view: LinearLayout = Views.row(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = resources.getDimensionPixelSize(UiR.dimen.sw_touch_target)
        isClickable = true
        isFocusable = true
        val ripple = android.util.TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        if (ripple.resourceId != 0) setBackgroundResource(ripple.resourceId)
        contentDescription = ctx.getString(R.string.chart_card_open, title)
        setOnClickListener { onOpen() }
    }

    private val titleView = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_BodyLarge, title).apply {
        androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
    }
    private val tagView = Views.caption(ctx).apply { visibility = View.GONE }
    private val chevron = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_BodyLarge, ctx.getString(R.string.chart_next_symbol)).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        view.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        view.addView(tagView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        view.addView(chevron, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = Views.dp(ctx, 8)
        })
    }

    /** The short tag before the chevron; null hides it. */
    var tag: CharSequence?
        get() = tagView.text
        set(value) {
            tagView.text = value
            tagView.visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
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
