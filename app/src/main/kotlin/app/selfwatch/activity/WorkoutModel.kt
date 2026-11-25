/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.AppGraph
import app.selfwatch.body.Series
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.db.dao.WorkoutTrim
import app.selfwatch.interop.Interop
import app.selfwatch.interop.api.ExportRequest
import app.selfwatch.interop.api.Exporter
import app.selfwatch.interop.db.AppFiles
import app.selfwatch.interop.db.DbExportSource
import app.selfwatch.model.health.TrackPoint
import app.selfwatch.model.health.Workout
import app.selfwatch.model.log.SwLog
import app.selfwatch.shell.DisplayUnits
import app.selfwatch.ui.format.Units
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One route point in degrees ×10⁷. */
data class GeoPoint(val latE7: Int, val lonE7: Int)

/**
 * A workout's decoded track for the detail screen.
 *
 * @property route positions in order.
 * @property markers indices into [route] where each whole kilometre (or mile) is passed.
 * @property speed speed in millimetres per second.
 * @property altitude altitude in centimetres.
 */
data class WorkoutTrack(
    val route: List<GeoPoint> = emptyList(),
    val markers: List<Int> = emptyList(),
    val hr: Series = Series.EMPTY,
    val speed: Series = Series.EMPTY,
    val altitude: Series = Series.EMPTY,
)

/** Pure track arithmetic. */
object WorkoutMath {
    private const val EARTH_RADIUS_M = 6_371_008.8
    private const val E7 = 1e7
    private const val SPEED_WINDOW = 9

    /** Centred moving average of [v] over [window] samples (GPS speed is noisy second to second). */
    fun smooth(v: IntArray, window: Int): IntArray {
        if (window <= 1 || v.size < 3) return v.copyOf()
        val half = window / 2
        return IntArray(v.size) { i ->
            val from = maxOf(0, i - half)
            val to = minOf(v.size - 1, i + half)
            var sum = 0L
            for (j in from..to) sum += v[j]
            (sum / (to - from + 1)).toInt()
        }
    }

