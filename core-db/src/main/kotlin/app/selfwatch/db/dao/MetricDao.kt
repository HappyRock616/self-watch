/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db.dao

import android.database.sqlite.SQLiteDatabase
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

    internal companion object {
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
