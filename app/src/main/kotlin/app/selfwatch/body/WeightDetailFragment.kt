/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.fragment.app.viewModels
import app.selfwatch.R
import app.selfwatch.graph
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.TimeRange
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.Axis
import app.selfwatch.ui.charts.BarLayer
import app.selfwatch.ui.charts.ChartLayer
import app.selfwatch.ui.charts.DotTrendLayer
import app.selfwatch.ui.charts.LayerValue
import app.selfwatch.ui.charts.LineLayer
import app.selfwatch.ui.charts.OverlayChartView
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.ListRow
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.components.ToggleChipGroup
import app.selfwatch.ui.format.Fmt
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Lists measurements newest first as tappable rows (tap edits). */
object EntryList {
    /** Most rows shown; older entries stay in the chart. */
    const val MAX_ROWS: Int = 100

    /** Replaces [box]'s rows with [entries] (any order), formatted by [format]; tapping a row calls [onTap]. */
    fun bind(box: LinearLayout, entries: List<BodyMeasure>, format: (BodyMeasure) -> String, onTap: (BodyMeasure) -> Unit) {
        val ctx = box.context
        box.removeAllViews()
        val zone = ZoneId.systemDefault()
        for (m in entries.sortedByDescending { it.tSec }.take(MAX_ROWS)) {
            val row = ListRow(ctx).apply {
                title = format(m)
                subtitle = ctx.getString(R.string.entry_when, Fmt.day(ctx, Day.of(m.tSec, zone).yyyymmdd), Fmt.time(ctx, m.tSec, zone))
                showChevron = false
                onClick = { onTap(m) }
                contentDescription = ctx.getString(R.string.entry_edit_description, title, subtitle)
            }
            box.addView(row, Views.matchWrap(ctx))
        }
    }
}

/** The Weight screen's overlay chart: layers built from the screen state and the scrub read-out. */
object WeightChart {
    private const val DAY_SEC = 86_400L
    private const val HALF_DAY_SEC = 43_200L
    private const val WEIGHT_SCALE = 10
    private const val NET_HEIGHT = 0.4f

    /**
     * The layers for [s]: the weight (dots, trend and goal, left axis, always on); the energy balance
     * (7-day average of in minus out) as low signed bars, to read against the weight trend; active minutes
     * (7-day average) as a line on its own scale; and, off by default, calories in and out per day on the
     * right axis and steps. Layers without any data are left out. (Weekly buckets on long ranges.)
     */
    fun layers(ctx: Context, s: WeightUiState, zone: ZoneId): List<ChartLayer> {
        fun series(index: Int): Int = ChartScreens.series(ctx, index)
        fun display(kgX100: Int): Int = (BodyUnits.toDisplay(BodyKind.WEIGHT, kgX100, s.units) * WEIGHT_SCALE).roundToInt()
        val n = s.entries.size
        val o = s.overlay
        val bucketSec = (o?.bucketDays ?: 1) * DAY_SEC
        val out = ArrayList<ChartLayer>()
        out += DotTrendLayer(
            WeightLayers.WEIGHT, ctx.getString(R.string.body_weight), series(0), Axis.LEFT,
            LongArray(n) { s.entries[it].tSec }, IntArray(n) { display(s.entries[it].valueX100) },
            trend = IntArray(minOf(n, s.trend.size)) { display(s.trend[it]) },
            goal = s.goal?.value?.let { display(it) },
            scale = WEIGHT_SCALE, gapSec = maxOf(HALF_DAY_SEC, bucketSec / 2),
            unit = BodyUnits.unit(BodyKind.WEIGHT, s.units), lockedOn = true,
        )
        if (o == null) return out
        val start = o.firstDay.startSec(zone)
        val kcal = ctx.getString(R.string.unit_kcal)
        fun line(id: String, @StringRes label: Int, seriesIndex: Int, values: IntArray, axis: Axis, unit: String) {
            val idx = values.indices.filter { values[it] != WeightOverlay.MISSING }
            if (idx.isEmpty()) return
            out += LineLayer(
                id, ctx.getString(label), series(seriesIndex), axis,
                LongArray(idx.size) { o.bucketStart(idx[it]).startSec(zone) + bucketSec / 2 },
                IntArray(idx.size) { values[idx[it]] },
                smooth = true, gapSec = bucketSec * 3 / 2, unit = unit,
            )
        }
        // Order sets the chip order: the defaults (balance, activity) first.
        if (o.netKcal.any { it != WeightOverlay.MISSING }) {
            // Surplus above the zero line, deficit below; low on its own scale so it sits under the weight.
            out += BarLayer(
                WeightLayers.NET, ctx.getString(R.string.weight_layer_net), series(1), Axis.HIDDEN,
                start, bucketSec, o.netKcal, unit = kcal, signed = true, heightFraction = NET_HEIGHT,
            )
        }
        line(WeightLayers.ACTIVE_MIN, R.string.weight_layer_active, 2, o.activeMin, Axis.HIDDEN, ctx.getString(R.string.unit_min))
        line(WeightLayers.KCAL_IN, R.string.weight_layer_kcal_in, 3, o.kcalIn, Axis.RIGHT, kcal)
        line(WeightLayers.KCAL_OUT, R.string.weight_layer_kcal_out, 4, o.kcalOut, Axis.RIGHT, kcal)
        line(WeightLayers.STEPS, R.string.activity_steps, 5, o.steps, Axis.HIDDEN, "")
        return out
    }

