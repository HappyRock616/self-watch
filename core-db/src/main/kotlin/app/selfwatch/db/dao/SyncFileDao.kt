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
import app.selfwatch.db.SyncFileState
import app.selfwatch.db.Table
import app.selfwatch.db.args
import app.selfwatch.db.bindLongOrNull
import app.selfwatch.db.queryOne
import app.selfwatch.db.read
import app.selfwatch.db.withStatement
import app.selfwatch.db.write
import app.selfwatch.model.watch.SyncFileKey

/**
 * Which watch files were already handled (table `sync_file`), so a sync never stores a file twice.
 * A file is identified by device, type, subtype, time and size; the watch's file index can be reused.
 */
public class SyncFileDao internal constructor(private val db: SelfWatchDb) {

    /** True when [key] was stored ([SyncFileState.DONE]) or deliberately [SyncFileState.SKIPPED]. Failed files are retried. */
    public suspend fun isDone(key: SyncFileKey): Boolean = state(key).let { it == SyncFileState.DONE || it == SyncFileState.SKIPPED }

    /** The recorded state of [key], or null if never seen. */
    public suspend fun state(key: SyncFileKey): SyncFileState? = db.read { d ->
        d.queryOne(
            "SELECT state FROM sync_file WHERE device_id = ? AND file_type = ? AND file_subtype = ? AND t_sec = ? AND size = ?",
            args(key.deviceId, key.fileType, key.fileSubtype, key.tSec, key.size),
        ) { SyncFileState.fromCode(it.getInt(0)) }
    }

    /** Records [state] for [key] at [doneSec] (replacing any earlier state). */
    public suspend fun mark(key: SyncFileKey, state: SyncFileState, doneSec: Long?) {
        db.write(setOf(Table.SYNC_FILE)) { markIn(it, key, state, doneSec) }
    }

    internal companion object {
        internal fun markIn(d: SQLiteDatabase, key: SyncFileKey, state: SyncFileState, doneSec: Long?) {
            d.withStatement(
                "INSERT OR REPLACE INTO sync_file (device_id, file_index, file_type, file_subtype, t_sec, size, state, done_sec) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ) { st ->
                st.bindLong(1, key.deviceId)
                st.bindLong(2, key.fileIndex.toLong())
                st.bindLong(3, key.fileType.toLong())
                st.bindLong(4, key.fileSubtype.toLong())
                st.bindLong(5, key.tSec)
                st.bindLong(6, key.size.toLong())
                st.bindLong(7, state.code.toLong())
                st.bindLongOrNull(8, doneSec)
                st.executeInsert()
            }
        }
    }
}
