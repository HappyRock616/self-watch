/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.db

import android.content.Context
import app.selfwatch.db.CustomFoodOrigin
import app.selfwatch.db.RecipeOrigin
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.db.dao.CustomFood
import app.selfwatch.interop.api.ImportData
import app.selfwatch.interop.api.ImportResult
import app.selfwatch.interop.api.ImportSink
import app.selfwatch.interop.api.ParsedWorkout
import app.selfwatch.interop.api.TrackFormat
import app.selfwatch.interop.io.LocalStamp
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.device.SupportTier
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.Workout
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/**
 * The [ImportSink] backed by `selfwatch.db`.
 *
 * [write] stores one import as one batch: `ImportDao.begin`, every row tagged with the batch id, then
 * `ImportDao.setCounts`, all inside **one** SQLite transaction, so a failure leaves nothing behind.
 * Afterwards it notifies [app.selfwatch.db.DbChanges] for the tables it touched.
 *
 * - **Diary**: meal names go to meal slots as [MealSlots] describes (no slots are created). Lines
 *   without a time get the slot's nominal time.
 * - **Exercises** (calorie-app exercise logs) become workouts without a track: generic sport (0),
 *   source IMPORT, starting at the logged time or noon.
 * - **Notes** have no table in `selfwatch.db` and are not written (they are left out of the counts).
 * - **Workouts**: the original file is saved under `files/workouts/import/<importId>/` and its path,
 *   relative to `filesDir` (see [AppFiles]), goes into `fit_path` (FIT) or `track_path` (GPX, TCX).
 *   On failure the folder is removed again.
 * - **Watch data** ([app.selfwatch.interop.api.DeviceSeries]): each series gets a device row of its
 *   own (address `import:<importId>:<sourceKey>`, marked removed so it is never connected to), and its
 *   activity minutes, metrics and sleep go in under that device, tagged with the batch's `import_id`
 *   like every other imported row, so `ImportDao.undo` removes them. [undo] also removes the device
 *   rows (and, for batches written before `import_id` existed on watch data, the rows under them).
 *
 * The whole import runs in one [SelfWatchDb.inTransaction], which the DAO calls join.
 */
