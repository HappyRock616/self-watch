/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.hc

import android.database.Cursor
import android.health.connect.datatypes.Record
import android.os.Build
import androidx.annotation.RequiresApi
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.health.Workout
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.TimeRange
import java.time.ZoneId

/** A permission the sync needed was not granted (or was revoked during the run). */
class HcPermissionException(val permission: String, cause: Throwable? = null) : Exception("missing $permission", cause)

/**
 * Writes `selfwatch.db` rows to Health Connect.
 *
 * - Each record carries `clientRecordId` = [HcIds] (`sw:<table>:<id>`) and `clientRecordVersion` =
 *   the run's time in ms, so writing a row again updates its record instead of adding a duplicate.
 * - Per type it writes the rows whose time lies between the end of the last successful write
 *   (`hc.last_write.<type>`) minus [OVERLAP_SEC] and now, so recent edits are sent again; the first
 *   write covers the last [FIRST_WINDOW_SEC]. The key is advanced only after every batch of the type
 *   was accepted.
 * - Calls carry at most [BATCH_MAX] records.
 * - Rows that came from Health Connect (source HEALTH_CONNECT, the Health Connect import batch, the
 *   `health-connect` device) are not written back.
 * - Activity minutes and heart rate are grouped per device and clock hour (one steps, distance,
 *   active-calories and heart-rate record per hour) to keep the record count small.
 *
 * Not written: deletions (a row deleted in Self Watch stays in Health Connect until the user removes it
 * there) and edits to rows older than the overlap window.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class HcWriter(
    private val db: SelfWatchDb,
    private val api: HcApi,
    private val zone: ZoneId,
    private val clock: Clock,
    private val retry: HcRetry = HcRetry(),
) {
    private val store = HcStore(db)

    /**
     * Writes every [HcType.WRITTEN] type whose write permission is in [granted]. Returns the number of
     * records written per type. Throws [HcPermissionException] when Health Connect refuses a type's
     * permission, [HcRateLimitedException] after the retries are used up, or the API's other errors.
     */
    suspend fun writeAll(granted: Set<String>): Map<HcType, Int> {
        val out = LinkedHashMap<HcType, Int>()
        val now = clock.nowSec()
        val version = now * 1000
        val activityCache = HashMap<Long, List<ActivityHour>>()
        for (type in HcType.WRITTEN) {
            if (type.writePermission !in granted) continue
            val from = (db.settings.getLong(HcKeys.lastWrite(type)) ?: (now - FIRST_WINDOW_SEC + OVERLAP_SEC)) - OVERLAP_SEC
            val records = load(type, TimeRange(from.coerceAtMost(now), now), version, activityCache)
            val n = send(type, records)
            db.settings.setLong(HcKeys.lastWrite(type), now)
            out[type] = n
        }
        SwLog.i(TAG, "wrote ${out.values.sum()} records")
        return out
    }

    /** Sends [records] in batches of at most [BATCH_MAX]. */
    internal suspend fun send(type: HcType, records: List<Record>): Int {
        var n = 0
        for (chunk in records.chunked(BATCH_MAX)) {
            try {
                retry.run { api.insert(chunk) }
            } catch (e: SecurityException) {
                throw HcPermissionException(type.writePermission, e)
            }
            n += chunk.size
        }
        return n
    }

    /** The records of [type] for rows in [range]. */
    internal suspend fun load(type: HcType, range: TimeRange, version: Long, activityCache: MutableMap<Long, List<ActivityHour>>): List<Record> {
        val hcDev = store.hcDeviceId() ?: NO_DEVICE
        return when (type) {
            HcType.WEIGHT -> body(BodyKind.WEIGHT, range).mapNotNull { HcMapping.weight(it, version, zone) }
            HcType.BODY_FAT -> body(BodyKind.BODY_FAT, range).mapNotNull { HcMapping.bodyFat(it, version, zone) }
            HcType.NUTRITION -> diary(range).mapNotNull { HcMapping.nutrition(it, version, zone) }
            HcType.HYDRATION -> water(range).mapNotNull { HcMapping.hydration(it, version, zone) }
            HcType.STEPS -> activityHours(range, hcDev, activityCache).mapNotNull { HcMapping.steps(it, version, zone) }
            HcType.DISTANCE -> activityHours(range, hcDev, activityCache).mapNotNull { HcMapping.distance(it, version, zone) }
            HcType.ACTIVE_KCAL -> activityHours(range, hcDev, activityCache).mapNotNull { HcMapping.activeKcal(it, version, zone) }
            HcType.EXERCISE -> workouts(range).mapNotNull { HcMapping.exercise(it, version, zone) }
            HcType.SLEEP -> sleeps(range, hcDev).mapNotNull { HcMapping.sleep(it, version, zone) }
            HcType.HEART_RATE -> metrics(MetricKind.HEART_RATE, hourStart(range), hcDev)
                .groupBy { it.deviceId to Math.floorDiv(it.tSec, HOUR) * HOUR }
                .mapNotNull { (k, v) -> HcMapping.heartRate(k.first, k.second, v, version, zone) }
            HcType.HRV -> metrics(MetricKind.HRV_RMSSD, range, hcDev).mapNotNull { HcMapping.hrv(it, version, zone) }
            HcType.SPO2 -> metrics(MetricKind.SPO2, range, hcDev).mapNotNull { HcMapping.spo2(it, version, zone) }
            HcType.RESPIRATORY_RATE -> metrics(MetricKind.RESPIRATION, range, hcDev).mapNotNull { HcMapping.respiration(it, version, zone) }
        }
    }

    private fun hourStart(r: TimeRange): TimeRange = TimeRange(Math.floorDiv(r.startSec, HOUR) * HOUR, r.endSec)

    private suspend fun body(kind: BodyKind, r: TimeRange): List<BodyMeasure> = store.query { d ->
        d.rawQuery(
            "SELECT id, t_sec, value_x100, source, device_id FROM body_measure WHERE kind = ? AND t_sec >= ? AND t_sec < ? AND source != ? ORDER BY t_sec",
            arrayOf(kind.code.toString(), r.startSec.toString(), r.endSec.toString(), DataSource.HEALTH_CONNECT.code.toString()),
        ).use { c ->
            rows(c) {
                BodyMeasure(
                    id = c.getLong(0), tSec = c.getLong(1), kind = kind, customKindId = null, valueX100 = c.getInt(2),
                    source = DataSource.fromCode(c.getInt(3)) ?: DataSource.MANUAL, deviceId = longOrNull(c, 4), importId = null,
                )
            }
        }
    }

    private suspend fun diary(r: TimeRange): List<DiaryOut> = store.query { d ->
        d.rawQuery(
            "SELECT e.id, e.day, e.meal_slot_id, e.t_sec, e.name, e.kcal_x10, e.protein_x10, e.fat_x10, e.satfat_x10, e.carb_x10, " +
                "e.sugar_x10, e.fibre_x10, e.sodium_mg, e.cholesterol_mg, s.name FROM diary_entry e LEFT JOIN meal_slot s ON s.id = e.meal_slot_id " +
                "WHERE e.t_sec >= ? AND e.t_sec < ? AND (e.import_id IS NULL OR e.import_id NOT IN (${store.hcBatchesSql})) ORDER BY e.t_sec, e.id",
            arrayOf(r.startSec.toString(), r.endSec.toString()),
        ).use { c ->
            rows(c) {
                val entry = DiaryEntry(
                    id = c.getLong(0), day = Day(c.getInt(1)), mealSlotId = c.getLong(2), tSec = c.getLong(3), food = null,
                    name = c.getString(4), brand = null, amountX100 = 100, servingLabel = "", gramsX10 = null,
                    nutrients = Nutrients(
                        intOrNull(c, 5), intOrNull(c, 6), intOrNull(c, 7), intOrNull(c, 8), intOrNull(c, 9),
                        intOrNull(c, 10), intOrNull(c, 11), intOrNull(c, 12), intOrNull(c, 13),
                    ),
                    importId = null,
                )
                DiaryOut(entry, if (c.isNull(14)) null else c.getString(14))
            }
        }
    }

    private suspend fun water(r: TimeRange): List<WaterOut> = store.query { d ->
        d.rawQuery(
            "SELECT id, t_sec, ml FROM water WHERE t_sec >= ? AND t_sec < ? AND ml > 0 AND (import_id IS NULL OR import_id NOT IN (${store.hcBatchesSql})) ORDER BY t_sec",
            arrayOf(r.startSec.toString(), r.endSec.toString()),
        ).use { c -> rows(c) { WaterOut(c.getLong(0), c.getLong(1), c.getInt(2)) } }
    }

    private suspend fun activityHours(r: TimeRange, hcDev: Long, cache: MutableMap<Long, List<ActivityHour>>): List<ActivityHour> {
        val start = Math.floorDiv(r.startSec, HOUR) * HOUR
        cache[start]?.let { return it }
        val list = store.query { d ->
            d.rawQuery(
                "SELECT device_id, (t_sec / $HOUR) * $HOUR AS h, MIN(t_sec), MAX(t_sec) + 60, SUM(steps), SUM(distance_cm), SUM(active_kcal_x10) " +
                    "FROM activity_minute WHERE t_sec >= ? AND t_sec < ? AND device_id != ? GROUP BY device_id, h ORDER BY h, device_id",
                arrayOf(start.toString(), r.endSec.toString(), hcDev.toString()),
            ).use { c -> rows(c) { ActivityHour(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getLong(5), c.getLong(6)) } }
        }
        cache[start] = list
        return list
    }

    private suspend fun workouts(r: TimeRange): List<Workout> = store.query { d ->
        d.rawQuery(
            "SELECT id, device_id, sport, sub_sport, start_sec, end_sec, duration_sec, source FROM workout " +
                "WHERE start_sec >= ? AND start_sec < ? AND source != ? ORDER BY start_sec",
            arrayOf(r.startSec.toString(), r.endSec.toString(), DataSource.HEALTH_CONNECT.code.toString()),
        ).use { c ->
            rows(c) {
                Workout(
                    id = c.getLong(0), deviceId = longOrNull(c, 1), sport = c.getInt(2), subSport = intOrNull(c, 3), startSec = c.getLong(4),
                    endSec = c.getLong(5), durationSec = c.getInt(6), distanceM = null, kcal = null, avgHr = null, maxHr = null,
                    ascentM = null, descentM = null, hasTrack = false, fitPath = null, trackPath = null, routePreview = null,
                    source = DataSource.fromCode(c.getInt(7)) ?: DataSource.WATCH,
                )
            }
        }
    }

    private suspend fun sleeps(r: TimeRange, hcDev: Long): List<SleepOut> {
        val sessions = store.query { d ->
            d.rawQuery(
                "SELECT id, device_id, start_sec, end_sec, score FROM sleep_session WHERE end_sec >= ? AND start_sec < ? AND device_id != ? " +
                    "AND end_sec > start_sec ORDER BY start_sec",
                arrayOf(r.startSec.toString(), r.endSec.toString(), hcDev.toString()),
            ).use { c -> rows(c) { c.getLong(0) to SleepSession(c.getLong(1), c.getLong(2), c.getLong(3), intOrNull(c, 4)) } }
        }
        return sessions.map { (id, s) -> SleepOut(id, s, db.sleep.stages(TimeRange(s.startSec, s.endSec), s.deviceId)) }
    }

    private suspend fun metrics(kind: MetricKind, r: TimeRange, hcDev: Long): List<MetricSample> = store.query { d ->
        d.rawQuery(
            "SELECT device_id, t_sec, value FROM metric WHERE kind = ? AND t_sec >= ? AND t_sec < ? AND device_id != ? ORDER BY t_sec, device_id",
            arrayOf(kind.code.toString(), r.startSec.toString(), r.endSec.toString(), hcDev.toString()),
        ).use { c -> rows(c) { MetricSample(c.getLong(0), kind, c.getLong(1), c.getInt(2)) } }
    }

    private inline fun <T> rows(c: Cursor, map: () -> T): List<T> {
        val out = ArrayList<T>(c.count.coerceAtLeast(0))
        while (c.moveToNext()) out += map()
        return out
    }

    private fun intOrNull(c: Cursor, i: Int): Int? = if (c.isNull(i)) null else c.getInt(i)

    private fun longOrNull(c: Cursor, i: Int): Long? = if (c.isNull(i)) null else c.getLong(i)

    /** Constants. */
    companion object {
        private const val TAG = "HcWriter"
        private const val HOUR = 3600L
        private const val NO_DEVICE = -1L

        /** Most records per Health Connect insert call (its limit is about 1000 per request). */
        const val BATCH_MAX: Int = 1000

        /** How far back each write re-sends rows, so recent edits reach Health Connect: two days. */
        const val OVERLAP_SEC: Long = 2 * 86_400L

        /** What the first write of a type covers: 30 days. */
        const val FIRST_WINDOW_SEC: Long = 30 * 86_400L
    }
}
