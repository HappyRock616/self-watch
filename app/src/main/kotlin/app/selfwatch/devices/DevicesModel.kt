/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.AppGraph
import app.selfwatch.ble.pair.PairedDevice
import app.selfwatch.db.dao.DataCounts
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.device.Feature
import app.selfwatch.model.device.SupportTier
import app.selfwatch.model.log.SwLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * The paired watch as the Devices screen shows it.
 *
 * @property state the connection state the service last published, or null when the service is not
 *   managing the watch (stopped, or never started since pairing).
 */
data class WatchCard(
    /** `device.id`. */
    val id: Long,
    /** Bluetooth address. */
    val address: String,
    /** Bluetooth name. */
    val name: String,
    /** Model name from `models.json`. */
    val model: String,
    /** How well this model is known to work. */
    val tier: SupportTier,
    /** Companion device association id (API 33+), when known. */
    val associationId: Int?,
    /** Connection state (see above). */
    val state: ConnectionState?,
    /** End of the last successful sync (UTC epoch seconds), or null if never. */
    val lastSyncSec: Long?,
    /** Last battery level the watch reported, or null. */
    val batteryPct: Int?,
    /** What the watch can do (empty until its first connection; then nothing is hidden). */
    val features: Set<Feature> = emptySet(),
)

/** Devices screen state. */
data class DevicesUiState(
    /** True until the database has answered once. */
    val loading: Boolean = true,
    /** The paired watch, or null when none is paired. */
    val watch: WatchCard? = null,
    /** How much watch data is stored ("Data so far"). */
    val counts: DataCounts? = null,
    /** Whether Bluetooth capture is on. */
    val bleCapture: Boolean = false,
    /** True while pairing or removing is in progress. */
    val busy: Boolean = false,
    /** Every paired device (watches, scales, straps, cuffs) in the user's order: one card each. */
    val devices: List<DeviceCard> = emptyList(),
    /** The first device of the order (the primary one), or null. */
    val primaryId: Long? = null,
)

/** Where the Devices screen reads from (the database and the service's published status). */
interface DevicesSource {
    /** The first active paired watch, or null; updates on every change. */
    val watch: Flow<WatchCard?>

    /** The stored data counts; updates as a sync stores files. */
    val counts: Flow<DataCounts>

    /** Whether Bluetooth capture is on. */
    val bleCapture: Flow<Boolean>
}

/** What the Devices screen can do. */
interface DeviceActions {
    /** Saves a newly paired watch and marks its first connection. Returns the `device.id`. */
    suspend fun savePaired(paired: PairedDevice): Long

    /** Starts the service; false when Android or the start rules refused. */
    fun startService(): Boolean

    /** Asks the service to sync now; false when refused. */
    fun syncNow(): Boolean

    /** Forgets [watch] (stops presence, marks it removed; its data stays) and stops or restarts the service. */
    suspend fun forget(watch: WatchCard)

    /** Turns Bluetooth capture on or off. */
    suspend fun setBleCapture(on: Boolean)
}

/**
 * Devices view model (C06): pairing, the watch card, "Data so far" and the debug tools. [list], when
 * given, adds the other paired devices and their actions (make primary, remove).
 */
class DevicesViewModel(
    private val source: DevicesSource,
    private val actions: DeviceActions,
    private val list: DeviceList? = null,
) : ViewModel() {
    private val busy = MutableStateFlow(false)

    /** Screen state. */
    val state: StateFlow<DevicesUiState> =
        combine(source.watch, source.counts, source.bleCapture, busy, list?.devices ?: flowOf(emptyList())) { watch, counts, capture, b, all ->
            DevicesUiState(
                loading = false, watch = watch, counts = counts, bleCapture = capture, busy = b,
                devices = all,
                primaryId = all.firstOrNull()?.id,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DevicesUiState())

    init {
        // Opening the app connects a paired watch (the service re-reads devices; harmless if running).
        viewModelScope.launch {
            try {
                if (source.watch.first() != null || list?.devices?.first().orEmpty().isNotEmpty()) actions.startService()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "could not check for a paired watch", e)
            }
        }
    }

    /** Marks pairing (or removing) as in progress. */
    fun setBusy(on: Boolean) {
        busy.value = on
    }

    /** Saves [paired]; returns its `device.id`. */
    suspend fun savePaired(paired: PairedDevice): Long = actions.savePaired(paired)

    /** Starts the service after pairing. */
    fun startService(): Boolean = actions.startService()

    /** "Sync now". */
    fun syncNow(): Boolean = actions.syncNow()

    /** "Remove watch" (after the companion association is ended). */
    suspend fun forget(watch: WatchCard) {
        actions.forget(watch)
    }

    /**
     * A tap on [device]'s card: disconnects it when the service keeps it connected, else connects it
     * (for a watch that is not in use, it becomes the watch in use). Returns the new "wanted" state.
     */
    suspend fun toggle(device: DeviceCard): Boolean {
        val on = !device.wanted
        list?.setConnected(device, on)
        return on
    }

    /** The Exclusive switch of watch [id]. */
    fun setExclusive(id: Long, on: Boolean) {
        val l = list ?: return
        viewModelScope.launch { l.setExclusive(id, on) }
    }

    /** The 3-second hold: deletes [device]'s readings, keeps workouts. Returns the rows deleted. */
    suspend fun resetData(device: DeviceCard): Int = list?.resetData(device) ?: 0

    /** Forgets a device (after its companion association is ended); [deleteData] also deletes its data. */
    suspend fun forget(device: DeviceCard, deleteData: Boolean = false) {
        list?.forget(device, deleteData)
    }

    /** The Bluetooth capture switch. */
    fun setBleCapture(on: Boolean) {
        viewModelScope.launch { actions.setBleCapture(on) }
    }

    /** Factory with the graph. */
    companion object {
        private const val TAG = "DevicesViewModel"
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { DevicesViewModel(graph.devicesSource, graph.deviceActions, graph.deviceList) }
        }
    }
}
