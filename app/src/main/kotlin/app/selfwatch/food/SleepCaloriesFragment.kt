/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import app.selfwatch.R
import app.selfwatch.body.ChartScreens
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.graph
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.SystemClock
import app.selfwatch.model.time.TimeRange
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.Axis
import app.selfwatch.ui.charts.BarLayer
import app.selfwatch.ui.charts.ChartLayer
import app.selfwatch.ui.charts.LayerValue
import app.selfwatch.ui.charts.LineLayer
import app.selfwatch.ui.charts.OverlayChartView
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.ToggleChipGroup
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong
import app.selfwatch.ui.R as UiR

/** What the sleep and calories screen shows: the series, the weeks chosen and the hidden layers. */
data class SleepCaloriesState(val overlay: SleepCaloriesOverlay, val weeks: Int, val hidden: Set<String>)

/**
 * Sleep and calories (overlay O2): last night's sleep against the day's food over the last 4 or 8
 * weeks, in one [OverlayChartView] with toggle chips (both on by default, the choice kept in settings,
 * [SleepCaloriesLayers]), averages after short and longer nights, and a note on how much people differ.
 * Opened from the Food tab (the calories header's info and the logging days card).
 */
class SleepCaloriesFragment : DetailFragment() {

    private val weeks = MutableStateFlow(DEFAULT_WEEKS)