    /** Great-circle distance between [a] and [b] in metres. */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val la1 = Math.toRadians(a.latE7 / E7)
        val la2 = Math.toRadians(b.latE7 / E7)
        val dLa = la2 - la1
        val dLo = Math.toRadians((b.lonE7 - a.lonE7) / E7)
        val h = sin(dLa / 2) * sin(dLa / 2) + cos(la1) * cos(la2) * sin(dLo / 2) * sin(dLo / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Indices of [route] where the running distance first reaches each multiple of [stepM]. */
    fun markers(route: List<GeoPoint>, stepM: Double): List<Int> {
        if (route.size < 2 || stepM <= 0) return emptyList()
        val out = ArrayList<Int>()
        var total = 0.0
        var next = stepM
        for (i in 1 until route.size) {
            total += distanceM(route[i - 1], route[i])
            while (total >= next) {
                out += i
                next += stepM
            }
        }
        return out
    }

    /** Total route length in metres. */
    fun lengthM(route: List<GeoPoint>): Double = (1 until route.size).sumOf { distanceM(route[it - 1], route[it]) }

    /** Builds the track from [points] with markers every [stepM] metres. */
    fun build(points: List<TrackPoint>, stepM: Double): WorkoutTrack {
        val route = points.mapNotNull { p -> if (p.latE7 != null && p.lonE7 != null) GeoPoint(p.latE7 ?: 0, p.lonE7 ?: 0) else null }
        fun series(sel: (TrackPoint) -> Int?): Series {
            val withV = points.filter { sel(it) != null }
            return Series(LongArray(withV.size) { withV[it].tSec }, IntArray(withV.size) { sel(withV[it]) ?: 0 })
        }
        val speed = series { it.speedMms }
        return WorkoutTrack(route, markers(route, stepM), series { it.hrBpm }, Series(speed.t, smooth(speed.v, SPEED_WINDOW)), series { it.altCm })
    }
}

/** Workout detail state. */
data class WorkoutUiState(
    val workout: Workout? = null,
    val track: WorkoutTrack = WorkoutTrack(),
    val units: Units = Units.METRIC,
    val loading: Boolean = true,
    /** The trim, when the workout is trimmed (its window and the values from before). */
    val trim: WorkoutTrim? = null,
    /** True once the workout no longer exists (deleted here or elsewhere). */
    val gone: Boolean = false,
)

/** Workout detail view model: the row, its decoded track, delete. */
class WorkoutViewModel(
    private val db: SelfWatchDb,
    private val filesDir: File,
    /** The workout shown. */
    val workoutId: Long,
    private val track: suspend (Workout) -> List<TrackPoint>,
) : ViewModel() {
    private val deleted = MutableStateFlow(false)

    /** Screen state. */
    val state: StateFlow<WorkoutUiState> = combine(
        db.changes.observe(setOf(Table.WORKOUT)).conflate(),
        DisplayUnits.observe(db),
        deleted,
    ) { _, u, d -> u to d }
        .conflate()
        .map { (u, d) ->
            try {
                val w = if (d) null else db.workouts.get(workoutId)
                if (w == null) {
                    WorkoutUiState(units = u, loading = false, gone = true)
                } else {
                    val step = if (u == Units.METRIC) 1000.0 else Sports.METERS_PER_MILE
                    WorkoutUiState(w, WorkoutMath.build(track(w), step), u, loading = false, trim = db.workouts.trim(workoutId))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                SwLog.e(TAG, "workout load failed", e)
                WorkoutUiState(units = u, loading = false)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), WorkoutUiState())

    /** Puts the workout back as recorded (the trim is removed). Returns false when it was not trimmed. */
    suspend fun undoTrim(): Boolean = db.workouts.undoTrim(workoutId)

    /** Deletes the workout row and its stored files. */
    suspend fun delete(): Boolean {
        val w = db.workouts.get(workoutId) ?: return false
        val ok = db.workouts.delete(workoutId)
        withContext(Dispatchers.IO) {
            val files = AppFiles(filesDir)
            for (p in listOfNotNull(w.fitPath, w.trackPath)) {
                val f = files.resolve(p)
                // Only files inside the app's own storage are ours to remove.
                if (f.canonicalPath.startsWith(filesDir.canonicalPath) && f.isFile && !f.delete()) SwLog.w(TAG, "could not delete a workout file")
            }
        }
        deleted.value = true
        return ok
    }

    /** Factory. */
    companion object {
        private const val TAG = "WorkoutViewModel"

        /** Builds the view model for workout [id] from [graph]. */
        fun factory(graph: AppGraph, ctx: Context, id: Long): ViewModelProvider.Factory = viewModelFactory {
            val app = ctx.applicationContext
            val source = DbExportSource(graph.db, AppFiles(app.filesDir))
            initializer { WorkoutViewModel(graph.db, app.filesDir, id) { source.track(it) } }
        }
    }
}

/**
 * Workout list state.
 *
 * @property sport the sport picked (null: all).
 * @property dashboard the picked sport's trend dashboard (null with no sport picked).
 * @property dashboardRemoved true when the user removed the picked sport's dashboard.
 * @property hidden the dashboard layers the user switched off for the picked sport.
 */
data class WorkoutListUiState(
    val units: Units = Units.METRIC,
    val workouts: List<Workout> = emptyList(),
    val loading: Boolean = true,
    val sport: Int? = null,
    val dashboard: SportDashboard? = null,
    val dashboardRemoved: Boolean = false,
    val hidden: Set<String> = emptySet(),
) {
    /** The workouts of the picked sport (all without one), newest first. */
    val shown: List<Workout> get() = workouts.filter { sport == null || it.sport == sport }
}

/** Every workout, newest first (up to [LIMIT]), and the picked sport's trend dashboard. */
class WorkoutListViewModel(
    private val db: SelfWatchDb,
    track: suspend (Workout) -> List<TrackPoint> = { emptyList() },
    initialSport: Int? = null,
) : ViewModel() {
    private val sport = MutableStateFlow(initialSport)
    private val dashboards = SportDashboardLoader(db, track)

    /** Screen state. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state: StateFlow<WorkoutListUiState> = combine(
        db.changes.observe(setOf(Table.WORKOUT, Table.SETTING)).conflate(),
        DisplayUnits.observe(db),
        sport,
    ) { _, u, s -> u to s }
        .conflate()
        .mapLatest { (u, s) ->
            val all = db.workouts.list(LIMIT)
            if (s == null) return@mapLatest WorkoutListUiState(u, all, loading = false)
            val dash = try {
                dashboards.load(s, all)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                SwLog.e(TAG, "dashboard load failed", e)
                null
            }
            WorkoutListUiState(
                u, all, loading = false, sport = s, dashboard = dash,
                dashboardRemoved = s in SportDashboardPrefs.removed(db.settings),
                hidden = SportDashboardPrefs.hidden(db.settings, s, dash?.metrics.orEmpty()),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), WorkoutListUiState(sport = initialSport))

    /** Shows [s] only (null: every sport). */
    fun selectSport(s: Int?) {
        sport.value = s
    }

    /** Saves the dashboard layers switched off for [s]. */
    suspend fun saveHidden(s: Int, hidden: Set<String>): Unit = SportDashboardPrefs.saveHidden(db.settings, s, hidden)

    /** Removes [s]'s dashboard, or with [shown] brings it back. */
    suspend fun setDashboardShown(s: Int, shown: Boolean): Unit = SportDashboardPrefs.setShown(db.settings, s, shown)

    /** Factory. */
    companion object {
        private const val TAG = "WorkoutList"

        /** Most workouts listed. */
        const val LIMIT: Int = 500

        /** Builds the view model from [graph], showing [sport] first (null: all). */
        fun factory(graph: AppGraph, ctx: Context, sport: Int?): ViewModelProvider.Factory = viewModelFactory {
            val source = DbExportSource(graph.db, AppFiles(ctx.applicationContext.filesDir))
            initializer { WorkoutListViewModel(graph.db, { source.track(it) }, sport) }
        }
    }
}

/** What a "create document" request asks for: MIME type and suggested file name. */
data class ExportTarget(val mime: String, val name: String)

/**
 * Storage Access Framework "create document" for a [ExportTarget] (the stock contract fixes the MIME
 * type at registration; workouts need GPX, FIT or TCX).
 */
class CreateDocumentFor : ActivityResultContract<ExportTarget, Uri?>() {
    override fun createIntent(context: Context, input: ExportTarget): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mime)
            .putExtra(Intent.EXTRA_TITLE, input.name)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/** Workout export: GPX (default), FIT or TCX through interop's exporters. */
object WorkoutExport {
    /** Format ids in menu order; GPX first (the default). */
    val FORMATS: List<String> = listOf("gpx", "fit", "tcx")

    /** The exporters for this process's database. */
    fun exporters(ctx: Context): List<Exporter> = Interop.exporters(DbExportSource(ctx))

    /** The export request for one workout. */
    fun request(workoutId: Long, zone: ZoneId = ZoneId.systemDefault()): ExportRequest =
        ExportRequest(workoutIds = listOf(workoutId), zone = zone)

    /** The "create document" target for [formatId] of [workoutId], or null if no exporter has that id. */
    fun target(exporters: List<Exporter>, formatId: String, workoutId: Long): ExportTarget? {
        val e = exporters.firstOrNull { it.id == formatId } ?: return null
        val req = request(workoutId)
        return ExportTarget(e.mimeType(req), e.suggestedName(req))
    }
}
