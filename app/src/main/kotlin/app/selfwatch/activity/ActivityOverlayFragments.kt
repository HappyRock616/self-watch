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
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import app.selfwatch.R
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.graph
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.SystemClock
import app.selfwatch.model.time.TimeRange
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.Axis
import app.selfwatch.ui.charts.BandLayer
import app.selfwatch.ui.charts.BarLayer
import app.selfwatch.ui.charts.ChartLayer
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.charts.LayerValue
import app.selfwatch.ui.charts.LineLayer
import app.selfwatch.ui.charts.OverlayChartView
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.ToggleChipGroup
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * A detail screen with one overlay chart over [T]: the chart with toggle chips (every layer on by
 * default; the choice is remembered in settings by [layerStore]), a scrub read-out, a note on how the
 * series are smoothed, and an empty state.
 */
abstract class ActivityOverlayFragment<T : Any> : DetailFragment() {

    /** Where the hidden layers are remembered. */
    protected abstract val layerStore: OverlayLayers

    /** Tables that reload the chart. */
    protected abstract val tables: Set<Table>

    /** The chart's view id (keeps the zoom across configuration changes). */
    @get:IdRes
    protected abstract val chartId: Int

    /** The note under the chart. */
    @get:StringRes
    protected abstract val noteRes: Int

    /** The empty-state text. */
    @get:StringRes
    protected abstract val emptyRes: Int

    /** Reads the data (off the main thread). */
    protected abstract suspend fun loadData(db: SelfWatchDb, clock: Clock): T

    /** True when [data] has anything to draw. */
    protected abstract fun hasData(data: T): Boolean

    /** The time range of [data]. */
    protected abstract fun range(data: T, zone: ZoneId): TimeRange

    /** The layers of [data]. */
    protected abstract fun layers(ctx: Context, data: T, zone: ZoneId): List<ChartLayer>

    private class Loaded<T>(val data: T?, val hidden: Set<String>)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val db = ctx.graph.db
        val card = Views.card(column, null, first = true)
        val chart = OverlayChartView(ctx).apply { id = chartId }
        val chips = ToggleChipGroup(ctx)
        val readout = Views.caption(ctx, getString(R.string.chart_scrub_hint))
        val note = Views.caption(ctx, getString(noteRes))
        card.addView(chart, Views.matchWrap(ctx))
        card.addView(chips, Views.matchWrap(ctx, 8))
        card.addView(readout, Views.matchWrap(ctx, 4))
        card.addView(note, Views.matchWrap(ctx, 4))
        chart.onScrub = { t, values -> readout.text = readoutText(ctx, t, values) }
        val empty = EmptyState(ctx).apply { message = getString(emptyRes) }
        column.addView(empty, Views.matchWrap(ctx, 16))
        card.visibility = View.GONE
        empty.visibility = View.GONE
        var savedLayersApplied = false

        val flow: Flow<Loaded<T>> = db.changes.observe(tables).conflate().map {
            try {
                Loaded(loadData(db, SystemClock), layerStore.load(db))
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                SwLog.e(TAG, "overlay load failed", e)
                Loaded<T>(null, emptySet())
            }
        }.flowOn(Dispatchers.Default)

