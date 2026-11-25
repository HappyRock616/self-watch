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

/**
 * A trimmed workout's window ([startSec]..[endSec], epoch seconds) and the workout as it was stored
 * before the trim ([original]; same id, files and source).
 */
public data class WorkoutTrim(val startSec: Long, val endSec: Long, val original: Workout)

/** Workouts (table `workout`; trims in `workout_trim`). */
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

    /** The trim of workout [id] (its window and the values from before the trim), or null when it is not trimmed. */
    public suspend fun trim(id: Long): WorkoutTrim? = db.read { d -> trimIn(d, id) }

    /** Ids of every trimmed workout. */
    public suspend fun trimmedIds(): Set<Long> = db.read { d ->
        d.queryList("SELECT workout_id FROM workout_trim", null) { it.getLong(0) }.toSet()
    }

    /**
     * Trims workout `trimmed.id` to [startSec]..[endSec]: the row takes [trimmed]'s summary (start, end,
     * duration, distance, calories, heart rate, climb, route) and the values it had before the first trim
     * are kept for [undoTrim]. Trimming again keeps those first values. Returns false when the workout is
     * gone or the window is empty.
     */
    public suspend fun saveTrim(trimmed: Workout, startSec: Long, endSec: Long): Boolean {
        if (endSec <= startSec) return false
        return db.write(setOf(Table.WORKOUT, Table.WORKOUT_TRIM)) { d ->
            val current = d.queryOne("$SELECT WHERE id = ?", args(trimmed.id), ::map) ?: return@write false
            if (trimIn(d, trimmed.id) == null) {
                d.withStatement(
                    "INSERT INTO workout_trim (workout_id, trim_start_sec, trim_end_sec, $ORIG_NAMES) VALUES (?, ?, ?, ${"?, ".repeat(ORIG_COLS - 1)}?)",
                ) { st ->
                    st.bindLong(1, trimmed.id)
                    st.bindLong(2, startSec)
                    st.bindLong(3, endSec)
                    bindOrig(st, 4, current)
                    st.executeInsert()
                }
            } else {
                d.withStatement("UPDATE workout_trim SET trim_start_sec = ?, trim_end_sec = ? WHERE workout_id = ?") { st ->
                    st.bindLong(1, startSec)
                    st.bindLong(2, endSec)
                    st.bindLong(3, trimmed.id)
                    st.executeUpdateDelete()
                }
            }
            // Only the summary changes; the device, sport, files and source stay as stored.
            val row = current.copy(
                startSec = trimmed.startSec, endSec = trimmed.endSec, durationSec = trimmed.durationSec,
                distanceM = trimmed.distanceM, kcal = trimmed.kcal, avgHr = trimmed.avgHr, maxHr = trimmed.maxHr,
                ascentM = trimmed.ascentM, descentM = trimmed.descentM, hasTrack = trimmed.hasTrack, routePreview = trimmed.routePreview,
            )
            d.withStatement("UPDATE workout SET $SET_COLS WHERE id = ?") { st ->
                bindCols(st, row)
                st.bindLong(COLS + 1, row.id)
                st.executeUpdateDelete() > 0
            }
        }
    }

    /** Puts workout [id] back as it was before it was trimmed. Returns false when it was not trimmed. */
    public suspend fun undoTrim(id: Long): Boolean = db.write(setOf(Table.WORKOUT, Table.WORKOUT_TRIM)) { d ->
        val t = trimIn(d, id) ?: return@write false
        d.withStatement("UPDATE workout SET $SET_COLS WHERE id = ?") { st ->
            bindCols(st, t.original)
            st.bindLong(COLS + 1, id)
            st.executeUpdateDelete()
        }
        d.delete("workout_trim", "workout_id = ?", args(id)) > 0
    }

    internal companion object {
        private const val COLS = 17
        private const val ORIG_COLS = 11
        private const val ORIG_NAMES = "orig_start_sec, orig_end_sec, orig_duration_sec, orig_distance_m, orig_kcal, orig_avg_hr, " +
            "orig_max_hr, orig_ascent_m, orig_descent_m, orig_has_track, orig_route_preview"

        private fun bindOrig(st: SQLiteStatement, first: Int, w: Workout) {
            st.bindLong(first, w.startSec)
            st.bindLong(first + 1, w.endSec)
            st.bindLong(first + 2, w.durationSec.toLong())
            st.bindIntOrNull(first + 3, w.distanceM)
            st.bindIntOrNull(first + 4, w.kcal)
            st.bindIntOrNull(first + 5, w.avgHr)
            st.bindIntOrNull(first + 6, w.maxHr)
            st.bindIntOrNull(first + 7, w.ascentM)
            st.bindIntOrNull(first + 8, w.descentM)
            st.bindBool(first + 9, w.hasTrack)
            val preview = w.routePreview
            if (preview == null) st.bindNull(first + 10) else st.bindBlob(first + 10, preview)
        }

        private fun trimIn(d: SQLiteDatabase, id: Long): WorkoutTrim? {
            val w = d.queryOne("$SELECT WHERE id = ?", args(id), ::map) ?: return null
            return d.queryOne("SELECT trim_start_sec, trim_end_sec, $ORIG_NAMES FROM workout_trim WHERE workout_id = ?", args(id)) { c ->
                WorkoutTrim(
                    startSec = c.getLong(0),
                    endSec = c.getLong(1),
                    original = w.copy(
                        startSec = c.getLong(2), endSec = c.getLong(3), durationSec = c.getInt(4), distanceM = c.intOrNull(5),
                        kcal = c.intOrNull(6), avgHr = c.intOrNull(7), maxHr = c.intOrNull(8), ascentM = c.intOrNull(9),
                        descentM = c.intOrNull(10), hasTrack = c.bool(11), routePreview = if (c.isNull(12)) null else c.getBlob(12),
                    ),
                )
            }
        }

        /** The id of a trimmed workout of [deviceId] that originally started at [startSec], or null. */
        private fun trimmedAt(d: SQLiteDatabase, deviceId: Long?, startSec: Long): Long? {
            val sql = "SELECT t.workout_id FROM workout_trim t JOIN workout w ON w.id = t.workout_id WHERE t.orig_start_sec = ? AND " +
                if (deviceId == null) "w.device_id IS NULL" else "w.device_id = ?"
            val a = if (deviceId == null) args(startSec) else args(startSec, deviceId)
            return d.queryOne(sql, a) { it.getLong(0) }
        }
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
            // The same workout synced again after the user trimmed it: the trim stays.
            trimmedAt(d, w.deviceId, w.startSec)?.let { return it }
            val id =d.withStatement("INSERT OR IGNORE INTO workout ($COL_NAMES, import_id) VALUES (${"?, ".repeat(COLS)}?)") { st ->
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
