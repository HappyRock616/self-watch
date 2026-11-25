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
import app.selfwatch.interop.api.ExportRequest
import app.selfwatch.interop.api.ExportResult
import app.selfwatch.interop.api.ExportSource
import app.selfwatch.interop.api.Exporter
import app.selfwatch.interop.api.ImportChoices
import app.selfwatch.interop.api.ImportData
import app.selfwatch.interop.api.ImportSink
import app.selfwatch.interop.api.NoteRow
import app.selfwatch.interop.api.OutputSink
import app.selfwatch.interop.api.WeightUnit
import app.selfwatch.interop.io.ColumnMatcher
import app.selfwatch.interop.io.CsvWriter
import app.selfwatch.interop.io.DateFormat
import app.selfwatch.interop.io.LocalStamp
import app.selfwatch.interop.io.Numbers
import app.selfwatch.interop.io.Texts
import app.selfwatch.interop.io.UnitDetector
import app.selfwatch.interop.io.Zips
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.time.Day
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

/** A body measurement from an import (id 0, source IMPORT). */
internal fun importedMeasure(tSec: Long, kind: BodyKind, valueX100: Int): BodyMeasure =
    BodyMeasure(0, tSec, kind, null, valueX100, DataSource.IMPORT, null, null)

/** Cronometer's file layout, shared by the reader and the writer. */
object CronometerLayout {
    /**
     * Nutrient columns in the order Cronometer's servings export lists them (the subset Self Watch keeps),
     * then the food database v2 nutrients with their units. Vitamin D is written in µg (Cronometer
     * writes IU; both are read).
     */
    val NUTRIENT_HEADERS: List<Pair<String, String>> = listOf(
        NutrientColumns.KCAL to "Energy (kcal)",
        NutrientColumns.CARB to "Carbs (g)",
        NutrientColumns.FIBRE to "Fiber (g)",
        NutrientColumns.SUGAR to "Sugars (g)",
        NutrientColumns.ADDSUGAR to "Added Sugars (g)",
        NutrientColumns.FAT to "Fat (g)",
        NutrientColumns.CHOLESTEROL to "Cholesterol (mg)",
        NutrientColumns.MONOFAT to "Monounsaturated (g)",
        NutrientColumns.POLYFAT to "Polyunsaturated (g)",
        NutrientColumns.SATFAT to "Saturated (g)",
        NutrientColumns.TRANSFAT to "Trans-Fats (g)",
        NutrientColumns.SODIUM to "Sodium (mg)",
        NutrientColumns.POTASSIUM to "Potassium (mg)",
        NutrientColumns.CALCIUM to "Calcium (mg)",
        NutrientColumns.IRON to "Iron (mg)",
        NutrientColumns.VITA to "Vitamin A (µg)",
        NutrientColumns.VITC to "Vitamin C (mg)",
        NutrientColumns.VITD to "Vitamin D (µg)",
        NutrientColumns.PROTEIN to "Protein (g)",
    )

    /** Body kinds and Cronometer's metric names (first name is written; all are read). */
    val METRICS: Map<BodyKind, List<String>> = linkedMapOf(
        BodyKind.WEIGHT to listOf("Weight", "Body Weight"),
        BodyKind.BODY_FAT to listOf("Body Fat", "Body Fat %", "Body Fat Percentage"),
        BodyKind.BMI to listOf("BMI", "Body Mass Index"),
        BodyKind.WAIST to listOf("Waist", "Waist Size", "Waist Circumference"),
        BodyKind.HIPS to listOf("Hips", "Hip Size", "Hip Circumference"),
        BodyKind.CHEST to listOf("Chest", "Chest Size"),
        BodyKind.ARM to listOf("Arm", "Arms", "Bicep", "Biceps"),
        BodyKind.THIGH to listOf("Thigh", "Thighs"),
        BodyKind.NECK to listOf("Neck", "Neck Size"),
        BodyKind.MUSCLE_MASS to listOf("Muscle Mass", "Skeletal Muscle Mass"),
        BodyKind.BONE_MASS to listOf("Bone Mass"),
        BodyKind.BODY_WATER to listOf("Body Water", "Water Percentage", "Body Water %"),
    )

