/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body

import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.viewModels
import app.selfwatch.R
import app.selfwatch.graph
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.SystemClock
import app.selfwatch.model.time.TimeRange
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.Nav
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.Axis
import app.selfwatch.ui.charts.LineLayer
import app.selfwatch.ui.charts.OverlayChartView
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.ListRow
import app.selfwatch.ui.components.Sparkline
import app.selfwatch.ui.format.Fmt
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.time.ZoneId
import app.selfwatch.ui.R as UiR

/**
 * Measurements (C13): waist, hips, chest, arm, thigh, neck, body fat, muscle, bone and water, plus the
 * user's own custom measurements, each with its latest value and a 90-day sparkline. Tap a row for its
 * history; the menu adds a custom measurement.
 */
class MeasurementsFragment : DetailFragment(R.menu.menu_measurements) {

    private val vm: MeasurementsViewModel by viewModels { MeasurementsViewModel.factory(requireContext().graph) }

    override fun title(): CharSequence = getString(R.string.body_measurements)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val card = Views.card(column, null, first = true)
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        card.addView(list, Views.matchWrap(ctx))
        column.addView(Views.caption(ctx, getString(R.string.measure_hint)), Views.matchWrap(ctx, 8))
        collectWhileStarted(vm.state) { s ->
            list.removeAllViews()
            val zone = ZoneId.systemDefault()
            for (r in s.rows) {
                val name = r.custom?.name ?: MeasureEditor.kindLabel(ctx, r.kind)
                val unit = r.custom?.unit ?: ""
                val row = ListRow(ctx).apply {
                    title = name
                    subtitle = r.latest?.let { Fmt.day(ctx, Day.of(it.tSec, zone).yyyymmdd) } ?: getString(R.string.measure_none)
                    trailing = r.latest?.let { BodyUnits.format(r.kind, it.valueX100, s.units, unit) }
                    onClick = { Nav.open(this@MeasurementsFragment, MeasureDetailFragment.newInstance(r.kind, r.custom?.id)) }
                }
                if (r.recent.size >= 2) row.addTrailingAccessory(Sparkline(ctx).apply { values = r.recent.toIntArray() })
                list.addView(row, Views.matchWrap(ctx))
            }
        }
    }

    override fun onMenuItem(item: MenuItem): Boolean {
        if (item.itemId != R.id.menu_add_custom) return false
        val ctx = requireContext()
        val nameField = TextInputLayout(ctx).apply { hint = getString(R.string.measure_custom_name) }
        val name = TextInputEditText(ctx).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        nameField.addView(name)
        val unitField = TextInputLayout(ctx).apply { hint = getString(R.string.measure_custom_unit) }
        val unit = TextInputEditText(ctx).apply { inputType = InputType.TYPE_CLASS_TEXT }
        unitField.addView(unit)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Views.dp(ctx, 24)
            setPadding(pad, Views.dp(ctx, 8), pad, 0)
            addView(nameField, Views.matchWrap(ctx))
            addView(unitField, Views.matchWrap(ctx, 8))
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.measure_add_custom)
            .setView(box)
            .setNegativeButton(R.string.measure_cancel, null)
            .setPositiveButton(R.string.measure_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = name.text?.toString()?.trim().orEmpty()
                if (n.isEmpty()) {
                    nameField.error = getString(R.string.measure_custom_name_needed)
                } else {
                    vm.addCustomKind(n, unit.text?.toString().orEmpty())
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
        return true
    }
}

/** One measurement's history (C13): a line chart over all time and every entry (tap to edit or delete). */
class MeasureDetailFragment : DetailFragment(R.menu.menu_measure_detail) {

    private val kind: BodyKind by lazy { BodyKind.fromCode(requireArguments().getInt(ARG_KIND)) ?: BodyKind.WAIST }
    private val customId: Long? by lazy { requireArguments().getLong(ARG_CUSTOM, -1).takeIf { it >= 0 } }

    private val vm: MeasureDetailViewModel by viewModels {
        MeasureDetailViewModel.factory(requireContext().graph, kind, customId)
    }

