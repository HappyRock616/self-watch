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
 * Imports Lose It!'s account export (a zip from the website): the food log
 * (`Date,Name,Icon,Type,Quantity,Units,Calories,…`, US dates, the meal in `Type` or `Meal`), weights
 * and the exercise log. Rows marked deleted are skipped. Id "loseit".
 */
class LoseItImporter(sink: ImportSink = ImportSink.NONE, zone: ZoneId = ZoneId.systemDefault()) : CsvBundleImporter(sink, zone) {
    override val id: String = "loseit"
    override val preferredDate: DateFormat = DateFormat.US
    override val mainKind: String = FOOD

    override fun sniffSafe(name: String, head: ByteArray): Int {
        val lower = Texts.baseName(name).lowercase()
        if (Zips.isZip(head)) {
            val names = Zips.namesInHead(head).map { Texts.baseName(it).lowercase() }
            return if (names.any { it.startsWith("food-log") || it.startsWith("food_log") || it == "weights.csv" || it.startsWith("exercise-log") }) 85 else 0
        }
        val first = Texts.headText(head).lineSequence().firstOrNull().orEmpty().lowercase()
        return when {
            "icon" in first && "quantity" in first && "calories" in first -> 90
            lower.startsWith("food-log") || lower.startsWith("food_log") -> 70
            else -> 0
        }
    }

    override fun classify(table: CsvTable): String? {
        val lower = Texts.baseName(table.name).lowercase()
        val h = table.header.map { ColumnMatcher.normalize(it) }.toSet()
        return when {
            lower.startsWith("exercise") -> EXERCISE
            lower.startsWith("weight") || (h.contains("weight") && h.contains("date") && !h.contains("name")) -> WEIGHT
            "name" in h && "calories" in h && ("quantity" in h || "icon" in h) -> FOOD
            else -> null
        }
    }

    override fun dateSamples(kind: String, table: CsvTable): List<String> {
        val m = table.columns(if (kind == WEIGHT) WEIGHT_COLUMNS else FOOD_COLUMNS)
        return table.rows.mapNotNull { m[it, "date"] }
    }

    override fun weightSamples(tables: Map<String, List<CsvTable>>): Pair<String?, List<Double>>? {
        val t = tables[WEIGHT]?.firstOrNull() ?: return null
        val m = t.columns(WEIGHT_COLUMNS)
        return m.headerOf("weight") to t.rows.mapNotNull { Numbers.parseDecimal(m[it, "weight"], t.decimalComma) }
    }

    override fun columnsOf(tables: Map<String, List<CsvTable>>): List<ColumnMapping> {
        val t = tables[FOOD]?.firstOrNull() ?: return emptyList()
        return t.columns(FOOD_COLUMNS).mappings(listOf("date", "name", "meal", "quantity", "units", "deleted") + NutrientColumns.ALIASES.keys)
    }

    override fun build(tables: Map<String, List<CsvTable>>, choices: ImportChoices, warnings: MutableList<String>): ImportData {
        val diary = ArrayList<DiaryRow>()
        val exercises = ArrayList<ExerciseRow>()
        val body = ArrayList<BodyMeasure>()
        for (t in tables[FOOD].orEmpty()) {
            val m = t.columns(FOOD_COLUMNS)
            for (r in t.rows) {
                if (truthy(m[r, "deleted"])) continue
                val d = date(m[r, "date"], choices.dateFormat)
                val name = m[r, "name"]
                if (d == null || name == null) {
                    warnings.add("${t.name}: row skipped (no date or name)")
                    continue
                }
                val qty = Numbers.parseDecimal(m[r, "quantity"], t.decimalComma) ?: 1.0
                val units = m[r, "units"] ?: "serving"
                diary.add(
                    DiaryRow(
                        day = d.day, meal = m[r, "meal"] ?: "Snacks", secOfDay = d.secOfDay, name = name, brand = null,
                        amountX100 = Numbers.scaled(qty, 100), servingLabel = units, gramsX10 = NutrientColumns.gramsX10(qty, units),
                        nutrients = NutrientColumns.read(m, r, t.decimalComma),
                    ),
                )
            }
        }
        for (t in tables[EXERCISE].orEmpty()) {
            val m = t.columns(FOOD_COLUMNS)
            for (r in t.rows) {
                if (truthy(m[r, "deleted"])) continue
                val d = date(m[r, "date"], choices.dateFormat) ?: continue
                val name = m[r, "name"] ?: continue
                val qty = Numbers.parseDecimal(m[r, "quantity"], t.decimalComma)
                val units = m[r, "units"]?.lowercase().orEmpty()
                val sec = when {
                    qty == null -> null
                    units.startsWith("min") -> Numbers.scaled(qty * 60, 1)
                    units.startsWith("h") -> Numbers.scaled(qty * 3600, 1)
                    else -> null
                }
                val kcal = Numbers.parseDecimal(m[r, NutrientColumns.KCAL], t.decimalComma)?.let { Numbers.scaled(kotlin.math.abs(it), 1) }
                exercises.add(ExerciseRow(d.day, d.secOfDay, name, sec, kcal))
            }
        }
        for (t in tables[WEIGHT].orEmpty()) {
            val m = t.columns(WEIGHT_COLUMNS)
            for (r in t.rows) {
                val d = date(m[r, "date"], choices.dateFormat) ?: continue
                val v = Numbers.parseDecimal(m[r, "weight"], t.decimalComma)?.takeIf { it > 0 } ?: continue
                body.add(importedMeasure(d.epochSec(zone), BodyKind.WEIGHT, Numbers.scaled(toStoredUnit(BodyKind.WEIGHT, v, m.unit("weight"), choices.weightUnit), 100)))
            }
        }
        return ImportData(diary = diary, body = body, exercises = exercises)
    }

    private companion object {
        const val FOOD = "food"
        const val EXERCISE = "exercise"
        const val WEIGHT = "weight"

        val FOOD_COLUMNS = ColumnMatcher(
            linkedMapOf(
                "date" to listOf("date", "day"),
                "name" to listOf("name", "food name", "food", "exercise"),
                "meal" to listOf("type", "meal", "meal type"),
                "quantity" to listOf("quantity", "amount", "servings"),
                "units" to listOf("units", "unit", "serving units"),
                "deleted" to listOf("deleted"),
            ) + NutrientColumns.ALIASES,
        )

        val WEIGHT_COLUMNS = ColumnMatcher(linkedMapOf("date" to listOf("date", "day"), "weight" to listOf("weight", "body weight", "value")))
    }
}