    /** Field aliases per file. */
    val SERVINGS: ColumnMatcher = ColumnMatcher(
        linkedMapOf(
            "day" to listOf("day", "date"),
            "time" to listOf("time"),
            "group" to listOf("group", "meal", "diary group"),
            "food" to listOf("food name", "food", "name"),
            "amount" to listOf("amount", "quantity"),
        ) + NutrientColumns.ALIASES,
    )

    /** Exercise file aliases. */
    val EXERCISES: ColumnMatcher = ColumnMatcher(
        linkedMapOf(
            "day" to listOf("day", "date"),
            "time" to listOf("time"),
            "exercise" to listOf("exercise", "name", "activity"),
            "minutes" to listOf("minutes", "duration", "duration minutes"),
            "kcal" to listOf("calories burned", "calories", "energy burned", "kcal"),
        ),
    )

    /** Biometrics file aliases. */
    val BIOMETRICS: ColumnMatcher = ColumnMatcher(
        linkedMapOf(
            "day" to listOf("day", "date"),
            "time" to listOf("time"),
            "metric" to listOf("metric", "biometric", "name"),
            "unit" to listOf("unit", "units"),
            "amount" to listOf("amount", "value"),
        ),
    )

    /** Notes file aliases. */
    val NOTES: ColumnMatcher = ColumnMatcher(linkedMapOf("day" to listOf("day", "date"), "note" to listOf("note", "notes", "text")))
}

/**
 * Imports Cronometer's CSV exports (`servings.csv`, `exercises.csv`, `biometrics.csv`, `notes.csv`,
 * `dailySummary.csv`), as a zip or one file at a time. Servings become diary lines with their
 * nutrients; biometrics become body measures; daily summaries are not imported because Self Watch
 * adds them up from the diary. Id "cronometer".
 */
class CronometerImporter(sink: ImportSink = ImportSink.NONE, zone: ZoneId = ZoneId.systemDefault()) : CsvBundleImporter(sink, zone) {
    override val id: String = "cronometer"
    override val preferredDate: DateFormat = DateFormat.ISO
    override val mainKind: String = SERVINGS

    override fun sniffSafe(name: String, head: ByteArray): Int {
        val lower = Texts.baseName(name).lowercase()
        if (Zips.isZip(head)) {
            val names = Zips.namesInHead(head).map { Texts.baseName(it).lowercase() }
            return if (names.any { it in FILE_NAMES }) 85 else 0
        }
        val first = Texts.headText(head).lineSequence().firstOrNull().orEmpty().lowercase()
        return when {
            "food name" in first && "energy (kcal)" in first -> 90
            "metric" in first && "amount" in first && lower.startsWith("biometric") -> 85
            lower in FILE_NAMES -> 50
            else -> 0
        }
    }

    override fun classify(table: CsvTable): String? {
        val h = table.header.map { ColumnMatcher.normalize(it) }
        return when {
            "food name" in h -> SERVINGS
            "metric" in h && "amount" in h -> BIOMETRICS
            "exercise" in h -> EXERCISES
            "note" in h -> NOTES
            "energy" in h && ("date" in h || "day" in h) -> SUMMARY
            else -> null
        }
    }

    override fun dateSamples(kind: String, table: CsvTable): List<String> {
        if (kind == SUMMARY) return emptyList()
        val m = table.columns(matcherFor(kind))
        return table.rows.mapNotNull { m[it, "day"] }
    }