    /** "Nov 20 · 181.6 lb · net -310 · 52 active min · in 1,920 · out 2,230" for the layers shown at [tSec]. */
    fun readout(ctx: Context, tSec: Long, values: List<LayerValue>): String {
        val locale = Locale.getDefault()
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd")
        val date = DateTimeFormatter.ofPattern(pattern, locale).format(Instant.ofEpochSecond(tSec).atZone(ZoneId.systemDefault()))
        // Weight first, then the other layers in chip order (the chart reports them in drawing order).
        val order = listOf(WeightLayers.WEIGHT) + WeightLayers.TOGGLEABLE
        val parts = values.sortedBy { v -> order.indexOf(v.layerId).let { if (it < 0) order.size else it } }.mapNotNull { v ->
            val x = v.value ?: return@mapNotNull null
            val whole = x.roundToLong()
            when (v.layerId) {
                WeightLayers.KCAL_IN -> ctx.getString(R.string.weight_scrub_in, Fmt.number(whole, locale))
                WeightLayers.KCAL_OUT -> ctx.getString(R.string.weight_scrub_out, Fmt.number(whole, locale))
                WeightLayers.NET -> ctx.getString(R.string.weight_scrub_net, (if (whole > 0) "+" else "") + Fmt.number(whole, locale))
                WeightLayers.ACTIVE_MIN -> ctx.getString(R.string.weight_scrub_active, Fmt.number(whole, locale))
                WeightLayers.STEPS -> ctx.resources.getQuantityString(
                    R.plurals.weight_scrub_steps, whole.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(), Fmt.number(whole, locale),
                )
                else -> v.text
            }
        }
        return if (parts.isEmpty()) date else ctx.getString(R.string.weight_scrub_line, date, parts.joinToString(" · "))
    }
}

/**
 * Weight (C13): the weigh-ins with a smoothed trend and goal line, overlaid by default with the energy
 * balance and active minutes as 7-day averages (daily calories in and out and steps on request) in one [OverlayChartView] with toggle
 * chips remembered in settings ([WeightLayers]); tiles for latest, trend, goal and BMI, range 30 days ·
 * 90 days · 1 year · All, and the list of weigh-ins (tap to edit or delete with undo). "Log" adds one; the
 * menu sets the goal.
 */
class WeightDetailFragment : DetailFragment(R.menu.menu_weight) {

    private val vm: WeightViewModel by viewModels { WeightViewModel.factory(requireContext().graph) }

    override fun title(): CharSequence = getString(R.string.body_weight)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val segments = Views.segments(
            ctx,
            listOf(getString(R.string.range_30d), getString(R.string.range_90d), getString(R.string.range_1y), getString(R.string.range_all)),
        ) { vm.setRange(WeightRange.entries[it]) }
        column.addView(segments, Views.matchWrap(ctx))
        val chartCard = Views.card(column, getString(R.string.weight_chart))
        chartCard.actionText = getString(R.string.body_log)
        chartCard.onAction = { WeightEditor.log(this, vm.state.value.units) }
        val chart = OverlayChartView(ctx).apply { id = R.id.weight_chart }
        val chips = ToggleChipGroup(ctx)
        val readout = Views.caption(ctx, getString(R.string.chart_scrub_hint))
        val smoothingNote = Views.caption(ctx, getString(R.string.weight_overlay_weekly))
        chartCard.addView(chart, Views.matchWrap(ctx))
        chartCard.addView(chips, Views.matchWrap(ctx, 8))
        chartCard.addView(readout, Views.matchWrap(ctx, 4))
        chartCard.addView(smoothingNote, Views.matchWrap(ctx, 4))
        chart.onScrub = { t, values -> readout.text = WeightChart.readout(ctx, t, values) }
        var savedLayersApplied = false
        val empty = EmptyState(ctx).apply {
            message = getString(R.string.weight_empty)
            buttonText = getString(R.string.body_log_weight)
            onButton = { WeightEditor.log(this@WeightDetailFragment, vm.state.value.units) }
        }
        column.addView(empty, Views.matchWrap(ctx, 16))
        val tiles = ChartScreens.tiles(
            column,
            listOf(getString(R.string.weight_latest), getString(R.string.weight_trend), getString(R.string.weight_goal), getString(R.string.weight_bmi)),
        )
        val listCard: SwCard = Views.card(column, getString(R.string.weight_entries))
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        listCard.addView(list, Views.matchWrap(ctx))

