/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import app.selfwatch.R
import app.selfwatch.graph
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.TimeRange
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.Nav
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.Axis
import app.selfwatch.ui.charts.ChartLayer
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.charts.DotTrendLayer
import app.selfwatch.ui.charts.OverlayChartView
import app.selfwatch.ui.charts.ValueStyle
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.components.ToggleChipGroup
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.math.roundToInt
import app.selfwatch.ui.R as UiR

/**
 * All activities (from Recent activities): every workout, newest first, with a row of sport chips to
 * show one sport only ("All" first). With a sport picked, a trend dashboard for its recent workouts sits
 * above the list: one chart with a chip per metric (switched on and off like the other charts and
 * remembered per sport). The dashboard can be removed for a sport and brought back from the menu. Make
 * one with [newInstance] to open on a sport.
 */
class WorkoutListFragment : DetailFragment(R.menu.menu_workout_list) {

    private val initialSport: Int? by lazy { arguments?.getInt(ARG_SPORT, NO_SPORT)?.takeIf { it != NO_SPORT } }
    private val vm: WorkoutListViewModel by viewModels { WorkoutListViewModel.factory(requireContext().graph, requireContext(), initialSport) }

    /** The sport shown, or null for all. */
    private var sport: Int? = null
    private var dashboard: DashboardCard? = null

    override fun title(): CharSequence = getString(R.string.workout_list_title)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        sport = (savedInstanceState ?: arguments)?.getInt(ARG_SPORT, NO_SPORT)?.takeIf { it != NO_SPORT }
        vm.selectSport(sport)
        val chips = ChipGroup(ctx).apply {
            id = R.id.workout_list_sports
            isSingleSelection = true
            isSelectionRequired = true
        }
        column.addView(
            HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            },
            Views.matchWrap(ctx),
        )
        val dash = DashboardCard(column)
        dashboard = dash
        val card = Views.card(column, null)
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        card.addView(list, Views.matchWrap(ctx))
        val empty = EmptyState(ctx).apply { message = getString(R.string.activity_recent_empty) }
        column.addView(empty, Views.matchWrap(ctx, 16))
        var shownSports: List<Int>? = null
        collectWhileStarted(vm.state) { s ->
            val sports = s.workouts.map { it.sport }.distinct().sortedBy { getString(Sports.label(it)) }
            if (sports != shownSports) {
                shownSports = sports
                chips.removeAllViews()
                for (sp in listOf<Int?>(null) + sports) {
                    chips.addView(
                        Chip(ctx).apply {
                            id = View.generateViewId()
                            text = sp?.let { getString(Sports.label(it)) } ?: getString(R.string.workout_list_all)
                            isCheckable = true
                            isChecked = sp == sport
                            setOnClickListener {
                                sport = sp
                                vm.selectSport(sp)
                            }
                        },
                    )
                }
            }
            // Until the state catches up with a new pick, keep showing what was picked.
            if (s.sport != sport) return@collectWhileStarted
            render(list, card, empty, s)
            bindDashboard(dash, s)
            updateMenu(s)
        }
    }

    private fun render(list: LinearLayout, card: View, empty: View, s: WorkoutListUiState) {
        val ctx = requireContext()
        val shown = s.shown
        list.removeAllViews()
        for (w in shown) {
            list.addView(WorkoutSummaryRow.create(ctx, w, s.units) { Nav.open(this, WorkoutDetailFragment.newInstance(w.id)) }, Views.matchWrap(ctx))
        }
        card.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (shown.isEmpty() && !s.loading) View.VISIBLE else View.GONE
    }

    private fun bindDashboard(card: DashboardCard, s: WorkoutListUiState) {
        val sp = s.sport
        val d = s.dashboard
        if (sp == null || d == null || s.dashboardRemoved || s.shown.isEmpty()) {
            card.hide()
            return
        }
        card.bind(d, s.units, s.hidden, onSave = { hidden -> viewLifecycleOwner.lifecycleScope.launch { vm.saveHidden(sp, hidden) } }) {
            viewLifecycleOwner.lifecycleScope.launch { vm.setDashboardShown(sp, false) }
            Snackbar.make(requireView(), getString(R.string.sport_dashboard_removed, getString(Sports.label(sp))), Snackbar.LENGTH_LONG).show()
        }
    }

    private fun updateMenu(s: WorkoutListUiState) {
        val item = toolbar.menu.findItem(R.id.menu_show_sport_dashboard) ?: return
        val sp = s.sport
        item.isVisible = sp != null && s.dashboardRemoved
        if (sp != null) item.title = getString(R.string.sport_dashboard_show, getString(Sports.label(sp)))
    }

    override fun onMenuItem(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_show_sport_dashboard -> {
            sport?.let { sp -> viewLifecycleOwner.lifecycleScope.launch { vm.setDashboardShown(sp, true) } }
            true
        }
        else -> false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(ARG_SPORT, sport ?: NO_SPORT)
    }

    override fun onDestroyView() {
        dashboard = null
        super.onDestroyView()
    }

    /** Factory. */
    companion object {
        private const val ARG_SPORT = "sport"
        private const val NO_SPORT = -1

        /** All activities, showing [sport] only (null: all). */
        fun newInstance(sport: Int?): WorkoutListFragment = WorkoutListFragment().apply {
            arguments = Bundle().apply { putInt(ARG_SPORT, sport ?: NO_SPORT) }
        }
    }
}