    override fun weightSamples(tables: Map<String, List<CsvTable>>): Pair<String?, List<Double>>? {
        // Units are written per row; only rows without one need the user's choice.
        val values = ArrayList<Double>()
        for (t in tables[BIOMETRICS].orEmpty()) {
            val m = t.columns(CronometerLayout.BIOMETRICS)
            for (r in t.rows) {
                if (kindOf(m[r, "metric"]) == BodyKind.WEIGHT && m[r, "unit"] == null) {
                    Numbers.parseDecimal(m[r, "amount"], t.decimalComma)?.let { values.add(it) }
                }
            }
        }
        return if (values.isEmpty()) null else null to values
    }

    override fun columnsOf(tables: Map<String, List<CsvTable>>): List<ColumnMapping> {
        val t = tables[SERVINGS]?.firstOrNull() ?: return emptyList()
        return t.columns(CronometerLayout.SERVINGS).mappings(listOf("day", "time", "group", "food", "amount") + NutrientColumns.ALIASES.keys)
    }

    override fun build(tables: Map<String, List<CsvTable>>, choices: ImportChoices, warnings: MutableList<String>): ImportData {
        val diary = ArrayList<DiaryRow>()
        val body = ArrayList<BodyMeasure>()
        val exercises = ArrayList<ExerciseRow>()
        val notes = ArrayList<NoteRow>()
        for (t in tables[SERVINGS].orEmpty()) {
            val m = t.columns(CronometerLayout.SERVINGS)
            for (r in t.rows) {
                val d = date(m[r, "day"], choices.dateFormat)
                val name = m[r, "food"]
                if (d == null || name == null) {
                    warnings.add("${t.name}: row skipped (no date or food)")
                    continue
                }
                val (amount, label) = amountAndLabel(m[r, "amount"])
                diary.add(
                    DiaryRow(
                        day = d.day, meal = m[r, "group"] ?: "Uncategorized", secOfDay = TimeOfDay.parse(m[r, "time"]),
                        name = name, brand = null, amountX100 = Numbers.scaled(amount, 100), servingLabel = label,
                        gramsX10 = NutrientColumns.gramsX10(amount, label), nutrients = NutrientColumns.read(m, r, t.decimalComma),
                    ),
                )
            }
        }
        for (t in tables[BIOMETRICS].orEmpty()) {
            val m = t.columns(CronometerLayout.BIOMETRICS)
            for (r in t.rows) {
                val d = date(m[r, "day"], choices.dateFormat) ?: continue
                val kind = kindOf(m[r, "metric"])
                val v = Numbers.parseDecimal(m[r, "amount"], t.decimalComma)
                if (kind == null || v == null) {
                    warnings.add("${t.name}: metric '${m[r, "metric"].orEmpty()}' skipped")
                    continue
                }
                val stamp = LocalStamp(d.day, TimeOfDay.parse(m[r, "time"]))
                body.add(importedMeasure(stamp.epochSec(zone), kind, Numbers.scaled(toStoredUnit(kind, v, m[r, "unit"], choices.weightUnit), 100)))
            }
        }
        for (t in tables[EXERCISES].orEmpty()) {
            val m = t.columns(CronometerLayout.EXERCISES)
            for (r in t.rows) {
                val d = date(m[r, "day"], choices.dateFormat) ?: continue
                val name = m[r, "exercise"] ?: continue
                exercises.add(
                    ExerciseRow(
                        d.day, TimeOfDay.parse(m[r, "time"]), name,
                        Numbers.parseDecimal(m[r, "minutes"], t.decimalComma)?.let { Numbers.scaled(it * 60, 1) },
                        Numbers.parseDecimal(m[r, "kcal"], t.decimalComma)?.let { Numbers.scaled(kotlin.math.abs(it), 1) },
                    ),
                )
            }
        }
        for (t in tables[NOTES].orEmpty()) {
            val m = t.columns(CronometerLayout.NOTES)
            for (r in t.rows) {
                val d = date(m[r, "day"], choices.dateFormat) ?: continue
                val text = m[r, "note"] ?: continue
                notes.add(NoteRow(d.day, text))
            }
        }
        if (tables.containsKey(SUMMARY)) warnings.add("dailySummary.csv not imported: daily totals are added up from the diary")
        return ImportData(diary = diary, body = body, exercises = exercises, notes = notes)
    }

