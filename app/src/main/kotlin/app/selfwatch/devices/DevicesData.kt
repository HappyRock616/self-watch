/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.devices

import android.content.Context
import app.selfwatch.ServiceClient
import app.selfwatch.ble.pair.PairedDevice
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.db.dao.DataCounts
import app.selfwatch.garmin.models.GarminModels
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.device.SupportTier
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.SystemClock
import app.selfwatch.model.time.TimeRange
import app.selfwatch.service.device.DeviceKinds
import app.selfwatch.service.device.WatchStatus
import app.selfwatch.service.presence.PresenceObserver
import app.selfwatch.watch.WatchSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [DevicesSource] on the database. The service runs in process `:watch` and publishes each watch's
 * state and battery as settings ([WatchStatus]); settings and device writes broadcast change notices
 * that reach this process, so the card follows the service live.
 */
class DbDevicesSource(
    private val db: SelfWatchDb,
    private val models: () -> GarminModels?,
    private val debuggable: Boolean,
    private val clock: Clock = SystemClock,
) : DevicesSource {

    override val watch: Flow<WatchCard?> =
        db.changes.observe(setOf(Table.DEVICE, Table.SETTING)).conflate().map { loadWatch() }.distinctUntilChanged()

    override val counts: Flow<DataCounts> =
        db.changes.observe(setOf(Table.ACTIVITY, Table.METRIC, Table.SLEEP_STAGE, Table.WORKOUT, Table.SYNC_FILE))
            .conflate()
            .map { loadCounts() }
            .distinctUntilChanged()

    override val bleCapture: Flow<Boolean> =
        db.settings.observe(WatchSettings.BLE_CAPTURE).map { WatchSettings.bleCaptureOn(it, debuggable) }

    private suspend fun loadWatch(): WatchCard? {
        val d = db.devices.active().firstOrNull { DeviceKinds.isWatch(it.kind) } ?: return null
        val model = models()?.match(d.name, d.productId)
        return WatchCard(
            id = d.id,
            address = d.address,
            name = d.name,
            model = d.model,
            tier = model?.tier ?: SupportTier.UNVERIFIED,
            associationId = db.devices.associationId(d.id)?.toInt(),
            state = WatchStatus.decode(db.settings.get(WatchStatus.stateKey(d.id))),
            lastSyncSec = db.devices.lastSync(d.id),
            batteryPct = db.settings.getInt(WatchStatus.batteryKey(d.id)),
            features = d.features,
        )
    }

    private suspend fun loadCounts(): DataCounts {
        val today = clock.today()
        val dayStart = today.startSec(clock.zone())
        // "Last night": 18:00 yesterday to 12:00 today.
        val night = TimeRange(dayStart - 6 * HOUR_SEC, dayStart + 12 * HOUR_SEC)
        return db.stats.counts(today.toTimeRange(clock.zone()), night)
    }

    private companion object {
        const val HOUR_SEC = 3_600L
    }
}

/** [DeviceActions] on the database, the service client and the companion device manager. */
class AppDeviceActions(
    ctx: Context,
    private val db: SelfWatchDb,
    private val service: ServiceClient,
    private val models: () -> GarminModels?,
    private val clock: Clock = SystemClock,
) : DeviceActions {
    private val app: Context = ctx.applicationContext ?: ctx

    override suspend fun savePaired(paired: PairedDevice): Long {
        val info = deviceInfoFor(paired, models())
        val id = db.devices.upsert(info, clock.nowSec())
        db.devices.setAssociationId(id, paired.associationId?.toLong())
        db.settings.setBool(WatchSettings.firstConnectKey(paired.address), true)
        SwLog.i(TAG, "paired watch saved as device $id (${info.model}, ${info.tier})")
        return id
    }

    override fun startService(): Boolean = service.start()

    override fun syncNow(): Boolean = service.syncNow()

    override suspend fun forget(watch: WatchCard) {
        PresenceObserver.stop(app, watch.address, watch.associationId?.toLong())
        db.devices.markRemoved(watch.id)
        db.settings.setAll(
            mapOf(
                WatchSettings.firstConnectKey(watch.address) to null,
                WatchStatus.stateKey(watch.id) to null,
                WatchStatus.batteryKey(watch.id) to null,
            ),
        )
        if (db.devices.active().isEmpty()) service.stop() else service.start()
        SwLog.i(TAG, "device ${watch.id} removed")
    }

    override suspend fun setBleCapture(on: Boolean) {
        db.settings.setBool(WatchSettings.BLE_CAPTURE, on)
    }

    /** Pure parts, for tests. */
    companion object {
        private const val TAG = "DeviceActions"

        /** Name shown when the picker gave no name. */
        const val UNNAMED: String = "Garmin watch"

        /** The `device` row for a newly paired watch: model and tier from `models.json` by name. */
        fun deviceInfoFor(paired: PairedDevice, models: GarminModels?): DeviceInfo {
            val name = paired.name?.takeIf { it.isNotBlank() } ?: UNNAMED
            val model = models?.match(name, null)
            return DeviceInfo(
                id = 0,
                kind = DeviceKind.GARMIN_WATCH,
                address = paired.address.uppercase(),
                name = name,
                model = model?.name ?: name,
                productId = null,
                firmware = null,
                serial = null,
                tier = model?.tier ?: SupportTier.UNVERIFIED,
                features = emptySet(),
                batteryPct = null,
            )
        }
    }
}