/**
 * The per-sport trend dashboard: a chart over the sport's recent workouts (a dot per workout and a
 * 3-workout trend line per metric), a chip per metric the workouts have, a read-out of the workout under
 * the finger and a Remove action. Heart rate is on the left scale, distance on the right; the other metrics
 * each fit their own range and show their values in the read-out.
 */
private class DashboardCard(parent: LinearLayout) {
    private val ctx: Context = parent.context

    /** The card. */
    val card: SwCard = Views.card(parent, null).apply { id = R.id.sport_dashboard_card }
    private val caption: TextView = Views.caption(ctx)
    private val chart = OverlayChartView(ctx).apply { id = R.id.sport_dashboard_chart }
    private val chips = ToggleChipGroup(ctx)
    private val readout: TextView = Views.caption(ctx, ctx.getString(R.string.chart_scrub_hint))
    private val empty = EmptyState(ctx).apply { message = ctx.getString(R.string.sport_dashboard_empty) }
    private var appliedSport: Int? = null
    private var points: List<SportPoint> = emptyList()

    init {
        card.addView(caption, Views.matchWrap(ctx))
        card.addView(chart, Views.matchWrap(ctx, 8))
        card.addView(chips, Views.matchWrap(ctx, 8))
        card.addView(readout, Views.matchWrap(ctx, 4))
        card.addView(empty, Views.matchWrap(ctx, 8))
        card.visibility = View.GONE
        chart.onScrub = { t, values ->
            val w = points.minByOrNull { kotlin.math.abs(it.workout.startSec - t) }?.workout
            val day = w?.let { Fmt.day(ctx, Day.of(it.startSec, ZoneId.systemDefault()).yyyymmdd) } ?: Fmt.time(ctx, t)
            readout.text = ctx.getString(R.string.chart_scrub_values, day, values.joinToString(" · ") { "${it.label} ${it.text}" })
        }
    }

    fun hide() {
        card.visibility = View.GONE
    }

    fun bind(d: SportDashboard, units: Units, hidden: Set<String>, onSave: (Set<String>) -> Unit, onRemove: () -> Unit) {
        card.visibility = View.VISIBLE
        val name = ctx.getString(Sports.label(d.sport))
        card.title = ctx.getString(R.string.sport_dashboard_title, name)
        card.actionText = ctx.getString(R.string.sport_dashboard_remove)
        card.onAction = onRemove
        points = d.points
        val show = !d.tooFew
        chart.visibility = if (show) View.VISIBLE else View.GONE
        chips.visibility = chart.visibility
        readout.visibility = chart.visibility
        caption.visibility = chart.visibility
        empty.visibility = if (show) View.GONE else View.VISIBLE
        if (!show) return
        caption.text = ctx.getString(R.string.sport_dashboard_caption, Fmt.number(d.points.size.toLong()))
        if (appliedSport != d.sport) {
            for (id in SportMetric.IDS) chart.setVisible(id, id !in hidden)
            appliedSport = d.sport
        }
        val first = d.points.first().workout.startSec
        val last = d.points.last().workout.startSec
        val pad = maxOf(PAD_MIN_SEC, (last - first) / PAD_PARTS)
        chart.range = TimeRange(first - pad, last + pad)
        chart.layers = layers(d, units)
        chips.chartId = null
        chart.bindChips(chips)
        chips.onChange = { id, on, _ ->
            chart.setVisible(id, on)
            onSave(SportMetric.IDS.filterNot { chart.isLayerVisible(it) }.toSet())
        }
    }

