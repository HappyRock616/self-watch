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
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.db.args
import app.selfwatch.db.bindIntOrNull
import app.selfwatch.db.bindLongOrNull
import app.selfwatch.db.intOrNull
import app.selfwatch.db.queryList
import app.selfwatch.db.queryOne
import app.selfwatch.db.read
import app.selfwatch.db.toTimeRange
import app.selfwatch.db.withStatement
import app.selfwatch.db.write
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.health.SleepStageSample
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.TimeRange
import java.time.ZoneId

/** One night: the [session] (with its row [id]) and its [stages], oldest first. */
public data class SleepNight(val id: Long, val session: SleepSession, val stages: List<SleepStageSample>)

/**
 * Sleep sessions and stages (tables `sleep_session`, `sleep_stage`). A night belongs to the local day
 * it ends on (the wake-up day).
 */
public class SleepDao internal constructor(private val db: SelfWatchDb) {

    /**
     * Stores [session]; a session with the same device and start is updated in place (its id kept).
     * [importId] tags the row from an import batch (null for watch syncs). Returns the row id.
     */
    public suspend fun insertSession(session: SleepSession, importId: Long? = null): Long =
        db.write(setOf(Table.SLEEP_SESSION)) { insertSessionIn(it, session, importId) }

    /** Stores [stages] in one transaction (same device and time replaces), tagged with [importId] if given. Returns the count. */
    public suspend fun insertStages(stages: List<SleepStageSample>, importId: Long? = null): Int {
        if (stages.isEmpty()) return 0
        return db.write(setOf(Table.SLEEP_STAGE)) { insertStagesIn(it, stages, importId) }
    }

    /**
     * The main sleep that ended on [day] in [zone], or null: the longest **joined** night ending that day.
     *
     * A night can reach the phone in pieces: when the watch syncs at midnight, the part before
     * midnight comes in that sync's monitoring file and the rest in the morning's sleep file (Pixel 8
     * field test, 2026-11-26). Pieces of one device less than [JOIN_GAP_SEC] apart are joined (see
     * [join]), so the night starts when you fell asleep, not at midnight, and belongs to the day it ends.
     * The returned [SleepNight.id] is the longest piece's row (used to delete).
     */
    public suspend fun night(day: Day, zone: ZoneId): SleepNight? = db.read { d ->
        val r = day.toTimeRange(zone)
        val best = joinedIn(d, TimeRange(r.startSec, r.endSec))
            .filter { it.session.endSec >= r.startSec && it.session.endSec < r.endSec }
            .maxByOrNull { it.session.endSec - it.session.startSec } ?: return@read null
        val s = best.session
        SleepNight(best.id, s, stagesIn(d, TimeRange(s.startSec, s.endSec), s.deviceId))
    }

    /** Joined sessions (naps included) that ended within [days] in [zone], oldest first (see [night]). */
    public suspend fun sessions(days: DayRange, zone: ZoneId): List<SleepSession> = db.read { d ->
        val r = days.toTimeRange(zone)
        joinedIn(d, r).map { it.session }.filter { it.endSec >= r.startSec && it.endSec < r.endSec }.sortedBy { it.endSec }
    }

    /** Stage samples of [deviceId] in [range], oldest first. */
    public suspend fun stages(range: TimeRange, deviceId: Long): List<SleepStageSample> = db.read { stagesIn(it, range, deviceId) }

    /** Deletes the session [id] and the stages of its device inside its time span. Returns true if it existed. */
    public suspend fun deleteSession(id: Long): Boolean = db.write(setOf(Table.SLEEP_SESSION, Table.SLEEP_STAGE)) { d ->
        val s = d.queryOne("$SELECT_SESSION WHERE id = ?", args(id), ::mapSession) ?: return@write false
        d.delete("sleep_stage", "device_id = ? AND t_sec >= ? AND t_sec < ?", args(s.deviceId, s.startSec, s.endSec))
        d.delete("sleep_session", "id = ?", args(id)) > 0
    }

