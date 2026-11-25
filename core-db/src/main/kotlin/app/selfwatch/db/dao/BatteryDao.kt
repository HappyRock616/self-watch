/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db.dao

import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.db.args
import app.selfwatch.db.queryList
import app.selfwatch.db.queryOne
import app.selfwatch.db.read
import app.selfwatch.db.withStatement
import app.selfwatch.db.write
import app.selfwatch.model.time.TimeRange

/** One battery level a device reported: [pct] percent at [tSec]. */
public data class BatteryReading(val deviceId: Long, val tSec: Long, val pct: Int)

/**
 * Each device's battery history (table `device_battery`, schema 3): one row each time a device reports
 * a new level. Rows older than [KEEP_SEC] are dropped as new ones arrive.
 */
public class BatteryDao internal constructor(private val db: SelfWatchDb) {

    /** Stores [pct] (clamped to 0–100) for [deviceId] at [tSec] (a second reading at the same second replaces it). */
    public suspend fun add(deviceId: Long, tSec: Long, pct: Int) {
        db.write(setOf(Table.DEVICE_BATTERY)) { d ->
            d.withStatement("INSERT OR REPLACE INTO device_battery (device_id, t_sec, pct) VALUES (?, ?, ?)") { st ->
                st.bindLong(1, deviceId)
                st.bindLong(2, tSec)
                st.bindLong(3, pct.coerceIn(0, 100).toLong())
                st.executeInsert()
            }
            d.delete("device_battery", "t_sec < ?", args(tSec - KEEP_SEC))
        }
    }

    /** Readings in [range], oldest first; all devices unless [deviceId] is given. */
    public suspend fun readings(range: TimeRange, deviceId: Long? = null): List<BatteryReading> = db.read { d ->
        val (where, a) = if (deviceId == null) {
            "t_sec >= ? AND t_sec < ?" to args(range.startSec, range.endSec)
        } else {
            "device_id = ? AND t_sec >= ? AND t_sec < ?" to args(deviceId, range.startSec, range.endSec)
        }
        d.queryList("SELECT device_id, t_sec, pct FROM device_battery WHERE $where ORDER BY t_sec, device_id", a) { c ->
            BatteryReading(c.getLong(0), c.getLong(1), c.getInt(2))
        }
    }

    /** The newest reading of [deviceId], or null. */
    public suspend fun latest(deviceId: Long): BatteryReading? = db.read { d ->
        d.queryOne("SELECT device_id, t_sec, pct FROM device_battery WHERE device_id = ? ORDER BY t_sec DESC LIMIT 1", args(deviceId)) { c ->
            BatteryReading(c.getLong(0), c.getLong(1), c.getInt(2))
        }
    }

    /** True when any reading is stored. */
    public suspend fun hasAny(): Boolean = db.read { d ->
        d.queryOne("SELECT 1 FROM device_battery LIMIT 1", null) { true } ?: false
    }

    /** Limits. */
    public companion object {
        /** How long readings are kept (90 days). */
        public const val KEEP_SEC: Long = 90L * 86_400L
    }
}
