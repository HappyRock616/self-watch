/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.viewModels
import app.selfwatch.R
import app.selfwatch.graph
import app.selfwatch.model.health.SleepStage
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.Axis
import app.selfwatch.ui.charts.BarLayer
import app.selfwatch.ui.charts.ChartLayer
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.charts.LineLayer
import app.selfwatch.ui.charts.StageLayer
import app.selfwatch.ui.charts.StageSpan
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import java.time.ZoneId
import java.util.Locale

/**
 * Sleep detail (C11): the night's hypnogram with heart rate, HRV, breathing, SpO2 and skin temperature
 * overlaid (toggle chips), stage totals, and Week / Month views of sleep time, resting HR and HRV.
 */
class SleepDetailFragment : DetailFragment() {

    private val vm: SleepViewModel by viewModels { SleepViewModel.factory(requireContext().graph) }

    override fun title(): CharSequence = getString(R.string.sleep_title)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val segments = Views.segments(
            ctx,
            listOf(getString(R.string.range_night), getString(R.string.range_week), getString(R.string.range_month)),
        ) { vm.setRange(PeriodRange.entries[it]) }
        column.addView(segments, Views.matchWrap(ctx))
        val stepper = DayStepper(ctx, vm::previous, vm::next)
        column.addView(stepper.view, Views.matchWrap(ctx, 8))
        val chart = ChartCard(column, R.id.sleep_chart, getString(R.string.sleep_empty))
        val nightTiles = ChartScreens.tiles(
            column,
            listOf(getString(R.string.stage_deep), getString(R.string.stage_light), getString(R.string.stage_rem), getString(R.string.stage_awake)),
        )
        val moreTiles = ChartScreens.tiles(
            column,
            listOf(getString(R.string.sleep_asleep), getString(R.string.sleep_avg_hr), getString(R.string.body_hrv), getString(R.string.sleep_breathing)),
        )
        val nightRow = nightTiles.first().parentCard()

