/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.device

import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.model.ble.BlePort
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.SyncReason
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.time.Clock
import app.selfwatch.model.watch.FileStore
import app.selfwatch.model.watch.HealthSink
import app.selfwatch.model.watch.SyncReport
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchDriver
import app.selfwatch.model.watch.WatchEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/** What [DeviceManager.start] found. */
public enum class StartResult {
    /** No active paired device: the service should stop. */
    NO_DEVICES,

    /** `always` mode: every device is connected and kept connected. */
    ALWAYS,

    /** `sync_only` mode: the schedule is set; nothing stays connected. */
    SYNC_ONLY,
}

/**
 * Keeps the paired watches connected (or syncs them on demand), stores what they send through
 * [sink] and [files], and routes their events to [handlers].
 *
 * In `always` mode each active device gets a supervised job that runs `driver.run(port, sink, files)`
 * and, whenever it returns, waits per [Backoff] before connecting again (the back-off resets once a
 * connection reached [ConnectionState.Ready]). In `sync_only` mode nothing stays connected:
 * [syncCycle] connects, syncs and disconnects, and [scheduler] runs it every few hours.
 *
 * Every job is a child of [scope]; [stop] cancels them all.
 */
public class DeviceManager(
    private val db: SelfWatchDb,
    private val clock: Clock,
    private val portFactory: PortFactory,
    private val driverFactory: DriverFactory,
    private val sink: HealthSink,
    private val files: FileStore,
    scope: CoroutineScope,
    handlers: EventHandlers = EventHandlers(),
    private val scheduler: SyncScheduler = SyncScheduler.NONE,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val scope = CoroutineScope(scope.coroutineContext + job)
    private val router = EventRouter(handlers)
    private val lock = Mutex()
    private val managed = LinkedHashMap<Long, ManagedDevice>()

    @Volatile
    private var order: List<ManagedDevice> = emptyList()

    private val mutableDevices = MutableStateFlow<List<DeviceStatus>>(emptyList())

    /** Every managed device and its state, highest priority first. */
    public val devices: StateFlow<List<DeviceStatus>> = mutableDevices.asStateFlow()

    /** The mode read by the last [start]. */
    @Volatile
    public var mode: ConnectionMode = ConnectionMode.ALWAYS
        private set

    /**
     * Reads the active devices and the connection mode, then connects (`always`) or sets the
     * schedule (`sync_only`). Safe to call again (for example after pairing or a mode change):
     * new devices are added, removed ones are dropped, running connections are kept.
     */
    public suspend fun start(): StartResult = lock.withLock {
        val active = db.devices.active()
        mode = ConnectionMode.fromKey(db.settings.get(SettingKeys.CONNECTION_MODE))
        val hours = (db.settings.getInt(SettingKeys.CONNECTION_SYNC_ONLY_HOURS) ?: DEFAULT_SYNC_HOURS)
            .coerceIn(1, MAX_SYNC_HOURS)
        val activeIds = active.mapTo(HashSet()) { it.id }
        for (gone in managed.values.filter { it.info.id !in activeIds }) {
            stopDevice(gone)
            managed.remove(gone.info.id)
        }
        for (info in active) {
            if (managed[info.id] == null) managed[info.id] = newManaged(info, db.devices.lastSync(info.id))
        }
        order = active.mapNotNull { managed[it.id] }
        publish()
        if (active.isEmpty()) {
            scheduler.cancel()
            return@withLock StartResult.NO_DEVICES
        }
        when (mode) {
            ConnectionMode.ALWAYS -> {
                scheduler.cancel()
                for (md in order) {
                    if (md.supervisor?.isActive != true) md.supervisor = scope.launch { supervise(md) }
                }
                StartResult.ALWAYS
            }
            ConnectionMode.SYNC_ONLY -> {
                scheduler.schedule(hours)
                for (md in order) {
                    md.supervisor?.cancelAndJoin()
                    md.supervisor = null
                }
                StartResult.SYNC_ONLY
            }
        }
    }

    /** Disconnects every device and cancels every job. [start] may be called again afterwards. */
    public suspend fun stop() {
        lock.withLock {
            for (md in managed.values) stopDevice(md)
            managed.clear()
            order = emptyList()
            publish()
        }
    }

    /**
     * Syncs device [deviceId] now. In `always` mode it asks the connected driver; in `sync_only` mode
     * it runs a connect-sync-disconnect cycle for that device.
     */
    public suspend fun syncNow(deviceId: Long, reason: SyncReason = SyncReason.USER): Outcome<SyncReport> {
        val md = order.firstOrNull { it.info.id == deviceId } ?: return Outcome.Fail(SwError.NotConnected(deviceId))
        return if (mode == ConnectionMode.ALWAYS) syncConnected(md, reason) else cycleOne(md, reason)
    }

    /** Syncs every device now ([syncNow] for each). */
    public suspend fun syncAll(reason: SyncReason = SyncReason.USER): Map<Long, Outcome<SyncReport>> =
        if (mode == ConnectionMode.ALWAYS) {
            order.associate { it.info.id to syncConnected(it, reason) }
        } else {
            syncCycle(reason)
        }

    /**
     * The sync-only cycle: for each device in turn, connect, wait for [ConnectionState.Ready] (at
     * most the connect timeout), sync, then disconnect and release the link.
     */
    public suspend fun syncCycle(reason: SyncReason): Map<Long, Outcome<SyncReport>> {
        val out = LinkedHashMap<Long, Outcome<SyncReport>>()
        for (md in order) out[md.info.id] = cycleOne(md, reason)
        return out
    }

    /** Sends [cmd] to device [deviceId]; fails with [SwError.NotConnected] when it is not managed. */
    public suspend fun send(deviceId: Long, cmd: WatchCommand): Outcome<Unit> {
        val md = order.firstOrNull { it.info.id == deviceId } ?: return Outcome.Fail(SwError.NotConnected(deviceId))
        return md.send(cmd)
    }

    /**
     * Asks for high (or balanced) connection priority on the open link to [address]; used when the
     * watch leaves range (presence) to release the high-speed priority while `auto` reconnect stays armed.
     */
    public suspend fun setHighSpeed(address: String, on: Boolean) {
        val port = order.firstOrNull { it.info.address.equals(address, ignoreCase = true) }?.port ?: return
        try {
            port.setHighSpeed(on)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SwLog.w(TAG, "setHighSpeed failed", e)
        }
    }

    private fun newManaged(info: DeviceInfo, lastSyncSec: Long?): ManagedDevice {
        val md = ManagedDevice(info, driverFactory.create(info), lastSyncSec)
        md.watchers = scope.launch {
            launch { md.driver.events.collect { onEvent(md, it) } }
            launch { md.driver.state.collect { onState(md, it) } }
            launch { md.driver.info.collect { if (it != null) onInfo(md, it) } }
        }
        return md
    }

    private suspend fun stopDevice(md: ManagedDevice) {
        md.supervisor?.cancelAndJoin()
        md.supervisor = null
        md.watchers?.cancelAndJoin()
        md.watchers = null
    }

    private suspend fun supervise(md: ManagedDevice) {
        md.attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            md.retry = null
            md.reachedReady = false
            connectOnce(md, autoReconnect = true)
            if (md.reachedReady) md.attempt = 0
            val waitSec = Backoff.delaySec(md.attempt)
            md.attempt++
            // Keep the driver's own reason (handshake timeout, secure pairing...) for the screens.
            val why = (md.driverState as? ConnectionState.Failed)?.error ?: SwError.NotConnected(md.info.id)
            md.retry = ConnectionState.Failed(why, waitSec)
            publish()
            SwLog.i(TAG, "device ${md.info.id} disconnected; retry in $waitSec s")
            delay(waitSec * 1000L)
        }
    }

    /** Opens a link and runs the driver on it until it returns; the link is always released. */
    private suspend fun connectOnce(md: ManagedDevice, autoReconnect: Boolean) {
        val port = try {
            portFactory.create(md.info.address, autoReconnect)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SwLog.w(TAG, "could not open a link to device ${md.info.id}", e)
            return
        }
        md.port = port
        try {
            md.driver.run(port, sink, files)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SwLog.w(TAG, "driver for device ${md.info.id} stopped with an error", e)
        } finally {
            md.port = null
            withContext(NonCancellable) {
                try {
                    portFactory.release(port)
                } catch (e: Exception) {
                    SwLog.w(TAG, "releasing the link failed", e)
                }
            }
        }
    }

    private suspend fun syncConnected(md: ManagedDevice, reason: SyncReason): Outcome<SyncReport> {
        val result = try {
            md.driver.sync(reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SwLog.e(TAG, "sync of device ${md.info.id} threw", e)
            Outcome.Fail(SwError.Io("sync failed"))
        }
        if (result is Outcome.Ok) recordSync(md)
        return result
    }

    private suspend fun cycleOne(md: ManagedDevice, reason: SyncReason): Outcome<SyncReport> = coroutineScope {
        md.reachedReady = false
        md.retry = null
        val runJob = launch { connectOnce(md, autoReconnect = false) }
        val readyWait = async { md.driver.state.first { it is ConnectionState.Ready } }
        val ready = withTimeoutOrNull(connectTimeoutMs) {
            select {
                readyWait.onAwait { true }
                runJob.onJoin { false }
            }
        }
        readyWait.cancel()
        val result = when (ready) {
            true -> syncConnected(md, reason)
            false -> Outcome.Fail(SwError.NotConnected(md.info.id))
            null -> Outcome.Fail(SwError.Timeout("connect"))
        }
        runJob.cancelAndJoin()
        publish()
        result
    }

    private suspend fun recordSync(md: ManagedDevice) {
        val now = clock.nowSec()
        md.lastSyncSec = now
        publish()
        try {
            db.devices.setLastSync(md.info.id, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SwLog.e(TAG, "could not store the sync time", e)
        }
    }

    private suspend fun onEvent(md: ManagedDevice, event: WatchEvent) {
        if (event is WatchEvent.Battery) {
            md.info = md.info.copy(batteryPct = event.pct.coerceIn(0, 100))
            publish()
            return
        }
        router.route(md, event)
    }

    private suspend fun onState(md: ManagedDevice, state: ConnectionState) {
        val previous = md.driverState
        md.driverState = state
        if (state is ConnectionState.Ready || state is ConnectionState.Syncing) {
            md.reachedReady = true
            md.retry = null
        }
        publish()
        // A sync the driver started by itself (on connect, or asked by the watch) just finished.
        if (previous is ConnectionState.Syncing && state is ConnectionState.Ready) recordSync(md)
    }

    private suspend fun onInfo(md: ManagedDevice, reported: DeviceInfo) {
        val known = md.info
        val merged = reported.copy(
            id = known.id,
            address = known.address,
            batteryPct = reported.batteryPct ?: known.batteryPct,
        )
        md.info = merged
        publish()
        val changed = merged.name != known.name || merged.model != known.model || merged.productId != known.productId ||
            merged.firmware != known.firmware || merged.serial != known.serial || merged.features != known.features
        if (!changed) return
        try {
            db.devices.upsert(merged, clock.nowSec())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SwLog.e(TAG, "could not store device info", e)
        }
    }

    private fun publish() {
        mutableDevices.value = order.map { DeviceStatus(it.info, it.effectiveState(), it.lastSyncSec) }
    }

    private inner class ManagedDevice(
        @Volatile var info: DeviceInfo,
        val driver: WatchDriver,
        @Volatile var lastSyncSec: Long?,
    ) : WatchLink {
        @Volatile var watchers: Job? = null

        @Volatile var supervisor: Job? = null

        @Volatile var port: BlePort? = null

        @Volatile var driverState: ConnectionState = ConnectionState.Disconnected

        /** Set while waiting to reconnect. */
        @Volatile var retry: ConnectionState.Failed? = null

        @Volatile var reachedReady: Boolean = false

        @Volatile var attempt: Int = 0

        override val deviceId: Long get() = info.id

        override suspend fun send(cmd: WatchCommand): Outcome<Unit> = try {
            driver.send(cmd)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SwLog.e(TAG, "send to device ${info.id} threw", e)
            Outcome.Fail(SwError.Io("send failed"))
        }

        fun effectiveState(): ConnectionState {
            val r = retry
            val s = driverState
            return if (r != null && (s is ConnectionState.Disconnected || s is ConnectionState.Failed)) r else s
        }
    }

    /** Defaults. */
    public companion object {
        /** How long a sync-only cycle waits for the watch to be ready. */
        public const val CONNECT_TIMEOUT_MS: Long = 120_000

        /** Hours between scheduled syncs when the setting is missing. */
        public const val DEFAULT_SYNC_HOURS: Int = 4

        /** Upper bound for `connection.sync_only_hours`. */
        public const val MAX_SYNC_HOURS: Int = 24

        private const val TAG = "DeviceManager"
    }
}
