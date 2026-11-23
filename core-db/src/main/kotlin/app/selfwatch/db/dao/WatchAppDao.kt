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
import app.selfwatch.db.bindIntOrNull
import app.selfwatch.db.bindStrOrNull
import app.selfwatch.db.bool
import app.selfwatch.db.intOrNull
import app.selfwatch.db.queryList
import app.selfwatch.db.queryOne
import app.selfwatch.db.read
import app.selfwatch.db.strOrNull
import app.selfwatch.db.withStatement
import app.selfwatch.db.write
import java.util.UUID

/**
 * A Connect IQ app seen on a watch.
 *
 * @property kind Connect IQ app type code, if known.
 * @property seenSec when the watch last listed it.
 */
public data class WatchAppRow(
    val deviceId: Long,
    val appUuid: UUID,
    val name: String?,
    val version: Int?,
    val kind: Int?,
    val installed: Boolean,
    val seenSec: Long,
)

/** One saved copy of a watch app's settings: [valuesJson] and, if known, the settings [definitionJson]. */
public data class WatchAppSettings(val appUuid: UUID, val savedSec: Long, val valuesJson: String, val definitionJson: String?)

/** Connect IQ apps per watch (table `watch_app`) and saved settings history (table `watch_app_settings`). */
public class WatchAppDao internal constructor(private val db: SelfWatchDb) {

    /** Stores [app], replacing the row for the same device and app. */
    public suspend fun upsert(app: WatchAppRow) {
        db.write(setOf(Table.WATCH_APP)) { d ->
            d.withStatement(
                "INSERT OR REPLACE INTO watch_app (device_id, app_uuid, name, version, kind, installed, seen_sec) VALUES (?, ?, ?, ?, ?, ?, ?)",
            ) { st ->
                st.bindLong(1, app.deviceId)
                st.bindString(2, app.appUuid.toString())
                st.bindStrOrNull(3, app.name)
                st.bindIntOrNull(4, app.version)
                st.bindIntOrNull(5, app.kind)
                st.bindLong(6, if (app.installed) 1 else 0)
                st.bindLong(7, app.seenSec)
                st.executeInsert()
            }
        }
    }

    /** Apps of [deviceId], installed ones only unless [includeRemoved]. By name. */
    public suspend fun forDevice(deviceId: Long, includeRemoved: Boolean = false): List<WatchAppRow> = db.read { d ->
        val w = if (includeRemoved) "" else "AND installed = 1"
        d.queryList("$SELECT WHERE device_id = ? $w ORDER BY name COLLATE NOCASE, app_uuid", args(deviceId), ::map)
    }

    /** The row for [deviceId] and [appUuid], or null. */
    public suspend fun get(deviceId: Long, appUuid: UUID): WatchAppRow? = db.read {
        it.queryOne("$SELECT WHERE device_id = ? AND app_uuid = ?", arrayOf(deviceId.toString(), appUuid.toString()), ::map)
    }

    /** Marks an app installed or not (row kept for history). Returns true if the row exists. */
    public suspend fun setInstalled(deviceId: Long, appUuid: UUID, installed: Boolean, seenSec: Long): Boolean =
        db.write(setOf(Table.WATCH_APP)) { d ->
            d.withStatement("UPDATE watch_app SET installed = ?, seen_sec = ? WHERE device_id = ? AND app_uuid = ?") { st ->
                st.bindLong(1, if (installed) 1 else 0)
                st.bindLong(2, seenSec)
                st.bindLong(3, deviceId)
                st.bindString(4, appUuid.toString())
                st.executeUpdateDelete() > 0
            }
        }

    /** Removes the row for [deviceId] and [appUuid]. Returns true if it existed. */
    public suspend fun delete(deviceId: Long, appUuid: UUID): Boolean = db.write(setOf(Table.WATCH_APP)) {
        it.delete("watch_app", "device_id = ? AND app_uuid = ?", arrayOf(deviceId.toString(), appUuid.toString())) > 0
    }

    /** Saves a settings copy (same app and time replaces). */
    public suspend fun saveSettings(s: WatchAppSettings) {
        db.write(setOf(Table.WATCH_APP_SETTINGS)) { d ->
            d.withStatement(
                "INSERT OR REPLACE INTO watch_app_settings (app_uuid, saved_sec, values_json, definition_json) VALUES (?, ?, ?, ?)",
            ) { st ->
                st.bindString(1, s.appUuid.toString())
                st.bindLong(2, s.savedSec)
                st.bindString(3, s.valuesJson)
                st.bindStrOrNull(4, s.definitionJson)
                st.executeInsert()
            }
        }
    }

    /** The newest saved settings of [appUuid], or null. */
    public suspend fun latestSettings(appUuid: UUID): WatchAppSettings? = db.read {
        it.queryOne("$SELECT_SETTINGS WHERE app_uuid = ? ORDER BY saved_sec DESC LIMIT 1", arrayOf(appUuid.toString()), ::mapSettings)
    }

    /** Every saved settings copy of [appUuid], newest first. */
    public suspend fun settingsHistory(appUuid: UUID): List<WatchAppSettings> = db.read {
        it.queryList("$SELECT_SETTINGS WHERE app_uuid = ? ORDER BY saved_sec DESC", arrayOf(appUuid.toString()), ::mapSettings)
    }

    /** Keeps only the newest [keep] settings copies of [appUuid]; returns how many were deleted. */
    public suspend fun pruneSettings(appUuid: UUID, keep: Int): Int = db.write(setOf(Table.WATCH_APP_SETTINGS)) { d ->
        val u = appUuid.toString()
        d.delete(
            "watch_app_settings",
            "app_uuid = ? AND saved_sec NOT IN (SELECT saved_sec FROM watch_app_settings WHERE app_uuid = ? " +
                "ORDER BY saved_sec DESC LIMIT ${keep.coerceAtLeast(0)})",
            arrayOf(u, u),
        )
    }

    private companion object {
        const val SELECT = "SELECT device_id, app_uuid, name, version, kind, installed, seen_sec FROM watch_app"
        const val SELECT_SETTINGS = "SELECT app_uuid, saved_sec, values_json, definition_json FROM watch_app_settings"

        fun map(c: Cursor): WatchAppRow =
            WatchAppRow(c.getLong(0), UUID.fromString(c.getString(1)), c.strOrNull(2), c.intOrNull(3), c.intOrNull(4), c.bool(5), c.getLong(6))

        fun mapSettings(c: Cursor): WatchAppSettings =
            WatchAppSettings(UUID.fromString(c.getString(0)), c.getLong(1), c.getString(2), c.strOrNull(3))
    }
}
