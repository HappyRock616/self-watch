/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.diary

import app.selfwatch.fit.encode.WeightFileWriter
import app.selfwatch.interop.api.DiaryRow
import app.selfwatch.interop.api.ExerciseRow
import app.selfwatch.interop.api.ExportRequest
import app.selfwatch.interop.api.ExportResult
import app.selfwatch.interop.api.ExportSource
import app.selfwatch.interop.api.Exporter
import app.selfwatch.interop.api.NoteRow
import app.selfwatch.interop.api.OutputSink
import app.selfwatch.interop.api.WeightUnit
import app.selfwatch.interop.io.CsvWriter
import app.selfwatch.interop.io.DateFormat
import app.selfwatch.interop.io.JsonBuild
import app.selfwatch.interop.io.Numbers
import app.selfwatch.interop.io.Zips
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Recipe
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.Workout
import app.selfwatch.model.json.Json
import app.selfwatch.model.json.JsonValue
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.time.Day
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId

/** Runs [block] on the IO dispatcher with [out] open, turning I/O errors into [SwError.Io]. */
internal suspend fun writeTo(out: OutputSink, block: (OutputStream) -> Unit): Outcome<Unit> = withContext(Dispatchers.IO) {
    try {
        out.open().use(block)
        Outcome.Ok(Unit)
    } catch (e: IOException) {
        Outcome.Fail(SwError.Io("export failed: ${e.javaClass.simpleName}"))
    }
}

/** The local date of [req]'s creation time, for file names. */
internal fun createdDate(req: ExportRequest): String = Instant.ofEpochSecond(req.createdSec).atZone(req.zone).toLocalDate().toString()

/**
 * Writes Garmin Connect's body CSV: the line `Body`, then `Date,Time,Weight,BMI,Fat`, one row per
 * weighing (BMI and body fat taken at the same time go on the same row). Dates `yyyy-MM-dd`, times
 * `HH:mm`, weight in the request's unit with one decimal. Id "garmin-body-csv".
 */
class GarminBodyCsvExporter(private val source: ExportSource) : Exporter {
    override val id: String = "garmin-body-csv"

    override fun mimeType(req: ExportRequest): String = "text/csv"

    override fun suggestedName(req: ExportRequest): String = "selfwatch-garmin-body-${createdDate(req)}.csv"

    override suspend fun export(req: ExportRequest, out: OutputSink): Outcome<ExportResult> {
        val body = source.body(req.days, req.zone)
        var rows = 0
        val r = writeTo(out) { rows = write(it, body, req.weightUnit, req.zone) }
        return if (r is Outcome.Fail) r else Outcome.Ok(ExportResult(mapOf("body" to rows)))
    }

    /** Writer, usable on its own. */
    companion object {
        /** Writes [body] to [out]; returns the number of rows (weighings). No byte order mark, as Garmin's sample has none. */
        fun write(out: OutputStream, body: List<BodyMeasure>, weightUnit: String, zone: ZoneId): Int {
            val w = CsvWriter(out, bom = false)
            w.row("Body")
            w.row("Date", "Time", "Weight", "BMI", "Fat")
            val byTime = body.groupBy { it.tSec }.toSortedMap()
            var rows = 0
            for ((t, ms) in byTime) {
                val weight = ms.firstOrNull { it.kind == BodyKind.WEIGHT } ?: continue
                val kg = weight.valueX100 / 100.0
                val shown = if (weightUnit == WeightUnit.LB) kg * WeightUnit.LB_PER_KG else kg
                val at = Instant.ofEpochSecond(t).atZone(zone)
                w.row(
                    DateFormat.ISO.format(Day.of(at.toLocalDate())),
                    TimeOfDay.format(at.toLocalTime().toSecondOfDay()),
                    Numbers.format(shown, 1),
                    ms.firstOrNull { it.kind == BodyKind.BMI }?.let { Numbers.formatScaled(it.valueX100, 100) }.orEmpty(),
                    ms.firstOrNull { it.kind == BodyKind.BODY_FAT }?.let { Numbers.formatScaled(it.valueX100, 100) }.orEmpty(),
                )
                rows++
            }
            w.flush()
            return rows
        }
    }
}

/** Writes a FIT weight file with [WeightFileWriter] for Garmin Connect's import. Id "fit-weight". */
class FitWeightExporter(private val source: ExportSource) : Exporter {
    override val id: String = "fit-weight"

    override fun mimeType(req: ExportRequest): String = "application/vnd.ant.fit"

    override fun suggestedName(req: ExportRequest): String = "selfwatch-weight-${createdDate(req)}.fit"

    override suspend fun export(req: ExportRequest, out: OutputSink): Outcome<ExportResult> {
        val body = source.body(req.days, req.zone).sortedBy { it.tSec }
        if (body.none { it.kind == BodyKind.WEIGHT }) return Outcome.Fail(SwError.BadInput("no weights in range"))
        val r = writeTo(out) { WeightFileWriter.write(it, body, req.createdSec) }
        return if (r is Outcome.Fail) r else Outcome.Ok(ExportResult(mapOf("body" to body.size)))
    }
}

/**
 * Writes one JSON file per area into a zip: `diary.json`, `exercises.json`, `notes.json`, `body.json`,
 * `workouts.json` (summaries), `recipes.json` and `foods.json`. Each is a plain array of objects with
 * field names as in core-model. Id "json".
 */
class JsonExporter(private val source: ExportSource) : Exporter {
    override val id: String = "json"

