/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.device

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.selfwatch.interop.api.BaseImporter
import app.selfwatch.interop.api.DateFormatGuess
import app.selfwatch.interop.api.ImportData
import app.selfwatch.interop.api.ImportPlan
import app.selfwatch.interop.api.ImportSink
import app.selfwatch.interop.api.InputSource
import app.selfwatch.interop.api.ParsedWorkout
import app.selfwatch.interop.api.TrackFormat
import app.selfwatch.interop.api.UnitGuess
import app.selfwatch.interop.io.Texts
import app.selfwatch.interop.io.TooLargeException
import app.selfwatch.interop.io.Zips
import app.selfwatch.interop.workout.WorkoutFiles
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.health.SleepStageSample
import app.selfwatch.model.health.Workout
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

/**
 * Imports a Gadgetbridge (or Pulse, its fork) backup zip: `database/Gadgetbridge` (the SQLite database),
 * `preferences/…json` and the exported `files/…` (workout FIT files among them). A bare database file
 * is accepted too.
 *
 * The database is copied to a temp file in [cacheDir] and opened with Android SQLite (read-only when
 * possible; never written). From the Garmin tables it reads activity minutes, heart rate, stress, SpO2,
 * respiration, resting heart rate, HRV values, sleep stages (nights are rebuilt from them, with the
 * sleep score when present), weight samples, and workout summaries with their FIT files from the zip.
 * Tables or columns that are missing are skipped with a warning, so older and newer Gadgetbridge
 * versions both work.
 *
 * Gadgetbridge stores "not measured" as -1; such values are dropped. Activity samples carry seconds,
 * the newer time samples milliseconds.
 */
