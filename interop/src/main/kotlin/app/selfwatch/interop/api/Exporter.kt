/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.api

import app.selfwatch.model.food.Food
import app.selfwatch.model.food.Recipe
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.TrackPoint
import app.selfwatch.model.health.Workout
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.time.DayRange
import java.io.InputStream
import java.time.ZoneId

/** Writes Self Watch data in another app's format. */
interface Exporter {
    /** Stable id, for example "gpx" or "cronometer-csv". */
    val id: String

    /** MIME type of the file written (for the SAF "create document" call). */
    fun mimeType(req: ExportRequest): String

    /** File name to offer the user, for example `selfwatch-gpx-2026-05-01.zip`. */
    fun suggestedName(req: ExportRequest): String

    /** Writes the export to [out]. */
    suspend fun export(req: ExportRequest, out: OutputSink): Outcome<ExportResult>
}

/**
 * What to export.
 *
 * @property days day range for diary, body and workout exports (null = everything).
 * @property workoutIds specific workouts; exactly one gives a single file instead of a zip.
 * @property weightUnit "kg" or "lb" where the format has a free unit (Garmin body CSV).
 * @property zone the user's time zone, for local dates and times.
 * @property createdSec "now" in UTC epoch seconds, stamped into files and names.
 */
data class ExportRequest(
    val days: DayRange? = null,
    val workoutIds: List<Long> = emptyList(),
    val weightUnit: String = WeightUnit.KG,
    val zone: ZoneId = ZoneId.systemDefault(),
    val createdSec: Long = System.currentTimeMillis() / 1000,
)

/** What an export wrote: items per area and any warnings (for example workouts without a FIT file). */
data class ExportResult(val counts: Map<String, Int>, val warnings: List<String> = emptyList())

/**
 * The data exporters read. The database module implements it; every method defaults to "nothing" so
 * tests implement only what they need. Ranges are inclusive local days in the request's zone; a null
 * range means everything.
 */
interface ExportSource {
    /** Workouts that start within [days], or the ones with [ids] when not empty, oldest first. */
    suspend fun workouts(days: DayRange?, ids: List<Long>, zone: ZoneId): List<Workout> = emptyList()

    /** The track of [workout] in time order (decoded from its FIT or GPX file). */
    suspend fun track(workout: Workout): List<TrackPoint> = emptyList()

    /** The original FIT file of [workout], or null if it has none. The caller closes the stream. */
    fun openFit(workout: Workout): InputStream? = null

    /**
     * The FIT file to export for [workout], or null if it has none: the original, or for a trimmed
     * workout one written from its trimmed track. The caller closes the stream.
     */
    suspend fun fitFile(workout: Workout): InputStream? = openFit(workout)

    /** Diary lines in [days]. */
    suspend fun diary(days: DayRange?): List<DiaryRow> = emptyList()

    /** Logged exercises in [days]. */
    suspend fun exercises(days: DayRange?): List<ExerciseRow> = emptyList()

    /** Body measurements taken within [days]. */
    suspend fun body(days: DayRange?, zone: ZoneId): List<BodyMeasure> = emptyList()

    /** Day notes in [days]. */
    suspend fun notes(days: DayRange?): List<NoteRow> = emptyList()

    /** The recipe book (recipes and saved meals). */
    suspend fun recipes(): List<Recipe> = emptyList()

    /** The user's custom foods. */
    suspend fun customFoods(): List<Food> = emptyList()
}
