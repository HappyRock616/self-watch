/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.diary

import app.selfwatch.interop.api.ColumnMapping
import app.selfwatch.interop.api.DiaryRow
import app.selfwatch.interop.api.ExerciseRow
import app.selfwatch.interop.api.ImportChoices
import app.selfwatch.interop.api.ImportData
import app.selfwatch.interop.api.ImportSink
import app.selfwatch.interop.io.ColumnMatcher
import app.selfwatch.interop.io.DateFormat
import app.selfwatch.interop.io.Numbers
import app.selfwatch.interop.io.Texts
import app.selfwatch.interop.io.Zips
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import java.time.ZoneId

/**
 * Imports MyFitnessPal's export zip (Premium): the nutrition summary (one row per meal per day), the
 * exercise summary and the measurement (progress) summary. Each meal row becomes one diary line named
 * "MyFitnessPal <meal>" with the nutrients as given. Headers are matched by alias because MyFitnessPal
 * does not publish them, so the preview matters. Id "mfp".
 */
class MfpImporter(sink: ImportSink = ImportSink.NONE, zone: ZoneId = ZoneId.systemDefault()) : CsvBundleImporter(sink, zone) {
    override val id: String = "mfp"
    override val preferredDate: DateFormat = DateFormat.ISO
    override val mainKind: String = NUTRITION

    override fun sniffSafe(name: String, head: ByteArray): Int {
        val lower = Texts.baseName(name).lowercase()
        if (Zips.isZip(head)) {
            val names = Zips.namesInHead(head).map { Texts.baseName(it).lowercase() }
            return if (names.any { it.startsWith("nutrition-summary") || it.startsWith("measurement-summary") || it.startsWith("exercise-summary") }) 90 else 0
        }
        val first = Texts.headText(head).lineSequence().firstOrNull().orEmpty().lowercase()
        return when {
            lower.startsWith("nutrition-summary") || lower.startsWith("measurement-summary") || lower.startsWith("exercise-summary") -> 90
            "meal" in first && "calories" in first && "date" in first && "food name" !in first && "icon" !in first -> 45
            else -> 0
        }
    }

    override fun classify(table: CsvTable): String? {
        val lower = Texts.baseName(table.name).lowercase()
        val h = table.header.map { ColumnMatcher.normalize(it) }.toSet()
        return when {
            lower.startsWith("nutrition") || ("meal" in h && "calories" in h) -> NUTRITION
            lower.startsWith("exercise") || "exercise" in h -> EXERCISE
            lower.startsWith("measurement") || lower.startsWith("progress") || "weight" in h || ("measurement" in h && "value" in h) -> MEASUREMENT
            else -> null
        }
    }

    override fun dateSamples(kind: String, table: CsvTable): List<String> {
        val m = table.columns(matcherFor(kind))
        return table.rows.mapNotNull { m[it, "date"] }
    }

    override fun weightSamples(tables: Map<String, List<CsvTable>>): Pair<String?, List<Double>>? {
        val t = tables[MEASUREMENT]?.firstOrNull() ?: return null
        val m = t.columns(MEASUREMENTS)
        if (m.has("weight")) return m.headerOf("weight") to t.rows.mapNotNull { Numbers.parseDecimal(m[it, "weight"], t.decimalComma) }
        val values = t.rows.filter { m[it, "measurement"]?.lowercase()?.startsWith("weight") == true }
            .mapNotNull { Numbers.parseDecimal(m[it, "value"], t.decimalComma) }
        return if (values.isEmpty()) null else null to values
    }

    override fun columnsOf(tables: Map<String, List<CsvTable>>): List<ColumnMapping> {
        val t = tables[NUTRITION]?.firstOrNull() ?: return emptyList()
        return t.columns(NUTRITION_COLUMNS).mappings(listOf("date", "meal", "time") + NUTRIENTS.keys)
    }

    override fun build(tables: Map<String, List<CsvTable>>, choices: ImportChoices, warnings: MutableList<String>): ImportData {
        val diary = ArrayList<DiaryRow>()
        val exercises = ArrayList<ExerciseRow>()
        val body = ArrayList<BodyMeasure>()
        for (t in tables[NUTRITION].orEmpty()) {
            val m = t.columns(NUTRITION_COLUMNS)
            for (r in t.rows) {
                val d = date(m[r, "date"], choices.dateFormat)
                if (d == null) {
                    warnings.add("${t.name}: row skipped (no date)")
                    continue
                }
                val meal = m[r, "meal"] ?: "Meal"
                diary.add(
                    DiaryRow(
                        day = d.day, meal = meal, secOfDay = TimeOfDay.parse(m[r, "time"]) ?: d.secOfDay,
                        name = "MyFitnessPal $meal", brand = null, amountX100 = 100, servingLabel = "meal", gramsX10 = null,
                        nutrients = NutrientColumns.read(m, r, t.decimalComma),
                    ),
                )
            }
        }
        for (t in tables[EXERCISE].orEmpty()) {
            val m = t.columns(EXERCISE_COLUMNS)
            for (r in t.rows) {
                val d = date(m[r, "date"], choices.dateFormat) ?: continue
                val name = m[r, "exercise"] ?: continue
                exercises.add(
                    ExerciseRow(
                        d.day, d.secOfDay, name,
                        Numbers.parseDecimal(m[r, "minutes"], t.decimalComma)?.let { Numbers.scaled(it * 60, 1) },
                        Numbers.parseDecimal(m[r, "kcal"], t.decimalComma)?.let { Numbers.scaled(kotlin.math.abs(it), 1) },
                    ),
                )
            }
        }
        for (t in tables[MEASUREMENT].orEmpty()) body.addAll(readMeasurements(t, choices, warnings))
        return ImportData(diary = diary, body = body, exercises = exercises)
    }

