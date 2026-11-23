/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.hc

import android.database.sqlite.SQLiteDatabase
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.device.SupportTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Setting keys used by Health Connect sync (generic keys in table `setting`, no defaults in
 * `SettingKeys`: an absent key reads as off / never).
 */
object HcKeys {
    /** Sync with Health Connect on app open and after each watch sync (bool, default false). */
    const val ENABLED: String = SettingKeys.HC_ENABLED

    /** Also read when the app is in the background (bool, default false; needs [HcPermissions.BACKGROUND]). */
    const val BACKGROUND_READ: String = SettingKeys.HC_BACKGROUND_READ

    /** Epoch second until which sync waits after a rate-limit refusal. */
    const val BACKOFF_UNTIL: String = "hc.backoff_until"

    /** Consecutive rate-limit refusals (sets the next back-off length). */
    const val BACKOFF_STEP: String = "hc.backoff_step"

    /** The `import_batch` id that tags rows read from Health Connect. */
    const val IMPORT_ID: String = "hc.import_id"

    /** End (epoch second) of the last successful write of [type]. */
    fun lastWrite(type: HcType): String = "hc.last_write.${type.key}"

    /** The changes token of [type]. */
    fun changesToken(type: HcType): String = "hc.changes_token.${type.key}"
}

/** Database helpers shared by [HcWriter] and [HcReader]. */
internal class HcStore(val db: SelfWatchDb) {

    /** Runs a read-only query block on the IO dispatcher. */
    suspend fun <T> query(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) { block(db.readableDatabase) }

    /** The id of the Health Connect device row, or null when none exists yet. */
    suspend fun hcDeviceId(): Long? = query { d ->
        d.rawQuery("SELECT id FROM device WHERE address = ?", arrayOf(DEVICE_ADDRESS)).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }

    /**
     * The id of the Health Connect device row, created (marked removed, so the service never tries to
     * connect to it, and ranked below every real device) when missing.
     */
    suspend fun ensureHcDevice(nowSec: Long): Long {
        hcDeviceId()?.let { return it }
        val id = db.devices.upsert(
            DeviceInfo(
                id = 0, kind = DeviceKind.GARMIN_WATCH, address = DEVICE_ADDRESS, name = DEVICE_NAME, model = DEVICE_NAME,
                productId = null, firmware = null, serial = null, tier = SupportTier.UNVERIFIED, features = emptySet(), batteryPct = null,
            ),
            nowSec,
        )
        db.devices.markRemoved(id)
        return id
    }

    /** Ids of import batches made by Health Connect reads (SQL sub-select). */
    val hcBatchesSql: String = "SELECT id FROM import_batch WHERE importer = '$IMPORTER'"

    /** Constants. */
    companion object {
        /** `device.address` of the row that holds steps and sleep read from Health Connect. */
        const val DEVICE_ADDRESS: String = "health-connect"

        /** Name and model of that row. */
        const val DEVICE_NAME: String = "Health Connect"

        /** `import_batch.importer` of rows read from Health Connect. */
        const val IMPORTER: String = "health-connect"
    }
}
