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
import app.selfwatch.db.bindIntOrNull
import app.selfwatch.db.bindLongOrNull
import app.selfwatch.db.dayOfEpochDay
import app.selfwatch.db.deviceIdsIn
import app.selfwatch.db.inList
import app.selfwatch.db.intOrNull
import app.selfwatch.db.offsetSegments
import app.selfwatch.db.queryList
import app.selfwatch.db.queryOne
import app.selfwatch.db.read
import app.selfwatch.db.withStatement
import app.selfwatch.db.write
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.TimeRange
import java.time.ZoneId

/**
 * Activity totals for one local day.
 *
 * @property activeMin minutes with intensity moderate or vigorous (2 or 3).
 */
public data class ActivityDayTotals(
    val day: Day,
    val steps: Long,
    val activeKcalX10: Long,
    val distanceCm: Long,
    val activeMin: Int,
    val minutes: Int,
)

/** Minute activity data (table `activity_minute`). */
public class ActivityDao internal constructor(private val db: SelfWatchDb) {

    /**
     * Stores [minutes] in one transaction; a minute already stored for the same device is replaced.
     * [importId] tags rows from an import batch (null for watch syncs). Returns the count.
     */
    public suspend fun insert(minutes: List<ActivityMinute>, importId: Long? = null): Int {
        if (minutes.isEmpty()) return 0
        return db.write(setOf(Table.ACTIVITY)) { insertIn(it, minutes, importId) }
    }

    /**
     * Sets the intensity of each of [minutes] (matched by device and minute) in one transaction, keeping
     * the rest of the row; a minute not stored yet is added as given. Returns how many rows changed.
     */
    public suspend fun setIntensity(minutes: List<ActivityMinute>): Int {
        if (minutes.isEmpty()) return 0
        return db.write(setOf(Table.ACTIVITY)) { d ->
            var n = 0
            d.withStatement("UPDATE activity_minute SET intensity = ? WHERE device_id = ? AND t_sec = ?") { up ->
                val missing = ArrayList<ActivityMinute>()
                for (m in minutes) {
                    up.bindLong(1, m.intensity.toLong())
                    up.bindLong(2, m.deviceId)
                    up.bindLong(3, m.tSec)
                    if (up.executeUpdateDelete() > 0) n++ else missing += m
                }
                if (missing.isNotEmpty()) n += insertIn(d, missing)
            }
            n
        }
    }

    /** From the first stored minute to one minute after the last, or null when none is stored. */
    public suspend fun span(): TimeRange? = db.read { d ->
        d.queryOne("SELECT MIN(t_sec), MAX(t_sec) FROM activity_minute", null) { c ->
            if (c.isNull(0)) null else TimeRange(c.getLong(0), c.getLong(1) + 60)
        }
    }

    /** Minutes in [range], oldest first; all devices unless [deviceId] is given. */
    public suspend fun minutes(range: TimeRange, deviceId: Long? = null): List<ActivityMinute> = db.read { d ->
        val ids = deviceId?.let { listOf(it) } ?: d.deviceIdsIn("activity_minute")
        if (ids.isEmpty()) return@read emptyList()
        d.queryList(
            "SELECT device_id, t_sec, steps, active_kcal_x10, distance_cm, intensity, activity_type FROM activity_minute " +
                "WHERE device_id IN ${inList(ids)} AND t_sec >= ? AND t_sec < ? ORDER BY t_sec, device_id",
            args(range.startSec, range.endSec),
        ) { c -> ActivityMinute(c.getLong(0), c.getLong(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getInt(5), c.intOrNull(6)) }
    }

    /**
     * Totals per local day of [days] in [zone] (correct across daylight-saving changes). Days with no
     * data are left out. All devices are summed unless [deviceId] is given.
     */
    public suspend fun totalsByDay(days: DayRange, zone: ZoneId, deviceId: Long? = null): List<ActivityDayTotals> =
        db.read { d ->
            val ids = deviceId?.let { listOf(it) } ?: d.deviceIdsIn("activity_minute")
            if (ids.isEmpty()) return@read emptyList()
            val acc = java.util.TreeMap<Long, LongArray>()
            for (seg in offsetSegments(days, zone)) {
                d.queryList(
                    "SELECT (t_sec + ${seg.offsetSec}) / 86400 AS dn, SUM(steps), SUM(active_kcal_x10), SUM(distance_cm), " +
                        "SUM(CASE WHEN intensity >= 2 THEN 1 ELSE 0 END), COUNT(*) FROM activity_minute " +
                        "WHERE device_id IN ${inList(ids)} AND t_sec >= ? AND t_sec < ? GROUP BY dn",
                    args(seg.startSec, seg.endSec),
                ) { c ->
                    val a = acc.getOrPut(c.getLong(0)) { LongArray(5) }
                    for (k in 0 until 5) a[k] += c.getLong(k + 1)
                }
            }
            acc.map { (dn, a) -> ActivityDayTotals(dayOfEpochDay(dn), a[0], a[1], a[2], a[3].toInt(), a[4].toInt()) }
        }

    internal companion object {
        internal fun insertIn(d: SQLiteDatabase, minutes: List<ActivityMinute>, importId: Long? = null): Int {
            d.withStatement(
                "INSERT OR REPLACE INTO activity_minute (device_id, t_sec, steps, active_kcal_x10, distance_cm, intensity, activity_type, import_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ) { st ->
                for (m in minutes) {
                    st.bindLong(1, m.deviceId)
                    st.bindLong(2, m.tSec)
                    st.bindLong(3, m.steps.toLong())
                    st.bindLong(4, m.activeKcalX10.toLong())
                    st.bindLong(5, m.distanceCm.toLong())
                    st.bindLong(6, m.intensity.toLong())
                    st.bindIntOrNull(7, m.activityType)
                    st.bindLongOrNull(8, importId)
                    st.executeInsert()
                }
            }
            return minutes.size
        }
    }
}