class GadgetbridgeImporter(
    sink: ImportSink = ImportSink.NONE,
    private val cacheDir: File = File(System.getProperty("java.io.tmpdir") ?: "."),
) : BaseImporter(sink) {
    override val id: String = "gadgetbridge"

    override fun sniffSafe(name: String, head: ByteArray): Int {
        if (Zips.isZip(head)) {
            val names = Zips.namesInHead(head)
            return when {
                names.any { it == DB_ENTRY || it.endsWith("/$DB_ENTRY") || it == META_ENTRY } -> 95
                names.any { it == "preferences/global.json" || it.startsWith("preferences/device_") } -> 90
                else -> 0
            }
        }
        val sqlite = head.size >= 16 && String(head, 0, 15, Charsets.US_ASCII) == "SQLite format 3"
        return if (sqlite && name.contains("gadgetbridge", ignoreCase = true)) 85 else 0
    }

    override fun parse(src: InputSource): Outcome<ImportPlan> {
        val tmp = File(cacheDir, "gb-${System.nanoTime()}.db")
        try {
            val head = src.open().use { input -> ByteArray(16).let { b -> b.copyOf(input.read(b).coerceAtLeast(0)) } }
            val entryNames = ArrayList<String>()
            if (Zips.isZip(head)) {
                var found = false
                zipEntries(src) { name, input ->
                    entryNames.add(name)
                    if (!found && isDbEntry(name)) {
                        copyBounded(input, tmp)
                        found = true
                    }
                }
                if (!found) return Outcome.Fail(SwError.BadInput("no Gadgetbridge database in the backup"))
            } else {
                src.open().use { copyBounded(it, tmp) }
            }
            val warnings = ArrayList<String>()
            val read = openDb(tmp).use { d -> GbReader(d, warnings).read() }
            val workouts = loadWorkouts(src, read.summaries, entryNames, warnings)
            val data = ImportData(body = read.body, workouts = workouts, devices = read.devices.filterNot { it.isEmpty() })
            if (data.isEmpty()) return Outcome.Fail(SwError.BadInput(warnings.firstOrNull() ?: "no Garmin data in the Gadgetbridge database"))
            val preview = listOf(listOf("data", "rows")) + data.counts().map { (k, v) -> listOf(k, v.toString()) } +
                read.devices.filterNot { it.isEmpty() }.map { listOf("device", it.name) }
            return Outcome.Ok(
                ImportPlan(
                    importerId = id, fileName = src.name, counts = data.counts(), preview = preview, columns = emptyList(),
                    dateFormat = DateFormatGuess.NONE, units = UnitGuess.NONE, duplicates = 0, warnings = warnings,
                    handle = { Outcome.Ok(data) },
                ),
            )
        } finally {
            for (suffix in listOf("", "-journal", "-wal", "-shm")) File(tmp.path + suffix).delete()
        }
    }

    private fun isDbEntry(name: String): Boolean {
        val base = Texts.baseName(name)
        return name == DB_ENTRY || name.endsWith("/$DB_ENTRY") || base == "Gadgetbridge" || base == "Gadgetbridge.db"
    }

    private fun openDb(f: File): SQLiteDatabase = try {
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    } catch (e: android.database.sqlite.SQLiteException) {
        // A database copied in WAL mode can't always be opened read-only without its -shm; the temp
        // copy is ours, so open it read-write instead (nothing is written).
        SwLog.w(TAG, "read-only open failed, retrying read-write", e)
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    }

    private fun loadWorkouts(src: InputSource, summaries: List<GbSummary>, entries: List<String>, warnings: MutableList<String>): List<ParsedWorkout> {
        if (summaries.isEmpty()) return emptyList()
        // Gadgetbridge stores an absolute path on the old phone; the backup keeps the external files
        // folder under "files/", so match the path after ".../files/", else the file name.
        val wanted = HashMap<String, MutableList<GbSummary>>()
        val fileEntries = entries.filter { it.startsWith("$FILES_DIR/") }
        for (s in summaries) {
            val path = s.rawDetailsPath ?: continue
            val norm = path.replace('\\', '/')
            val tail = norm.substringAfterLast("/$FILES_DIR/", "")
            val hit = fileEntries.firstOrNull { tail.isNotEmpty() && it == "$FILES_DIR/$tail" }
                ?: fileEntries.firstOrNull { Texts.baseName(it) == Texts.baseName(norm) }
            if (hit != null) wanted.getOrPut(hit) { ArrayList() }.add(s)
        }
        val fits = HashMap<GbSummary, Pair<String, ByteArray>>()
        if (wanted.isNotEmpty()) {
            zipEntries(src) { name, input ->
                val list = wanted[name] ?: return@zipEntries
                val bytes = Texts.readBounded(input, Zips.MAX_ENTRY_BYTES)
                for (s in list) fits[s] = name to bytes
            }
        }
        var noFile = 0
        val out = ArrayList<ParsedWorkout>()
        for (s in summaries) {
            val fit = fits[s]
            val parsed = fit?.let { (name, bytes) ->
                if (Texts.extension(name) == "fit") (WorkoutFiles.parse(TrackFormat.FIT, name, bytes) as? Outcome.Ok)?.value?.firstOrNull() else null
            }
            if (parsed != null) {
                out.add(parsed)
                continue
            }
            noFile++
            val start = s.startMs / 1000
            val end = (s.endMs / 1000).coerceAtLeast(start)
            val w = Workout(
                id = 0, deviceId = null, sport = 0, subSport = null, startSec = start, endSec = end,
                durationSec = (end - start).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), distanceM = null, kcal = null,
                avgHr = null, maxHr = null, ascentM = null, descentM = null, hasTrack = false, fitPath = null, trackPath = null,
                routePreview = null, source = DataSource.IMPORT,
            )
            out.add(ParsedWorkout(w, emptyList(), TrackFormat.FIT, s.name ?: "workout ${s.id}", ByteArray(0)))
        }
        if (noFile > 0) warnings.add("$noFile workouts have no readable FIT file in the backup; only their times are imported")
        return out.sortedBy { it.workout.startSec }
    }

    private fun zipEntries(src: InputSource, onEntry: (String, InputStream) -> Unit) {
        var n = 0
        ZipInputStream(BufferedInputStream(src.open())).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                if (++n > Zips.MAX_ENTRIES) throw TooLargeException("too many entries")
                onEntry(e.name.replace('\\', '/'), z)
            }
        }
    }

    private fun copyBounded(input: InputStream, out: File) {
        var total = 0L
        val buf = ByteArray(1 shl 16)
        FileOutputStream(out).use { os ->
            while (true) {
                val k = input.read(buf)
                if (k < 0) break
                total += k
                if (total > MAX_DB_BYTES) throw TooLargeException("database too large")
                os.write(buf, 0, k)
            }
        }
    }

    private companion object {
        const val TAG = "GadgetbridgeImporter"

        // Ref: Gadgetbridge 160beab41 app/src/main/java/nodomain/freeyourgadget/gadgetbridge/util/backup/AbstractZipBackupJob.java (zip layout: database/Gadgetbridge, preferences/*.json, files/, gadgetbridge.json)
        const val DB_ENTRY = "database/Gadgetbridge"
        const val META_ENTRY = "gadgetbridge.json"
        const val FILES_DIR = "files"
        const val MAX_DB_BYTES = 4L shl 30
    }
}