        collectWhileStarted(flow) { loaded ->
            val data = loaded.data ?: return@collectWhileStarted
            val has = hasData(data)
            card.visibility = if (has) View.VISIBLE else View.GONE
            empty.visibility = if (has) View.GONE else View.VISIBLE
            if (!has) return@collectWhileStarted
            if (!savedLayersApplied) {
                // The remembered choice, once per screen; after that the chips drive the chart.
                for (id in layerStore.toggleable) chart.setVisible(id, id !in loaded.hidden)
                savedLayersApplied = true
            }
            val zone = ZoneId.systemDefault()
            chart.zone = zone
            val range = range(data, zone)
            if (chart.range != range) chart.range = range
            chart.layers = layers(ctx, data, zone)
            chart.bindChips(chips)
            chips.onChange = { id, on, _ ->
                chart.setVisible(id, on)
                val hidden = layerStore.toggleable.filterNot { chart.isLayerVisible(it) }.toSet()
                viewLifecycleOwner.lifecycleScope.launch { layerStore.save(db, hidden) }
            }
        }
    }

    /** Helpers shared by the overlay screens. */
    companion object {
        private const val TAG = "ActivityOverlay"

        /** Day length used for buckets. */
        const val DAY_SEC: Long = 86_400L

        /** "Nov 20 · Training load 84 · HRV 52 ms" for the layers shown at [tSec]. */
        fun readoutText(ctx: Context, tSec: Long, values: List<LayerValue>): String {
            val locale = Locale.getDefault()
            val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd")
            val date = DateTimeFormatter.ofPattern(pattern, locale).format(Instant.ofEpochSecond(tSec).atZone(ZoneId.systemDefault()))
            val parts = values.filter { it.value != null }.joinToString(" · ") { "${it.label} ${it.text}" }
            return if (parts.isEmpty()) date else ctx.getString(R.string.chart_scrub_values, date, parts)
        }

        /** The color theme's color for series [index] ([ChartPalette.series]). */
        fun series(ctx: Context, index: Int): Int = ChartPalette.of(ctx).series(index)

        /** The neutral chart color (usual bands). */
        fun neutral(ctx: Context): Int = ChartPalette.of(ctx).muted

        /** Indices of [values] that hold data. */
        fun present(values: IntArray): List<Int> = values.indices.filter { values[it] != OverlayMath.MISSING }
    }
}

/**
 * Training and recovery (overlay O4), opened from the Training load card: daily training load and
 * intensity minutes as bars, with the next mornings' HRV (7-day average, inside the usual-HRV band) and
 * resting heart rate (7-day average), over the last 12 weeks ([TrainingOverlay]).
 */
class TrainingDetailFragment : ActivityOverlayFragment<TrainingOverlay>() {
    override val layerStore: OverlayLayers = OverlayLayers.TRAINING
    override val tables: Set<Table> = setOf(Table.WORKOUT, Table.METRIC, Table.ACTIVITY, Table.DAY_SUMMARY)
    override val chartId: Int = R.id.training_chart
    override val noteRes: Int = R.string.training_note
    override val emptyRes: Int = R.string.training_empty

    override fun title(): CharSequence = getString(R.string.training_title)

    override suspend fun loadData(db: SelfWatchDb, clock: Clock): TrainingOverlay = TrainingOverlay.load(db, clock)

    override fun hasData(data: TrainingOverlay): Boolean = data.hasData

    override fun range(data: TrainingOverlay, zone: ZoneId): TimeRange =
        TimeRange(data.firstDay.startSec(zone), data.firstDay.plusDays(data.size).startSec(zone))

    override fun layers(ctx: Context, data: TrainingOverlay, zone: ZoneId): List<ChartLayer> = layersOf(ctx, data, zone)

    /** Layer assembly, public for tests. */
    companion object {
        private const val INTENSITY_HEIGHT = 0.5f
        private const val MORNING_SEC = 7 * 3_600L

        /**
         * The O4 layers in chip order: load (left axis), intensity minutes (low bars on their own scale),
         * HRV and its usual band (right axis), resting heart rate (own scale). Layers without data are left out.
         */
        fun layersOf(ctx: Context, o: TrainingOverlay, zone: ZoneId): List<ChartLayer> {
            val out = ArrayList<ChartLayer>()
            val start = o.firstDay.startSec(zone)
            fun morning(i: Int): Long = o.firstDay.plusDays(i).startSec(zone) + MORNING_SEC
            if (o.load.any { it > 0 }) {
                out += BarLayer(
                    OverlayLayers.LOAD, ctx.getString(R.string.training_layer_load), series(ctx, 0), Axis.LEFT,
                    start, DAY_SEC, o.load,
                )
            }
            if (present(o.intensityMin).isNotEmpty()) {
                out += BarLayer(
                    OverlayLayers.INTENSITY, ctx.getString(R.string.training_layer_intensity), series(ctx, 1), Axis.HIDDEN,
                    start, DAY_SEC, o.intensityMin, unit = ctx.getString(R.string.unit_min), heightFraction = INTENSITY_HEIGHT,
                )
            }
            val hrv = present(o.hrv7)
            if (hrv.isNotEmpty()) {
                out += LineLayer(
                    OverlayLayers.HRV, ctx.getString(R.string.training_layer_hrv), series(ctx, 2), Axis.RIGHT,
                    LongArray(hrv.size) { morning(hrv[it]) }, IntArray(hrv.size) { o.hrv7[hrv[it]] },
                    smooth = true, gapSec = 2 * DAY_SEC, unit = ctx.getString(R.string.unit_ms),
                )
            }
            val band = o.hrvLow.indices.filter { o.hrvLow[it] != OverlayMath.MISSING && o.hrvHigh[it] != OverlayMath.MISSING }
            if (band.isNotEmpty() && hrv.isNotEmpty()) {
                out += BandLayer(
                    OverlayLayers.HRV_BAND, ctx.getString(R.string.training_layer_band), neutral(ctx), Axis.RIGHT,
                    LongArray(band.size) { morning(band[it]) }, IntArray(band.size) { o.hrvLow[band[it]] }, IntArray(band.size) { o.hrvHigh[band[it]] },
                    unit = ctx.getString(R.string.unit_ms),
                )
            }
            val rhr = present(o.restingHr7)
            if (rhr.isNotEmpty()) {
                out += LineLayer(
                    OverlayLayers.RESTING_HR, ctx.getString(R.string.training_layer_rhr), series(ctx, 3), Axis.HIDDEN,
                    LongArray(rhr.size) { morning(rhr[it]) }, IntArray(rhr.size) { o.restingHr7[rhr[it]] },
                    smooth = true, gapSec = 2 * DAY_SEC, unit = ctx.getString(R.string.unit_bpm),
                )
            }
            return out
        }
    }
}

