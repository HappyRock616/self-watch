/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.selfwatch.activity

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.AppGraph
import app.selfwatch.R
import app.selfwatch.body.ChartCard
import app.selfwatch.body.ChartScreens
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.graph
import app.selfwatch.interop.db.AppFiles
import app.selfwatch.interop.db.DbExportSource
import app.selfwatch.model.health.TrackPoint
import app.selfwatch.model.health.Workout
import app.selfwatch.model.log.SwLog
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.DisplayUnits
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.Axis
import app.selfwatch.ui.charts.ChartLayer
import app.selfwatch.ui.charts.LineLayer
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import app.selfwatch.ui.format.Units
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.RangeSlider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import app.selfwatch.ui.R as UiR

/**
 * The trim screen's state.
 *
 * @property original the workout as recorded (before any trim).
 * @property full the whole recording; [window] the part kept.
 * @property preview [original] cut to [window] (what saving stores).
 * @property track the kept part's route and charts.
 * @property trimmed true when the workout is trimmed now (saved).
 */
data class TrimUiState(
    val original: Workout? = null,
    val full: TrimWindow = TrimWindow(0, 1),
    val window: TrimWindow = TrimWindow(0, 1),
    val preview: Workout? = null,
    val track: WorkoutTrack = WorkoutTrack(),
    val trimmed: Boolean = false,
    val units: Units = Units.METRIC,
    val loading: Boolean = true,
) {
    /** True when the window keeps the whole recording. */
    val isWhole: Boolean get() = WorkoutTrimMath.isWhole(window, full)
}

/**
 * Trim view model: loads the workout as recorded and its whole track (a stored trim is ignored, so the
 * ends can be moved back out), keeps the chosen window and works out the trimmed summary as it moves.
 */
class WorkoutTrimViewModel(
    private val db: SelfWatchDb,
    /** The workout trimmed. */
    val workoutId: Long,
    private val fullTrack: suspend (Workout) -> List<TrackPoint>,
    compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private data class Base(val original: Workout, val points: List<TrackPoint>, val full: TrimWindow, val trimmed: Boolean, val units: Units)

    private val base = MutableStateFlow<Base?>(null)
    private val window = MutableStateFlow<TrimWindow?>(null)

    /** Screen state. */
    val state: StateFlow<TrimUiState> = combine(base, window) { b, w -> b to w }
        .mapLatest { (b, w) ->
            if (b == null) return@mapLatest TrimUiState()
            val win = WorkoutTrimMath.clamp(w ?: b.full, b.full)
            val step = if (b.units == Units.METRIC) 1000.0 else Sports.METERS_PER_MILE
            TrimUiState(
                original = b.original,
                full = b.full,
                window = win,
                preview = WorkoutTrimMath.trim(b.original, b.points, win),
                track = WorkoutMath.build(WorkoutTrimMath.inside(b.points, win), step),
                trimmed = b.trimmed,
                units = b.units,
                loading = false,
            )
        }
        .flowOn(compute)
        .stateIn(viewModelScope, SharingStarted.Eagerly, TrimUiState())

    init {
        viewModelScope.launch {
            try {
                val current = db.workouts.get(workoutId) ?: return@launch
                val trim = db.workouts.trim(workoutId)
                val original = trim?.original ?: current
                val points = fullTrack(original)
                val full = WorkoutTrimMath.fullWindow(original, points)
                val units = DisplayUnits.observe(db).first()
                window.value = trim?.let { TrimWindow(it.startSec, it.endSec) } ?: full
                base.value = Base(original, points, full, trim != null, units)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                SwLog.e(TAG, "trim load failed", e)
            }
        }
    }

    /** Keeps [startSec]..[endSec] of the recording. */
    fun setWindow(startSec: Long, endSec: Long) {
        window.value = TrimWindow(startSec, endSec)
    }

    /** Back to the whole recording (not saved until [save]). */
    fun reset() {
        window.value = base.value?.full
    }

    /**
     * Saves the window: the workout row takes the trimmed summary (the original stays stored). Keeping
     * the whole recording undoes a stored trim. Returns false when nothing could be saved.
     */
    suspend fun save(): Boolean {
        val b = base.value ?: return false
        val w = WorkoutTrimMath.clamp(window.value ?: b.full, b.full)
        if (WorkoutTrimMath.isWhole(w, b.full)) return if (b.trimmed) db.workouts.undoTrim(workoutId) else true
        return try {
            db.workouts.saveTrim(WorkoutTrimMath.trim(b.original, b.points, w), w.startSec, w.endSec)
        } catch (e: android.database.sqlite.SQLiteException) {
            // Another workout of the same device already starts at the new start time.
            SwLog.w(TAG, "trim not saved", e)
            false
        }
    }

    /** Factory. */
    companion object {
        private const val TAG = "WorkoutTrim"

        /** Builds the view model for workout [id] from [graph]. */
        fun factory(graph: AppGraph, ctx: Context, id: Long): ViewModelProvider.Factory = viewModelFactory {
            val source = DbExportSource(graph.db, AppFiles(ctx.applicationContext.filesDir))
            initializer { WorkoutTrimViewModel(graph.db, id, { source.fullTrack(it) }) }
        }
    }
}

