/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.device

import app.selfwatch.model.ble.BlePort
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchDriver

/** What the screens see for one paired device. */
public data class DeviceStatus(
    /** The device (battery filled in from the watch while connected). */
    val info: DeviceInfo,
    /** The connection state. */
    val state: ConnectionState,
    /** End of the last successful sync (UTC epoch seconds), or null if never. */
    val lastSyncSec: Long?,
)

/** How the watch stays in touch (setting `connection.mode`). */
public enum class ConnectionMode(
    /** The stored setting value. */
    public val key: String,
) {
    /** Connect and stay connected, reconnecting by itself. */
    ALWAYS("always"),

    /** Connect only at app open, on "Sync now" and on a schedule; sync, then disconnect. */
    SYNC_ONLY("sync_only"),
    ;

    /** Lookup by stored value. */
    public companion object {
        /** The mode stored as [key]; unknown or missing values mean [ALWAYS]. */
        public fun fromKey(key: String?): ConnectionMode = entries.firstOrNull { it.key == key } ?: ALWAYS
    }
}

/**
 * Makes Bluetooth links. The app wires this to `ble.BleConnection`; tests use `FakeBlePort`.
 */
public interface PortFactory {
    /**
     * A link to the device at Bluetooth [address]. With [autoReconnect] the link uses the low-power
     * background connect (GATT `autoConnect`), as in `always` mode.
     */
    public fun create(address: String, autoReconnect: Boolean): BlePort

    /** Releases a link made by [create] (closes GATT). Called once per link, even after failures. */
    public suspend fun release(port: BlePort) {}

    /**
     * True when another app on this phone holds a Bluetooth link to [address] while Self Watch has
     * none (Android lists the phone's connected GATT devices). False when unknown.
     */
    public fun heldElsewhere(address: String): Boolean = false
}

/** Makes the protocol driver for a device kind. The app wires this to `garmin.GarminDriver`. */
public fun interface DriverFactory {
    /** A new driver for [info]'s kind ([DeviceKind]). */
    public fun create(info: DeviceInfo): WatchDriver
}

/** Starts and cancels the sync-only schedule (JobScheduler job 1001 in the app). */
public interface SyncScheduler {
    /** Schedules a persisted sync about every [hours] hours (replacing a different schedule). */
    public fun schedule(hours: Int)

    /** Cancels the schedule. */
    public fun cancel()

    /** A scheduler that does nothing. */
    public companion object {
        /** No schedule (tests, or before the app wires one). */
        public val NONE: SyncScheduler = object : SyncScheduler {
            override fun schedule(hours: Int) {}

            override fun cancel() {}
        }
    }
}

/** Reconnect delays after a connection ends or fails. */
public object Backoff {
    /**
     * Delays in seconds: 5, 10, 30, 60, 120, then every 5 minutes. Every attempt wakes the watch's radio
     * (Gadgetbridge starts at 2 s and doubles to 64 s).
     */
    public val DELAYS_SEC: List<Int> = listOf(5, 10, 30, 60, 120, 300)

    /** A link must stay up this long (ms) before the delays start again from the first one. */
    public const val STABLE_MS: Long = 5 * 60_000L

    /** The delay for retry number [attempt] (0-based); attempts past the list repeat the last delay. */
    public fun delaySec(attempt: Int): Int = DELAYS_SEC[attempt.coerceIn(0, DELAYS_SEC.lastIndex)]
}

/** Which devices are watches (they take commands and notifications) and which are health sensors. */
public object DeviceKinds {
    /** True for kinds that are watches; scales, straps and cuffs only send readings. */
    public fun isWatch(kind: DeviceKind): Boolean = kind == DeviceKind.GARMIN_WATCH
}

/**
 * Which watches get a relayed phone event. The device list is in the user's device order (first =
 * primary), as [DeviceManager.devices] publishes it.
 *
 * - Notifications, calls and music go to **one** watch: the first connected watch in the order, so the
 *   primary watch gets them and a second watch takes over only while the primary is not connected.
 * - Time and weather go to every connected watch.
 * - Sensors never get anything.
 */
public object RelayTargets {
    /** The device ids that should get [cmd], given [devices] in order. */
    public fun of(devices: List<DeviceStatus>, cmd: WatchCommand): List<Long> {
        val connected = devices.filter { DeviceKinds.isWatch(it.info.kind) && isConnected(it.state) }
        return if (primaryOnly(cmd)) connected.take(1).map { it.info.id } else connected.map { it.info.id }
    }

    /** True for commands only the primary connected watch gets. */
    public fun primaryOnly(cmd: WatchCommand): Boolean = when (cmd) {
        is WatchCommand.Notify, is WatchCommand.RemoveNotification, is WatchCommand.IncomingCall,
        WatchCommand.CallEnded, is WatchCommand.Music,
        -> true
        else -> false
    }

    private fun isConnected(s: ConnectionState): Boolean = s is ConnectionState.Ready || s is ConnectionState.Syncing
}
