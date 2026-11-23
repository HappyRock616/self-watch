/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.hc

import android.health.connect.datatypes.BodyFatRecord
import android.health.connect.datatypes.ExerciseSessionRecord
import android.health.connect.datatypes.NutritionRecord
import android.health.connect.datatypes.Record
import android.health.connect.datatypes.SleepSessionRecord
import android.health.connect.datatypes.StepsRecord
import android.health.connect.datatypes.WeightRecord
import android.os.Build
import androidx.annotation.RequiresApi
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.interop.db.MealSlots
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * Reads Health Connect into `selfwatch.db`, using one changes token per type (`hc.changes_token.<type>`).
 *
 * - **First run, or the token expired:** a new token is taken first (so nothing is missed), then the
 *   last [WINDOW_SEC] (30 days) are read with `readRecords`; re-reading is safe because rows are matched
 *   as below.
 * - **Later runs:** `getChanges` from the stored token; the token is stored after each page is applied.
 * - Records written by this app ([ownPackage] data origin, or an `sw:` client record id) are skipped.
 * - Deletions are not applied (Health Connect gives only its own record id, which no table stores).
 *
 * Where records go (the least invasive choice for each, so watch data always wins):
 * | Type | Stored as | Matched on re-read by |
 * |---|---|---|
 * | Weight, body fat | `body_measure`, source HEALTH_CONNECT | kind and time (value updated) |
 * | Nutrition | `diary_entry` in the meal slot for its meal type ([MealSlots]) | time and name, within Health Connect batches |
 * | Exercise | `workout`, source HEALTH_CONNECT, no device, no track; skipped when a workout from another source starts within 2 minutes | start time |
 * | Sleep | `sleep_session` and stages under the `health-connect` device row; skipped when another device has a session overlapping it | device and start (stages replaced) |
 * | Steps | `activity_minute` under the `health-connect` device row (spread evenly over the record's minutes); minutes in hours where a real device has data are dropped | device and minute (replaced) |
 *
 * The `health-connect` device row is marked removed (never connected) and ranks below every real
 * device, so `day_summary` prefers watch data minute by minute. No summary rows are written here.
 * Diary, body and workout rows are tagged with one long-lived import batch (`hc.import_id`, importer
 * `health-connect`) so the import screen can undo them; steps and sleep have no `import_id` column.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class HcReader(
    private val db: SelfWatchDb,
    private val api: HcApi,
    private val ownPackage: String,
    private val zone: ZoneId,
    private val clock: Clock,
    private val retry: HcRetry = HcRetry(),
) {
    private val store = HcStore(db)

    /**
     * Reads every [HcType.READ] type whose read permission is in [granted]. Returns the rows stored per
     * type. Throws like [HcWriter.writeAll].
     */
    suspend fun readAll(granted: Set<String>): Map<HcType, Int> {
        val out = LinkedHashMap<HcType, Int>()
        val counts = LinkedHashMap<String, Long>()
        for (type in HcType.READ) {
            if (type.readPermission !in granted) continue
            val n = guarded(type) { readType(type) }
            out[type] = n
            if (n > 0) counts[type.key] = n.toLong()
        }
        addCounts(counts)
        SwLog.i(TAG, "read ${out.values.sum()} rows")
        return out
    }

    /**
     * Reads [types] between [startSec] and [endSec] with `readRecords` (an explicit import, for example
     * of history; the caller checks the history permission). Tokens are not touched.
     */
    suspend fun readRange(types: Collection<HcType>, startSec: Long, endSec: Long): Map<HcType, Int> {
        val out = LinkedHashMap<HcType, Int>()
        val counts = LinkedHashMap<String, Long>()
        for (type in types) {
            if (!type.read) continue
            val n = guarded(type) { readWindow(type, startSec, endSec) }
            out[type] = n
            if (n > 0) counts[type.key] = n.toLong()
        }
        addCounts(counts)
        return out
    }

    private suspend fun <T> guarded(type: HcType, block: suspend () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        throw HcPermissionException(type.readPermission, e)
    }

    private suspend fun readType(type: HcType): Int {
        val key = HcKeys.changesToken(type)
        var token = db.settings.get(key) ?: return restart(type)
        var n = 0
        while (true) {
            val page = retry.run { api.getChanges(token) }
            if (page.tokenExpired) {
                SwLog.i(TAG, "${type.key} token expired, re-reading ${WINDOW_SEC / 86_400} days")
                return n + restart(type)
            }
            n += apply(type, page.upserts)
            token = page.nextToken
            db.settings.set(key, token)
            if (!page.hasMore) return n
        }
    }

    /** New token first, then the last [WINDOW_SEC]; the token is stored once the window is in. */
    private suspend fun restart(type: HcType): Int {
        val token = retry.run { api.changesToken(setOf(type.recordClass())) }
        val now = clock.nowSec()
        val n = readWindow(type, now - WINDOW_SEC, now)
        db.settings.set(HcKeys.changesToken(type), token)
        return n
    }

    private suspend fun readWindow(type: HcType, startSec: Long, endSec: Long): Int {
        var n = 0
        var pageToken: String? = null
        do {
            val page = retry.run { api.readRecords(type.recordClass(), Instant.ofEpochSecond(startSec), Instant.ofEpochSecond(endSec), pageToken) }
            n += apply(type, page.records)
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return n
    }

    /** True when [r] was written by this app. */
    internal fun isOwn(r: Record): Boolean =
        r.metadata.dataOrigin.packageName == ownPackage || HcIds.isOurs(r.metadata.clientRecordId)

    /** Stores the records of [type] not written by this app. Returns the rows stored or updated. */
    internal suspend fun apply(type: HcType, records: List<Record>): Int {
        val foreign = records.filter { type.recordClass().isInstance(it) && !isOwn(it) }
        if (foreign.isEmpty()) return 0
        return when (type) {
            HcType.WEIGHT -> body(foreign.filterIsInstance<WeightRecord>().map { HcMapping.bodyFrom(it, importId()) })
            HcType.BODY_FAT -> body(foreign.filterIsInstance<BodyFatRecord>().map { HcMapping.bodyFrom(it, importId()) })
            HcType.NUTRITION -> nutrition(foreign.filterIsInstance<NutritionRecord>())
            HcType.STEPS -> steps(foreign.filterIsInstance<StepsRecord>())
            HcType.EXERCISE -> exercise(foreign.filterIsInstance<ExerciseSessionRecord>())
            HcType.SLEEP -> sleep(foreign.filterIsInstance<SleepSessionRecord>())
            else -> 0
        }
    }

    private suspend fun body(rows: List<BodyMeasure>): Int {
        for (m in rows) {
            val existing = store.query { d ->
                d.rawQuery(
                    "SELECT id, import_id FROM body_measure WHERE kind = ? AND t_sec = ? AND source = ?",
                    arrayOf(m.kind.code.toString(), m.tSec.toString(), DataSource.HEALTH_CONNECT.code.toString()),
                ).use { c -> if (c.moveToFirst()) c.getLong(0) to (if (c.isNull(1)) null else c.getLong(1)) else null }
            }
            if (existing != null) db.body.update(m.copy(id = existing.first, importId = existing.second)) else db.body.insert(m)
        }
        return rows.size
    }

    private suspend fun nutrition(records: List<NutritionRecord>): Int {
        val slots = store.query { MealSlots.load(it) }
        val importId = importId()
        for (r in records) {
            val e = HcMapping.diaryFrom(r, slots, zone, importId)
            val existing = store.query { d ->
                d.rawQuery(
                    "SELECT id, import_id FROM diary_entry WHERE t_sec = ? AND name = ? AND import_id IN (${store.hcBatchesSql})",
                    arrayOf(e.tSec.toString(), e.name),
                ).use { c -> if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else null }
            }
            if (existing != null) db.diary.update(e.copy(id = existing.first, importId = existing.second)) else db.diary.add(e)
        }
        return records.size
    }

    private suspend fun exercise(records: List<ExerciseSessionRecord>): Int {
        var n = 0
        for (r in records) {
            val w = HcMapping.workoutFrom(r)
            val other = store.query { d ->
                d.rawQuery(
                    "SELECT COUNT(*) FROM workout WHERE source != ? AND start_sec BETWEEN ? AND ?",
                    arrayOf(DataSource.HEALTH_CONNECT.code.toString(), (w.startSec - SAME_WORKOUT_SEC).toString(), (w.startSec + SAME_WORKOUT_SEC).toString()),
                ).use { c -> c.moveToFirst() && c.getLong(0) > 0 }
            }
            if (other) continue
            val existing = store.query { d ->
                d.rawQuery(
                    "SELECT id FROM workout WHERE source = ? AND start_sec = ?",
                    arrayOf(DataSource.HEALTH_CONNECT.code.toString(), w.startSec.toString()),
                ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            }
            if (existing != null) db.workouts.update(w.copy(id = existing)) else db.workouts.insert(w, importId())
            n++
        }
        return n
    }

    private suspend fun sleep(records: List<SleepSessionRecord>): Int {
        val dev = store.ensureHcDevice(clock.nowSec())
        var n = 0
        for (r in records) {
            val (session, stages) = HcMapping.sleepFrom(r, dev)
            if (session.endSec <= session.startSec) continue
            val overlap = store.query { d ->
                d.rawQuery(
                    "SELECT COUNT(*) FROM sleep_session WHERE device_id != ? AND start_sec < ? AND end_sec > ?",
                    arrayOf(dev.toString(), session.endSec.toString(), session.startSec.toString()),
                ).use { c -> c.moveToFirst() && c.getLong(0) > 0 }
            }
            if (overlap) continue
            db.sleep.insertSession(session)
            withContext(Dispatchers.IO) {
                db.writableDatabase.delete(
                    "sleep_stage", "device_id = ? AND t_sec >= ? AND t_sec < ?",
                    arrayOf(dev.toString(), session.startSec.toString(), session.endSec.toString()),
                )
            }
            db.changes.notify(setOf(Table.SLEEP_STAGE))
            db.sleep.insertStages(stages)
            n++
        }
        return n
    }

    private suspend fun steps(records: List<StepsRecord>): Int {
        val dev = store.ensureHcDevice(clock.nowSec())
        val byMinute = HashMap<Long, ActivityMinute>()
        for (r in records) {
            for (m in HcMapping.stepsToMinutes(r, dev)) {
                val cur = byMinute[m.tSec]
                if (cur == null || m.steps > cur.steps) byMinute[m.tSec] = m
            }
        }
        if (byMinute.isEmpty()) return 0
        val minSec = byMinute.keys.min()
        val maxSec = byMinute.keys.max()
        val busyHours = store.query { d ->
            d.rawQuery(
                "SELECT DISTINCT t_sec / $HOUR FROM activity_minute WHERE device_id != ? AND t_sec >= ? AND t_sec <= ?",
                arrayOf(dev.toString(), (Math.floorDiv(minSec, HOUR) * HOUR).toString(), (maxSec + HOUR).toString()),
            ).use { c ->
                val s = HashSet<Long>()
                while (c.moveToNext()) s += c.getLong(0)
                s
            }
        }
        val keep = byMinute.values.filter { Math.floorDiv(it.tSec, HOUR) !in busyHours }.sortedBy { it.tSec }
        return db.activity.insert(keep)
    }

    private var importIdCache: Long? = null

    /** The long-lived Health Connect import batch, created (or re-created after an undo) when needed. */
    private suspend fun importId(): Long {
        importIdCache?.let { return it }
        val stored = db.settings.getLong(HcKeys.IMPORT_ID)
        val ok = stored?.let { db.imports.get(it) }?.takeIf { !it.undone && it.importer == HcStore.IMPORTER }
        val id = ok?.id ?: db.imports.begin(clock.nowSec(), HcStore.IMPORTER, null).also { db.settings.setLong(HcKeys.IMPORT_ID, it) }
        importIdCache = id
        return id
    }

    private suspend fun addCounts(add: Map<String, Long>) {
        if (add.isEmpty()) return
        val id = importId()
        val cur = db.imports.get(id)?.counts ?: emptyMap()
        val merged = LinkedHashMap(cur)
        for ((k, v) in add) merged[k] = (merged[k] ?: 0L) + v
        db.imports.setCounts(id, merged)
    }

    /** Constants. */
    companion object {
        private const val TAG = "HcReader"
        private const val HOUR = 3600L

        /** What a first read (or a read after token expiry) covers: 30 days, the default Health Connect read window. */
        const val WINDOW_SEC: Long = 30 * 86_400L

        /** Workouts from other sources starting this close are treated as the same session. */
        const val SAME_WORKOUT_SEC: Long = 120
    }
}
