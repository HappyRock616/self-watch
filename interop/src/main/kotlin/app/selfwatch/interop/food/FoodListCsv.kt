/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.food

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
import app.selfwatch.interop.api.UnitGuess
import app.selfwatch.interop.diary.CsvTable
import app.selfwatch.interop.diary.NutrientColumns
import app.selfwatch.interop.diary.createdDate
import app.selfwatch.interop.diary.writeTo
import app.selfwatch.interop.io.ColumnMatcher
import app.selfwatch.interop.io.CsvWriter
import app.selfwatch.interop.io.Numbers
import app.selfwatch.interop.io.Texts
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.Serving
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import java.io.OutputStream

/**
 * The food-list CSV: one food per row. Written columns: `Name, Brand, Barcode, Basis, Serving,
 * Serving grams` then the nutrients with units in the names. `Basis` is `per 100 g`, `per 100 ml` or
 * `per serving` (the nutrients are for that amount). Only the first serving size is written.
 *
 * Reading is tolerant of other apps' lists: columns are matched by alias, and without a `Basis`
 * column the values are taken as per serving (converted to per 100 g when the serving's grams are
 * known).
 */
object FoodListCsv {
    /** Header written by [write]. */
    val HEADER: List<String> = listOf(
        "Name", "Brand", "Barcode", "Basis", "Serving", "Serving grams",
        "Energy (kcal)", "Protein (g)", "Fat (g)", "Saturated fat (g)", "Carbs (g)", "Sugar (g)", "Fiber (g)",
        "Sodium (mg)", "Cholesterol (mg)",
    )

    private val COLUMNS = ColumnMatcher(
        linkedMapOf(
            "name" to listOf("name", "food name", "food", "description", "product name", "product"),
            "brand" to listOf("brand", "brand name", "brands", "manufacturer"),
            "barcode" to listOf("barcode", "gtin", "ean", "upc", "code"),
            "basis" to listOf("basis", "per", "nutrients per"),
            "serving" to listOf("serving", "serving size", "serving label", "portion", "unit"),
            "grams" to listOf("serving grams", "grams", "serving weight", "weight", "serving g", "gramm"),
        ) + NutrientColumns.ALIASES,
    )

    private fun basisName(b: Basis): String = when (b) {
        Basis.PER_100G -> "per 100 g"
        Basis.PER_100ML -> "per 100 ml"
        Basis.PER_ITEM -> "per serving"
    }

    private fun parseBasis(s: String?): Basis? {
        val t = s?.lowercase()?.replace(" ", "") ?: return null
        return when {
            "100ml" in t -> Basis.PER_100ML
            "100g" in t -> Basis.PER_100G
            else -> Basis.PER_ITEM
        }
    }

    /** Writes [foods] (UTF-8 with a byte order mark). */
    fun write(out: OutputStream, foods: List<Food>) {
        val w = CsvWriter(out)
        w.row(HEADER)
        for (f in foods) {
            val s = f.servings.minByOrNull { it.seq }
            val n = f.per
            fun x10(v: Int?): String = v?.let { Numbers.formatScaled(it, 10) }.orEmpty()
            w.row(
                f.name, f.brand.orEmpty(), f.barcode?.toString().orEmpty(), basisName(f.basis), s?.label.orEmpty(),
                s?.gramsX10?.let { Numbers.formatScaled(it, 10) }.orEmpty(),
                x10(n.kcalX10), x10(n.proteinX10), x10(n.fatX10), x10(n.satfatX10), x10(n.carbX10), x10(n.sugarX10),
                x10(n.fibreX10), n.sodiumMg?.toString().orEmpty(), n.cholesterolMg?.toString().orEmpty(),
            )
        }
        w.flush()
    }