    override fun title(): CharSequence = getString(R.string.sleep_calories_title)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val db = ctx.graph.db
        savedInstanceState?.getInt(KEY_WEEKS)?.takeIf { it in WEEK_CHOICES }?.let { weeks.value = it }
        val segments = Views.segments(ctx, listOf(getString(R.string.sleep_calories_range_4w), getString(R.string.sleep_calories_range_8w))) {
            weeks.value = WEEK_CHOICES[it]
        }
        column.addView(segments, Views.matchWrap(ctx))
        val chartCard = Views.card(column, getString(R.string.sleep_calories_chart))
        val chart = OverlayChartView(ctx).apply { id = R.id.sleep_calories_chart }
        val chips = ToggleChipGroup(ctx)
        val readout = Views.caption(ctx, getString(R.string.chart_scrub_hint))
        chartCard.addView(chart, Views.matchWrap(ctx))
        chartCard.addView(chips, Views.matchWrap(ctx, 8))
        chartCard.addView(readout, Views.matchWrap(ctx, 4))
        chart.onScrub = { t, values -> readout.text = readoutText(ctx, t, values) }
        val empty = EmptyState(ctx).apply { message = getString(R.string.sleep_calories_empty) }
        column.addView(empty, Views.matchWrap(ctx, 16))
        val tiles = ChartScreens.tiles(column, listOf(getString(R.string.sleep_calories_short), getString(R.string.sleep_calories_long)))
        val compareHint = Views.caption(ctx, getString(R.string.sleep_calories_compare_hint))
        column.addView(compareHint, Views.matchWrap(ctx, 4))
        val noteCard = Views.card(column, null)
        noteCard.addView(Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, getString(R.string.sleep_calories_note)), Views.matchWrap(ctx))
        var hiddenApplied = false

        collectWhileStarted(states(db, SystemClock)) { s ->
            Views.selectSegment(segments, WEEK_CHOICES.indexOf(s.weeks).coerceAtLeast(0))
            val o = s.overlay
            val has = o.hasData
            chartCard.visibility = if (has) View.VISIBLE else View.GONE
            empty.visibility = if (has) View.GONE else View.VISIBLE
            val zone = ZoneId.systemDefault()
            if (has) {
                if (!hiddenApplied) {
                    for (id in SleepCaloriesLayers.ALL) chart.setVisible(id, id !in s.hidden)
                    hiddenApplied = true
                }
                chart.zone = zone
                val range = TimeRange(o.firstDay.startSec(zone), o.day(o.size).startSec(zone))
                if (chart.range != range) chart.range = range
                chart.layers = layers(ctx, o, zone)
                chart.bindChips(chips)
                chips.onChange = { id, on, _ ->
                    chart.setVisible(id, on)
                    val hidden = SleepCaloriesLayers.ALL.filterNot { chart.isLayerVisible(it) }.toSet()
                    viewLifecycleOwner.lifecycleScope.launch { SleepCaloriesLayers.save(db, hidden) }
                }
            }
            val c = o.compare()
            ChartScreens.set(tiles[0], c.shortKcal?.let { Fmt.number(it.toLong()) }, getString(R.string.unit_kcal))
            ChartScreens.set(tiles[1], c.longKcal?.let { Fmt.number(it.toLong()) }, getString(R.string.unit_kcal))
            compareHint.visibility = if (c.shortKcal == null || c.longKcal == null) View.VISIBLE else View.GONE
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_WEEKS, weeks.value)
    }

    private fun states(db: SelfWatchDb, clock: Clock): Flow<SleepCaloriesState> =
        combine(db.changes.observe(TABLES).conflate(), weeks) { _, w ->
            val today = clock.today()
            val overlay = SleepCaloriesOverlay.load(db, today.plusDays(1 - w * WEEK), today, clock.zone())
            SleepCaloriesState(overlay, w, SleepCaloriesLayers.load(db))
        }.flowOn(Dispatchers.IO)

    /** Builders shared with tests. */
    companion object {
        /** Tables that change the chart. */
        val TABLES: Set<Table> = setOf(Table.SLEEP_SESSION, Table.DIARY)

        /** Weeks offered, in segment order. */
        val WEEK_CHOICES: List<Int> = listOf(4, 8)

        private const val DEFAULT_WEEKS = 8
        private const val WEEK = 7
        private const val DAY_SEC = 86_400L
        private const val HOUR_X10_PER_MIN = 6.0
        private const val KEY_WEEKS = "weeks"

        /**
         * The chart layers for [o]: sleep in hours (a line, left axis, at the middle of the day it ended
         * in) and calories eaten (bars, right axis). A layer without any data is left out.
         */
        fun layers(ctx: Context, o: SleepCaloriesOverlay, zone: ZoneId): List<ChartLayer> {
            val out = ArrayList<ChartLayer>()
            val idx = o.sleepMin.indices.filter { o.sleepMin[it] != SleepCaloriesOverlay.MISSING }
            if (idx.isNotEmpty()) {
                out += LineLayer(
                    SleepCaloriesLayers.SLEEP, ctx.getString(R.string.sleep_calories_layer_sleep),
                    ChartScreens.series(ctx, 1), Axis.LEFT,
                    LongArray(idx.size) { ChartScreens.noon(o.day(idx[it]), zone) },
                    IntArray(idx.size) { Math.round(o.sleepMin[idx[it]] / HOUR_X10_PER_MIN).toInt() },
                    scale = 10, smooth = false, gapSec = 3 * DAY_SEC / 2, unit = ctx.getString(R.string.unit_hours),
                )
            }
            if (o.kcalIn.any { it != SleepCaloriesOverlay.MISSING }) {
                out += BarLayer(
                    SleepCaloriesLayers.KCAL, ctx.getString(R.string.sleep_calories_layer_kcal),
                    ChartScreens.series(ctx, 0), Axis.RIGHT,
                    o.firstDay.startSec(zone), DAY_SEC, o.kcalIn, unit = ctx.getString(R.string.unit_kcal),
                )
            }
            return out
        }

        private fun readoutText(ctx: Context, tSec: Long, values: List<LayerValue>): String {
            val locale = Locale.getDefault()
            val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd")
            val date = DateTimeFormatter.ofPattern(pattern, locale).format(Instant.ofEpochSecond(tSec).atZone(ZoneId.systemDefault()))
            val parts = values.sortedBy { SleepCaloriesLayers.ALL.indexOf(it.layerId) }.mapNotNull { v ->
                val x = v.value ?: return@mapNotNull null
                when (v.layerId) {
                    SleepCaloriesLayers.SLEEP -> ctx.getString(
                        R.string.sleep_calories_scrub_sleep,
                        Fmt.duration((x * SEC_PER_HOUR).roundToLong(), locale, FmtLabels.from(ctx)),
                    )
                    SleepCaloriesLayers.KCAL -> ctx.getString(R.string.sleep_calories_scrub_kcal, Fmt.number(x.roundToLong(), locale))
                    else -> v.text
                }
            }
            return if (parts.isEmpty()) date else ctx.getString(R.string.sleep_calories_scrub, date, parts.joinToString(" · "))
        }

        private const val SEC_PER_HOUR = 3_600.0
    }
}
