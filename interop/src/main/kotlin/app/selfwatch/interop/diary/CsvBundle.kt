/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.diary

import app.selfwatch.interop.api.BaseImporter
import app.selfwatch.interop.api.ColumnMapping
import app.selfwatch.interop.api.DateFormatGuess
import app.selfwatch.interop.api.ImportChoices
import app.selfwatch.interop.api.ImportData
import app.selfwatch.interop.api.ImportPlan
import app.selfwatch.interop.api.ImportSink
import app.selfwatch.interop.api.InputSource
import app.selfwatch.interop.api.UnitGuess
import app.selfwatch.interop.api.WeightUnit
import app.selfwatch.interop.io.ColumnMap
import app.selfwatch.interop.io.ColumnMatcher
import app.selfwatch.interop.io.CsvReader
import app.selfwatch.interop.io.DateFormat
import app.selfwatch.interop.io.DateFormatDetector
import app.selfwatch.interop.io.LocalStamp
import app.selfwatch.interop.io.Numbers
import app.selfwatch.interop.io.Texts
import app.selfwatch.interop.io.UnitDetector
import app.selfwatch.interop.io.Zips
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import java.time.ZoneId

/** One CSV file read into memory. */
class CsvTable(val name: String, val header: List<String>, val rows: List<List<String>>, val delimiter: Char) {
    /** True when numbers likely use a decimal comma (semicolon-separated files). */
    val decimalComma: Boolean get() = delimiter == ';'

    /** Matches this table's header with [matcher]. */
    fun columns(matcher: ColumnMatcher): ColumnMap = matcher.match(header)

    /** Factories. */
    companion object {
        /** Reads [bytes] as CSV, or null if there is no header row. */
        fun read(name: String, bytes: ByteArray): CsvTable? {
            val text = Texts.decode(bytes)
            val reader = CsvReader(java.io.StringReader(text))
            val all = reader.use { it.readAll() }
            if (all.isEmpty()) return null
            return CsvTable(name, all[0].map { it.trim() }, all.drop(1), reader.delimiter)
        }
    }
}

/** Reads a time of day such as `08:15`, `8:15:30` or `8:15 PM`. */
object TimeOfDay {
    private val PATTERN = Regex("""(\d{1,2}):(\d{2})(?::(\d{2}))?\s*([AaPp][Mm])?""")

    /** Seconds since midnight, or null. */
    fun parse(text: String?): Int? {
        val m = PATTERN.matchEntire(text?.trim() ?: return null) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        val s = m.groupValues[3].ifEmpty { "0" }.toInt()
        val ampm = m.groupValues[4].lowercase()
        if (ampm == "pm" && h in 1..11) h += 12 else if (ampm == "am" && h == 12) h = 0
        if (h > 23 || min > 59 || s > 59) return null
        return h * 3600 + min * 60 + s
    }

    /** `HH:mm`. */
    fun format(secOfDay: Int): String =
        (secOfDay / 3600).toString().padStart(2, '0') + ":" + (secOfDay / 60 % 60).toString().padStart(2, '0')
}

/** Nutrient column aliases and reading, shared by the calorie-app importers. */
object NutrientColumns {
    /** Field: energy (kcal; kJ converted). */
    const val KCAL: String = "kcal"

    /** Field: protein (g). */
    const val PROTEIN: String = "protein"

    /** Field: total fat (g). */
    const val FAT: String = "fat"

    /** Field: saturated fat (g). */
    const val SATFAT: String = "satfat"

    /** Field: carbohydrates (g). */
    const val CARB: String = "carb"

    /** Field: sugars (g). */
    const val SUGAR: String = "sugar"

    /** Field: fibre (g). */
    const val FIBRE: String = "fibre"

    /** Field: sodium (mg). */
    const val SODIUM: String = "sodium"

    /** Field: cholesterol (mg). */
    const val CHOLESTEROL: String = "cholesterol"