    private fun matcherFor(kind: String): ColumnMatcher = when (kind) {
        SERVINGS -> CronometerLayout.SERVINGS
        BIOMETRICS -> CronometerLayout.BIOMETRICS
        EXERCISES -> CronometerLayout.EXERCISES
        else -> CronometerLayout.NOTES
    }

    private companion object {
        const val SERVINGS = "servings"
        const val BIOMETRICS = "biometrics"
        const val EXERCISES = "exercises"
        const val NOTES = "notes"
        const val SUMMARY = "summary"
        val FILE_NAMES = setOf("servings.csv", "dailysummary.csv", "exercises.csv", "biometrics.csv", "notes.csv")

        fun kindOf(metric: String?): BodyKind? {
            val m = metric?.trim()?.lowercase() ?: return null
            return CronometerLayout.METRICS.entries.firstOrNull { (_, names) -> names.any { it.lowercase() == m } }?.key
        }
    }
}

/** Splits `1.50 cup` into 1.5 and "cup"; a bare label means one of it. */
internal fun amountAndLabel(text: String?): Pair<Double, String> {
    val t = text?.trim().orEmpty()
    val q = Numbers.leadingQuantity(t) ?: return 1.0 to t.ifEmpty { "serving" }
    return q.first to q.second.ifEmpty { "serving" }
}

/** Converts a body value in [unit] (or [defaultWeightUnit] for weights without one) to the stored unit of [kind]. */
internal fun toStoredUnit(kind: BodyKind, v: Double, unit: String?, defaultWeightUnit: String): Double {
    val u = unit?.trim()?.lowercase()
    return when (kind) {
        BodyKind.WEIGHT, BodyKind.MUSCLE_MASS, BodyKind.BONE_MASS ->
            if (u == null) UnitDetector.toKg(v, defaultWeightUnit) else if (u.startsWith("lb") || u.startsWith("pound")) v / WeightUnit.LB_PER_KG else v
        BodyKind.WAIST, BodyKind.HIPS, BodyKind.CHEST, BodyKind.ARM, BodyKind.THIGH, BodyKind.NECK ->
            if (u == "in" || u == "inch" || u == "inches" || u == "\"") v * 2.54 else if (u == "mm") v / 10 else v
        else -> v
    }
}

/**
 * Writes Self Watch data in Cronometer's CSV layout as a zip of `servings.csv`, `dailySummary.csv`,
 * `exercises.csv`, `biometrics.csv` and `notes.csv`: ISO dates, units in the column names, UTF-8 with
 * a byte order mark. Id "cronometer-csv".
 */
class CronometerExporter(private val source: ExportSource) : Exporter {
    override val id: String = "cronometer-csv"

    override fun mimeType(req: ExportRequest): String = "application/zip"

    override fun suggestedName(req: ExportRequest): String =
        "selfwatch-cronometer-${Instant.ofEpochSecond(req.createdSec).atZone(req.zone).toLocalDate()}.zip"

    override suspend fun export(req: ExportRequest, out: OutputSink): Outcome<ExportResult> {
        val diary = source.diary(req.days).sortedWith(compareBy({ it.day }, { it.secOfDay ?: Int.MAX_VALUE }))
        val exercises = source.exercises(req.days)
        val body = source.body(req.days, req.zone).sortedBy { it.tSec }
        val notes = source.notes(req.days)
        return withContext(Dispatchers.IO) {
            try {
                out.open().use { stream -> Zips.write(stream) { writeAll(this, diary, exercises, body, notes, req.zone) } }
                Outcome.Ok(
                    ExportResult(mapOf("diary" to diary.size, "exercise" to exercises.size, "body" to body.size, "note" to notes.size)),
                )
            } catch (e: IOException) {
                Outcome.Fail(SwError.Io("export failed: ${e.javaClass.simpleName}"))
            }
        }
    }

