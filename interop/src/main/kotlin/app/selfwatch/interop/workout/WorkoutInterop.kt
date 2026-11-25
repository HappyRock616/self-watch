/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.workout

import app.selfwatch.fit.decode.WorkoutDecoder
import app.selfwatch.interop.api.BaseImporter
import app.selfwatch.interop.api.DateFormatGuess
import app.selfwatch.interop.api.ExportRequest
import app.selfwatch.interop.api.ExportResult
import app.selfwatch.interop.api.ExportSource
import app.selfwatch.interop.api.Exporter
import app.selfwatch.interop.api.ImportData
import app.selfwatch.interop.api.ImportPlan
import app.selfwatch.interop.api.ImportSink
import app.selfwatch.interop.api.InputSource
import app.selfwatch.interop.api.OutputSink
import app.selfwatch.interop.api.ParsedWorkout
import app.selfwatch.interop.api.TrackFormat
import app.selfwatch.interop.api.UnitGuess
import app.selfwatch.interop.api.guard
import app.selfwatch.interop.io.Texts
import app.selfwatch.interop.io.Zips
import app.selfwatch.model.health.TrackPoint
import app.selfwatch.model.health.Workout
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId

/** Parses one workout file of [format]. */
object WorkoutFiles {
    /** The workouts in [bytes] (a FIT, GPX or TCX file named [name]). */
    fun parse(format: TrackFormat, name: String, bytes: ByteArray): Outcome<List<ParsedWorkout>> = guard("WorkoutFiles") {
        when (format) {
            TrackFormat.GPX -> when (val r = GpxReader.read(name, bytes)) {
                is Outcome.Ok -> Outcome.Ok(listOf(r.value))
                is Outcome.Fail -> r
            }
            TrackFormat.TCX -> TcxReader.read(name, bytes)
            TrackFormat.FIT -> {
                when (val s = WorkoutDecoder.summary(ByteArrayInputStream(bytes), null)) {
                    is Outcome.Fail -> s
                    is Outcome.Ok -> {
                        val points = ArrayList<TrackPoint>()
                        when (val t = WorkoutDecoder.track(ByteArrayInputStream(bytes)) { points.add(it) }) {
                            is Outcome.Fail -> t
                            is Outcome.Ok -> Outcome.Ok(listOf(ParsedWorkout(s.value, points, TrackFormat.FIT, name, bytes)))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Imports workouts from FIT, GPX or TCX files ([format]), one file or a zip of them. The original
 * file is kept with each workout. Ids: "fit", "gpx", "tcx".
 */
class WorkoutFileImporter(private val format: TrackFormat, sink: ImportSink = ImportSink.NONE) : BaseImporter(sink) {
    override val id: String = format.extension

    override fun sniffSafe(name: String, head: ByteArray): Int {
        val ext = Texts.extension(name)
        if (Zips.isZip(head)) {
            val names = Zips.namesInHead(head)
            return if (names.isNotEmpty() && names.all { it.endsWith("/") || Texts.extension(it) == format.extension }) 60 else 0
        }
        return when (format) {
            TrackFormat.FIT -> {
                val magic = head.size >= 12 && head[8] == '.'.code.toByte() && head[9] == 'F'.code.toByte() &&
                    head[10] == 'I'.code.toByte() && head[11] == 'T'.code.toByte()
                when {
                    magic -> 95
                    ext == "fit" -> 40
                    else -> 0
                }
            }
            TrackFormat.GPX -> {
                val text = Texts.headText(head)
                when {
                    text.contains("<gpx") -> 95
                    ext == "gpx" -> 40
                    else -> 0
                }
            }
            TrackFormat.TCX -> {
                val text = Texts.headText(head)
                when {
                    text.contains("<TrainingCenterDatabase") -> 95
                    ext == "tcx" -> 40
                    else -> 0
                }
            }
        }
    }

    override fun parse(src: InputSource): Outcome<ImportPlan> {
        val bytes = src.open().use { Texts.readBounded(it) }
        val files = if (Zips.isZip(bytes)) {
            Zips.readAll(bytes).filter { Texts.extension(it.name) == format.extension }.map { it.name to it.bytes }
        } else {
            listOf(src.name to bytes)
        }
        val workouts = ArrayList<ParsedWorkout>()
        val warnings = ArrayList<String>()
        for ((name, data) in files) {
            when (val r = WorkoutFiles.parse(format, name, data)) {
                is Outcome.Ok -> workouts.addAll(r.value)
                is Outcome.Fail -> warnings.add("${Texts.baseName(name)}: skipped (${(r.error as? SwError.BadInput)?.detail ?: "unreadable"})")
            }
        }
        if (workouts.isEmpty()) return Outcome.Fail(SwError.BadInput(warnings.firstOrNull() ?: "no ${format.extension} files"))
        workouts.sortBy { it.workout.startSec }
        val data = ImportData(workouts = workouts)
        val preview = listOf(listOf("start", "sport", "duration_s", "distance_m", "points")) + workouts.take(20).map {
            listOf(
                Instant.ofEpochSecond(it.workout.startSec).toString(), Sports.gpxName(it.workout.sport),
                it.workout.durationSec.toString(), it.workout.distanceM?.toString().orEmpty(), it.points.size.toString(),
            )
        }
        return Outcome.Ok(
            ImportPlan(
                importerId = id, fileName = src.name, counts = data.counts(), preview = preview, columns = emptyList(),
                dateFormat = DateFormatGuess.NONE, units = UnitGuess.NONE, duplicates = 0, warnings = warnings,
                handle = { Outcome.Ok(data) },
            ),
        )
    }
}

/**
 * Exports workouts as GPX, TCX or the original FIT ([format]). One requested workout gives a single
 * file; a range (or several ids) gives a zip. In a GPX zip, workouts without a track are written as
 * TCX instead; in a FIT zip, workouts without a FIT file are skipped with a warning.
 * Ids: "gpx", "tcx", "fit".
 */
class WorkoutExporter(private val format: TrackFormat, private val source: ExportSource) : Exporter {
    override val id: String = format.extension

    private fun single(req: ExportRequest): Boolean = req.workoutIds.size == 1

    override fun mimeType(req: ExportRequest): String = if (!single(req)) "application/zip" else mime(format)

    override fun suggestedName(req: ExportRequest): String = if (single(req)) {
        "selfwatch-workout-${req.workoutIds[0]}.${format.extension}"
    } else {
        "selfwatch-${format.extension}-${Instant.ofEpochSecond(req.createdSec).atZone(req.zone).toLocalDate()}.zip"
    }

    override suspend fun export(req: ExportRequest, out: OutputSink): Outcome<ExportResult> {
        val workouts = source.workouts(req.days, req.workoutIds, req.zone)
        if (workouts.isEmpty()) return Outcome.Fail(SwError.BadInput("no workouts in range"))
        return withContext(Dispatchers.IO) {
            try {
                val warnings = ArrayList<String>()
                var written = 0
                out.open().use { stream ->
                    if (single(req)) {
                        val w = workouts[0]
                        if (format == TrackFormat.FIT) {
                            val fit = source.fitFile(w) ?: return@withContext Outcome.Fail(SwError.BadInput("workout has no FIT file"))
                            fit.use { it.copyTo(stream) }
                        } else {
                            writeTrack(format, w, source.track(w), req.zone, stream)
                        }
                        written = 1
                    } else {
                        // One workout at a time: its track is loaded, written as an entry, then dropped.
                        Zips.write(stream) {
                            for (w in workouts) {
                                val base = fileBase(w, req.zone)
                                if (format == TrackFormat.FIT) {
                                    val fit = source.fitFile(w)
                                    if (fit == null) {
                                        warnings.add("$base: no FIT file")
                                        continue
                                    }
                                    fit.use { input -> entry("$base.fit") { input.copyTo(it) } }
                                } else {
                                    val points = source.track(w)
                                    val hasPos = points.any { it.latE7 != null && it.lonE7 != null }
                                    val f = if (format == TrackFormat.GPX && !hasPos) TrackFormat.TCX else format
                                    entry("$base.${f.extension}") { writeTrack(f, w, points, req.zone, it) }
                                }
                                written++
                            }
                        }
                    }
                }
                Outcome.Ok(ExportResult(mapOf("workout" to written), warnings))
            } catch (e: java.io.IOException) {
                Outcome.Fail(SwError.Io("export failed: ${e.javaClass.simpleName}"))
            }
        }
    }

    /** Helpers shared with the auto-export job. */
    companion object {
        /** MIME type of a single file of [format]. */
        fun mime(format: TrackFormat): String = when (format) {
            TrackFormat.GPX -> "application/gpx+xml"
            TrackFormat.TCX -> "application/vnd.garmin.tcx+xml"
            TrackFormat.FIT -> "application/vnd.ant.fit"
        }

        /** `2026-05-01_0730_running` in [zone]. */
        fun fileBase(w: Workout, zone: ZoneId): String {
            val t = Instant.ofEpochSecond(w.startSec).atZone(zone)
            val hhmm = t.hour.toString().padStart(2, '0') + t.minute.toString().padStart(2, '0')
            return "${t.toLocalDate()}_${hhmm}_${Sports.gpxName(w.sport)}"
        }

        /** Track name used in GPX files: `Running 2026-05-01 07:30`. */
        fun trackName(w: Workout, zone: ZoneId): String {
            val t = Instant.ofEpochSecond(w.startSec).atZone(zone)
            val sport = Sports.gpxName(w.sport).replace('_', ' ').replaceFirstChar { it.uppercase() }
            return "$sport ${t.toLocalDate()} ${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
        }

        /** Writes [w] as GPX or TCX to [out]. */
        fun writeTrack(format: TrackFormat, w: Workout, points: List<TrackPoint>, zone: ZoneId, out: OutputStream) {
            when (format) {
                TrackFormat.GPX -> GpxWriter.write(out, w, points, trackName(w, zone))
                TrackFormat.TCX -> TcxWriter.write(out, w, points)
                TrackFormat.FIT -> error("FIT is copied, not written")
            }
        }
    }
}
