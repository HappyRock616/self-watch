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
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.R
import app.selfwatch.body.cards.BodySignalsCard
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.graph
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.SystemClock
import app.selfwatch.model.time.TimeRange
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.Axis
import app.selfwatch.ui.charts.BandLayer
import app.selfwatch.ui.charts.BarLayer
import app.selfwatch.ui.charts.ChartLayer
import app.selfwatch.ui.charts.DotTrendLayer
import app.selfwatch.ui.charts.LayerValue
import app.selfwatch.ui.charts.LineLayer
import app.selfwatch.ui.charts.OverlayChartView
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.components.ToggleChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import app.selfwatch.ui.R as UiR

/** An overlay's data (null until loaded or when loading failed) and the layers the user switched off. */
data class OverlayState<T>(val data: T? = null, val hidden: Set<String> = emptySet(), val loading: Boolean = true)

/**
 * Loads one overlay chart's data with [loader] (again whenever one of [tables] changes) and its remembered
 * layer choice ([choice]); [saveHidden] stores a new choice.
 */
class OverlayViewModel<T : Any>(
    private val db: SelfWatchDb,
    tables: Set<Table>,
    private val choice: LayerChoice,
    private val loader: suspend () -> T,
) : ViewModel() {

    /** Screen state. */
    val state: StateFlow<OverlayState<T>> = db.changes.observe(tables).conflate()
        .map {
            try {
                OverlayState(loader(), choice.load(db), loading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                SwLog.e(TAG, "overlay load failed", e)
                OverlayState<T>(null, emptySet(), loading = false)
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), OverlayState())

    /** Saves the hidden layer ids. */
    fun saveHidden(hidden: Set<String>) {
        viewModelScope.launch { choice.save(db, hidden) }
    }

    /** Factory. */
    companion object {
        private const val TAG = "OverlayViewModel"
        private const val STOP_MS = 5_000L

        /** A factory for an overlay loaded by [loader] from the database. */
        fun <T : Any> factory(db: SelfWatchDb, tables: Set<Table>, choice: LayerChoice, loader: suspend (SelfWatchDb) -> T): ViewModelProvider.Factory =
            viewModelFactory { initializer { OverlayViewModel(db, tables, choice, { loader(db) }) } }
    }
}

/**
 * A card with an overlay chart, its toggle chips (every layer on by default, the choice remembered in
 * settings), a read-out line for the finger, a caption, and an empty state shown instead when there is no
 * data.
 */
class OverlayCard(parent: LinearLayout, title: CharSequence?, chartId: Int, caption: CharSequence, emptyMessage: CharSequence) {
    private val ctx: Context = parent.context

    /** The card. */
    val card: SwCard = Views.card(parent, title)

    /** The chart. */
    val chart: OverlayChartView = OverlayChartView(ctx).apply { id = chartId }

    /** Its chips. */
    val chips: ToggleChipGroup = ToggleChipGroup(ctx)

    /** The read-out line. */
    val readout: TextView = Views.caption(ctx, ctx.getString(R.string.chart_scrub_hint))

    /** The empty state. */
    val empty: EmptyState = EmptyState(ctx).apply { message = emptyMessage }

    private var savedApplied = false

    init {
        card.addView(chart, Views.matchWrap(ctx))
        card.addView(chips, Views.matchWrap(ctx, 8))
        card.addView(readout, Views.matchWrap(ctx, 4))
        card.addView(Views.caption(ctx, caption), Views.matchWrap(ctx, 4))
        parent.addView(empty, Views.matchWrap(ctx, 16))
        card.visibility = View.GONE
        empty.visibility = View.GONE
    }

    /**
     * Shows [layers] over [range]. [hidden] is the remembered choice among [toggleable] (applied once per
     * screen; after that the chips drive the chart); a chip change calls [onSave] with the new hidden set.
     * [readoutText] turns a scrub into the read-out line. With [hasData] false the empty state shows.
     */
    fun bind(
        range: TimeRange,
        layers: List<ChartLayer>,
        hidden: Set<String>,
        toggleable: List<String>,
        hasData: Boolean,
        onSave: (Set<String>) -> Unit,
        readoutText: (Long, List<LayerValue>) -> String = { t, v -> OverlayText.readout(ctx, t, v) },
    ) {
        card.visibility = if (hasData) View.VISIBLE else View.GONE
        empty.visibility = if (hasData) View.GONE else View.VISIBLE
        if (!hasData) return
        if (!savedApplied) {
            for (id in toggleable) chart.setVisible(id, id !in hidden)
            savedApplied = true
        }
        chart.zone = ZoneId.systemDefault()
        if (chart.range != range) chart.range = range
        chart.layers = layers
        chart.bindChips(chips)
        chips.onChange = { id, on, _ ->
            chart.setVisible(id, on)
            onSave(toggleable.filterNot { chart.isLayerVisible(it) }.toSet())
        }
        chart.onScrub = { t, values -> readout.text = readoutText(t, values) }
    }
}

/** Shared text for the overlay charts. */
object OverlayText {
    /** "Nov 20" for [tSec]. */
    fun date(tSec: Long): String {
        val locale = Locale.getDefault()
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd")
        return DateTimeFormatter.ofPattern(pattern, locale).format(Instant.ofEpochSecond(tSec).atZone(ZoneId.systemDefault()))
    }

    /** "Nov 20 · Protein 1.4 · Strength sessions 2" for the layers with a value at [tSec]. */
    fun readout(ctx: Context, tSec: Long, values: List<LayerValue>): String {
        val parts = values.filter { it.value != null || it.low != null }.map { "${it.label} ${it.text}" }
        return if (parts.isEmpty()) date(tSec) else ctx.getString(R.string.card_line_pair, date(tSec), parts.joinToString(" · "))
    }
}

/** The layers of the O3, O6 and O7 charts. */
object BodyOverlayLayers {
    private const val DAY_SEC = 86_400L
    private const val WEEK_SEC = 7 * DAY_SEC
    private const val STRENGTH_HEIGHT = 0.3f

    /** O3: weekly protein g/kg with its 1.6 line (right axis) and strength sessions as low bars. */
    fun protein(ctx: Context, o: ProteinOverlay, zone: ZoneId): List<ChartLayer> {
        val out = ArrayList<ChartLayer>()
        val idx = (0 until o.weeks).filter { o.proteinPerKgX10[it] != ProteinOverlay.MISSING }
        val mid = { i: Int -> o.weekStart(i).startSec(zone) + WEEK_SEC / 2 }
        if (idx.isNotEmpty()) {
            val values = IntArray(idx.size) { o.proteinPerKgX10[idx[it]] }
            out += DotTrendLayer(
                BodyCompLayers.PROTEIN, ctx.getString(R.string.body_comp_layer_protein), ChartScreens.series(ctx, 1), Axis.RIGHT,
                LongArray(idx.size) { mid(idx[it]) }, values, trend = values, goal = ProteinOverlay.REFERENCE_X10,
                scale = 10, gapSec = WEEK_SEC * 2, unit = ctx.getString(R.string.unit_g_per_kg),
            )
        }
        if (o.strength.any { it > 0 }) {
            out += BarLayer(
                BodyCompLayers.STRENGTH, ctx.getString(R.string.body_comp_layer_strength), ChartScreens.series(ctx, 2), Axis.HIDDEN,
                o.firstDay.startSec(zone), WEEK_SEC, o.strength, heightFraction = STRENGTH_HEIGHT,
            )
        }
        return out
    }

    /** O6: weekly intensity minutes as bars (right axis) and the 4-week rolling resting heart rate (left axis). */
    fun heartTrend(ctx: Context, o: HeartTrendOverlay, zone: ZoneId): List<ChartLayer> {
        val out = ArrayList<ChartLayer>()
        if (o.intensityMin.any { it >= 0 }) {
            out += BarLayer(
                HeartTrendOverlay.INTENSITY, ctx.getString(R.string.heart_trend_layer_intensity), ChartScreens.series(ctx, 1), Axis.RIGHT,
                o.firstDay.startSec(zone), WEEK_SEC, o.intensityMin, unit = ctx.getString(R.string.unit_min),
            )
        }
        if (o.restBpm.isNotEmpty()) {
            out += LineLayer(
                HeartTrendOverlay.RESTING, ctx.getString(R.string.heart_trend_layer_resting), ChartScreens.series(ctx, 0), Axis.LEFT,
                LongArray(o.restDays.size) { ChartScreens.noon(o.restDays[it], zone) }, o.restBpm,
                smooth = true, gapSec = 3 * DAY_SEC, unit = ctx.getString(R.string.unit_bpm),
            )
        }
        return out
    }

    /** O7: each signal's distance from baseline (standard deviations, one shared axis) and the usual band. */
    fun signals(ctx: Context, o: SignalsOverlay, zone: ZoneId): List<ChartLayer> {
        val out = ArrayList<ChartLayer>()
        val points = LongArray(o.days.size) { ChartScreens.noon(o.days[it], zone) }
        val band = BodySignalsMath.Z_SCALE
        out += BandLayer(
            SignalsOverlay.BAND, ctx.getString(R.string.signals_layer_band), ChartScreens.neutral(ctx), Axis.LEFT,
            points, IntArray(points.size) { -band }, IntArray(points.size) { band }, scale = BodySignalsMath.Z_SCALE, gapSec = Long.MAX_VALUE / 4,
        )
        val colors = mapOf(
            SignalKind.SKIN_TEMP to 0,
            SignalKind.RESTING_HR to 1,
            SignalKind.RESPIRATION to 2,
        )
        for (kind in SignalKind.entries) {
            val z = o.z[kind] ?: continue
            val idx = z.indices.filter { z[it] != BodySignalsMath.MISSING }
            if (idx.isEmpty()) continue
            out += LineLayer(
                SignalsOverlay.layerId(kind), ctx.getString(BodySignalsCard.label(kind)), ChartScreens.series(ctx, colors.getValue(kind)), Axis.LEFT,
                LongArray(idx.size) { points[idx[it]] }, IntArray(idx.size) { z[idx[it]] },
                scale = BodySignalsMath.Z_SCALE, smooth = true, gapSec = 3 * DAY_SEC,
            )
        }
        return out
    }

    /** "Nov 20 · Skin temperature +0.3 °C · Resting heart rate −1 bpm" at [tSec], from the raw deviations. */
    fun signalsReadout(ctx: Context, o: SignalsOverlay, tSec: Long, visible: (String) -> Boolean): String {
        val day = Day.of(tSec, ZoneId.systemDefault())
        val i = o.days.indexOf(day)
        val parts = if (i < 0) {
            emptyList()
        } else {
            SignalKind.entries.mapNotNull { kind ->
                val d = o.deviation[kind]?.get(i)?.takeIf { it != BodySignalsMath.MISSING } ?: return@mapNotNull null
                if (!visible(SignalsOverlay.layerId(kind))) return@mapNotNull null
                ctx.getString(R.string.signals_row, ctx.getString(BodySignalsCard.label(kind)), BodySignalsCard.deviation(ctx, kind, d))
            }
        }
        val date = OverlayText.date(tSec)
        return if (parts.isEmpty()) date else ctx.getString(R.string.card_line_pair, date, parts.joinToString(" · "))
    }
}

/**
 * Body signals (O7): skin temperature change, resting heart rate and breathing rate over the last 60
 * nights, each as its distance from the user's own baseline, on one axis with the usual band; chips to
 * switch layers (remembered). Never diagnostic.
 */
class BodySignalsDetailFragment : DetailFragment() {

    private val vm: OverlayViewModel<SignalsOverlay> by viewModels {
        OverlayViewModel.factory(requireContext().graph.db, TABLES, SignalsOverlay.LAYERS) { db -> SignalsOverlay.load(db, SystemClock) }
    }

    override fun title(): CharSequence = getString(R.string.signals_title)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val card = OverlayCard(column, getString(R.string.signals_chart_title), R.id.body_signals_chart, getString(R.string.signals_caption), getString(R.string.signals_empty))
        collectWhileStarted(vm.state) { s ->
            val o = s.data
            if (s.loading) return@collectWhileStarted
            val zone = ZoneId.systemDefault()
            if (o == null || !o.hasData || o.days.isEmpty()) {
                card.bind(TimeRange(0, 0), emptyList(), s.hidden, SignalsOverlay.LAYERS.toggleable, hasData = false, onSave = vm::saveHidden)
                return@collectWhileStarted
            }
            val range = TimeRange(o.days.first().startSec(zone), o.days.last().plusDays(1).startSec(zone))
            card.bind(range, BodyOverlayLayers.signals(ctx, o, zone), s.hidden, SignalsOverlay.LAYERS.toggleable, hasData = true, onSave = vm::saveHidden) { t, _ ->
                BodyOverlayLayers.signalsReadout(ctx, o, t) { card.chart.isLayerVisible(it) }
            }
        }
    }

    private companion object {
        val TABLES: Set<Table> = setOf(Table.METRIC, Table.DAY_SUMMARY, Table.SLEEP_SESSION)
    }
}