/**
 * Trim a workout: a two-thumb slider over the whole recording cuts time off the start and the end (say
 * the watch kept recording on the drive home). The heart-rate and speed chart, the route and the summary
 * follow the thumbs. Saving changes the workout everywhere (cards, totals, trends, exports); the original
 * recording stays, so "Undo trim" on the workout screen (or Reset here) brings it back.
 */
class WorkoutTrimFragment : DetailFragment() {

    private val workoutId: Long by lazy { requireArguments().getLong(ARG_ID) }
    private val vm: WorkoutTrimViewModel by viewModels { WorkoutTrimViewModel.factory(requireContext().graph, requireContext(), workoutId) }

    override fun title(): CharSequence = getString(R.string.trim_title)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val card = Views.card(column, null, first = true)
        card.addView(Views.caption(ctx, getString(R.string.trim_intro)), Views.matchWrap(ctx))
        val slider = RangeSlider(ctx).apply {
            id = R.id.trim_slider
            contentDescription = getString(R.string.trim_slider_description)
        }
        card.addView(slider, Views.matchWrap(ctx, 8))
        val ends = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body)
        card.addView(ends, Views.matchWrap(ctx, 4))
        val cut = Views.caption(ctx)
        card.addView(cut, Views.matchWrap(ctx, 2))

        val tiles = ChartScreens.tiles(column, listOf(getString(R.string.workout_time), getString(R.string.workout_distance), getString(R.string.workout_avg_hr), getString(R.string.workout_kcal)))
        val routeCard = Views.card(column, getString(R.string.workout_route))
        val route = RouteView(ctx)
        routeCard.addView(route, Views.matchWrap(ctx))
        val charts = ChartCard(column, R.id.trim_chart, getString(R.string.workout_no_track))

        val buttons = Views.row(ctx)
        val reset = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = R.id.trim_reset
            text = getString(R.string.trim_reset)
            setOnClickListener {
                vm.reset()
                shownFull = null
            }
        }
        val save = MaterialButton(ctx).apply {
            id = R.id.trim_save
            text = getString(R.string.trim_save)
            setOnClickListener { save() }
        }
        buttons.addView(reset, Views.weighted(ctx))
        buttons.addView(save, Views.weighted(ctx, 8))
        column.addView(buttons, Views.matchWrap(ctx, 16))

        slider.addOnChangeListener { s, _, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val full = vm.state.value.full
            val v = s.values
            vm.setWindow(full.startSec + v[0].roundToInt(), full.startSec + v[1].roundToInt())
        }

        collectWhileStarted(vm.state) { s ->
            val original = s.original ?: return@collectWhileStarted
            val p = s.preview ?: original
            val labels = FmtLabels.from(ctx)
            val loc = Locale.getDefault()
            if (shownFull != s.full) {
                shownFull = s.full
                slider.valueFrom = 0f
                slider.valueTo = s.full.lengthSec.toFloat()
                slider.stepSize = 1f
                slider.setMinSeparationValue(minOf(WorkoutTrimMath.MIN_WINDOW_SEC, s.full.lengthSec).toFloat())
                slider.setLabelFormatter { v -> Fmt.time(ctx, s.full.startSec + v.roundToInt()) }
            }
            val want = listOf((s.window.startSec - s.full.startSec).toFloat(), (s.window.endSec - s.full.startSec).toFloat())
            if (slider.values != want) slider.values = want
            ends.text = getString(R.string.trim_ends, Fmt.time(ctx, s.window.startSec), Fmt.time(ctx, s.window.endSec))
            val cutSec = s.full.lengthSec - s.window.lengthSec
            cut.text = if (cutSec <= 0) getString(R.string.trim_nothing_cut) else getString(R.string.trim_cut, Fmt.duration(cutSec, loc, labels))
            ChartScreens.set(tiles[0], Fmt.duration(p.durationSec.toLong(), loc, labels))
            ChartScreens.set(tiles[1], p.distanceM?.takeIf { it > 0 }?.let { Fmt.distance(it.toDouble(), s.units, loc, labels) })
            ChartScreens.set(tiles[2], p.avgHr?.toString(), getString(R.string.unit_bpm))
            ChartScreens.set(tiles[3], p.kcal?.let { Fmt.number(it.toLong()) }, getString(UiR.string.sw_unit_kcal))
            routeCard.visibility = if (s.track.route.size >= 2) View.VISIBLE else View.GONE
            route.route = s.track.route
            route.markers = s.track.markers
            route.contentDescription = getString(R.string.trim_route_description)
            bindChart(charts, s)
            save.text = getString(if (s.isWhole && s.trimmed) R.string.trim_save_undo else R.string.trim_save)
            save.isEnabled = !(s.isWhole && !s.trimmed)
        }
    }

    private var shownFull: TrimWindow? = null

    private fun bindChart(card: ChartCard, s: TrimUiState) {
        val ctx = requireContext()
        val t = s.track
        val layers = ArrayList<ChartLayer>()
        if (t.hr.size > 0) {
            layers += LineLayer("hr", getString(R.string.series_hr), ChartScreens.series(ctx, 0), Axis.LEFT, t.hr.t, t.hr.v, unit = getString(R.string.unit_bpm), gapSec = GAP_SEC)
        }
        if (t.speed.size > 0) {
            val k = if (s.units == Units.METRIC) MMS_TO_KMH_X10 else MMS_TO_MPH_X10
            val v = IntArray(t.speed.size) { (t.speed.v[it] * k).roundToInt() }
            layers += LineLayer("speed", getString(R.string.workout_speed), ChartScreens.series(ctx, 1), Axis.RIGHT, t.speed.t, v, scale = 10, smooth = true, unit = getString(if (s.units == Units.METRIC) R.string.unit_kmh else R.string.unit_mph), gapSec = GAP_SEC)
        }
        card.bind(s.window.startSec, maxOf(s.window.endSec, s.window.startSec + 60), layers, "trim", emptySet(), hasData = layers.isNotEmpty())
    }

    private fun save() {
        val app = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = vm.save()
            Toast.makeText(app, if (ok) R.string.trim_saved else R.string.trim_failed, Toast.LENGTH_SHORT).show()
            if (ok) close()
        }
    }

    /** Factory. */
    companion object {
        private const val ARG_ID = "workout_id"
        private const val GAP_SEC = 120L
        private const val MMS_TO_KMH_X10 = 0.036
        private const val MMS_TO_MPH_X10 = 0.0223694

        /** The trim screen of workout [id]. */
        fun newInstance(id: Long): WorkoutTrimFragment = WorkoutTrimFragment().apply {
            arguments = Bundle().apply { putLong(ARG_ID, id) }
        }
    }
}