    /** Reads every food in [table]; rows without a name are skipped and noted in [warnings]. */
    fun read(table: CsvTable, warnings: MutableList<String>): List<Food> {
        val m = table.columns(COLUMNS)
        if (!m.has("name")) {
            warnings.add("${table.name}: no name column")
            return emptyList()
        }
        val out = ArrayList<Food>()
        for (r in table.rows) {
            val name = m[r, "name"]
            if (name == null) {
                warnings.add("${table.name}: row without a name skipped")
                continue
            }
            val grams = Numbers.parseDecimal(m[r, "grams"], table.decimalComma)?.takeIf { it > 0 }
            val label = m[r, "serving"]
            val servings = if (label != null || grams != null) {
                listOf(Serving(1, label ?: "serving", grams?.let { Numbers.scaled(it, 10) }))
            } else {
                emptyList()
            }
            var n = NutrientColumns.read(m, r, table.decimalComma)
            var basis = parseBasis(m[r, "basis"])
            if (basis == null) {
                basis = if (grams != null) Basis.PER_100G else Basis.PER_ITEM
                if (grams != null) n = n.scaled(Numbers.scaled(100_000.0 / grams, 1))
            }
            out.add(
                Food(
                    ref = FoodRef(FoodOrigin.MY_FOOD, 0), name = name, brand = m[r, "brand"], kind = 0, basis = basis,
                    per = n, servings = servings, dataYear = null, sourceLabel = "Imported",
                    barcode = m[r, "barcode"]?.filter { it.isDigit() }?.takeIf { it.length in 6..18 }?.toLongOrNull(),
                ),
            )
        }
        return out
    }
}

/** Imports a food-list CSV as custom foods. Id "foodlist-csv". */
class FoodListImporter(sink: ImportSink = ImportSink.NONE) : BaseImporter(sink) {
    override val id: String = "foodlist-csv"

    override fun sniffSafe(name: String, head: ByteArray): Int {
        val first = Texts.headText(head).trimStart(0xFEFF.toChar()).lineSequence().firstOrNull().orEmpty().lowercase()
        val ext = Texts.extension(name)
        return when {
            first.startsWith("name,brand,barcode,basis") -> 95
            ext == "csv" && "name" in first && ("kcal" in first || "calories" in first || "energy" in first) &&
                "date" !in first && "day" !in first -> 40
            else -> 0
        }
    }

    override fun parse(src: InputSource): Outcome<ImportPlan> {
        val bytes = src.open().use { Texts.readBounded(it) }
        val table = CsvTable.read(src.name, bytes) ?: return Outcome.Fail(SwError.BadInput("empty file"))
        val warnings = ArrayList<String>()
        val foods = FoodListCsv.read(table, warnings)
        if (foods.isEmpty()) return Outcome.Fail(SwError.BadInput(warnings.firstOrNull() ?: "no foods"))
        val data = ImportData(foods = foods)
        val cols = table.columns(
            ColumnMatcher(linkedMapOf("name" to listOf("name", "food name", "food", "description")) + NutrientColumns.ALIASES),
        )
        return Outcome.Ok(
            ImportPlan(
                importerId = id, fileName = src.name, counts = data.counts(), preview = listOf(table.header) + table.rows.take(20),
                columns = cols.mappings(listOf("name") + NutrientColumns.ALIASES.keys), dateFormat = DateFormatGuess.NONE,
                units = UnitGuess.NONE, duplicates = 0, warnings = warnings.distinct().take(20), handle = { Outcome.Ok(data) },
            ),
        )
    }
}

/** Exports custom foods as a food-list CSV. Id "foodlist-csv". */
class FoodListExporter(private val source: ExportSource) : Exporter {
    override val id: String = "foodlist-csv"

    override fun mimeType(req: ExportRequest): String = "text/csv"

    override fun suggestedName(req: ExportRequest): String = "selfwatch-foods-${createdDate(req)}.csv"

    override suspend fun export(req: ExportRequest, out: OutputSink): Outcome<ExportResult> {
        val foods = source.customFoods()
        val r = writeTo(out) { FoodListCsv.write(it, foods) }
        return if (r is Outcome.Fail) r else Outcome.Ok(ExportResult(mapOf("food" to foods.size)))
    }
}