/**
 * Exercise and sleep (overlay O5), opened from the Intensity minutes card: weekly active minutes as bars
 * and the weekly average night's sleep as a line, over the last 26 weeks ([ExerciseSleepOverlay]).
 */
class ExerciseSleepDetailFragment : ActivityOverlayFragment<ExerciseSleepOverlay>() {
    override val layerStore: OverlayLayers = OverlayLayers.EXERCISE_SLEEP
    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY)
    override val chartId: Int = R.id.exercise_sleep_chart
    override val noteRes: Int = R.string.exercise_sleep_note
    override val emptyRes: Int = R.string.exercise_sleep_empty

    override fun title(): CharSequence = getString(R.string.exercise_sleep_title)

    override suspend fun loadData(db: SelfWatchDb, clock: Clock): ExerciseSleepOverlay = ExerciseSleepOverlay.load(db, clock)

    override fun hasData(data: ExerciseSleepOverlay): Boolean = data.hasData

    override fun range(data: ExerciseSleepOverlay, zone: ZoneId): TimeRange =
        TimeRange(data.firstWeek.startSec(zone), data.firstWeek.plusDays(data.size * ActivityWeek.DAYS).startSec(zone))

    override fun layers(ctx: Context, data: ExerciseSleepOverlay, zone: ZoneId): List<ChartLayer> = layersOf(ctx, data, zone)

    /** Layer assembly, public for tests. */
    companion object {
        private const val WEEK_SEC = 7 * DAY_SEC
        private const val MIN_PER_HOUR = 60

        /** The O5 layers in chip order: exercise minutes (left axis), sleep in hours (right axis). */
        fun layersOf(ctx: Context, o: ExerciseSleepOverlay, zone: ZoneId): List<ChartLayer> {
            val out = ArrayList<ChartLayer>()
            val start = o.firstWeek.startSec(zone)
            if (present(o.exerciseMin).isNotEmpty()) {
                out += BarLayer(
                    OverlayLayers.EXERCISE, ctx.getString(R.string.exercise_sleep_layer_exercise), series(ctx, 0), Axis.LEFT,
                    start, WEEK_SEC, o.exerciseMin, unit = ctx.getString(R.string.unit_min),
                )
            }
            val sleep = present(o.sleepMin)
            if (sleep.isNotEmpty()) {
                out += LineLayer(
                    OverlayLayers.SLEEP, ctx.getString(R.string.exercise_sleep_layer_sleep), series(ctx, 1), Axis.RIGHT,
                    LongArray(sleep.size) { o.firstWeek.plusDays(sleep[it] * ActivityWeek.DAYS).startSec(zone) + WEEK_SEC / 2 },
                    IntArray(sleep.size) { o.sleepMin[sleep[it]] },
                    scale = MIN_PER_HOUR, smooth = true, gapSec = WEEK_SEC * 3 / 2, unit = ctx.getString(R.string.unit_hours),
                )
            }
            return out
        }
    }
}
