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

    /** The main sleep that ended on [day] in [zone] (the longest session ending that day), or null. */
    public suspend fun night(day: Day, zone: ZoneId): SleepNight? = db.read { d ->
        val r = day.toTimeRange(zone)
        val row = d.queryOne(
            "$SELECT_SESSION WHERE end_sec >= ? AND end_sec < ? ORDER BY (end_sec - start_sec) DESC, id LIMIT 1",
            args(r.startSec, r.endSec),
        ) { c -> c.getLong(0) to mapSession(c) } ?: return@read null
        val s = row.second
        SleepNight(row.first, s, stagesIn(d, TimeRange(s.startSec, s.endSec), s.deviceId))
    }

    /** Sessions (naps included) that ended within [days] in [zone], oldest first. */
    public suspend fun sessions(days: DayRange, zone: ZoneId): List<SleepSession> = db.read { d ->
        val r = days.toTimeRange(zone)
        d.queryList("$SELECT_SESSION WHERE end_sec >= ? AND end_sec < ? ORDER BY end_sec", args(r.startSec, r.endSec), ::mapSession)
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
