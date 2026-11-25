/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db.dao

import android.database.sqlite.SQLiteDatabase
import app.selfwatch.db.DeviceKindCodes
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.db.args
import app.selfwatch.db.bindLongOrNull
import app.selfwatch.db.dayOfEpochDay
import app.selfwatch.db.deviceIdsIn
import app.selfwatch.db.inList
import app.selfwatch.db.offsetSegments
import app.selfwatch.db.queryList
import app.selfwatch.db.queryOne
import app.selfwatch.db.read
import app.selfwatch.db.withStatement
import app.selfwatch.db.write
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.TimeRange
import java.time.ZoneId

/** Minimum, maximum and average (rounded to the nearest whole value) of one metric over one local day. */
public data class MetricDayStats(val day: Day, val min: Int, val max: Int, val avg: Int, val count: Int)

/** Time-series metrics (table `metric`): heart rate, HRV, SpO2, stress and the rest. */
public class MetricDao internal constructor(private val db: SelfWatchDb) {

    /**
     * Stores [samples] in one transaction (a sample with the same kind, device and time is replaced).
     * [importId] tags rows from an import batch (null for watch syncs). Returns the count.
     */
    public suspend fun insert(samples: List<MetricSample>, importId: Long? = null): Int {
        if (samples.isEmpty()) return 0
        return db.write(setOf(Table.METRIC)) { insertIn(it, samples, importId) }
    }

    /** Samples of [kind] in [range], oldest first; all devices unless [deviceId] is given. */
    public suspend fun series(kind: MetricKind, range: TimeRange, deviceId: Long? = null): List<MetricSample> =
        db.read { d ->
            val ids = deviceId?.let { listOf(it) } ?: d.deviceIdsIn("metric", kind.code)
            if (ids.isEmpty()) return@read emptyList()
            d.queryList(
                "SELECT device_id, t_sec, value FROM metric WHERE kind = ${kind.code} AND device_id IN ${inList(ids)} " +
                    "AND t_sec >= ? AND t_sec < ? ORDER BY t_sec, device_id",
                args(range.startSec, range.endSec),
            ) { c -> MetricSample(c.getLong(0), kind, c.getLong(1), c.getInt(2)) }
        }

    /** True when any sample of [kind] is stored (one primary-key seek; used to decide which cards to offer). */
    public suspend fun hasAny(kind: MetricKind): Boolean = db.read { d ->
        d.queryOne("SELECT 1 FROM metric WHERE kind = ${kind.code} LIMIT 1", null) { true } ?: false
    }

    /** The newest sample of [kind], or null. */
    public suspend fun latest(kind: MetricKind): MetricSample? = db.read { d ->
        d.queryOne("SELECT device_id, t_sec, value FROM metric WHERE kind = ${kind.code} ORDER BY t_sec DESC LIMIT 1", null) { c ->
            MetricSample(c.getLong(0), kind, c.getLong(1), c.getInt(2))
        }
    }

