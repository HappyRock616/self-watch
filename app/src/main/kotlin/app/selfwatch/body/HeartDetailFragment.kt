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
import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.viewModels
import app.selfwatch.R
import app.selfwatch.db.Table
import app.selfwatch.graph
import app.selfwatch.model.time.SystemClock
import app.selfwatch.model.time.TimeRange
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.Axis
import app.selfwatch.ui.charts.BandLayer
import app.selfwatch.ui.charts.ChartLayer
import app.selfwatch.ui.charts.LineLayer
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import java.time.ZoneId
import app.selfwatch.ui.R as UiR

/**
 * Heart detail (C11): heart rate, resting heart rate, HRV and stress overlaid with toggle chips, for one
 * day or the last 7 or 30 days (daily heart-rate range as a band); below, weekly intensity minutes against
 * the 4-week rolling resting heart rate over about six months (O6).
 */
class HeartDetailFragment : DetailFragment() {

    private val vm: HeartViewModel by viewModels { HeartViewModel.factory(requireContext().graph) }

    private val trendVm: OverlayViewModel<HeartTrendOverlay> by viewModels {
        OverlayViewModel.factory(requireContext().graph.db, TREND_TABLES, HeartTrendOverlay.LAYERS) { db -> HeartTrendOverlay.load(db, SystemClock) }
    }

    override fun title(): CharSequence = getString(R.string.heart_title)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val segments = Views.segments(
            ctx,
            listOf(getString(R.string.range_day), getString(R.string.range_week), getString(R.string.range_month)),
        ) { vm.setRange(PeriodRange.entries[it]) }
        column.addView(segments, Views.matchWrap(ctx))
        val stepper = DayStepper(ctx, vm::previous, vm::next)
        column.addView(stepper.view, Views.matchWrap(ctx, 8))
        val chart = ChartCard(column, R.id.heart_chart, getString(R.string.heart_empty))
        val tiles = ChartScreens.tiles(
            column,
            listOf(getString(R.string.body_rest_hr), getString(R.string.heart_min), getString(R.string.heart_max), getString(R.string.heart_stress)),
        )
        // O6: weekly intensity minutes against the 4-week rolling resting heart rate, over about six months.
        val trend = OverlayCard(
            column, getString(R.string.heart_trend_title), R.id.heart_trend_chart,
            getString(R.string.heart_trend_caption), getString(R.string.heart_trend_empty),
        )
        collectWhileStarted(trendVm.state) { s -> bindTrend(trend, s) }
        collectWhileStarted(vm.state) { s ->
            Views.selectSegment(segments, s.range.ordinal)
            stepper.view.visibility = if (s.range == PeriodRange.SINGLE) View.VISIBLE else View.GONE
            if (s.range == PeriodRange.SINGLE) bindDay(s, stepper, chart, tiles) else bindPeriod(s, chart, tiles)
        }
    }

    private fun bindDay(s: HeartUiState, stepper: DayStepper, card: ChartCard, tiles: List<StatTile>) {
        val ctx = requireContext()
        val dayText = Fmt.day(ctx, s.day.yyyymmdd)
        stepper.bind(dayText, s.canGoNext)
        toolbar.subtitle = dayText
        HeartChart.bindDay(card, s)
        val bpm = getString(R.string.unit_bpm)
        ChartScreens.set(tiles[0], s.restingHr?.toString(), bpm)
        ChartScreens.set(tiles[1], s.hr.v.minOrNull()?.toString(), bpm)
        ChartScreens.set(tiles[2], s.hr.v.maxOrNull()?.toString(), bpm)
        ChartScreens.set(tiles[3], s.stress.average()?.toString())
    }

    private fun bindPeriod(s: HeartUiState, card: ChartCard, tiles: List<StatTile>) {
        val ctx = requireContext()
        val zone = ZoneId.systemDefault()
        toolbar.subtitle = getString(if (s.range == PeriodRange.WEEK) R.string.range_last_7 else R.string.range_last_30)
        val days = s.days
        if (days.isEmpty() || s.isEmpty) {
            card.bind(0, 0, emptyList(), "heart_period", emptySet(), hasData = false)
            tiles.forEach { ChartScreens.set(it, null) }
            return
        }
        val withRange = days.filter { it.minHr != null && it.maxHr != null }
        val withRest = days.filter { it.restingHr != null }
        val withHrv = days.filter { it.hrvMs != null }
        val withStress = days.filter { it.stress != null }
        fun pts(list: List<HeartDay>): LongArray = list.map { ChartScreens.noon(it.day, zone) }.toLongArray()
        val bpm = getString(R.string.unit_bpm)
        val layers = ArrayList<ChartLayer>()
        if (withRange.isNotEmpty()) {
            layers += BandLayer("range", getString(R.string.heart_range), ChartScreens.series(ctx, 0), Axis.LEFT, pts(withRange), withRange.map { it.minHr ?: 0 }.toIntArray(), withRange.map { it.maxHr ?: 0 }.toIntArray(), unit = bpm)
        }
        if (withRest.isNotEmpty()) {
            layers += LineLayer("rhr", getString(R.string.series_resting_hr), ChartScreens.series(ctx, 2), Axis.LEFT, pts(withRest), withRest.map { it.restingHr ?: 0 }.toIntArray(), gapSec = 2 * DAY_SEC, unit = bpm)
        }
        if (withHrv.isNotEmpty()) {
            layers += LineLayer("hrv", getString(R.string.body_hrv), ChartScreens.series(ctx, 1), Axis.RIGHT, pts(withHrv), withHrv.map { it.hrvMs ?: 0 }.toIntArray(), gapSec = 2 * DAY_SEC, unit = getString(R.string.unit_ms))
        }
        if (withStress.isNotEmpty()) {
            layers += LineLayer("stress", getString(R.string.heart_stress_series), ChartScreens.series(ctx, 3), Axis.HIDDEN, pts(withStress), withStress.map { it.stress ?: 0 }.toIntArray(), gapSec = 2 * DAY_SEC)
        }
        card.bind(days.first().day.startSec(zone), days.last().day.plusDays(1).startSec(zone), layers, "heart_period", setOf("stress"), hasData = true)
        ChartScreens.set(tiles[0], withRest.takeIf { it.isNotEmpty() }?.map { it.restingHr ?: 0 }?.average()?.toInt()?.toString(), bpm)
        ChartScreens.set(tiles[1], withRange.mapNotNull { it.minHr }.minOrNull()?.toString(), bpm)
        ChartScreens.set(tiles[2], withRange.mapNotNull { it.maxHr }.maxOrNull()?.toString(), bpm)
        ChartScreens.set(tiles[3], withStress.takeIf { it.isNotEmpty() }?.map { it.stress ?: 0 }?.average()?.toInt()?.toString())
    }

    private fun bindTrend(card: OverlayCard, s: OverlayState<HeartTrendOverlay>) {
        if (s.loading) return
        val o = s.data
        val zone = ZoneId.systemDefault()
        val toggleable = HeartTrendOverlay.LAYERS.toggleable
        if (o == null || !o.hasData) {
            card.bind(TimeRange(0, 0), emptyList(), s.hidden, toggleable, hasData = false, onSave = trendVm::saveHidden)
            return
        }
        val end = o.firstDay.plusDays(HeartTrendOverlay.WEEKS * 7).startSec(zone)
        val range = TimeRange(o.firstDay.startSec(zone), end)
        card.bind(range, BodyOverlayLayers.heartTrend(requireContext(), o, zone), s.hidden, toggleable, hasData = true, onSave = trendVm::saveHidden)
    }

    private companion object {
        const val DAY_SEC = 86_400L
        val TREND_TABLES: Set<Table> = setOf(Table.ACTIVITY, Table.METRIC, Table.DAY_SUMMARY)
    }
}