    /** Writers for each file, usable without a zip. */
    companion object {
        /** Writes all five files into [zip]. */
        fun writeAll(
            zip: app.selfwatch.interop.io.ZipBuilder,
            diary: List<DiaryRow>,
            exercises: List<ExerciseRow>,
            body: List<BodyMeasure>,
            notes: List<NoteRow>,
            zone: ZoneId,
        ) {
            zip.entry("servings.csv") { writeServings(it, diary) }
            zip.entry("dailySummary.csv") { writeDailySummary(it, diary) }
            zip.entry("exercises.csv") { writeExercises(it, exercises) }
            zip.entry("biometrics.csv") { writeBiometrics(it, body, zone) }
            zip.entry("notes.csv") { writeNotes(it, notes) }
        }

        private fun nutrientCells(n: Nutrients): List<String> = CronometerLayout.NUTRIENT_HEADERS.map { (f, _) -> NutrientColumns.cell(n, f) }

        /** `servings.csv`: one row per diary line. */
        fun writeServings(out: java.io.OutputStream, diary: List<DiaryRow>) {
            val w = CsvWriter(out)
            w.row(listOf("Day", "Time", "Group", "Food Name", "Amount") + CronometerLayout.NUTRIENT_HEADERS.map { it.second })
            for (d in diary) {
                val amount = Numbers.formatScaled(d.amountX100, 100, trim = false) + " " + d.servingLabel
                w.row(listOf(DateFormat.ISO.format(d.day), d.secOfDay?.let { TimeOfDay.format(it) }.orEmpty(), d.meal, d.name, amount) + nutrientCells(d.nutrients))
            }
            w.flush()
        }

        /** `dailySummary.csv`: totals per day. */
        fun writeDailySummary(out: java.io.OutputStream, diary: List<DiaryRow>) {
            val w = CsvWriter(out)
            w.row(listOf("Date") + CronometerLayout.NUTRIENT_HEADERS.map { it.second })
            val byDay = java.util.TreeMap<Day, Nutrients>()
            for (d in diary) byDay[d.day] = (byDay[d.day] ?: Nutrients.UNKNOWN) + d.nutrients
            for ((day, n) in byDay) w.row(listOf(DateFormat.ISO.format(day)) + nutrientCells(n))
            w.flush()
        }

        /** `exercises.csv`. */
        fun writeExercises(out: java.io.OutputStream, exercises: List<ExerciseRow>) {
            val w = CsvWriter(out)
            w.row("Day", "Time", "Exercise", "Minutes", "Calories Burned")
            for (e in exercises.sortedBy { it.day }) {
                w.row(
                    DateFormat.ISO.format(e.day), e.secOfDay?.let { TimeOfDay.format(it) }.orEmpty(), e.name,
                    e.durationSec?.let { Numbers.format(it / 60.0, 2) }.orEmpty(), e.kcal?.toString().orEmpty(),
                )
            }
            w.flush()
        }

        /** `biometrics.csv`: one row per measurement, in kg, % and cm. */
        fun writeBiometrics(out: java.io.OutputStream, body: List<BodyMeasure>, zone: ZoneId) {
            val w = CsvWriter(out)
            w.row("Day", "Time", "Metric", "Unit", "Amount")
            for (b in body) {
                val name = CronometerLayout.METRICS[b.kind]?.first() ?: continue
                val t = Instant.ofEpochSecond(b.tSec).atZone(zone)
                w.row(
                    DateFormat.ISO.format(Day.of(t.toLocalDate())), TimeOfDay.format(t.toLocalTime().toSecondOfDay()),
                    name, b.kind.unit, Numbers.formatScaled(b.valueX100, 100),
                )
            }
            w.flush()
        }

        /** `notes.csv`. */
        fun writeNotes(out: java.io.OutputStream, notes: List<NoteRow>) {
            val w = CsvWriter(out)
            w.row("Day", "Note")
            for (n in notes.sortedBy { it.day }) w.row(DateFormat.ISO.format(n.day), n.text)
            w.flush()
        }
    }
}