    internal companion object {
        /** Pieces of one device's sleep less than this apart are one night (a midnight sync splits a night with no gap). */
        internal const val JOIN_GAP_SEC: Long = 60 * 60

        /** How far around a range to look for pieces that join into a night ending inside it. */
        private const val JOIN_LOOKAROUND_SEC: Long = 24 * 3600

        /** A joined night: the longest piece's row [id] and the joined [session]. */
        internal data class Joined(val id: Long, val session: SleepSession)

        private fun joinedIn(d: SQLiteDatabase, range: TimeRange): List<Joined> {
            val rows = d.queryList(
                "$SELECT_SESSION WHERE end_sec >= ? AND start_sec < ? ORDER BY device_id, start_sec",
                args(range.startSec - JOIN_LOOKAROUND_SEC, range.endSec + JOIN_LOOKAROUND_SEC),
            ) { c -> c.getLong(0) to mapSession(c) }
            return join(rows)
        }

        /**
         * Joins [rows] (row id to session) of the same device whose gap is at most [JOIN_GAP_SEC]
         * (overlaps included) into one session from the earliest start to the latest end. The score
         * and id come from the longest piece (the sleep file's piece carries the watch's score).
         */
        internal fun join(rows: List<Pair<Long, SleepSession>>): List<Joined> {
            val out = ArrayList<Joined>()
            for ((_, group) in rows.groupBy { it.second.deviceId }) {
                val sorted = group.sortedBy { it.second.startSec }
                var start = 0L
                var end = 0L
                var best: Pair<Long, SleepSession>? = null
                fun flush() {
                    val b = best ?: return
                    out += Joined(b.first, SleepSession(b.second.deviceId, start, end, b.second.score))
                }
                for (row in sorted) {
                    val s = row.second
                    val cur = best
                    if (cur == null || s.startSec - end > JOIN_GAP_SEC) {
                        flush()
                        start = s.startSec; end = s.endSec; best = row
                    } else {
                        end = maxOf(end, s.endSec)
                        if (s.endSec - s.startSec > cur.second.endSec - cur.second.startSec) best = row
                    }
                }
                flush()
            }
            return out
        }

        private const val SELECT_SESSION = "SELECT id, device_id, start_sec, end_sec, score FROM sleep_session"

        private fun mapSession(c: Cursor): SleepSession = SleepSession(c.getLong(1), c.getLong(2), c.getLong(3), c.intOrNull(4))

        private fun stagesIn(d: SQLiteDatabase, range: TimeRange, deviceId: Long): List<SleepStageSample> = d.queryList(
            "SELECT t_sec, stage FROM sleep_stage WHERE device_id = ? AND t_sec >= ? AND t_sec < ? ORDER BY t_sec",
            args(deviceId, range.startSec, range.endSec),
        ) { c -> SleepStageSample(deviceId, c.getLong(0), SleepStage.fromCode(c.getInt(1)) ?: SleepStage.UNMEASURABLE) }

        internal fun insertSessionIn(d: SQLiteDatabase, s: SleepSession, importId: Long? = null): Long {
            val inserted = d.withStatement(
                "INSERT OR IGNORE INTO sleep_session (device_id, start_sec, end_sec, score, import_id) VALUES (?, ?, ?, ?, ?)",
            ) { st ->
                st.bindLong(1, s.deviceId)
                st.bindLong(2, s.startSec)
                st.bindLong(3, s.endSec)
                st.bindIntOrNull(4, s.score)
                st.bindLongOrNull(5, importId)
                st.executeInsert()
            }
            if (inserted != -1L) return inserted
            d.withStatement("UPDATE sleep_session SET end_sec = ?, score = ? WHERE device_id = ? AND start_sec = ?") { st ->
                st.bindLong(1, s.endSec)
                st.bindIntOrNull(2, s.score)
                st.bindLong(3, s.deviceId)
                st.bindLong(4, s.startSec)
                st.executeUpdateDelete()
            }
            return d.queryOne("SELECT id FROM sleep_session WHERE device_id = ? AND start_sec = ?", args(s.deviceId, s.startSec)) {
                it.getLong(0)
            } ?: error("sleep session vanished")
        }

        internal fun insertStagesIn(d: SQLiteDatabase, stages: List<SleepStageSample>, importId: Long? = null): Int {
            d.withStatement("INSERT OR REPLACE INTO sleep_stage (device_id, t_sec, stage, import_id) VALUES (?, ?, ?, ?)") { st ->
                for (s in stages) {
                    st.bindLong(1, s.deviceId)
                    st.bindLong(2, s.tSec)
                    st.bindLong(3, s.stage.code.toLong())
                    st.bindLongOrNull(4, importId)
                    st.executeInsert()
                }
            }
            return stages.size
        }
    }
}