        collectWhileStarted(vm.state) { s ->
            Views.selectSegment(segments, s.range.ordinal)
            stepper.view.visibility = if (s.range == PeriodRange.SINGLE) View.VISIBLE else View.GONE
            nightRow.visibility = if (s.range == PeriodRange.SINGLE) View.VISIBLE else View.GONE
            if (s.range == PeriodRange.SINGLE) bindNight(s, stepper, chart, nightTiles, moreTiles) else bindPeriod(s, chart, moreTiles)
        }
    }

    private fun duration(min: Int?): String? =
        min?.let { Fmt.duration(it * 60L, Locale.getDefault(), FmtLabels.from(requireContext())) }

    private fun bindNight(s: SleepUiState, stepper: DayStepper, card: ChartCard, nightTiles: List<StatTile>, moreTiles: List<StatTile>) {
        val ctx = requireContext()
        val today = s.nightDay
        val dayText = if (!s.canGoNext) getString(R.string.sleep_last_night) else Fmt.day(ctx, today.yyyymmdd)
        stepper.bind(dayText, s.canGoNext)
        val n = s.night
        toolbar.subtitle = n?.let { getString(R.string.sleep_subtitle, dayText, duration(it.asleepMin) ?: "") }
        if (n == null) {
            card.bind(0, 0, emptyList(), "sleep_night", emptySet(), hasData = false)
            (nightTiles + moreTiles).forEach { ChartScreens.set(it, null) }
            return
        }
        val layers = ArrayList<ChartLayer>()
        layers += StageLayer("stages", getString(R.string.sleep_stages), ChartPalette.of(ctx).secondary, StageSpan.fromSamples(n.stages, n.endSec))
        if (n.hr.size > 0) {
            layers += LineLayer("hr", getString(R.string.series_hr), ChartScreens.series(ctx, 0), Axis.LEFT, n.hr.t, n.hr.v, unit = getString(R.string.unit_bpm))
        }
        if (n.hrv.size > 0) {
            layers += LineLayer("hrv", getString(R.string.body_hrv), ChartScreens.series(ctx, 1), Axis.RIGHT, n.hrv.t, n.hrv.v, gapSec = HRV_GAP_SEC, unit = getString(R.string.unit_ms))
        }
        if (n.breathing.size > 0) {
            layers += LineLayer("breathing", getString(R.string.series_breathing), ChartScreens.series(ctx, 2), Axis.HIDDEN, n.breathing.t, n.breathing.v, scale = 10, unit = getString(R.string.unit_br_min))
        }
        if (n.spo2.size > 0) {
            layers += LineLayer("spo2", getString(R.string.series_spo2), ChartScreens.series(ctx, 3), Axis.HIDDEN, n.spo2.t, n.spo2.v, gapSec = HRV_GAP_SEC, unit = getString(R.string.unit_percent))
        }
        if (n.skinTemp.size > 0) {
            layers += LineLayer("temp", getString(R.string.series_temp), ChartScreens.series(ctx, 4), Axis.HIDDEN, n.skinTemp.t, n.skinTemp.v, scale = 100, gapSec = HRV_GAP_SEC, unit = getString(R.string.unit_celsius_delta))
        }
        card.chart.zone = ZoneId.systemDefault()
        card.bind(n.startSec, n.endSec, layers, "sleep_night", setOf("spo2", "temp"), hasData = true)
        val stages = listOf(SleepStage.DEEP, SleepStage.LIGHT, SleepStage.REM, SleepStage.AWAKE)
        stages.forEachIndexed { i, st -> ChartScreens.set(nightTiles[i], duration(n.stageMin[st] ?: 0)) }
        ChartScreens.set(moreTiles[0], duration(n.asleepMin))
        moreTiles[0].label = getString(R.string.sleep_asleep)
        ChartScreens.set(moreTiles[1], n.hr.average()?.toString(), getString(R.string.unit_bpm))
        moreTiles[1].label = getString(R.string.sleep_avg_hr)
        ChartScreens.set(moreTiles[2], n.hrv.average()?.toString(), getString(R.string.unit_ms))
        ChartScreens.set(moreTiles[3], n.breathing.average()?.let { Fmt.number(it / 10.0, 1) }, getString(R.string.unit_br_min))
        moreTiles[3].label = getString(R.string.sleep_breathing)
    }

    private fun bindPeriod(s: SleepUiState, card: ChartCard, tiles: List<StatTile>) {
        val ctx = requireContext()
        val zone = ZoneId.systemDefault()
        val days = s.days
        toolbar.subtitle = getString(if (s.range == PeriodRange.WEEK) R.string.range_last_7 else R.string.range_last_30)
        val hasData = days.any { it.sleepMin != null || it.restingHr != null || it.hrvMs != null }
        if (days.isEmpty() || !hasData) {
            card.bind(0, 0, emptyList(), "sleep_period", emptySet(), hasData = false)
            tiles.forEach { ChartScreens.set(it, null) }
            return
        }
        val start = days.first().day.startSec(zone)
        val end = days.last().day.plusDays(1).startSec(zone)
        val withHr = days.filter { it.restingHr != null }
        val withHrv = days.filter { it.hrvMs != null }
        val layers = listOf<ChartLayer>(
            BarLayer("sleep", getString(R.string.sleep_time), ChartScreens.series(ctx, 1), Axis.LEFT, start, DAY_SEC, days.map { it.sleepMin ?: -1 }.toIntArray(), scale = 60, unit = getString(R.string.unit_hours)),
            LineLayer("rhr", getString(R.string.series_resting_hr), ChartScreens.series(ctx, 0), Axis.RIGHT, withHr.map { ChartScreens.noon(it.day, zone) }.toLongArray(), withHr.map { it.restingHr ?: 0 }.toIntArray(), gapSec = 2 * DAY_SEC, unit = getString(R.string.unit_bpm)),
            LineLayer("hrv", getString(R.string.body_hrv), ChartScreens.series(ctx, 2), Axis.HIDDEN, withHrv.map { ChartScreens.noon(it.day, zone) }.toLongArray(), withHrv.map { it.hrvMs ?: 0 }.toIntArray(), gapSec = 2 * DAY_SEC, unit = getString(R.string.unit_ms)),
        )
        card.bind(start, end, layers, "sleep_period", emptySet(), hasData = true)
        val sleeps = days.mapNotNull { it.sleepMin }
        ChartScreens.set(tiles[0], duration(sleeps.takeIf { it.isNotEmpty() }?.average()?.toInt()))
        tiles[0].label = getString(R.string.sleep_avg)
        ChartScreens.set(tiles[1], withHr.takeIf { it.isNotEmpty() }?.map { it.restingHr ?: 0 }?.average()?.toInt()?.toString(), getString(R.string.unit_bpm))
        tiles[1].label = getString(R.string.series_resting_hr)
        ChartScreens.set(tiles[2], withHrv.takeIf { it.isNotEmpty() }?.map { it.hrvMs ?: 0 }?.average()?.toInt()?.toString(), getString(R.string.unit_ms))
        ChartScreens.set(tiles[3], sleeps.size.toString())
        tiles[3].label = getString(R.string.sleep_nights)
    }

    private companion object {
        const val DAY_SEC = 86_400L
        const val HRV_GAP_SEC = 1_800L
    }
}
