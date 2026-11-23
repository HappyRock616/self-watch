/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.devices

import android.content.Context
import androidx.annotation.StringRes
import app.selfwatch.R
import app.selfwatch.ServiceClient
import app.selfwatch.ble.pair.PairFilter
import app.selfwatch.ble.pair.PairedDevice
import app.selfwatch.ble.profile.HealthProfile
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.device.Feature
import app.selfwatch.model.device.SupportTier
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.SystemClock
import app.selfwatch.model.time.TimeRange
import app.selfwatch.service.device.DeviceKinds
import app.selfwatch.service.device.WatchStatus
import app.selfwatch.service.presence.PresenceObserver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * One paired device of any kind, as the Devices tab, Device order and Other devices show it.
 *
 * @property state the connection state the service last published, or null when it is not managing it.
 * @property heartRateNow the strap's latest heart rate when it is from the last half minute, else null.
 */
data class DeviceCard(
    /** `device.id`. */
    val id: Long,
    /** What sort of device. */
    val kind: DeviceKind,
    /** Bluetooth address. */
    val address: String,
    /** Name shown. */
    val name: String,
    /** Companion device association id (API 33+), when known. */
    val associationId: Int?,
    /** See above. */
    val state: ConnectionState?,
    /** End of the last successful sync, or null. */
    val lastSyncSec: Long?,
    /** Last battery level, or null. */
    val batteryPct: Int?,
    /** What it can do (empty until its first connection). */
    val features: Set<Feature>,
    /** See above. */
    val heartRateNow: Int? = null,
) {
    /** True for watches (they get notifications); false for scales, straps and cuffs. */
    val isWatch: Boolean get() = DeviceKinds.isWatch(kind)
}

/** Every paired device in the user's order (first = primary), and what can be done to them. */
interface DeviceList {
    /** Every active device in order; updates on every change. */
    val devices: Flow<List<DeviceCard>>

    /** Stores the order: [ids] first to last; the first is the primary device. */
    suspend fun setOrder(ids: List<Long>)

    /** Moves device [id] to the top of the order (makes it primary). */
    suspend fun makePrimary(id: Long)

    /** Saves a newly paired sensor of [kind]; [fallbackName] is used when the picker gave no name. Returns its `device.id`. */
    suspend fun savePairedSensor(paired: PairedDevice, kind: DeviceKind, fallbackName: String): Long

    /** Forgets [device] (stops presence, marks it removed; its data stays) and stops or restarts the service. */
    suspend fun forget(device: DeviceCard)

    /** Starts (or refreshes) the service; false when Android refused. */
    fun startService(): Boolean
}

/** The device types of "Other devices": standard Bluetooth health profiles. */
enum class SensorType(
    /** Stored kind. */
    val kind: DeviceKind,
    /** Title. */
    @param:StringRes val title: Int,
    /** One-line description. */
    @param:StringRes val summary: Int,
) {
    SCALE(DeviceKind.WEIGHT_SCALE, R.string.devices_kind_scale, R.string.devices_other_scale_sub),
    HEART_RATE(DeviceKind.HEART_RATE_SENSOR, R.string.devices_kind_hr, R.string.devices_other_hr_sub),
    BLOOD_PRESSURE(DeviceKind.BLOOD_PRESSURE_MONITOR, R.string.devices_kind_bp, R.string.devices_other_bp_sub),
    ;

    /** The system picker's filter: devices advertising one of this type's services. */
    val filter: PairFilter get() = PairFilter(namePattern = null, manufacturerId = null, serviceUuids = HealthProfile.forKind(kind).map { it.service })

    /** Lookups. */
    companion object {
        /** The type of [kind], or null for watches. */
        fun of(kind: DeviceKind): SensorType? = entries.firstOrNull { it.kind == kind }

        /** The label of a device [kind]. */
        @StringRes
        fun label(kind: DeviceKind): Int = of(kind)?.title ?: R.string.devices_kind_watch
    }
}

/** [DeviceList] on the database and the service client. */
class DbDeviceList(
    ctx: Context,
    private val db: SelfWatchDb,
    private val service: ServiceClient,
    private val clock: Clock = SystemClock,
) : DeviceList {
    private val app: Context = ctx.applicationContext ?: ctx

    override val devices: Flow<List<DeviceCard>> =
        db.changes.observe(setOf(Table.DEVICE, Table.SETTING, Table.METRIC)).conflate().map { load() }.distinctUntilChanged()

    private suspend fun load(): List<DeviceCard> {
        val now = clock.nowSec()
        return db.devices.active().map { d ->
            val hr = if (d.kind == DeviceKind.HEART_RATE_SENSOR) {
                db.metrics.series(MetricKind.HEART_RATE, TimeRange(now - LIVE_SEC, now + 1), d.id).lastOrNull()?.value
            } else {
                null
            }
            DeviceCard(
                id = d.id,
                kind = d.kind,
                address = d.address,
                name = d.name,
                associationId = db.devices.associationId(d.id)?.toInt(),
                state = WatchStatus.decode(db.settings.get(WatchStatus.stateKey(d.id))),
                lastSyncSec = db.devices.lastSync(d.id),
                batteryPct = db.settings.getInt(WatchStatus.batteryKey(d.id)),
                features = d.features,
                heartRateNow = hr,
            )
        }
    }

    override suspend fun setOrder(ids: List<Long>) {
        db.devices.setOrder(ids)
        // The service re-reads the order: summaries and the primary watch follow it.
        if (ids.isNotEmpty()) service.start()
        SwLog.i(TAG, "device order saved (${ids.size} devices)")
    }

    override suspend fun makePrimary(id: Long) {
        val ids = db.devices.active().map { it.id }
        if (id !in ids) return
        setOrder(listOf(id) + ids.filter { it != id })
    }

    override suspend fun savePairedSensor(paired: PairedDevice, kind: DeviceKind, fallbackName: String): Long {
        val id = db.devices.upsert(sensorInfo(paired, kind, fallbackName), clock.nowSec())
        db.devices.setAssociationId(id, paired.associationId?.toLong())
        SwLog.i(TAG, "paired ${kind.name.lowercase()} saved as device $id")
        return id
    }

    override suspend fun forget(device: DeviceCard) {
        PresenceObserver.stop(app, device.address, device.associationId?.toLong())
        db.devices.markRemoved(device.id)
        db.settings.setAll(mapOf(WatchStatus.stateKey(device.id) to null, WatchStatus.batteryKey(device.id) to null))
        if (db.devices.active().isEmpty()) service.stop() else service.start()
        SwLog.i(TAG, "device ${device.id} removed")
    }

    override fun startService(): Boolean = service.start()

    /** Pure parts, for tests. */
    companion object {
        private const val TAG = "DeviceList"

        /** How recent a heart rate must be to count as live. */
        const val LIVE_SEC: Long = 30

        /** The `device` row for a newly paired sensor: features from its kind until it connects. */
        fun sensorInfo(paired: PairedDevice, kind: DeviceKind, fallbackName: String): DeviceInfo {
            val name = paired.name?.takeIf { it.isNotBlank() } ?: fallbackName
            return DeviceInfo(
                id = 0,
                kind = kind,
                address = paired.address.uppercase(),
                name = name,
                model = fallbackName,
                productId = null,
                firmware = null,
                serial = null,
                tier = SupportTier.UNVERIFIED,
                features = HealthProfile.forKind(kind).mapTo(LinkedHashSet()) { it.feature },
                batteryPct = null,
            )
        }
    }
}