    /** Aliases for every nutrient (merge into an importer's table). */
    val ALIASES: Map<String, List<String>> = linkedMapOf(
        KCAL to listOf("energy", "calories", "kcal", "energy kcal", "food calories", "cals"),
        PROTEIN to listOf("protein", "proteins"),
        FAT to listOf("fat", "total fat", "fats"),
        SATFAT to listOf("saturated", "saturated fat", "sat fat", "saturated fats", "saturated fatty acids"),
        CARB to listOf("carbs", "carbohydrates", "carbohydrate", "total carbs", "carbohydrates total", "carb"),
        SUGAR to listOf("sugars", "sugar", "total sugars"),
        FIBRE to listOf("fiber", "fibre", "dietary fiber", "dietary fibre"),
        SODIUM to listOf("sodium", "salt sodium"),
        CHOLESTEROL to listOf("cholesterol"),
    )

    /** Grams ×10 from [v] given in [unit] (g by default). */
    private fun massX10(v: Double, unit: String?): Int = when (unit) {
        "mg" -> Numbers.scaled(v / 100.0, 1)
        "µg", "mcg", "ug" -> Numbers.scaled(v / 100_000.0, 1)
        else -> Numbers.scaled(v, 10)
    }

    /** Milligrams from [v] given in [unit] (mg by default). */
    private fun mg(v: Double, unit: String?): Int = when (unit) {
        "g" -> Numbers.scaled(v, 1000)
        "µg", "mcg", "ug" -> Numbers.scaled(v / 1000.0, 1)
        else -> Numbers.scaled(v, 1)
    }

    /** The nutrients in [row]; missing or blank cells are null. */
    fun read(map: ColumnMap, row: List<String>, decimalComma: Boolean): Nutrients {
        fun v(f: String): Double? = Numbers.parseDecimal(map[row, f], decimalComma)
        val kcal = v(KCAL)?.let { k -> if (map.unit(KCAL) == "kj") Numbers.scaled(k / 4.184, 10) else Numbers.scaled(k, 10) }
        return Nutrients(
            kcalX10 = kcal,
            proteinX10 = v(PROTEIN)?.let { massX10(it, map.unit(PROTEIN)) },
            fatX10 = v(FAT)?.let { massX10(it, map.unit(FAT)) },
            satfatX10 = v(SATFAT)?.let { massX10(it, map.unit(SATFAT)) },
            carbX10 = v(CARB)?.let { massX10(it, map.unit(CARB)) },
            sugarX10 = v(SUGAR)?.let { massX10(it, map.unit(SUGAR)) },
            fibreX10 = v(FIBRE)?.let { massX10(it, map.unit(FIBRE)) },
            sodiumMg = v(SODIUM)?.let { mg(it, map.unit(SODIUM)) },
            cholesterolMg = v(CHOLESTEROL)?.let { mg(it, map.unit(CHOLESTEROL)) },
        )
    }

    /** Grams ×10 for an amount in a mass unit, or null for other units. */
    fun gramsX10(amount: Double, unit: String): Int? = when (unit.trim().lowercase()) {
        "g", "gram", "grams", "gr" -> Numbers.scaled(amount, 10)
        "kg", "kilogram", "kilograms" -> Numbers.scaled(amount * 1000, 10)
        "oz", "ounce", "ounces" -> Numbers.scaled(amount * 28.349523125, 10)
        "lb", "lbs", "pound", "pounds" -> Numbers.scaled(amount * 453.59237, 10)
        else -> null
    }
}

/**
 * Base for importers that read a bundle of CSV files (a zip of them, or one CSV): each file is
 * classified by its header, dates are detected over all date columns, the weight unit over the
 * weight values, and the data is rebuilt for the user's choices on commit.
 */
abstract class CsvBundleImporter(sink: ImportSink, protected val zone: ZoneId) : BaseImporter(sink) {
    /** Date format preselected when the dates are ambiguous. */
    protected abstract val preferredDate: DateFormat