    override fun mimeType(req: ExportRequest): String = "application/zip"

    override fun suggestedName(req: ExportRequest): String = "selfwatch-json-${createdDate(req)}.zip"

    override suspend fun export(req: ExportRequest, out: OutputSink): Outcome<ExportResult> {
        val diary = source.diary(req.days)
        val exercises = source.exercises(req.days)
        val notes = source.notes(req.days)
        val body = source.body(req.days, req.zone)
        val workouts = source.workouts(req.days, emptyList(), req.zone)
        val recipes = source.recipes()
        val foods = source.customFoods()
        val r = writeTo(out) { stream ->
            Zips.write(stream) {
                fun put(name: String, items: List<JsonValue>) {
                    entry(name) { it.write(Json.write(JsonValue.Arr(items)).toByteArray(Charsets.UTF_8)) }
                }
                put("diary.json", diary.map { diaryJson(it) })
                put("exercises.json", exercises.map { exerciseJson(it) })
                put("notes.json", notes.map { JsonBuild.obj("day" to it.day.yyyymmdd, "text" to it.text) })
                put("body.json", body.map { bodyJson(it) })
                put("workouts.json", workouts.map { workoutJson(it) })
                put("recipes.json", recipes.map { recipeJson(it) })
                put("foods.json", foods.map { foodJson(it) })
            }
        }
        if (r is Outcome.Fail) return r
        return Outcome.Ok(
            ExportResult(
                mapOf(
                    "diary" to diary.size, "exercise" to exercises.size, "note" to notes.size, "body" to body.size,
                    "workout" to workouts.size, "recipe" to recipes.size, "food" to foods.size,
                ),
            ),
        )
    }

    /** JSON shapes, shared with tests. */
    companion object {
        /** Nutrient fields, nulls kept. */
        fun nutrientsJson(n: Nutrients): JsonValue = JsonBuild.obj(
            "kcalX10" to n.kcalX10, "proteinX10" to n.proteinX10, "fatX10" to n.fatX10, "satfatX10" to n.satfatX10,
            "carbX10" to n.carbX10, "sugarX10" to n.sugarX10, "fibreX10" to n.fibreX10, "sodiumMg" to n.sodiumMg,
            "cholesterolMg" to n.cholesterolMg,
        )

        /** A diary line. */
        fun diaryJson(d: DiaryRow): JsonValue = JsonBuild.obj(
            "day" to d.day.yyyymmdd, "meal" to d.meal, "secOfDay" to d.secOfDay, "name" to d.name, "brand" to d.brand,
            "amountX100" to d.amountX100, "servingLabel" to d.servingLabel, "gramsX10" to d.gramsX10,
            "nutrients" to nutrientsJson(d.nutrients),
        )

        /** An exercise. */
        fun exerciseJson(e: ExerciseRow): JsonValue = JsonBuild.obj(
            "day" to e.day.yyyymmdd, "secOfDay" to e.secOfDay, "name" to e.name, "durationSec" to e.durationSec, "kcal" to e.kcal,
        )

        /** A body measurement. */
        fun bodyJson(b: BodyMeasure): JsonValue = JsonBuild.obj(
            "id" to b.id, "tSec" to b.tSec, "kind" to b.kind.name, "customKindId" to b.customKindId,
            "valueX100" to b.valueX100, "unit" to b.kind.unit, "source" to b.source.name, "deviceId" to b.deviceId,
        )

        /** A workout summary (no route preview bytes). */
        fun workoutJson(w: Workout): JsonValue = JsonBuild.obj(
            "id" to w.id, "deviceId" to w.deviceId, "sport" to w.sport, "subSport" to w.subSport, "startSec" to w.startSec,
            "endSec" to w.endSec, "durationSec" to w.durationSec, "distanceM" to w.distanceM, "kcal" to w.kcal,
            "avgHr" to w.avgHr, "maxHr" to w.maxHr, "ascentM" to w.ascentM, "descentM" to w.descentM,
            "hasTrack" to w.hasTrack, "source" to w.source.name,
        )

        /** A recipe with its items. */
        fun recipeJson(r: Recipe): JsonValue = JsonBuild.obj(
            "id" to r.id, "kind" to r.kind.name, "name" to r.name, "servingsX10" to r.servingsX10,
            "servingLabel" to r.servingLabel, "prepMin" to r.prepMin, "cookMin" to r.cookMin, "steps" to r.steps,
            "notes" to r.notes, "sourceName" to r.sourceName, "sourceUrl" to r.sourceUrl,
            "statedPerServing" to r.statedPerServing?.let { nutrientsJson(it) }, "tags" to r.tags.sorted(),
            "items" to r.items.map {
                JsonBuild.obj(
                    "seq" to it.seq, "line" to it.line, "amountX100" to it.amountX100, "unit" to it.unit,
                    "gramsX10" to it.gramsX10, "match" to it.match.name,
                    "food" to it.food?.let { f -> JsonBuild.obj("origin" to f.origin.name, "id" to f.id) },
                )
            },
        )

        /** A custom food. */
        fun foodJson(f: Food): JsonValue = JsonBuild.obj(
            "id" to f.ref.id, "name" to f.name, "brand" to f.brand, "basis" to f.basis.name, "per" to nutrientsJson(f.per),
            "servings" to f.servings.map { JsonBuild.obj("seq" to it.seq, "label" to it.label, "gramsX10" to it.gramsX10) },
            "barcode" to f.barcode,
        )
    }
}
