/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db.dao

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.db.args
import app.selfwatch.db.bindBool
import app.selfwatch.db.bindIntOrNull
import app.selfwatch.db.bindLongOrNull
import app.selfwatch.db.bindStrOrNull
import app.selfwatch.db.bool
import app.selfwatch.db.intOrNull
import app.selfwatch.db.longOrNull
import app.selfwatch.db.queryList
import app.selfwatch.db.queryOne
import app.selfwatch.db.read
import app.selfwatch.db.strOrNull
import app.selfwatch.db.toTimeRange
import app.selfwatch.db.withStatement
import app.selfwatch.db.write
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.DayRange
import java.time.ZoneId

/** Workouts (table `workout`). */
public class WorkoutDao internal constructor(private val db: SelfWatchDb) {

    /**
     * Stores [w] (its `id` is ignored) and returns the row id. A workout with the same device and
     * start time is updated in place and keeps its id. [importId] tags rows from an import batch.
     */
    public suspend fun insert(w: Workout, importId: Long? = null): Long =
        db.write(setOf(Table.WORKOUT)) { insertIn(it, w, importId) }

    /** Rewrites the workout with `w.id` (every column except `import_id`). Returns true if it existed. */
    public suspend fun update(w: Workout): Boolean = db.write(setOf(Table.WORKOUT)) { d ->
        d.withStatement("UPDATE workout SET $SET_COLS WHERE id = ?") { st ->
            bindCols(st, w)
            st.bindLong(COLS + 1, w.id)
            st.executeUpdateDelete() > 0
        }
    }

    /** Up to [limit] workouts, newest first, starting before [beforeStartSec] when given (for paging). */
    public suspend fun list(limit: Int, beforeStartSec: Long? = null): List<Workout> = db.read { d ->
        val n = limit.coerceAtLeast(0)
        if (beforeStartSec == null) {
            d.queryList("$SELECT ORDER BY start_sec DESC, id DESC LIMIT $n", null, ::map)
        } else {
            d.queryList("$SELECT WHERE start_sec < ? ORDER BY start_sec DESC, id DESC LIMIT $n", args(beforeStartSec), ::map)
        }
    }

    /** The workout [id], or null. */
    public suspend fun get(id: Long): Workout? = db.read { it.queryOne("$SELECT WHERE id = ?", args(id), ::map) }

    /** Deletes workout [id]. Returns true if it existed. (Files on disk are the caller's to remove.) */
    public suspend fun delete(id: Long): Boolean = db.write(setOf(Table.WORKOUT)) { it.delete("workout", "id = ?", args(id)) > 0 }

    /** Every workout, oldest first. For exports. */
    public suspend fun all(): List<Workout> = db.read { it.queryList("$SELECT ORDER BY start_sec, id", null, ::map) }

    /** Workouts that started within [days] in [zone], oldest first. */
    public suspend fun forExport(days: DayRange, zone: ZoneId): List<Workout> = db.read { d ->
        val r = days.toTimeRange(zone)
        d.queryList("$SELECT WHERE start_sec >= ? AND start_sec < ? ORDER BY start_sec, id", args(r.startSec, r.endSec), ::map)
    }

    internal companion object {
        private const val COLS = 17
        private const val COL_NAMES = "device_id, sport, sub_sport, start_sec, end_sec, duration_sec, distance_m, kcal, " +
            "avg_hr, max_hr, ascent_m, descent_m, has_track, fit_path, track_path, route_preview, source"
        private val SET_COLS = COL_NAMES.split(", ").joinToString(", ") { "$it = ?" }
        private const val SELECT = "SELECT id, $COL_NAMES FROM workout"

        private fun bindCols(st: SQLiteStatement, w: Workout) {
            st.bindLongOrNull(1, w.deviceId)
            st.bindLong(2, w.sport.toLong())
            st.bindIntOrNull(3, w.subSport)
            st.bindLong(4, w.startSec)
            st.bindLong(5, w.endSec)
            st.bindLong(6, w.durationSec.toLong())
            st.bindIntOrNull(7, w.distanceM)
            st.bindIntOrNull(8, w.kcal)
            st.bindIntOrNull(9, w.avgHr)
            st.bindIntOrNull(10, w.maxHr)
            st.bindIntOrNull(11, w.ascentM)
            st.bindIntOrNull(12, w.descentM)
            st.bindBool(13, w.hasTrack)
            st.bindStrOrNull(14, w.fitPath)
            st.bindStrOrNull(15, w.trackPath)
            val preview = w.routePreview
            if (preview == null) st.bindNull(16) else st.bindBlob(16, preview)
            st.bindLong(17, w.source.code.toLong())
        }

        internal fun insertIn(d: SQLiteDatabase, w: Workout, importId: Long?): Long {
            val id = d.withStatement("INSERT OR IGNORE INTO workout ($COL_NAMES, import_id) VALUES (${"?, ".repeat(COLS)}?)") { st ->
                bindCols(st, w)
                st.bindLongOrNull(COLS + 1, importId)
                st.executeInsert()
            }
            if (id != -1L) return id
            // Same device and start: update in place.
            val existing = d.queryOne("SELECT id FROM workout WHERE device_id = ? AND start_sec = ?", args(w.deviceId, w.startSec)) {
                it.getLong(0)
            } ?: error("workout insert ignored without a conflict")
            d.withStatement("UPDATE workout SET $SET_COLS WHERE id = ?") { st ->
                bindCols(st, w)
                st.bindLong(COLS + 1, existing)
                st.executeUpdateDelete()
            }
            return existing
        }

        private fun map(c: Cursor): Workout = Workout(
            id = c.getLong(0),
            deviceId = c.longOrNull(1),
            sport = c.getInt(2),
            subSport = c.intOrNull(3),
            startSec = c.getLong(4),
            endSec = c.getLong(5),
            durationSec = c.getInt(6),
            distanceM = c.intOrNull(7),
            kcal = c.intOrNull(8),
            avgHr = c.intOrNull(9),
            maxHr = c.intOrNull(10),
            ascentM = c.intOrNull(11),
            descentM = c.intOrNull(12),
            hasTrack = c.bool(13),
            fitPath = c.strOrNull(14),
            trackPath = c.strOrNull(15),
            routePreview = if (c.isNull(16)) null else c.getBlob(16),
            source = DataSource.fromCode(c.getInt(17)) ?: DataSource.IMPORT,
        )
    }
}
