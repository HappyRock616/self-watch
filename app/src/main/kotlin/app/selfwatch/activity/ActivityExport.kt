/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.AppGraph
import app.selfwatch.R
import app.selfwatch.interop.Interop
import app.selfwatch.interop.api.ExportRequest
import app.selfwatch.interop.api.ExportSource
import app.selfwatch.interop.api.Exporter
import app.selfwatch.interop.api.OutputSink
import app.selfwatch.interop.db.AppFiles
import app.selfwatch.interop.db.DbExportSource
import app.selfwatch.model.health.TrackPoint
import app.selfwatch.model.health.Workout
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/** The date ranges Export activities offers, in menu order. */
enum class ExportRange(@param:StringRes val label: Int) {
    LAST_7(R.string.export_act_last_7),
    LAST_30(R.string.export_act_last_30),
    LAST_90(R.string.export_act_last_90),
    THIS_YEAR(R.string.export_act_this_year),
    ALL(R.string.export_act_all),
    CUSTOM(R.string.export_act_custom),
}

/** Pure rules of the bulk workout export: which days a range covers, the request and the file name. */
object ActivityExportPlan {
    /** Every bulk export is a zip with one file per workout. */
    const val MIME: String = "application/zip"

    /** Days a custom range starts with: the last 30. */
    const val DEFAULT_CUSTOM_DAYS: Int = 30

    /**
     * The inclusive days [range] covers on [today]; null means every workout. A custom range uses
     * [from] and [to] (either missing falls back to the last 30 days; reversed ends are swapped).
     */
    fun days(range: ExportRange, today: Day, from: Day? = null, to: Day? = null): DayRange? = when (range) {
        ExportRange.LAST_7 -> last(today, 7)
        ExportRange.LAST_30 -> last(today, 30)
        ExportRange.LAST_90 -> last(today, 90)
        ExportRange.THIS_YEAR -> DayRange(Day(today.year * 10_000 + 101), today)
        ExportRange.ALL -> null
        ExportRange.CUSTOM -> {
            val a = from ?: today.plusDays(-(DEFAULT_CUSTOM_DAYS - 1))
            val b = to ?: today
            if (a <= b) DayRange(a, b) else DayRange(b, a)
        }
    }

    private fun last(today: Day, n: Int): DayRange = DayRange(today.plusDays(-(n - 1)), today)

    /** The request for [days] (no workout ids, so the exporter always writes a zip). */
    fun request(days: DayRange?, zone: ZoneId, nowSec: Long): ExportRequest =
        ExportRequest(days = days, workoutIds = emptyList(), zone = zone, createdSec = nowSec)

    /** `selfwatch-workouts-gpx-2026-10-25-2026-11-23.zip`, or `…-gpx-all.zip` for every workout. */
    fun fileName(format: String, days: DayRange?): String {
        val span = days?.let { "${it.first.toLocalDate()}-${it.last.toLocalDate()}" } ?: "all"
        return "selfwatch-workouts-$format-$span.zip"
    }
}

/**
 * An [ExportSource] that reports each workout as the exporter reads it ([track] or [openFit] is
 * called exactly once per workout), so the screen can show "Exporting 3 of 12".
 */
class ProgressExportSource(private val inner: ExportSource, private val onWorkout: (Int) -> Unit) : ExportSource by inner {
    private val count = AtomicInteger()

    override suspend fun track(workout: Workout): List<TrackPoint> = inner.track(workout).also { onWorkout(count.incrementAndGet()) }

    override fun openFit(workout: Workout): InputStream? = inner.openFit(workout).also { onWorkout(count.incrementAndGet()) }
}

/** How the last export ended. */
sealed interface ActivityExportResult {
    /** [written] workouts saved; [skipped] had no FIT file. */
    data class Ok(val written: Int, val skipped: Int) : ActivityExportResult

    /** FIT was chosen and no workout in the range has a FIT file. */
    data object NoFit : ActivityExportResult

    /** Writing failed; [detail] is short and holds no health values. */
    data class Failed(val detail: String) : ActivityExportResult
}

/**
 * Export activities state.
 *
 * @property total workouts in the range; null while counting.
 * @property withFit how many of them have the watch's FIT file.
 * @property done workouts read so far while [running].
 */
