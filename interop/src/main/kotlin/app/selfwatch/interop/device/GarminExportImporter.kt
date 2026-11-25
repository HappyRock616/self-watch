/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.device

import app.selfwatch.fit.decode.FitFileType
import app.selfwatch.fit.decode.HrvDecoder
import app.selfwatch.fit.decode.MonitoringCarry
import app.selfwatch.fit.decode.MonitoringDecoder
import app.selfwatch.fit.decode.SleepDecoder
import app.selfwatch.fit.decode.WeightDecoder
import app.selfwatch.fit.decode.WorkoutDecoder
import app.selfwatch.fit.io.FitReader
import app.selfwatch.interop.api.BaseImporter
import app.selfwatch.interop.api.DateFormatGuess
import app.selfwatch.interop.api.ImportData
import app.selfwatch.interop.api.ImportPlan
import app.selfwatch.interop.api.ImportSink
import app.selfwatch.interop.api.InputSource
import app.selfwatch.interop.api.ParsedWorkout
import app.selfwatch.interop.api.TrackFormat
import app.selfwatch.interop.api.UnitGuess
import app.selfwatch.interop.api.WaterRow
import app.selfwatch.interop.io.Texts
import app.selfwatch.interop.io.TooLargeException
import app.selfwatch.interop.io.Zips
import app.selfwatch.interop.workout.WorkoutFiles
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.json.Json
import app.selfwatch.model.json.JsonValue
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.result.valueOrNull
import app.selfwatch.model.time.Day
import app.selfwatch.model.watch.IngestBatch
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Imports Garmin's "Export Your Data" zip. The layout changes over the years, so nothing depends on
 * exact folder names: every entry is classified by its name and contents.
 *
 * - `*.fit` anywhere, and `*.fit` inside nested zips (`DI_CONNECT/DI-Connect-Uploaded-Files/UploadedFiles_*.zip`
 *   and similar, up to two levels deep), go by their FIT file type: activity → workout (original kept);
 *   monitoring and metrics → activity minutes and metrics (decoded in time order with one counter carry);
 *   sleep → stages and nights; HRV status → HRV values; weight → body measurements.
 * - JSON files whose name mentions hydration → water, when their objects carry a millilitre value and a
 *   date (`valueInML`/`…ML` and `calendarDate`/`timestampLocal`); otherwise a warning.
 * - Everything else is counted and skipped with a warning.
 */
class GarminExportImporter(sink: ImportSink = ImportSink.NONE, private val zone: ZoneId = ZoneId.systemDefault()) : BaseImporter(sink) {
    override val id: String = "garmin-export"

    override fun sniffSafe(name: String, head: ByteArray): Int {
        if (!Zips.isZip(head)) return 0
        val names = Zips.namesInHead(head)
        return when {
            names.any { it.startsWith("DI_CONNECT/") || it.contains("/DI_CONNECT/") } -> 90
            names.any { it.contains("DI-Connect", ignoreCase = true) } -> 85
            else -> 0
        }
    }

    private class Fit(val name: String, val bytes: ByteArray, val type: Int?, val firstSec: Long)