    /** The kind of file [table] is (importer-specific), or null to ignore it. */
    protected abstract fun classify(table: CsvTable): String?

    /** Date cells of [table] of [kind], for detection. */
    protected abstract fun dateSamples(kind: String, table: CsvTable): List<String>

    /** Weight header and values for unit detection, or null if the bundle has no weights of unknown unit. */
    protected abstract fun weightSamples(tables: Map<String, List<CsvTable>>): Pair<String?, List<Double>>?

    /** Everything in [tables] read with [choices]; problems go to [warnings]. */
    protected abstract fun build(tables: Map<String, List<CsvTable>>, choices: ImportChoices, warnings: MutableList<String>): ImportData

    /** Column mappings of the main table, for the preview. */
    protected abstract fun columnsOf(tables: Map<String, List<CsvTable>>): List<ColumnMapping>

    /** The kind whose rows are shown in the preview first. */
    protected abstract val mainKind: String

    /** Whether a zip entry name is worth reading (CSV files by default). */
    protected open fun wanted(entryName: String): Boolean = Texts.extension(entryName) in setOf("csv", "txt")

    override fun parse(src: InputSource): Outcome<ImportPlan> {
        val bytes = src.open().use { Texts.readBounded(it) }
        val files = if (Zips.isZip(bytes)) {
            Zips.readAll(bytes).filter { wanted(it.name) && !it.name.substringAfterLast('/').startsWith("._") }.map { it.name to it.bytes }
        } else {
            listOf(src.name to bytes)
        }
        val tables = LinkedHashMap<String, MutableList<CsvTable>>()
        for ((name, data) in files) {
            val t = CsvTable.read(name, data) ?: continue
            val kind = classify(t) ?: continue
            tables.getOrPut(kind) { ArrayList() }.add(t)
        }
        if (tables.isEmpty()) return Outcome.Fail(SwError.BadInput("no recognized ${id} files"))
        val dates = tables.flatMap { (k, ts) -> ts.flatMap { dateSamples(k, it).take(DateFormatDetector.MAX_SAMPLES) } }
        val dateGuess = DateFormatDetector.detect(dates, preferredDate)
        val unitGuess = weightSamples(tables)?.let { (h, v) -> UnitDetector.detect(h, v) } ?: UnitGuess.NONE
        val probeWarnings = ArrayList<String>()
        val choices = ImportChoices(dateGuess.best ?: preferredDate.id, unitGuess.unit.ifEmpty { WeightUnit.KG })
        val data = build(tables, choices, probeWarnings)
        if (data.isEmpty()) return Outcome.Fail(SwError.BadInput(probeWarnings.firstOrNull() ?: "no rows found"))
        val main = tables[mainKind]?.firstOrNull() ?: tables.values.first().first()
        val preview = listOf(main.header) + main.rows.take(PREVIEW_ROWS)
        return Outcome.Ok(
            ImportPlan(
                importerId = id,
                fileName = src.name,
                counts = data.counts(),
                preview = preview,
                columns = columnsOf(tables),
                dateFormat = if (dates.isEmpty()) DateFormatGuess.NONE else dateGuess,
                units = unitGuess,
                duplicates = 0,
                warnings = probeWarnings.distinct().take(MAX_WARNINGS),
                handle = { c -> Outcome.Ok(build(tables, c, ArrayList())) },
            ),
        )
    }

    /** Parses [text] as a date with [formatId]. */
    protected fun date(text: String?, formatId: String): LocalStamp? {
        val f = DateFormat.byId(formatId) ?: preferredDate
        return f.parse(text ?: return null)
    }

    private companion object {
        const val PREVIEW_ROWS = 20
        const val MAX_WARNINGS = 20
    }
}

/** True if the cell means "yes" (Lose It!'s Deleted column, for example). */
internal fun truthy(s: String?): Boolean = s?.trim()?.lowercase() in setOf("true", "yes", "1", "y", "x")
