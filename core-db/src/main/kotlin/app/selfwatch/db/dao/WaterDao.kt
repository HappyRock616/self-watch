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
import app.selfwatch.db.bindLongOrNull
import app.selfwatch.db.longOrNull
import app.selfwatch.db.queryList
import app.selfwatch.db.queryOne
import app.selfwatch.db.read
import app.selfwatch.db.withStatement
import app.selfwatch.db.write
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange

/** One drink logged on [day]. */
public data class WaterEntry(val id: Long, val day: Day, val tSec: Long, val ml: Int, val importId: Long?)

/** Water log (table `water`). */
public class WaterDao internal constructor(private val db: SelfWatchDb) {

    /** Logs [ml] on [day] at [tSec]; returns the new id. */
    public suspend fun add(day: Day, tSec: Long, ml: Int, importId: Long? = null): Long = db.write(setOf(Table.WATER)) { d ->
        d.withStatement("INSERT INTO water (day, t_sec, ml, import_id) VALUES (?, ?, ?, ?)") { st ->
            st.bindLong(1, day.yyyymmdd.toLong())
            st.bindLong(2, tSec)
            st.bindLong(3, ml.toLong())
            st.bindLongOrNull(4, importId)
            st.executeInsert()
        }
    }

    /** Deletes entry [id]. Returns true if it existed. */
    public suspend fun delete(id: Long): Boolean = db.write(setOf(Table.WATER)) { it.delete("water", "id = ?", args(id)) > 0 }

    /** The entries of [day], oldest first. */
    public suspend fun entries(day: Day): List<WaterEntry> = db.read { d ->
        d.queryList("SELECT id, day, t_sec, ml, import_id FROM water WHERE day = ? ORDER BY t_sec, id", args(day.yyyymmdd)) { c ->
            WaterEntry(c.getLong(0), Day(c.getInt(1)), c.getLong(2), c.getInt(3), c.longOrNull(4))
        }
    }

    /** The entries of every day in [days] (inclusive; every day when null), oldest first. For exports. */
    public suspend fun range(days: DayRange?): List<WaterEntry> = db.read { d ->
        val (where, a) = if (days == null) "" to null else "WHERE day BETWEEN ? AND ?" to args(days.first.yyyymmdd, days.last.yyyymmdd)
        d.queryList("SELECT id, day, t_sec, ml, import_id FROM water $where ORDER BY t_sec, id", a) { c ->
            WaterEntry(c.getLong(0), Day(c.getInt(1)), c.getLong(2), c.getInt(3), c.longOrNull(4))
        }
    }

    /** Total millilitres on [day] (0 when nothing is logged). */
    public suspend fun dayTotal(day: Day): Int = db.read { d ->
        d.queryOne("SELECT COALESCE(SUM(ml), 0) FROM water WHERE day = ?", args(day.yyyymmdd)) { it.getInt(0) } ?: 0
    }
}