/** A workout summary row. */
internal data class GbSummary(val id: Long, val name: String?, val startMs: Long, val endMs: Long, val rawDetailsPath: String?)

/** What [GbReader] found. */
internal class GbRead(val devices: List<app.selfwatch.interop.api.DeviceSeries>, val body: List<BodyMeasure>, val summaries: List<GbSummary>)

/**
 * Reads the Garmin tables of a Gadgetbridge database. Table and column names follow greenDAO's
 * naming of Gadgetbridge's entities (class `GarminActivitySample` → table `GARMIN_ACTIVITY_SAMPLE`,
 * property `distanceCm` → column `DISTANCE_CM`).
 */
// Ref: Gadgetbridge 160beab41 GBDaoGenerator/src/nodomain/freeyourgadget/gadgetbridge/daogen/GBDaoGenerator.java (entity and property names: Device, GarminActivitySample, GarminStressSample, GarminSpo2Sample, GarminSleepStageSample, GarminSleepStatsSample, GarminHrvValueSample, GarminRespiratoryRateSample, GarminHeartRateRestingSample, GenericWeightSample/*WeightSample weightKg, BaseActivitySummary; activity samples keyed by int TIMESTAMP seconds, time samples by long TIMESTAMP ms)
internal class GbReader(private val d: SQLiteDatabase, private val warnings: MutableList<String>) {
    private val tables: Set<String> = HashSet<String>().also { s ->
        d.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c -> while (c.moveToNext()) s.add(c.getString(0)) }
    }

    private val builders = LinkedHashMap<Long, SeriesBuilder>()
    private val deviceNames = HashMap<Long, Pair<String, String>>()

    /** Reads everything. */
    fun read(): GbRead {
        readDevices()
        readActivity()
        timeSamples("GARMIN_STRESS_SAMPLE", "STRESS") { dev, t, v -> if (v in 0.0..100.0) add(dev, MetricKind.STRESS, t, v.roundToInt()) }
        timeSamples("GARMIN_SPO2_SAMPLE", "SPO2") { dev, t, v -> if (v in 50.0..100.0) add(dev, MetricKind.SPO2, t, v.roundToInt()) }
        timeSamples("GARMIN_RESPIRATORY_RATE_SAMPLE", "RESPIRATORY_RATE") { dev, t, v ->
            if (v > 0 && v < 100) add(dev, MetricKind.RESPIRATION, t, (v * 10).roundToInt())
        }
        timeSamples("GARMIN_HEART_RATE_RESTING_SAMPLE", "HEART_RATE") { dev, t, v -> if (v in 20.0..250.0) add(dev, MetricKind.RESTING_HR, t, v.roundToInt()) }
        timeSamples("GARMIN_HRV_VALUE_SAMPLE", "VALUE") { dev, t, v -> if (v in 1.0..1000.0) add(dev, MetricKind.HRV_RMSSD, t, v.roundToInt()) }
        val stagesByDevice = HashMap<Long, MutableList<SleepStageSample>>()
        timeSamples("GARMIN_SLEEP_STAGE_SAMPLE", "STAGE") { dev, t, v ->
            // FIT sleep_level codes: 0 unmeasurable, 1 awake, 2 light, 3 deep, 4 REM.
            val stage = when (v.toInt()) {
                0 -> SleepStage.UNMEASURABLE
                1 -> SleepStage.AWAKE
                2 -> SleepStage.LIGHT
                3 -> SleepStage.DEEP
                4 -> SleepStage.REM
                else -> null
            } ?: return@timeSamples
            val s = SleepStageSample(0, t, stage)
            builder(dev).stages[t] = s
            stagesByDevice.getOrPut(dev) { ArrayList() }.add(s)
        }
        val scores = HashMap<Long, MutableList<Pair<Long, Int>>>()
        timeSamples("GARMIN_SLEEP_STATS_SAMPLE", "SLEEP_SCORE") { dev, t, v -> if (v in 0.0..100.0) scores.getOrPut(dev) { ArrayList() }.add(t to v.roundToInt()) }
        for ((dev, stages) in stagesByDevice) {
            val devScores = scores[dev].orEmpty()
            val sessions = SeriesBuilder.sessionsFromStages(stages) { start, end ->
                devScores.firstOrNull { it.first in start - 3600..end + 4 * 3600 }?.second
            }
            for (s in sessions) builder(dev).sessions[s.startSec] = s
        }
        val body = readWeights()
        val summaries = readSummaries()
        return GbRead(builders.values.map { it.build() }, body, summaries)
    }

    private fun columns(table: String): Set<String> {
        if (table !in tables) return emptySet()
        val out = HashSet<String>()
        d.rawQuery("PRAGMA table_info(\"$table\")", null).use { c ->
            val i = c.getColumnIndex("name")
            while (c.moveToNext()) out.add(c.getString(i))
        }
        return out
    }

    private fun readDevices() {
        val cols = columns("DEVICE")
        if (cols.isEmpty() || "_id" !in cols) return
        val name = if ("NAME" in cols) "NAME" else "NULL"
        val ident = if ("IDENTIFIER" in cols) "IDENTIFIER" else "NULL"
        val model = when {
            "MODEL" in cols -> "MODEL"
            "TYPE_NAME" in cols -> "TYPE_NAME"
            else -> "NULL"
        }
        d.rawQuery("SELECT _id, $name, $ident, $model FROM DEVICE", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val n = c.strOrNull(1) ?: "device $id"
                deviceNames[id] = n to (c.strOrNull(3) ?: "")
                keys[id] = c.strOrNull(2) ?: "gb-$id"
            }
        }
    }

    private val keys = HashMap<Long, String>()

    private fun builder(dev: Long): SeriesBuilder = builders.getOrPut(dev) {
        val (n, m) = deviceNames[dev] ?: ("device $dev" to "")
        SeriesBuilder(keys[dev] ?: "gb-$dev", "Gadgetbridge: $n", m)
    }

    private fun add(dev: Long, kind: MetricKind, tSec: Long, value: Int) = builder(dev).metric(MetricSample(0, kind, tSec, value))

    private fun readActivity() {
        val t = "GARMIN_ACTIVITY_SAMPLE"
        val cols = columns(t)
        if (cols.isEmpty()) return
        if (!cols.containsAll(listOf("TIMESTAMP", "DEVICE_ID"))) {
            warnings.add("$t has an unknown layout; skipped")
            return
        }
        fun col(n: String) = if (n in cols) n else "-1"
        d.rawQuery(
            "SELECT TIMESTAMP, DEVICE_ID, ${col("STEPS")}, ${col("DISTANCE_CM")}, ${col("ACTIVE_CALORIES")}, ${col("RAW_INTENSITY")}, " +
                "${col("HEART_RATE")} FROM $t ORDER BY TIMESTAMP",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val ts = c.getLong(0)
                if (!SeriesBuilder.plausible(ts)) continue
                val dev = c.getLong(1)
                val steps = c.measured(2)
                val dist = c.measured(3)
                val kcal = c.measured(4)
                val intensity = c.measured(5)
                val hr = c.measured(6)
                if (hr in 20..250) add(dev, MetricKind.HEART_RATE, ts, hr)
                if (steps <= 0 && dist <= 0 && kcal <= 0 && intensity <= 0) continue
                val minute = ts - ts % 60
                val b = builder(dev)
                val prev = b.activity[minute]
                b.activity[minute] = ActivityMinute(
                    deviceId = 0, tSec = minute,
                    steps = (prev?.steps ?: 0) + steps.coerceAtLeast(0),
                    activeKcalX10 = (prev?.activeKcalX10 ?: 0) + kcal.coerceAtLeast(0) * 10,
                    distanceCm = (prev?.distanceCm ?: 0) + dist.coerceAtLeast(0),
                    intensity = maxOf(prev?.intensity ?: 0, intensity.coerceAtLeast(0)),
                    activityType = null,
                )
            }
        }
    }

    /** Calls [onRow] with (device, epoch second, value) for each row of a millisecond time-sample table. */
    private fun timeSamples(table: String, valueCol: String, onRow: (Long, Long, Double) -> Unit) {
        val cols = columns(table)
        if (cols.isEmpty()) return
        if (!cols.containsAll(listOf("TIMESTAMP", "DEVICE_ID", valueCol))) {
            warnings.add("$table has an unknown layout; skipped")
            return
        }
        d.rawQuery("SELECT TIMESTAMP, DEVICE_ID, $valueCol FROM $table WHERE $valueCol IS NOT NULL ORDER BY TIMESTAMP", null).use { c ->
            while (c.moveToNext()) {
                val t = c.getLong(0) / 1000
                if (!SeriesBuilder.plausible(t)) continue
                onRow(c.getLong(1), t, c.getDouble(2))
            }
        }
    }

    private fun readWeights(): List<BodyMeasure> {
        val out = ArrayList<BodyMeasure>()
        val seen = HashSet<Long>()
        for (t in tables.filter { it.endsWith("WEIGHT_SAMPLE") }.sorted()) {
            val cols = columns(t)
            if (!cols.containsAll(listOf("TIMESTAMP", "WEIGHT_KG"))) continue
            d.rawQuery("SELECT TIMESTAMP, WEIGHT_KG FROM $t WHERE WEIGHT_KG IS NOT NULL ORDER BY TIMESTAMP", null).use { c ->
                while (c.moveToNext()) {
                    val ts = c.getLong(0) / 1000
                    val kg = c.getDouble(1)
                    if (!SeriesBuilder.plausible(ts) || kg !in 1.0..500.0 || !seen.add(ts)) continue
                    out.add(BodyMeasure(0, ts, BodyKind.WEIGHT, null, (kg * 100).roundToInt(), DataSource.IMPORT, null, null))
                }
            }
        }
        return out
    }

    private fun readSummaries(): List<GbSummary> {
        val t = "BASE_ACTIVITY_SUMMARY"
        val cols = columns(t)
        if (cols.isEmpty()) return emptyList()
        if (!cols.containsAll(listOf("_id", "START_TIME", "END_TIME"))) {
            warnings.add("$t has an unknown layout; skipped")
            return emptyList()
        }
        val name = if ("NAME" in cols) "NAME" else "NULL"
        val path = if ("RAW_DETAILS_PATH" in cols) "RAW_DETAILS_PATH" else "NULL"
        val out = ArrayList<GbSummary>()
        d.rawQuery("SELECT _id, $name, START_TIME, END_TIME, $path FROM $t ORDER BY START_TIME", null).use { c ->
            while (c.moveToNext()) {
                val start = c.getLong(2)
                if (!SeriesBuilder.plausible(start / 1000)) continue
                out.add(GbSummary(c.getLong(0), c.strOrNull(1), start, c.getLong(3), c.strOrNull(4)))
            }
        }
        return out
    }

    private companion object {
        fun Cursor.strOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

        /** The value, or -1 (Gadgetbridge's "not measured") when null. */
        fun Cursor.measured(i: Int): Int = if (isNull(i)) -1 else getInt(i)
    }
}