    // O3 on the body fat and waist screens: weekly protein g/kg and strength sessions with the trend.
    private val compVm: OverlayViewModel<ProteinOverlay> by viewModels {
        OverlayViewModel.factory(requireContext().graph.db, BodyCompChart.TABLES, ProteinOverlay.LAYERS) { db -> ProteinOverlay.load(db, SystemClock) }
    }

    override fun title(): CharSequence = MeasureEditor.kindLabel(requireContext(), kind)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val chartCard = Views.card(column, null, first = true)
        chartCard.actionText = getString(R.string.body_log)
        chartCard.onAction = { log() }
        val chart = OverlayChartView(ctx).apply { id = R.id.measure_chart }
        chartCard.addView(chart, Views.matchWrap(ctx))
        val empty = EmptyState(ctx).apply {
            message = getString(R.string.measure_empty)
            buttonText = getString(R.string.body_log)
            onButton = { log() }
        }
        column.addView(empty, Views.matchWrap(ctx, 16))
        val listCard = Views.card(column, getString(R.string.weight_entries))
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        listCard.addView(list, Views.matchWrap(ctx))
        val comp = if (kind in BODY_COMP_KINDS) BodyCompChart(chartCard, chart, kind) else null
        if (comp != null) {
            collectWhileStarted(compVm.state) { o ->
                comp.overlay = o
                comp.render(vm.state.value, compVm::saveHidden)
            }
        }

        collectWhileStarted(vm.state) { s ->
            s.custom?.let { toolbar.title = it.name }
            val unit = s.custom?.unit ?: ""
            val has = s.entries.isNotEmpty()
            chartCard.visibility = if (has) View.VISIBLE else View.GONE
            listCard.visibility = if (has) View.VISIBLE else View.GONE
            empty.visibility = if (has || s.loading) View.GONE else View.VISIBLE
            if (has && comp != null) {
                comp.render(s, compVm::saveHidden)
            } else if (has) {
                val first = s.entries.first().tSec
                val last = s.entries.last().tSec
                val pad = maxOf(DAY_SEC, (last - first) / PAD_PARTS)
                chart.range = TimeRange(first - pad, last + pad)
                chart.layers = listOf(
                    LineLayer(
                        "value",
                        s.custom?.name ?: MeasureEditor.kindLabel(ctx, kind),
                        ChartScreens.series(ctx, 0),
                        Axis.LEFT,
                        LongArray(s.entries.size) { s.entries[it].tSec },
                        IntArray(s.entries.size) { (BodyUnits.toDisplay(kind, s.entries[it].valueX100, s.units) * 100).toInt() },
                        scale = 100,
                        gapSec = Long.MAX_VALUE / 4,
                        unit = BodyUnits.unit(kind, s.units, unit),
                    ),
                )
            }
            EntryList.bind(list, s.entries, { BodyUnits.format(kind, it.valueX100, s.units, unit) }) { m ->
                MeasureEditor.edit(this, m, s.units, unit, s.custom?.name)
            }
        }
    }

    private fun log() {
        val s = vm.state.value
        MeasureEditor.log(this, kind, s.units, customId, s.custom?.unit ?: "", s.custom?.name)
    }

    override fun onMenuItem(item: MenuItem): Boolean {
        if (item.itemId != R.id.menu_measure_log) return false
        log()
        return true
    }

    /** Factory. */
    companion object {
        private const val ARG_KIND = "kind"
        private const val ARG_CUSTOM = "custom"
        private const val DAY_SEC = 86_400L
        private const val PAD_PARTS = 20L

        /** Kinds whose screen shows the protein and strength overlay (O3). */
        val BODY_COMP_KINDS: Set<BodyKind> = setOf(BodyKind.BODY_FAT, BodyKind.WAIST)

        /** The history of [kind] (and [customKindId] for custom kinds). */
        fun newInstance(kind: BodyKind, customKindId: Long?): MeasureDetailFragment = MeasureDetailFragment().apply {
            arguments = Bundle().apply {
                putInt(ARG_KIND, kind.code)
                customKindId?.let { putLong(ARG_CUSTOM, it) }
            }
        }
    }
}