    override fun parse(src: InputSource): Outcome<ImportPlan> {
        val fits = ArrayList<Fit>()
        val water = ArrayList<WaterRow>()
        val warnings = ArrayList<String>()
        var skipped = 0
        var badFit = 0
        var garminLayout = false
        var hydrationFiles = 0
        var total = 0L
        var entries = 0

        fun visit(name: String, readBytes: () -> ByteArray, depth: Int) {
            if (name.contains("DI_CONNECT", ignoreCase = true) || name.contains("DI-Connect", ignoreCase = true)) garminLayout = true
            val ext = Texts.extension(name)
            when {
                ext == "fit" -> {
                    val bytes = readBytes()
                    total += bytes.size
                    var first = Long.MAX_VALUE
                    val r = FitReader(ByteArrayInputStream(bytes)).read { rec ->
                        val t = rec.tSec
                        if (t != null && t < first) first = t
                    }
                    if (r is Outcome.Ok) fits.add(Fit(name, bytes, r.value.fileType, first)) else badFit++
                }
                ext == "zip" && depth < MAX_DEPTH -> {
                    val bytes = readBytes()
                    for (item in Zips.readAll(bytes)) {
                        if (++entries > Zips.MAX_ENTRIES) throw TooLargeException("too many entries")
                        visit("$name/${item.name}", { item.bytes }, depth + 1)
                    }
                }
                ext == "json" && name.contains("hydration", ignoreCase = true) -> {
                    hydrationFiles++
                    val found = hydration(Texts.decode(readBytes()))
                    if (found.isEmpty()) warnings.add("${Texts.baseName(name)}: hydration layout not recognised; skipped")
                    water.addAll(found)
                }
                else -> skipped++
            }
            if (total > Zips.MAX_TOTAL_BYTES) throw TooLargeException("export too large")
        }

        ZipInputStream(BufferedInputStream(src.open())).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                if (++entries > Zips.MAX_ENTRIES) throw TooLargeException("too many entries")
                visit(e.name.replace('\\', '/'), { Texts.readBounded(z, Zips.MAX_ENTRY_BYTES) }, 0)
            }
        }

        val series = SeriesBuilder("garmin-export", "Garmin export", "")
        val workouts = ArrayList<ParsedWorkout>()
        val body = ArrayList<BodyMeasure>()
        var unknownFit = 0
        val carry = MonitoringCarry()
        for (f in fits.sortedBy { it.firstSec }) {
            val input = ByteArrayInputStream(f.bytes)
            when (f.type) {
                FitFileType.ACTIVITY -> when (val r = WorkoutFiles.parse(TrackFormat.FIT, Texts.baseName(f.name), f.bytes)) {
                    is Outcome.Ok -> {
                        workouts.addAll(r.value)
                        // The workout's VO2 max goes with the export's other watch data (one device series).
                        for (m in WorkoutDecoder.vo2Max(input, 0)) series.metric(m)
                    }
                    is Outcome.Fail -> badFit++
                }
                FitFileType.MONITORING_A, FitFileType.MONITORING_B, FitFileType.MONITORING_DAILY, FitFileType.METRICS ->
                    addBatch(series, MonitoringDecoder.decode(input, 0, zone, carry)) { badFit++ }
                FitFileType.SLEEP -> addBatch(series, SleepDecoder.decode(input, 0)) { badFit++ }
                FitFileType.HRV_STATUS -> addBatch(series, HrvDecoder.decode(input, 0)) { badFit++ }
                FitFileType.WEIGHT -> when (val r = WeightDecoder.decode(input)) {
                    is Outcome.Ok -> body.addAll(r.value)
                    is Outcome.Fail -> badFit++
                }
                else -> unknownFit++
            }
        }
        if (badFit > 0) warnings.add("$badFit FIT files could not be read; skipped")
        if (unknownFit > 0) warnings.add("$unknownFit FIT files of other types skipped")
        if (skipped > 0) warnings.add("$skipped other files skipped")
        if (!garminLayout && fits.isEmpty() && hydrationFiles == 0) return Outcome.Fail(SwError.BadInput("not a Garmin export"))

        val dev = series.build()
        val data = ImportData(
            body = body.distinctBy { it.tSec to it.kind }, workouts = workouts.sortedBy { it.workout.startSec },
            water = water, devices = if (dev.isEmpty()) emptyList() else listOf(dev),
        )
        if (data.isEmpty()) return Outcome.Fail(SwError.BadInput(warnings.firstOrNull() ?: "no data found in the Garmin export"))
        val preview = listOf(listOf("data", "rows")) + data.counts().map { (k, v) -> listOf(k, v.toString()) }
        return Outcome.Ok(
            ImportPlan(
                importerId = id, fileName = src.name, counts = data.counts(), preview = preview, columns = emptyList(),
                dateFormat = DateFormatGuess.NONE, units = UnitGuess.NONE, duplicates = 0, warnings = warnings,
                handle = { Outcome.Ok(data) },
            ),
        )
    }

    private inline fun addBatch(series: SeriesBuilder, r: Outcome<IngestBatch>, onFail: () -> Unit) {
        val b = (r as? Outcome.Ok)?.value ?: return onFail()
        for (m in b.activity) series.activity[m.tSec] = m.copy(deviceId = 0)
        for (m in b.metrics) series.metric(m.copy(deviceId = 0))
        for (s in b.sleepStages) series.stages[s.tSec] = s.copy(deviceId = 0)
        for (s in b.sleepSessions) series.sessions[s.startSec] = s.copy(deviceId = 0)
    }

    /** Water entries found in a hydration JSON file (any nesting of arrays and objects). */
    internal fun hydration(text: String): List<WaterRow> {
        val root = Json.parse(text).valueOrNull() ?: return emptyList()
        val out = ArrayList<WaterRow>()
        fun walk(v: JsonValue, depth: Int) {
            if (depth > 8) return
            when (v) {
                is JsonValue.Arr -> v.items.forEach { walk(it, depth + 1) }
                is JsonValue.Obj -> {
                    val row = waterRow(v.fields)
                    if (row != null) out.add(row) else v.fields.values.forEach { walk(it, depth + 1) }
                }
                else -> Unit
            }
        }
        walk(root, 0)
        return out
    }

    private fun waterRow(f: Map<String, JsonValue>): WaterRow? {
        val lower = f.mapKeys { it.key.lowercase(Locale.ROOT) }
        val ml = (lower["valueinml"] ?: lower.entries.firstOrNull { (k, v) -> k.endsWith("ml") && v is JsonValue.Num }?.value)?.asDouble ?: return null
        if (ml <= 0 || ml > MAX_ML) return null
        val stamp = (lower["timestamplocal"] ?: lower["calendardate"] ?: lower["timestamp"] ?: lower["timestampgmt"])?.asString ?: return null
        val (day, sec) = parseStamp(stamp) ?: return null
        return WaterRow(day, sec, ml.toInt())
    }

    private fun parseStamp(s: String): Pair<Day, Int?>? {
        if (s.length < 10) return null
        val date = try {
            LocalDate.parse(s.substring(0, 10))
        } catch (e: java.time.format.DateTimeParseException) {
            return null
        }
        var sec: Int? = null
        if (s.length >= 16 && (s[10] == 'T' || s[10] == ' ')) {
            val h = s.substring(11, 13).toIntOrNull()
            val m = s.substring(14, 16).toIntOrNull()
            val ss = if (s.length >= 19) s.substring(17, 19).toIntOrNull() ?: 0 else 0
            if (h != null && m != null && h in 0..23 && m in 0..59 && ss in 0..59) sec = h * 3600 + m * 60 + ss
        }
        return Day.of(date) to sec
    }

    private companion object {
        const val MAX_DEPTH = 2
        const val MAX_ML = 10_000.0
    }
}