        collectWhileStarted(vm.state) { s ->
            Views.selectSegment(segments, s.range.ordinal)
            val has = s.entries.isNotEmpty()
            chartCard.visibility = if (has) View.VISIBLE else View.GONE
            listCard.visibility = if (has) View.VISIBLE else View.GONE
            empty.visibility = if (has || s.loading) View.GONE else View.VISIBLE
            val zone = ZoneId.systemDefault()
            if (has && s.firstDay != null && s.lastDay != null) {
                if (!savedLayersApplied) {
                    // The remembered choice, once per screen; after that the chips drive the chart.
                    for (id in WeightLayers.TOGGLEABLE) chart.setVisible(id, id !in s.hiddenLayers)
                    savedLayersApplied = true
                }
                chart.zone = zone
                val range = TimeRange(s.firstDay.startSec(zone), s.lastDay.plusDays(1).startSec(zone))
                if (chart.range != range) chart.range = range // keeps the zoom when only the data changed
                chart.layers = WeightChart.layers(ctx, s, zone)
                chart.bindChips(chips)
                chips.onChange = { id, on, _ ->
                    chart.setVisible(id, on)
                    vm.saveHiddenLayers(WeightLayers.TOGGLEABLE.filterNot { chart.isLayerVisible(it) }.toSet())
                }
                smoothingNote.visibility = if (s.overlay?.hasData == true) View.VISIBLE else View.GONE
                smoothingNote.setText(if ((s.overlay?.bucketDays ?: 1) > 1) R.string.weight_overlay_weekly else R.string.weight_overlay_rolling)
            }
            val latest = s.entries.lastOrNull()
            ChartScreens.set(tiles[0], latest?.let { BodyUnits.number(BodyKind.WEIGHT, it.valueX100, s.units) }, BodyUnits.unit(BodyKind.WEIGHT, s.units))
            ChartScreens.set(tiles[1], s.trendKgX100?.let { BodyUnits.number(BodyKind.WEIGHT, it, s.units) }, BodyUnits.unit(BodyKind.WEIGHT, s.units))
            ChartScreens.set(tiles[2], s.goal?.let { BodyUnits.number(BodyKind.WEIGHT, it.value, s.units) }, BodyUnits.unit(BodyKind.WEIGHT, s.units))
            ChartScreens.set(tiles[3], s.bmi?.let { Fmt.number(it, 1) })
            toolbar.subtitle = s.goal?.let { getString(R.string.weight_goal_since, Fmt.weight(it.value, s.units), Fmt.day(ctx, it.fromDay.yyyymmdd)) }
            EntryList.bind(list, s.entries, { BodyUnits.format(BodyKind.WEIGHT, it.valueX100, s.units) }) { m ->
                MeasureEditor.edit(this, m, s.units)
            }
        }
    }

    override fun onMenuItem(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_weight_goal -> {
            askGoal(requireContext(), vm.state.value)
            true
        }
        R.id.menu_weight_log -> {
            WeightEditor.log(this, vm.state.value.units)
            true
        }
        else -> false
    }

    private fun askGoal(ctx: Context, s: WeightUiState) {
        val unit = BodyUnits.unit(BodyKind.WEIGHT, s.units)
        val field = TextInputLayout(ctx).apply { hint = getString(R.string.measure_value_in, unit) }
        val edit = TextInputEditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            s.goal?.let { setText(Fmt.x100((BodyUnits.toDisplay(BodyKind.WEIGHT, it.value, s.units) * 100).toLong(), 1)) }
        }
        field.addView(edit)
        val box = LinearLayout(ctx).apply {
            val pad = Views.dp(ctx, 24)
            setPadding(pad, Views.dp(ctx, 8), pad, 0)
            addView(field, Views.matchWrap(ctx))
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.weight_set_goal)
            .setMessage(R.string.weight_goal_note)
            .setView(box)
            .setNegativeButton(R.string.measure_cancel, null)
            .setPositiveButton(R.string.measure_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val v = MeasureInput.parse(BodyKind.WEIGHT, edit.text.toString(), s.units)
                if (v == null) {
                    field.error = getString(R.string.measure_invalid)
                } else {
                    vm.setGoal(v)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}
