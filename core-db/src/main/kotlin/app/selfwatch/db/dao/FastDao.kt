/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db.dao

import android.database.Cursor
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.db.args
import app.selfwatch.db.longOrNull
import app.selfwatch.db.queryList
import app.selfwatch.db.queryOne
import app.selfwatch.db.read
import app.selfwatch.db.withStatement
import app.selfwatch.db.write

/** One fast: started at [startSec], ended at [endSec] (null while running), aiming for [goalMin] minutes. */
public data class Fast(val id: Long, val startSec: Long, val endSec: Long?, val goalMin: Int)

/** Fasting log (table `fast`). */
public class FastDao internal constructor(private val db: SelfWatchDb) {

    /** Starts a fast; returns its id. */
    public suspend fun start(startSec: Long, goalMin: Int): Long = db.write(setOf(Table.FAST)) { d ->
        d.withStatement("INSERT INTO fast (start_sec, goal_min) VALUES (?, ?)") { st ->
            st.bindLong(1, startSec)
            st.bindLong(2, goalMin.toLong())
            st.executeInsert()
        }
    }

    /** Ends fast [id] at [endSec]. Returns true if it existed. */
    public suspend fun end(id: Long, endSec: Long): Boolean = db.write(setOf(Table.FAST)) { d ->
        d.withStatement("UPDATE fast SET end_sec = ? WHERE id = ?") { st ->
            st.bindLong(1, endSec)
            st.bindLong(2, id)
            st.executeUpdateDelete() > 0
        }
    }

    /** Rewrites fast `f.id` (to correct its times or goal). Returns true if it existed. */
    public suspend fun update(f: Fast): Boolean = db.write(setOf(Table.FAST)) { d ->
        d.withStatement("UPDATE fast SET start_sec = ?, end_sec = ?, goal_min = ? WHERE id = ?") { st ->
            st.bindLong(1, f.startSec)
            if (f.endSec == null) st.bindNull(2) else st.bindLong(2, f.endSec)
            st.bindLong(3, f.goalMin.toLong())
            st.bindLong(4, f.id)
            st.executeUpdateDelete() > 0
        }
    }

    /** Deletes fast [id]. Returns true if it existed. */
    public suspend fun delete(id: Long): Boolean = db.write(setOf(Table.FAST)) { it.delete("fast", "id = ?", args(id)) > 0 }

    /** The running fast (the newest one without an end), or null. */
    public suspend fun current(): Fast? = db.read {
        it.queryOne("$SELECT WHERE end_sec IS NULL ORDER BY start_sec DESC, id DESC LIMIT 1", null, ::map)
    }

    /** Up to [limit] fasts, newest first. */
    public suspend fun list(limit: Int): List<Fast> = db.read {
        it.queryList("$SELECT ORDER BY start_sec DESC, id DESC LIMIT ${limit.coerceAtLeast(0)}", null, ::map)
    }

    private companion object {
        const val SELECT = "SELECT id, start_sec, end_sec, goal_min FROM fast"

        fun map(c: Cursor): Fast = Fast(c.getLong(0), c.getLong(1), c.longOrNull(2), c.getInt(3))
    }
}
