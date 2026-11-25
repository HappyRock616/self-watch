/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.device

import app.selfwatch.db.SettingsStore
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.SwError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * How the `:watch` process tells the screens (another process) what each watch is doing.
 *
 * The service writes two setting rows per device and the settings write broadcasts a
 * `DbChanges` notice for `Table.SETTING`, which reaches the app process:
 * - [stateKey] `watch.state.<deviceId>`: the connection state as a short text ([encode]); `off` when
 *   the service stopped managing the device.
 * - [batteryKey] `watch.battery.<deviceId>`: the last battery percentage, kept after disconnecting.
 *
 * The end of each successful sync is in the `device` row (`last_sync_sec`, `Table.DEVICE`).
 * A process killed without warning leaves the last state behind; the next service start rewrites it.
 */
public object WatchStatus {
    /** Value of [stateKey] when the service is not managing the device (stopped or removed). */
    public const val OFF: String = "off"

    /** The setting key holding device [deviceId]'s connection state. */
    public fun stateKey(deviceId: Long): String = "watch.state.$deviceId"

    /** The setting key holding device [deviceId]'s last battery percentage. */
    public fun batteryKey(deviceId: Long): String = "watch.battery.$deviceId"

    /**
     * [state] as stored: `disconnected`, `connecting`, `handshaking`, `ready`,
     * `syncing|<done>|<total>|<label>` or `failed|<retrySec or empty>|<reason>`.
     */
    public fun encode(state: ConnectionState): String = when (state) {
        ConnectionState.Disconnected -> "disconnected"
        ConnectionState.Connecting -> "connecting"
        ConnectionState.Handshaking -> "handshaking"
        ConnectionState.Ready -> "ready"
        is ConnectionState.Syncing -> "syncing|${state.done}|${state.total}|${clean(state.label)}"
        is ConnectionState.Failed -> "failed|${state.retryInSec ?: ""}|${clean(reason(state.error))}"
    }

    /**
     * The state stored as [text]; null for [OFF], a missing value or anything unreadable. A failure
     * comes back with its reason as [SwError.Io].
     */
    public fun decode(text: String?): ConnectionState? {
        if (text == null) return null
        val parts = text.split('|')
        return when (parts[0]) {
            "disconnected" -> ConnectionState.Disconnected
            "connecting" -> ConnectionState.Connecting
            "handshaking" -> ConnectionState.Handshaking
            "ready" -> ConnectionState.Ready
            "syncing" -> {
                val done = parts.getOrNull(1)?.toIntOrNull() ?: return null
                val total = parts.getOrNull(2)?.toIntOrNull() ?: return null
                ConnectionState.Syncing(done, total, parts.getOrNull(3) ?: "")
            }
            "failed" -> ConnectionState.Failed(
                SwError.Io(parts.drop(2).joinToString("|")),
                parts.getOrNull(1)?.toIntOrNull(),
            )
            else -> null
        }
    }

    /** A short reason for [error], safe to show and store (no payloads). */
    public fun reason(error: SwError): String = when (error) {
        is SwError.NotConnected -> "not connected"
        is SwError.Timeout -> "timed out: ${error.what}"
        is SwError.Protocol -> error.detail
        is SwError.Io -> error.detail
        is SwError.PermissionMissing -> "permission missing: ${error.permission.substringAfterLast('.')}"
        is SwError.BadInput -> error.detail
        is SwError.Unsupported -> "unsupported: ${error.feature.name.lowercase()}"
        is SwError.Blocked -> "blocked: ${error.feature.name.lowercase()}"
        SwError.Offline -> "offline"
        SwError.WaitingForWifi -> "waiting for Wi-Fi"
        is SwError.RateLimited -> "rate limited"
    }

    private fun clean(s: String): String = s.replace('|', '/').replace('\n', ' ').take(MAX_TEXT)

    private const val MAX_TEXT = 120
}

/** Keeps a device's battery history (the real one writes `device_battery`, see `BatteryDao`). */
public fun interface BatteryLog {
    /** Device [deviceId] reported [pct] percent. */
    public suspend fun record(deviceId: Long, pct: Int)
}

/**
 * Writes [DeviceStatus] lists as [WatchStatus] settings, only when something changed. Devices that
 * drop out of the list are marked [WatchStatus.OFF]. Each new battery level also goes to [battery]
 * (the battery history), once per change. Never throws (a failed write is logged).
 */
public class WatchStatusPublisher(private val settings: SettingsStore, private val battery: BatteryLog? = null) {
    private val written = HashMap<String, String?>()
    private val lock = Mutex()

    /** Publishes [list] (the [DeviceManager.devices] value). */
    public suspend fun publish(list: List<DeviceStatus>) {
        val wanted = HashMap<String, String?>()
        for (key in written.keys) if (key.startsWith(STATE_PREFIX)) wanted[key] = WatchStatus.OFF
        val levels = HashMap<Long, Int>()
        for (d in list) {
            wanted[WatchStatus.stateKey(d.info.id)] = WatchStatus.encode(d.state)
            d.info.batteryPct?.let {
                val key = WatchStatus.batteryKey(d.info.id)
                if (lock.withLock { written[key] } != it.toString()) levels[d.info.id] = it
                wanted[key] = it.toString()
            }
        }
        write(wanted)
        val log = battery ?: return
        for ((id, pct) in levels) {
            try {
                log.record(id, pct)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "could not store the battery level", e)
            }
        }
    }

    /** Marks every device published so far [WatchStatus.OFF] (the service is stopping). */
    public suspend fun markAllOff() {
        write(written.keys.filter { it.startsWith(STATE_PREFIX) }.associateWith { WatchStatus.OFF })
    }

    private suspend fun write(wanted: Map<String, String?>): Unit = lock.withLock {
        val changed = wanted.filter { (k, v) -> !written.containsKey(k) || written[k] != v }
        if (changed.isEmpty()) return@withLock
        try {
            settings.setAll(changed)
            written.putAll(changed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SwLog.w(TAG, "could not publish the watch status", e)
        }
    }

    private companion object {
        const val TAG = "WatchStatus"
        val STATE_PREFIX: String = WatchStatus.stateKey(0).removeSuffix("0")
    }
}
