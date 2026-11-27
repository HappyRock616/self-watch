/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import app.selfwatch.R
import app.selfwatch.body.ChartCard
import app.selfwatch.body.ChartScreens
import app.selfwatch.graph
import app.selfwatch.interop.api.OutputSink
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.time.Day
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.Nav
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.Axis
import app.selfwatch.ui.charts.ChartLayer
import app.selfwatch.ui.charts.LineLayer
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import app.selfwatch.ui.format.Units
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import app.selfwatch.ui.R as UiR

/**
 * Workout detail (C12): summary tiles, the GPS route on a plain canvas with distance markers, heart
 * rate, speed and elevation charts, export as GPX (default), FIT or TCX through the Storage Access
 * Framework, trim (cut time off the start or end; undo keeps the original) and delete.
 */
class WorkoutDetailFragment : DetailFragment(R.menu.menu_workout) {

    private val workoutId: Long by lazy { requireArguments().getLong(ARG_ID) }
    private val vm: WorkoutViewModel by viewModels { WorkoutViewModel.factory(requireContext().graph, requireContext(), workoutId) }
    private var pendingFormat: String? = null
    private lateinit var createDoc: ActivityResultLauncher<ExportTarget>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingFormat = savedInstanceState?.getString(STATE_FORMAT)
        createDoc = registerForActivityResult(CreateDocumentFor()) { uri -> onDocument(uri) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_FORMAT, pendingFormat)
    }

    override fun title(): CharSequence = getString(R.string.workout_title)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val top = ChartScreens.tiles(column, listOf(getString(R.string.workout_distance), getString(R.string.workout_time), getString(R.string.workout_pace)), first = true)
        val trimNote = Views.caption(ctx).apply {
            id = R.id.workout_trim_note
            visibility = View.GONE
        }
        column.addView(trimNote, Views.matchWrap(ctx, 8))
        val bottom = ChartScreens.tiles(column, listOf(getString(R.string.workout_avg_hr), getString(R.string.workout_max_hr), getString(R.string.workout_ascent), getString(R.string.workout_kcal)))
        val routeCard = Views.card(column, getString(R.string.workout_route))
        val route = RouteView(ctx)
        routeCard.addView(route, Views.matchWrap(ctx))
        val routeNote = Views.caption(ctx)
        routeCard.addView(routeNote, Views.matchWrap(ctx, 4))
        val charts = ChartCard(column, R.id.workout_chart, getString(R.string.workout_no_track))

        collectWhileStarted(vm.state) { s ->
            if (s.gone) {
                close()
                return@collectWhileStarted
            }
            val w = s.workout ?: return@collectWhileStarted
            val labels = FmtLabels.from(ctx)
            val loc = Locale.getDefault()
            toolbar.title = getString(Sports.label(w.sport))
            toolbar.subtitle = getString(
                R.string.entry_when,
                Fmt.day(ctx, Day.of(w.startSec, ZoneId.systemDefault()).yyyymmdd),
                Fmt.time(ctx, w.startSec),
            )
            val trim = s.trim
            trimNote.visibility = if (trim == null) View.GONE else View.VISIBLE
            if (trim != null) {
                val cutSec = (trim.original.endSec - trim.original.startSec) - (w.endSec - w.startSec)
                trimNote.text = getString(R.string.workout_trimmed_note, Fmt.duration(cutSec.coerceAtLeast(0), loc, labels))
            }
            toolbar.menu.findItem(R.id.menu_undo_trim)?.isVisible = trim != null
            toolbar.menu.findItem(R.id.menu_export_fit)?.setTitle(if (trim != null) R.string.workout_export_fit_trimmed else R.string.workout_export_fit)
            ChartScreens.set(top[0], w.distanceM?.takeIf { it > 0 }?.let { Fmt.distance(it.toDouble(), s.units, loc, labels) })
            // Workout time is the timer time (as the watch's Timer); elapsed time, stops included, is noted when it differs.
            ChartScreens.set(top[1], Fmt.duration(w.durationSec.toLong(), loc, labels))
            val elapsed = w.endSec - w.startSec
            top[1].label = if (elapsed - w.durationSec >= MIN_STOP_SEC) {
                getString(R.string.workout_time_elapsed, Fmt.duration(elapsed, loc, labels))
            } else {
                getString(R.string.workout_time)
            }
            val pace = Sports.usesPace(w.sport)
            if (pace) {
                ChartScreens.set(top[2], Sports.pace(w.durationSec, w.distanceM, s.units), Sports.paceUnit(ctx, s.units))
                top[2].label = getString(R.string.workout_pace)
            } else {
                ChartScreens.set(top[2], Sports.speed(w.durationSec, w.distanceM, s.units), getString(if (s.units == Units.METRIC) R.string.unit_kmh else R.string.unit_mph))
                top[2].label = getString(R.string.workout_speed)
            }
            ChartScreens.set(bottom[0], w.avgHr?.toString(), getString(R.string.unit_bpm))
            ChartScreens.set(bottom[1], w.maxHr?.toString(), getString(R.string.unit_bpm))
            ChartScreens.set(bottom[2], w.ascentM?.let { elevation(it * 100, s.units).toString() }, elevationUnit(s.units))
            ChartScreens.set(bottom[3], w.kcal?.let { Fmt.number(it.toLong()) }, getString(UiR.string.sw_unit_kcal))

            val t = s.track
            routeCard.visibility = if (t.route.size >= 2) View.VISIBLE else View.GONE
            route.route = t.route
            route.markers = t.markers
            val unitName = getString(if (s.units == Units.METRIC) UiR.string.sw_unit_km else UiR.string.sw_unit_mi)
            routeNote.text = getString(R.string.workout_route_note, unitName)
            route.contentDescription = resources.getQuantityString(
                R.plurals.workout_route_description,
                t.markers.size,
                t.markers.size,
                Fmt.distance(WorkoutMath.lengthM(t.route), s.units, loc, labels),
                unitName,
            )
            bindCharts(charts, w.startSec, w.endSec, t, s.units)
        }
    }

    private fun elevation(cm: Int, units: Units): Int = if (units == Units.METRIC) (cm / 100.0).roundToInt() else (cm / CM_PER_FOOT).roundToInt()

    private fun elevationUnit(units: Units): String = getString(if (units == Units.METRIC) UiR.string.sw_unit_m else UiR.string.sw_unit_ft)

    private fun bindCharts(card: ChartCard, start: Long, end: Long, t: WorkoutTrack, units: Units) {
        val ctx = requireContext()
        val layers = ArrayList<ChartLayer>()
        if (t.hr.size > 0) {
            layers += LineLayer("hr", getString(R.string.series_hr), ChartScreens.series(ctx, 0), Axis.LEFT, t.hr.t, t.hr.v, unit = getString(R.string.unit_bpm), gapSec = GAP_SEC)
        }
        if (t.speed.size > 0) {
            // mm/s to tenths of km/h (× 0.036) or of mph (× 0.0223694).
            val k = if (units == Units.METRIC) MMS_TO_KMH_X10 else MMS_TO_MPH_X10
            val v = IntArray(t.speed.size) { (t.speed.v[it] * k).roundToInt() }
            layers += LineLayer("speed", getString(R.string.workout_speed), ChartScreens.series(ctx, 1), Axis.RIGHT, t.speed.t, v, scale = 10, smooth = true, unit = getString(if (units == Units.METRIC) R.string.unit_kmh else R.string.unit_mph), gapSec = GAP_SEC)
        }
        if (t.altitude.size > 0) {
            val v = IntArray(t.altitude.size) { elevation(t.altitude.v[it], units) }
            layers += LineLayer("elevation", getString(R.string.workout_elevation), ChartScreens.series(ctx, 2), Axis.HIDDEN, t.altitude.t, v, unit = elevationUnit(units), gapSec = GAP_SEC)
        }
        card.bind(start, maxOf(end, start + 60), layers, "workout", emptySet(), hasData = layers.isNotEmpty())
    }

    override fun onMenuItem(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_trim_workout -> {
            Nav.open(this, WorkoutTrimFragment.newInstance(workoutId))
            true
        }
        R.id.menu_undo_trim -> {
            val app = requireContext().applicationContext
            lifecycleScope.launch {
                if (vm.undoTrim()) Toast.makeText(app, R.string.workout_trim_undone, Toast.LENGTH_SHORT).show()
            }
            true
        }
        R.id.menu_export_gpx -> export("gpx")
        R.id.menu_export_fit -> export("fit")
        R.id.menu_export_tcx -> export("tcx")
        R.id.menu_delete_workout -> {
            confirmDelete()
            true
        }
        else -> false
    }

    private fun export(format: String): Boolean {
        val target = WorkoutExport.target(WorkoutExport.exporters(requireContext()), format, workoutId) ?: return true
        if (format == "fit" && vm.state.value.workout?.fitPath == null) {
            Toast.makeText(requireContext(), R.string.workout_no_fit, Toast.LENGTH_SHORT).show()
            return true
        }
        pendingFormat = format
        createDoc.launch(target)
        return true
    }

    private fun onDocument(uri: Uri?) {
        val format = pendingFormat ?: return
        pendingFormat = null
        if (uri == null) return
        val app = requireContext().applicationContext
        lifecycleScope.launch {
            val exporter = WorkoutExport.exporters(app).firstOrNull { it.id == format } ?: return@launch
            val msg = try {
                when (val r = exporter.export(WorkoutExport.request(workoutId), OutputSink.ofUri(app.contentResolver, uri, format))) {
                    is Outcome.Ok -> app.getString(R.string.workout_exported, format.uppercase(Locale.ROOT))
                    is Outcome.Fail -> app.getString(R.string.workout_export_failed, r.error.toString())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                SwLog.e(TAG, "workout export failed", e)
                app.getString(R.string.workout_export_failed, e.javaClass.simpleName)
            }
            Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.workout_delete_title)
            .setMessage(R.string.workout_delete_message)
            .setNegativeButton(R.string.measure_cancel, null)
            .setPositiveButton(R.string.measure_delete) { _, _ ->
                val app = requireContext().applicationContext
                lifecycleScope.launch {
                    if (vm.delete()) Toast.makeText(app, R.string.workout_deleted, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    /** Factory. */
    companion object {
        private const val TAG = "WorkoutDetail"
        private const val ARG_ID = "workout_id"
        private const val MIN_STOP_SEC = 60L
        private const val STATE_FORMAT = "pending_format"
        private const val GAP_SEC = 120L
        private const val CM_PER_FOOT = 30.48
        private const val MMS_TO_KMH_X10 = 0.036
        private const val MMS_TO_MPH_X10 = 0.0223694

        /** The detail of workout [id]. */
        fun newInstance(id: Long): WorkoutDetailFragment = WorkoutDetailFragment().apply {
            arguments = Bundle().apply { putLong(ARG_ID, id) }
        }
    }
}