class DbImportSink(
    private val db: SelfWatchDb,
    private val files: AppFiles,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: Clock = SystemClock,
) : ImportSink {

    /** The sink for this process's database and files. */
    constructor(ctx: Context, zone: ZoneId = ZoneId.systemDefault()) : this(SelfWatchDb.get(ctx), AppFiles(ctx.filesDir), zone)

    override suspend fun countDuplicates(importerId: String, data: ImportData): Int = withContext(Dispatchers.IO) {
        try {
            val d = db.readableDatabase
            Duplicates.find(d, data, MealSlots.load(d), zone).total
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SwLog.w(TAG, "duplicate check failed", e)
            0
        }
    }

    override suspend fun write(importerId: String, fileName: String, data: ImportData, skipDuplicates: Boolean): Outcome<ImportResult> =
        withContext(Dispatchers.IO) { writeOnIo(importerId, fileName, data, skipDuplicates) }

    private suspend fun writeOnIo(importerId: String, fileName: String, data: ImportData, skipDuplicates: Boolean): Outcome<ImportResult> {
        var importDir: File? = null
        return try {
            // The DAO calls inside announce the tables they write; the notice goes out after the commit.
            val result = db.inTransaction(setOf(Table.IMPORT_BATCH)) { d ->
                val now = clock.nowSec()
                val id = db.imports.begin(now, importerId, fileName)
                importDir = files.importDir(id)
                val slots = MealSlots.load(d)
                val dups = if (skipDuplicates) Duplicates.find(d, data, slots, zone) else Duplicates.none(data)
                val counts = LinkedHashMap<String, Long>()
                writeAll(id, now, importerId, data, dups, slots, counts)
                db.imports.setCounts(id, counts)
                ImportResult(id, counts.mapValues { it.value.toInt() })
            }
            SwLog.i(TAG, "import ${result.importId} ($importerId) wrote ${result.counts.values.sum()} rows")
            Outcome.Ok(result)
        } catch (e: CancellationException) {
            importDir?.deleteRecursively()
            throw e
        } catch (e: Exception) {
            importDir?.deleteRecursively()
            SwLog.e(TAG, "import ($importerId) failed", e)
            Outcome.Fail(SwError.Io("import failed: ${e.javaClass.simpleName}"))
        }
    }

    private suspend fun writeAll(
        id: Long,
        now: Long,
        importerId: String,
        data: ImportData,
        dups: Duplicates,
        slots: MealSlots,
        counts: MutableMap<String, Long>,
    ) {
        fun count(key: String, n: Int) {
            if (n <= 0) return
            counts[key] = (counts[key] ?: 0L) + n
        }

        val diary = data.diary.filterIndexed { i, _ -> !dups.diary[i] }.map { r ->
            val (slotId, nominal) = slots.slotFor(r.meal, r.secOfDay)
            DiaryEntry(
                id = 0, day = r.day, mealSlotId = slotId,
                tSec = LocalStamp(r.day, (r.secOfDay ?: nominal).coerceIn(0, Duplicates.MAX_SEC)).epochSec(zone),
                food = null, name = r.name, brand = r.brand, amountX100 = r.amountX100, servingLabel = r.servingLabel,
                gramsX10 = r.gramsX10, nutrients = r.nutrients, importId = id,
            )
        }
        count("diary", db.diary.addAll(diary))

        var n = 0
        data.body.forEachIndexed { i, m ->
            if (dups.body[i]) return@forEachIndexed
            db.body.insert(m.copy(id = 0, importId = id))
            n++
        }
        count("body", n)

        n = 0
        data.exercises.forEachIndexed { i, e ->
            if (dups.exercises[i]) return@forEachIndexed
            val start = Duplicates.exerciseStart(e.day, e.secOfDay, zone)
            val dur = (e.durationSec ?: 0).coerceAtLeast(0)
            val w = Workout(
                id = 0, deviceId = null, sport = GENERIC_SPORT, subSport = null, startSec = start, endSec = start + dur,
                durationSec = dur, distanceM = null, kcal = e.kcal, avgHr = null, maxHr = null, ascentM = null, descentM = null,
                hasTrack = false, fitPath = null, trackPath = null, routePreview = null, source = DataSource.IMPORT,
            )
            db.workouts.insert(w, id)
            n++
        }
        count("exercise", n)

        n = 0
        data.workouts.forEachIndexed { i, p ->
            if (dups.workouts[i]) return@forEachIndexed
            db.workouts.insert(stored(id, i, p), id)
            n++
        }
        count("workout", n)

        n = 0
        data.foods.forEachIndexed { i, f ->
            if (dups.foods[i]) return@forEachIndexed
            db.foods.insert(
                CustomFood(
                    food = f.copy(ref = FoodRef(FoodOrigin.MY_FOOD, 0)), origin = CustomFoodOrigin.IMPORT, originRef = importerId,
                    hidden = false, createdSec = now, updatedSec = now, importId = id,
                ),
            )
            n++
        }
        count("food", n)

        n = 0
        data.recipes.forEachIndexed { i, r ->
            if (dups.recipes[i]) return@forEachIndexed
            db.recipes.insert(r.copy(id = 0, importId = id), now, RecipeOrigin.IMPORT, importerId)
            n++
        }
        count("recipe", n)

        n = 0
        data.water.forEachIndexed { i, w ->
            if (dups.water[i] || w.ml <= 0) return@forEachIndexed
            db.water.add(w.day, Duplicates.waterTime(w.day, w.secOfDay, zone), w.ml, id)
            n++
        }
        count("water", n)

        data.devices.forEachIndexed { si, s ->
            if (s.isEmpty()) return@forEachIndexed
            val activity = s.activity.filterIndexed { i, _ -> !dups.activity[si][i] }
            val metrics = s.metrics.filterIndexed { i, _ -> !dups.metrics[si][i] }
            val stages = s.sleepStages.filterIndexed { i, _ -> !dups.stages[si][i] }
            val sessions = s.sleepSessions.filterIndexed { i, _ -> !dups.sessions[si][i] }
            if (activity.isEmpty() && metrics.isEmpty() && stages.isEmpty() && sessions.isEmpty()) return@forEachIndexed
            val devId = db.devices.upsert(
                DeviceInfo(
                    id = 0, kind = DeviceKind.GARMIN_WATCH, address = deviceAddress(id, s.sourceKey), name = s.name.take(80),
                    model = s.model.take(80), productId = null, firmware = null, serial = null, tier = SupportTier.UNVERIFIED,
                    features = emptySet(), batteryPct = null,
                ),
                now,
            )
            db.devices.markRemoved(devId)
            count("activity", db.activity.insert(activity.map { it.copy(deviceId = devId) }, id))
            count("metric", db.metrics.insert(metrics.map { it.copy(deviceId = devId) }, id))
            count("sleep_stage", db.sleep.insertStages(stages.map { it.copy(deviceId = devId) }, id))
            var ns = 0
            for (ss in sessions) {
                db.sleep.insertSession(ss.copy(deviceId = devId), id)
                ns++
            }
            count("sleep_session", ns)
        }
    }

    /** [p]'s workout with its original saved under the batch folder. */
    private fun stored(importId: Long, index: Int, p: ParsedWorkout): Workout {
        var fitPath: String? = null
        var trackPath: String? = null
        if (p.original.isNotEmpty()) {
            val dir = files.importDir(importId)
            if (!dir.isDirectory && !dir.mkdirs()) throw java.io.IOException("cannot create ${dir.name}")
            var name = AppFiles.safeName(p.fileName)
            if (!name.lowercase().endsWith("." + p.format.extension)) name += "." + p.format.extension
            val f = File(dir, "$index-$name")
            f.writeBytes(p.original)
            if (p.format == TrackFormat.FIT) fitPath = files.relative(f) else trackPath = files.relative(f)
        }
        return p.workout.copy(
            id = 0, deviceId = null, fitPath = fitPath, trackPath = trackPath, source = DataSource.IMPORT,
            hasTrack = p.workout.hasTrack || p.points.any { it.latE7 != null },
        )
    }

    /**
     * Removes import batch [importId]: everything `ImportDao.undo` removes (watch data included, by
     * `import_id`), the batch's own device rows, and the saved workout originals. Watch rows under
     * those device rows that carry no `import_id` (batches written before it existed) go too.
     * Returns the number of rows deleted (cascaded child rows and device rows not counted).
     */
    suspend fun undo(importId: Long): Int {
        val n = db.inTransaction(setOf(Table.DEVICE)) { d ->
            var deleted = db.imports.undo(importId)
            val devices = ArrayList<Long>()
            d.rawQuery("SELECT id FROM device WHERE address LIKE ?", arrayOf("import:$importId:%")).use { c ->
                while (c.moveToNext()) devices.add(c.getLong(0))
            }
            for (dev in devices) {
                val a = arrayOf(dev.toString())
                for (t in WATCH_TABLES) deleted += d.delete(t, "device_id = ?", a)
                d.delete("device", "id = ?", a)
            }
            deleted
        }
        withContext(Dispatchers.IO) { files.importDir(importId).deleteRecursively() }
        return n
    }

    /** Constants. */
    companion object {
        private const val TAG = "DbImportSink"

        /** FIT sport "generic", used for exercise-log entries. */
        const val GENERIC_SPORT: Int = 0

        /** Tables with watch data; rows left under an import's device rows are removed with it. */
        private val WATCH_TABLES = listOf("activity_minute", "metric", "sleep_stage", "sleep_session")

        /** The address of the device row that holds watch data of import [importId] from source device [sourceKey]. */
        fun deviceAddress(importId: Long, sourceKey: String): String = "import:$importId:${sourceKey.take(64)}"
    }
}
