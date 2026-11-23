/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db.dao

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.selfwatch.db.DeviceKindCodes
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.db.args
import app.selfwatch.db.intOrNull
import app.selfwatch.db.longOrNull
import app.selfwatch.db.queryList
import app.selfwatch.db.queryOne
import app.selfwatch.db.read
import app.selfwatch.db.strOrNull
import app.selfwatch.db.write
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.device.Feature
import app.selfwatch.model.device.SupportTier
import app.selfwatch.model.time.SystemClock

/**
 * Paired devices (table `device`). Devices are never deleted, only marked removed, because health
 * rows keep their `device_id`.
 *
 * `DeviceInfo.tier` and `batteryPct` are not stored: rows read back with [SupportTier.UNVERIFIED]
 * and a null battery; the caller fills them from `models.json` and the live connection.
 */
public class DeviceDao internal constructor(private val db: SelfWatchDb) {

    /**
     * Inserts [info], or updates the row with the same address (which also clears `removed`).
     * Returns the row id. [nowSec] becomes `added_sec` for a new row.
     */
    public suspend fun upsert(info: DeviceInfo, nowSec: Long = SystemClock.nowSec()): Long =
        db.write(setOf(Table.DEVICE)) { upsertIn(it, info, nowSec) }

    /** Devices not marked removed, highest priority first. */
    public suspend fun active(): List<DeviceInfo> = db.read {
        it.queryList("$SELECT WHERE removed = 0 ORDER BY priority DESC, id", null, ::map)
    }

    /** Every device, removed ones included. */
    public suspend fun all(): List<DeviceInfo> = db.read { it.queryList("$SELECT ORDER BY id", null, ::map) }

    /** The device with Bluetooth [address], removed or not. */
    public suspend fun byAddress(address: String): DeviceInfo? = db.read {
        it.queryOne("$SELECT WHERE address = ?", arrayOf(address), ::map)
    }

    /** The device with [id]. */
    public suspend fun get(id: Long): DeviceInfo? = db.read { it.queryOne("$SELECT WHERE id = ?", args(id), ::map) }

    /** Marks the device removed (forgotten); its data stays. */
    public suspend fun markRemoved(id: Long) {
        update(id, ContentValues().apply { put("removed", 1) })
    }

    /** Records the end of a successful sync. */
    public suspend fun setLastSync(id: Long, tSec: Long) {
        update(id, ContentValues().apply { put("last_sync_sec", tSec) })
    }

    /** The last successful sync time, or null. */
    public suspend fun lastSync(id: Long): Long? = db.read {
        it.queryOne("SELECT last_sync_sec FROM device WHERE id = ?", args(id)) { c -> c.longOrNull(0) }
    }

    /** Sets the priority used when two devices report the same minute (higher wins). */
    public suspend fun setPriority(id: Long, priority: Int) {
        update(id, ContentValues().apply { put("priority", priority) })
    }

    /**
     * Stores the user's device order in one transaction: [ids] first to last, so [active] lists them
     * in that order and the first one is the primary device. The first gets priority `ids.size`, the
     * last 1; devices not listed keep their priority.
     */
    public suspend fun setOrder(ids: List<Long>) {
        if (ids.isEmpty()) return
        db.write(setOf(Table.DEVICE)) { d ->
            ids.forEachIndexed { i, id ->
                d.update("device", ContentValues().apply { put("priority", ids.size - i) }, "id = ?", args(id))
            }
        }
    }

    /** Stores the CompanionDeviceManager association id (null clears it). */
    public suspend fun setAssociationId(id: Long, associationId: Long?) {
        update(id, ContentValues().apply { if (associationId == null) putNull("association_id") else put("association_id", associationId) })
    }

    /** The CompanionDeviceManager association id, or null. */
    public suspend fun associationId(id: Long): Long? = db.read {
        it.queryOne("SELECT association_id FROM device WHERE id = ?", args(id)) { c -> c.longOrNull(0) }
    }

    private suspend fun update(id: Long, cv: ContentValues) {
        db.write(setOf(Table.DEVICE)) { it.update("device", cv, "id = ?", args(id)) }
    }

    internal companion object {
        private const val SELECT =
            "SELECT id, kind, address, name, model, product_id, firmware, serial, features FROM device"

        internal fun upsertIn(d: SQLiteDatabase, info: DeviceInfo, nowSec: Long): Long {
            val cv = ContentValues().apply {
                put("kind", DeviceKindCodes.code(info.kind))
                put("address", info.address)
                put("name", info.name)
                put("model", info.model)
                if (info.productId == null) putNull("product_id") else put("product_id", info.productId)
                if (info.firmware == null) putNull("firmware") else put("firmware", info.firmware)
                if (info.serial == null) putNull("serial") else put("serial", info.serial)
                put("features", info.features.map { it.name }.sorted().joinToString(","))
                put("removed", 0)
            }
            val existing = d.queryOne("SELECT id FROM device WHERE address = ?", arrayOf(info.address)) { it.getLong(0) }
            if (existing != null) {
                d.update("device", cv, "id = ?", args(existing))
                return existing
            }
            cv.put("added_sec", nowSec)
            return d.insertOrThrow("device", null, cv)
        }

        private fun map(c: Cursor): DeviceInfo = DeviceInfo(
            id = c.getLong(0),
            kind = DeviceKindCodes.fromCode(c.getInt(1)) ?: DeviceKind.GARMIN_WATCH,
            address = c.getString(2),
            name = c.getString(3),
            model = c.getString(4),
            productId = c.intOrNull(5),
            firmware = c.strOrNull(6),
            serial = c.longOrNull(7),
            tier = SupportTier.UNVERIFIED,
            features = parseFeatures(c.getString(8)),
            batteryPct = null,
        )

        private fun parseFeatures(s: String): Set<Feature> =
            s.split(',').mapNotNullTo(LinkedHashSet()) { name -> Feature.entries.firstOrNull { it.name == name } }
    }
}