    /** Daily minimum, maximum and average of [kind] for each local day of [days] in [zone] that has data. */
    public suspend fun dailyStats(kind: MetricKind, days: DayRange, zone: ZoneId, deviceId: Long? = null): List<MetricDayStats> =
        db.read { d ->
            val ids = deviceId?.let { listOf(it) } ?: d.deviceIdsIn("metric", kind.code)
            if (ids.isEmpty()) return@read emptyList()
            // dn -> [min, max, sum, count]
            val acc = java.util.TreeMap<Long, LongArray>()
            for (seg in offsetSegments(days, zone)) {
                d.queryList(
                    "SELECT (t_sec + ${seg.offsetSec}) / 86400 AS dn, MIN(value), MAX(value), SUM(value), COUNT(*) FROM metric " +
                        "WHERE kind = ${kind.code} AND device_id IN ${inList(ids)} AND t_sec >= ? AND t_sec < ? GROUP BY dn",
                    args(seg.startSec, seg.endSec),
                ) { c ->
                    val dn = c.getLong(0)
                    val a = acc[dn]
                    if (a == null) {
                        acc[dn] = longArrayOf(c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4))
                    } else {
                        a[0] = minOf(a[0], c.getLong(1))
                        a[1] = maxOf(a[1], c.getLong(2))
                        a[2] += c.getLong(3)
                        a[3] += c.getLong(4)
                    }
                }
            }
            acc.map { (dn, a) ->
                val avg = Math.floorDiv(2 * a[2] + a[3], 2 * a[3])
                MetricDayStats(dayOfEpochDay(dn), a[0].toInt(), a[1].toInt(), avg.toInt(), a[3].toInt())
            }
        }

    /** Sensor priority helpers (see [sensorPriorityIn]). */
    public companion object {
        /**
         * A watch heart-rate sample within this many seconds of a strap or sensor sample is replaced by
         * the sensor (straps send about one value a second; the watch logs far less often).
         */
        public const val SENSOR_HR_WINDOW_SEC: Long = 30

        private val WATCH_KIND = DeviceKindCodes.code(DeviceKind.GARMIN_WATCH)

        /**
         * Sensor priority for live device data, inside the caller's transaction: heart rate from a
         * strap or other sensor always wins over a watch's. Watch heart-rate rows within
         * [SENSOR_HR_WINDOW_SEC] of the incoming sensor samples are deleted, and incoming watch samples
         * that a stored sensor sample covers are dropped. Returns [samples] without the dropped ones. The
         * source stays recorded in each row's `device_id`.
         */
        internal fun sensorPriorityIn(d: SQLiteDatabase, samples: List<MetricSample>): List<MetricSample> {
            val hr = samples.filter { it.kind == MetricKind.HEART_RATE }
            if (hr.isEmpty()) return samples
            val ids = hr.mapTo(HashSet()) { it.deviceId }
            val kinds = HashMap<Long, Int>()
            d.queryList("SELECT id, kind FROM device WHERE id IN ${inList(ids)}", null) { c -> kinds[c.getLong(0)] = c.getInt(1) }
            // An id with no device row counts as neither watch nor sensor: its samples never win or lose.
            fun isSensor(id: Long): Boolean = kinds[id]?.let { it != WATCH_KIND } ?: false
            val hrCode = MetricKind.HEART_RATE.code
            val sensorTimes = hr.filter { isSensor(it.deviceId) }.map { it.tSec }
            // Way 1, the sensor arrives second: delete the watch rows it covers. Straps send ~1 sample a
            // second, so one DELETE per continuous run of samples instead of one per sample.
            for ((lo, hi) in SensorPriority.runs(sensorTimes, SENSOR_HR_WINDOW_SEC)) {
                d.execSQL(
                    "DELETE FROM metric WHERE kind = $hrCode AND device_id IN (SELECT id FROM device WHERE kind = $WATCH_KIND) " +
                        "AND t_sec >= ? AND t_sec <= ?",
                    arrayOf<Any>(lo - SENSOR_HR_WINDOW_SEC, hi + SENSOR_HR_WINDOW_SEC),
                )
            }
            // Way 2, the watch arrives second (its sync can come hours after the strap streamed live): drop
            // the incoming watch samples that a stored or incoming sensor sample covers.
            val watchHr = hr.filter { kinds[it.deviceId] == WATCH_KIND }
            if (watchHr.isEmpty()) return samples
            val from = watchHr.minOf { it.tSec } - SENSOR_HR_WINDOW_SEC
            val to = watchHr.maxOf { it.tSec } + SENSOR_HR_WINDOW_SEC
            val stored = d.queryList(
                "SELECT t_sec FROM metric WHERE kind = $hrCode AND device_id IN (SELECT id FROM device WHERE kind <> $WATCH_KIND) " +
                    "AND t_sec >= ? AND t_sec <= ? ORDER BY t_sec",
                args(from, to),
            ) { c -> c.getLong(0) }
            val covers = (stored + sensorTimes).sorted().toLongArray()
            if (covers.isEmpty()) return samples
            return samples.filter { s ->
                !(s.kind == MetricKind.HEART_RATE && kinds[s.deviceId] == WATCH_KIND && SensorPriority.covered(covers, s.tSec, SENSOR_HR_WINDOW_SEC))
            }
        }

        internal fun insertIn(d: SQLiteDatabase, samples: List<MetricSample>, importId: Long? = null): Int {
            d.withStatement("INSERT OR REPLACE INTO metric (kind, device_id, t_sec, value, import_id) VALUES (?, ?, ?, ?, ?)") { st ->
                for (s in samples) {
                    st.bindLong(1, s.kind.code.toLong())
                    st.bindLong(2, s.deviceId)
                    st.bindLong(3, s.tSec)
                    st.bindLong(4, s.value.toLong())
                    st.bindLongOrNull(5, importId)
                    st.executeInsert()
                }
            }
            return samples.size
        }
    }
}

/** Pure parts of the sensor heart-rate priority (for tests). */
public object SensorPriority {
    /**
     * Groups [times] (any order) into runs whose neighbors are at most `2 × window` apart, as
     * `(first, last)` pairs: every instant of `[first − window, last + window]` is then within [window]
     * of a sample.
     */
    public fun runs(times: List<Long>, window: Long): List<Pair<Long, Long>> {
        if (times.isEmpty()) return emptyList()
        val sorted = times.sorted()
        val out = ArrayList<Pair<Long, Long>>()
        var lo = sorted[0]
        var hi = sorted[0]
        for (t in sorted.drop(1)) {
            // Two samples up to 2 × window apart leave no instant uncovered between them (each covers
            // window on its side), so they belong to one run.
            if (t - hi > 2 * window) {
                out += lo to hi
                lo = t
            }
            hi = t
        }
        out += lo to hi
        return out
    }

    /** True when a time in [sorted] (ascending) is within [window] seconds of [t]. */
    public fun covered(sorted: LongArray, t: Long, window: Long): Boolean {
        var i = java.util.Arrays.binarySearch(sorted, t)
        if (i >= 0) return true
        // Not found: binarySearch returns -(insertion point) - 1; only the neighbours on either side of
        // the insertion point can be the nearest.
        i = -i - 1
        if (i < sorted.size && sorted[i] - t <= window) return true
        return i > 0 && t - sorted[i - 1] <= window
    }
}