data class ActivityExportUi(
    val format: String = WorkoutExport.FORMATS[0],
    val range: ExportRange = ExportRange.LAST_30,
    val days: DayRange? = null,
    val total: Int? = null,
    val withFit: Int = 0,
    val running: Boolean = false,
    val done: Int = 0,
    val result: ActivityExportResult? = null,
) {
    /** True when there is something to export and nothing running. */
    val canExport: Boolean get() = !running && (total ?: 0) > 0 && !(format == "fit" && withFit == 0)
}

/** Export activities: picks format and range, counts the workouts, then writes the zip. */
class ActivityExportViewModel(
    private val source: ExportSource,
    private val clock: Clock = SystemClock,
    private val exportersFor: (ExportSource) -> List<Exporter> = { Interop.exporters(it) },
) : ViewModel() {

    private val _state = MutableStateFlow(ActivityExportUi())

    /** The screen state. */
    val state: StateFlow<ActivityExportUi> = _state.asStateFlow()

    private var custom: DayRange? = null
    private var countJob: Job? = null

    init {
        recount()
    }

    /** Picks the format ("gpx", "fit" or "tcx"). */
    fun setFormat(format: String) {
        if (format in WorkoutExport.FORMATS && format != _state.value.format) _state.update { it.copy(format = format, result = null) }
    }

    /** Picks a preset range (or [ExportRange.CUSTOM] with the current custom days). */
    fun setRange(range: ExportRange) {
        _state.update { it.copy(range = range, result = null) }
        recount()
    }

    /** Sets the custom range's ends and switches to it. */
    fun setCustom(from: Day, to: Day) {
        custom = ActivityExportPlan.days(ExportRange.CUSTOM, clock.today(), from, to)
        setRange(ExportRange.CUSTOM)
    }

    /** The custom range shown when Custom is picked. */
    fun customDays(): DayRange = custom ?: ActivityExportPlan.days(ExportRange.CUSTOM, clock.today()) ?: DayRange(clock.today(), clock.today())

    private fun recount() {
        val range = _state.value.range
        val days = ActivityExportPlan.days(range, clock.today(), custom?.first, custom?.last)
        _state.update { it.copy(days = days, total = null, withFit = 0) }
        countJob?.cancel()
        countJob = viewModelScope.launch {
            val list = try {
                source.workouts(days, emptyList(), clock.zone())
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                SwLog.e(TAG, "counting workouts failed", e)
                emptyList()
            }
            _state.update { it.copy(total = list.size, withFit = list.count { w -> w.fitPath != null }) }
        }
    }

    /** The file the export asks the user to create. */
    fun target(): ExportTarget = ExportTarget(ActivityExportPlan.MIME, ActivityExportPlan.fileName(_state.value.format, _state.value.days))

    /** Writes the zip to [sink]; [discard] removes the created file when nothing useful was written. */
    fun export(sink: OutputSink, discard: suspend () -> Unit = {}) {
        val s = _state.value
        if (s.running) return
        _state.update { it.copy(running = true, done = 0, result = null) }
        viewModelScope.launch {
            val counted = ProgressExportSource(source) { n -> _state.update { it.copy(done = n) } }
            val result = try {
                val exporter = exportersFor(counted).firstOrNull { it.id == s.format }
                if (exporter == null) {
                    ActivityExportResult.Failed(s.format)
                } else {
                    when (val r = exporter.export(ActivityExportPlan.request(s.days, clock.zone(), clock.nowSec()), sink)) {
                        is Outcome.Ok -> {
                            val written = r.value.counts["workout"] ?: 0
                            if (written == 0 && s.format == "fit") ActivityExportResult.NoFit else ActivityExportResult.Ok(written, r.value.warnings.size)
                        }
                        is Outcome.Fail -> ActivityExportResult.Failed(r.error.toString())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                SwLog.e(TAG, "activity export failed", e)
                ActivityExportResult.Failed(e.javaClass.simpleName)
            }
            if (result !is ActivityExportResult.Ok) discard()
            _state.update { it.copy(running = false, result = result) }
        }
    }

    /** Factory. */
    companion object {
        private const val TAG = "ActivityExport"

        /** Builds the view model from [graph] and the app's workout files. */
        fun factory(graph: AppGraph, filesDir: java.io.File): ViewModelProvider.Factory = viewModelFactory {
            initializer { ActivityExportViewModel(DbExportSource(graph.db, AppFiles(filesDir))) }
        }
    }
}