    private fun readMeasurements(t: CsvTable, choices: ImportChoices, warnings: MutableList<String>): List<BodyMeasure> {
        val m = t.columns(MEASUREMENTS)
        val out = ArrayList<BodyMeasure>()
        for (r in t.rows) {
            val d = date(m[r, "date"], choices.dateFormat) ?: continue
            val tSec = d.epochSec(zone)
            if (m.has("measurement") && m.has("value")) {
                // Long layout: Date, Measurement, Value.
                val kind = longKind(m[r, "measurement"])
                val v = Numbers.parseDecimal(m[r, "value"], t.decimalComma)
                if (kind == null || v == null) continue
                out.add(importedMeasure(tSec, kind, Numbers.scaled(toStoredUnit(kind, v, null, choices.weightUnit), 100)))
                continue
            }
            for ((field, kind) in WIDE_KINDS) {
                val v = Numbers.parseDecimal(m[r, field], t.decimalComma) ?: continue
                if (v <= 0) continue
                out.add(importedMeasure(tSec, kind, Numbers.scaled(toStoredUnit(kind, v, m.unit(field), choices.weightUnit), 100)))
            }
        }
        if (out.isEmpty() && t.rows.isNotEmpty()) warnings.add("${t.name}: no measurements recognized")
        return out
    }

    private fun matcherFor(kind: String): ColumnMatcher = when (kind) {
        NUTRITION -> NUTRITION_COLUMNS
        EXERCISE -> EXERCISE_COLUMNS
        else -> MEASUREMENTS
    }

    private companion object {
        const val NUTRITION = "nutrition"
        const val EXERCISE = "exercise"
        const val MEASUREMENT = "measurement"

        /** MyFitnessPal writes vitamin A, vitamin C, calcium and iron as % of the daily value, not amounts: skip them. */
        val NUTRIENTS: Map<String, List<String>> =
            NutrientColumns.ALIASES - setOf(NutrientColumns.VITA, NutrientColumns.VITC, NutrientColumns.CALCIUM, NutrientColumns.IRON)

        val NUTRITION_COLUMNS = ColumnMatcher(
            linkedMapOf("date" to listOf("date", "day"), "meal" to listOf("meal", "meal name"), "time" to listOf("time")) +
                NUTRIENTS,
        )

        val EXERCISE_COLUMNS = ColumnMatcher(
            linkedMapOf(
                "date" to listOf("date", "day"),
                "exercise" to listOf("exercise", "exercise name", "name"),
                "minutes" to listOf("exercise minutes", "minutes", "duration"),
                "kcal" to listOf("exercise calories", "calories burned", "calories"),
            ),
        )

        val MEASUREMENTS = ColumnMatcher(
            linkedMapOf(
                "date" to listOf("date", "day"),
                "weight" to listOf("weight", "body weight"),
                "bodyfat" to listOf("body fat %", "body fat", "body fat percentage"),
                "waist" to listOf("waist"),
                "hips" to listOf("hips"),
                "neck" to listOf("neck"),
                "chest" to listOf("chest"),
                "measurement" to listOf("measurement", "type"),
                "value" to listOf("value", "amount"),
            ),
        )

        val WIDE_KINDS = listOf(
            "weight" to BodyKind.WEIGHT, "bodyfat" to BodyKind.BODY_FAT, "waist" to BodyKind.WAIST,
            "hips" to BodyKind.HIPS, "neck" to BodyKind.NECK, "chest" to BodyKind.CHEST,
        )

        fun longKind(name: String?): BodyKind? {
            val n = name?.trim()?.lowercase() ?: return null
            return when {
                n.startsWith("weight") -> BodyKind.WEIGHT
                n.startsWith("body fat") -> BodyKind.BODY_FAT
                n.startsWith("waist") -> BodyKind.WAIST
                n.startsWith("hip") -> BodyKind.HIPS
                n.startsWith("neck") -> BodyKind.NECK
                n.startsWith("chest") -> BodyKind.CHEST
                else -> null
            }
        }
    }
}