    private fun layers(d: SportDashboard, units: Units): List<ChartLayer> {
        val palette = ChartPalette.of(ctx)
        val pace = Sports.usesPace(d.sport)
        val perUnit = if (units == Units.METRIC) 1000.0 else Sports.METERS_PER_MILE
        return d.metrics.map { m ->
            val withV = d.points.filter { m in it.values }
            val t = LongArray(withV.size) { withV[it].workout.startSec }
            val raw = IntArray(withV.size) { withV[it].values.getValue(m) }
            val (v, scale, unit) = display(m, raw, pace, perUnit, units)
            DotTrendLayer(
                id = m.id,
                label = label(m, pace),
                color = palette.series(m.ordinal),
                axis = when (m) {
                    SportMetric.AVG_HR, SportMetric.MAX_HR -> Axis.LEFT
                    SportMetric.DISTANCE -> Axis.RIGHT
                    else -> Axis.HIDDEN
                },
                points = t,
                values = v,
                trend = SportTrendMath.trend(v),
                scale = scale,
                gapSec = GAP_SEC,
                unit = unit,
                style = if (m == SportMetric.PACE && pace) ValueStyle.MIN_SEC else ValueStyle.NUMBER,
            )
        }
    }

    /** Raw values to shown values, scale and unit. */
    private fun display(m: SportMetric, raw: IntArray, pace: Boolean, perUnit: Double, units: Units): Triple<IntArray, Int, String> {
        val metric = units == Units.METRIC
        return when (m) {
            SportMetric.AVG_HR, SportMetric.MAX_HR, SportMetric.RECOVERY -> Triple(raw, 1, ctx.getString(R.string.unit_bpm))
            SportMetric.KCAL -> Triple(raw, 1, ctx.getString(UiR.string.sw_unit_kcal))
            SportMetric.DISTANCE -> Triple(IntArray(raw.size) { (raw[it] * 100 / perUnit).roundToInt() }, 100, ctx.getString(if (metric) UiR.string.sw_unit_km else UiR.string.sw_unit_mi))
            SportMetric.DURATION -> Triple(IntArray(raw.size) { (raw[it] / 60.0).roundToInt() }, 1, ctx.getString(R.string.unit_min))
            SportMetric.PACE -> if (pace) {
                // Whole seconds per km or mile, drawn as minutes (scale 60) and written m:ss.
                Triple(IntArray(raw.size) { SportTrendMath.paceSec(raw[it], perUnit) ?: 0 }, SEC_PER_MIN, Sports.paceUnit(ctx, units))
            } else {
                // mm/s to tenths of km/h (× 0.036) or of mph (× 0.0223694).
                Triple(IntArray(raw.size) { (raw[it] * if (metric) MMS_TO_KMH_X10 else MMS_TO_MPH_X10).roundToInt() }, 10, ctx.getString(if (metric) R.string.unit_kmh else R.string.unit_mph))
            }
            SportMetric.ASCENT -> if (metric) {
                Triple(raw, 1, ctx.getString(UiR.string.sw_unit_m))
            } else {
                Triple(IntArray(raw.size) { (raw[it] / M_PER_FOOT).roundToInt() }, 1, ctx.getString(UiR.string.sw_unit_ft))
            }
            SportMetric.CADENCE -> Triple(raw, 1, ctx.getString(if (pace) R.string.unit_spm else R.string.unit_rpm))
            SportMetric.VO2_MAX -> Triple(raw, 10, ctx.getString(R.string.unit_vo2_max))
        }
    }

    private fun label(m: SportMetric, pace: Boolean): String = ctx.getString(
        when (m) {
            SportMetric.AVG_HR -> R.string.sport_metric_avg_hr
            SportMetric.MAX_HR -> R.string.sport_metric_max_hr
            SportMetric.KCAL -> R.string.sport_metric_kcal
            SportMetric.DISTANCE -> R.string.sport_metric_distance
            SportMetric.DURATION -> R.string.sport_metric_duration
            SportMetric.PACE -> if (pace) R.string.sport_metric_pace else R.string.sport_metric_speed
            SportMetric.RECOVERY -> R.string.sport_metric_recovery
            SportMetric.ASCENT -> R.string.sport_metric_ascent
            SportMetric.CADENCE -> R.string.sport_metric_cadence
            SportMetric.VO2_MAX -> R.string.sport_metric_vo2_max
        },
    )

    private companion object {
        const val GAP_SEC = 400L * 86_400L
        const val SEC_PER_MIN = 60
        const val PAD_MIN_SEC = 86_400L
        const val PAD_PARTS = 20L
        const val MMS_TO_KMH_X10 = 0.036
        const val MMS_TO_MPH_X10 = 0.0223694
        const val M_PER_FOOT = 0.3048
    }
}