/**
 * The Heart chart's Day view: one day's layers and how they are shown, shared by the Heart screen and the
 * Heart chart card on the tabs (same chips, remembered under [DAY_CHIPS]).
 */
object HeartChart {
    /** Where the Day view's chips are remembered ([ChartCard.bind] chips key). */
    const val DAY_CHIPS: String = "heart_day"

    private const val GAP_SEC = 1_800L

    /** The Day view layers of [s] in chip order: heart rate (left axis), stress (right axis), HRV (own scale). */
    fun dayLayers(ctx: Context, s: HeartUiState): List<ChartLayer> {
        val layers = ArrayList<ChartLayer>()
        if (s.hr.size > 0) {
            layers += LineLayer("hr", ctx.getString(R.string.series_hr), ChartScreens.series(ctx, 0), Axis.LEFT, s.hr.t, s.hr.v, unit = ctx.getString(R.string.unit_bpm))
        }
        if (s.stress.size > 0) {
            layers += LineLayer("stress", ctx.getString(R.string.heart_stress_series), ChartScreens.series(ctx, 1), Axis.RIGHT, s.stress.t, s.stress.v, gapSec = GAP_SEC)
        }
        if (s.hrv.size > 0) {
            layers += LineLayer("hrv", ctx.getString(R.string.body_hrv), ChartScreens.series(ctx, 2), Axis.HIDDEN, s.hrv.t, s.hrv.v, gapSec = GAP_SEC, unit = ctx.getString(R.string.unit_ms))
        }
        return layers
    }

    /** The Day view's time range: the day, from the night before when HRV was measured then. */
    fun dayRange(s: HeartUiState, zone: ZoneId): TimeRange =
        TimeRange(minOf(s.day.startSec(zone), s.hrv.t.firstOrNull() ?: Long.MAX_VALUE), s.day.plusDays(1).startSec(zone))

    /** Shows the single day [s] in [card] (the empty state when it has nothing to draw). */
    fun bindDay(card: ChartCard, s: HeartUiState) {
        val r = dayRange(s, ZoneId.systemDefault())
        card.bind(r.startSec, r.endSec, dayLayers(card.chart.context, s), DAY_CHIPS, emptySet(), hasData = !s.isEmpty)
    }
}