/**
 * The body fat or waist chart with O3: the measurement's readings and smoothed trend (always on), weekly
 * protein g/kg with the 1.6 line and strength sessions, chips remembered in settings.
 */
class BodyCompChart(private val card: SwCard, private val chart: OverlayChartView, private val kind: BodyKind) {
    private val ctx: Context = card.context
    private val chips = ToggleChipGroup(ctx)
    private val readout = Views.caption(ctx, ctx.getString(R.string.chart_scrub_hint))
    private val caption = Views.caption(ctx, ctx.getString(R.string.body_comp_caption))
    private var savedApplied = false

    /** The latest overlay state. */
    var overlay: OverlayState<ProteinOverlay> = OverlayState()

    init {
        card.addView(chips, Views.matchWrap(ctx, 8))
        card.addView(readout, Views.matchWrap(ctx, 4))
        card.addView(caption, Views.matchWrap(ctx, 4))
        chart.onScrub = { t, values -> readout.text = OverlayText.readout(ctx, t, values) }
    }

    /** Draws [s]'s readings with the overlay; chip changes go to [onSave]. */
    fun render(s: MeasureDetailUiState, onSave: (Set<String>) -> Unit) {
        if (s.entries.isEmpty()) return
        val zone = ZoneId.systemDefault()
        val entries = s.entries.sortedBy { it.tSec }
        val toDisplay = { x100: Int -> (BodyUnits.toDisplay(kind, x100, s.units) * DISPLAY_SCALE).toInt() }
        val trend = WeightMath.trend(LongArray(entries.size) { entries[it].tSec }, IntArray(entries.size) { entries[it].valueX100 })
        val layers = ArrayList<ChartLayer>()
        layers += DotTrendLayer(
            BodyCompLayers.MEASURE, MeasureEditor.kindLabel(ctx, kind), ChartScreens.series(ctx, 0), Axis.LEFT,
            LongArray(entries.size) { entries[it].tSec }, IntArray(entries.size) { toDisplay(entries[it].valueX100) },
            trend = IntArray(trend.size) { toDisplay(Math.round(trend[it]).toInt()) },
            scale = DISPLAY_SCALE, gapSec = WEEK_SEC * 2, unit = BodyUnits.unit(kind, s.units), lockedOn = true,
        )
        val o = overlay.data
        if (o != null) layers += BodyOverlayLayers.protein(ctx, o, zone)
        var start = entries.first().tSec
        var end = entries.last().tSec + DAY_SEC
        if (o != null && o.hasData) {
            start = minOf(start, o.firstDay.startSec(zone))
            end = maxOf(end, o.weekStart(o.weeks).startSec(zone))
        }
        if (!savedApplied && !overlay.loading) {
            for (id in ProteinOverlay.LAYERS.toggleable) chart.setVisible(id, id !in overlay.hidden)
            savedApplied = true
        }
        chart.zone = zone
        val range = TimeRange(start, end)
        if (chart.range != range) chart.range = range
        chart.layers = layers
        chart.bindChips(chips)
        chips.onChange = { id, on, _ ->
            chart.setVisible(id, on)
            onSave(ProteinOverlay.LAYERS.toggleable.filterNot { chart.isLayerVisible(it) }.toSet())
        }
        caption.visibility = if (o?.hasData == true) View.VISIBLE else View.GONE
    }

    /** Constants. */
    companion object {
        /** Tables that reload the overlay. */
        val TABLES: Set<Table> = setOf(Table.DIARY, Table.BODY, Table.WORKOUT)

        private const val DISPLAY_SCALE = 100
        private const val DAY_SEC = 86_400L
        private const val WEEK_SEC = 7 * DAY_SEC
    }
}
