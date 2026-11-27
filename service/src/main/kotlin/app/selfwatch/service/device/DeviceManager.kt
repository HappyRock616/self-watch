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

    /** One link opening at a time, across all devices (see [connectOnce]). */
    private val connectGate = Mutex()

    /** The one watch that connects (null: no watch); every sensor connects alongside it. */
    @Volatile
    private var activeWatchId: Long? = null

    @Volatile
    private var order: List<ManagedDevice> = emptyList()

    private val mutableDevices = MutableStateFlow<List<DeviceStatus>>(emptyList())

    /** Every managed device and its state, highest priority first. */
    public val devices: StateFlow<List<DeviceStatus>> = mutableDevices.asStateFlow()

    private val mutableLive = kotlinx.coroutines.flow.MutableSharedFlow<Pair<Long, WatchEvent>>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** Live readings ([WatchEvent.LiveHeartRate], [WatchEvent.LiveSteps]) with the id of the watch that sent them. */
    public val live: kotlinx.coroutines.flow.SharedFlow<Pair<Long, WatchEvent>> = mutableLive

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
            val md = managed[info.id] ?: newManaged(info, db.devices.lastSync(info.id)).also { managed[info.id] = it }
            // Devices page: a card tapped off stays off; a watch's Exclusive switch.
            md.userOff = db.settings.getBool(SettingKeys.deviceOff(info.id))
            md.exclusive = db.settings.getBool(SettingKeys.deviceExclusive(info.id))
        }
        order = active.mapNotNull { managed[it.id] }
        activeWatchId = pickActiveWatch(order, db.settings.get(ACTIVE_WATCH_KEY)?.toLongOrNull())
        publish()
        if (active.isEmpty()) {
            scheduler.cancel()
            return@withLock StartResult.NO_DEVICES
        }
        when (mode) {
            ConnectionMode.ALWAYS -> {
                scheduler.cancel()
                for (md in order) {
                    if (!connectable(md)) {
                        // Only one watch at a time (the others stay paired but off), and none the user turned off.
                        md.supervisor?.cancelAndJoin()
                        md.supervisor = null
                        md.retry = null
                        continue
                    }
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
        if (mode == ConnectionMode.ALWAYS && md.contest.contested && reason == SyncReason.USER) {
            // The user asked for this phone: forget the contest and reconnect now.
            SwLog.i(TAG, "device ${md.info.id}: the user chose this phone; reconnecting")
            md.contest.reset()
            md.attempt = 0
            md.wake.trySend(Unit)
            return Outcome.Fail(SwError.NotConnected(deviceId))
        }
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
        for (md in order) if (connectable(md)) out[md.info.id] = cycleOne(md, reason)
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
            md.readyAtSec = null
            val startedSec = clock.nowSec()
            connectOnce(md, autoReconnect = true)
            val endSec = clock.nowSec()
            // Only a link that stayed up resets the delays; a link that drops within minutes keeps backing off.
            if (md.reachedReady && (endSec - startedSec) * 1000 >= Backoff.STABLE_MS) {
                md.attempt = 0
                md.contest.reset()
            }
            // Exclusive (Devices page): never step back for another phone or app; keep the normal back-off,
            // which still ends at one try every 5 minutes, so the watch radio is not hammered.
            if (md.exclusive) md.contest.reset()
            val contested = md.contest.onLinkEnded(md.readyAtSec, endSec) && !md.exclusive
            val waitSec: Int
            val why: SwError
            if (contested) {
                // Another phone or app keeps taking the watch: stop fighting over it (each try costs the
                // watch battery). Check once an hour; "Sync now" takes it back at once.
                waitSec = ContestDetector.CHECK_EVERY_SEC
                val otherApp = try { portFactory.heldElsewhere(md.info.address) } catch (e: Exception) { false }
                why = SwError.Io(if (otherApp) ContestDetector.REASON_OTHER_APP else ContestDetector.REASON_OTHER_PHONE)
                SwLog.w(TAG, "device ${md.info.id} keeps dropping soon after connecting; ${if (otherApp) "another app on this phone" else "another phone"} seems to have it")
            } else {
                waitSec = Backoff.delaySec(md.attempt)
                md.attempt++
                // Keep the driver's own reason (handshake timeout, secure pairing...) for the screens.
                why = (md.driverState as? ConnectionState.Failed)?.error ?: SwError.NotConnected(md.info.id)
            }
            md.retry = ConnectionState.Failed(why, waitSec)
            publish()
            SwLog.i(TAG, "device ${md.info.id} disconnected; retry in $waitSec s")
            // Drop a wake-up left over from before this connection, so only a "Sync now" made during the
            // wait cuts it short (a stale one would skip the back-off and wake the watch radio again).
            md.wake.tryReceive()
            withTimeoutOrNull(waitSec * 1000L) { md.wake.receive() }
        }
    }

    /** True for sensors, and for the one active watch ([activeWatchId]). */
    private fun connectable(md: ManagedDevice): Boolean =
        !md.userOff && (!DeviceKinds.isWatch(md.info.kind) || md.info.id == activeWatchId)

    /**
     * Opens a link and runs the driver on it until it returns; the link is always released. Links open
     * one at a time across all devices ([connectGate]): Android's Bluetooth stack handles parallel GATT
     * connects badly (status 133), so a device waits until the previous one is past connecting.
     */
    private suspend fun connectOnce(md: ManagedDevice, autoReconnect: Boolean) {
        connectGate.lock()
        val opened = kotlinx.coroutines.CompletableDeferred<Unit>()
        md.linkOpened = opened
        scope.launch {
            withTimeoutOrNull(CONNECT_GATE_MS) { opened.await() }
            connectGate.unlock()
        }
        val port = try {
            portFactory.create(md.info.address, autoReconnect)
        } catch (e: CancellationException) {
            opened.complete(Unit)
            throw e
        } catch (e: Exception) {
            SwLog.w(TAG, "could not open a link to device ${md.info.id}", e)
            opened.complete(Unit)
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
            opened.complete(Unit)
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
        if (result is Outcome.Ok) {
            recordSync(md)
            if (result.value.filesNew == 0) reconnectIfStale(md)
        }
        return result
    }

    /**
     * Guard for a link that says "synced" but delivers nothing (Pixel 8 field test, 2026-11-26: manual
     * syncs finished with no new files for hours and only a phone restart brought the data). When a
     * watch's sync brings no new file, no file at all has been stored for [STALE_DATA_SEC] and it is
     * daytime (the watch writes its daily data at least every few hours while you are awake), the link
     * is dropped and opened fresh, which is what the restart did. At most once per [STALE_RECONNECT_GAP_SEC],
     * so a quiet watch is never hammered.
     */
    private suspend fun reconnectIfStale(md: ManagedDevice) {
        if (!DeviceKinds.isWatch(md.info.kind)) return
        val now = clock.nowSec()
        val hour = java.time.Instant.ofEpochSecond(now).atZone(clock.zone()).hour
        if (hour !in STALE_DAY_HOURS) return
        if (now - md.lastStaleReconnectSec < STALE_RECONNECT_GAP_SEC) return
        val recent = try {
            db.syncFiles.doneBetween(md.info.id, now - STALE_DATA_SEC, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SwLog.w(TAG, "stale check failed", e)
            return
        }
        if (recent.isNotEmpty()) return
        md.lastStaleReconnectSec = now
        SwLog.w(TAG, "device ${md.info.id}: synced but no new watch file for ${STALE_DATA_SEC / 3600} h; reconnecting")
        lock.withLock {
            if (md.supervisor?.isActive != true) return
            md.supervisor?.cancelAndJoin()
            md.supervisor = scope.launch { supervise(md) }
        }
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
        if (event is WatchEvent.LiveHeartRate || event is WatchEvent.LiveSteps) {
            mutableLive.tryEmit(md.info.id to event)
            return
        }
        if (event is WatchEvent.Battery) {
            md.info = md.info.copy(batteryPct = event.pct.coerceIn(0, 100))
            publish()
            return
        }
        if (event is WatchEvent.GarminSignInRequested) {
            noteSignInRequest(md.info.id, event.answered)
            return
        }
        router.route(md, event)
    }

    /**
     * Keeps [SettingKeys.deviceSignInAsked] for Watch settings: set when the watch asked to sign in to
     * Garmin and nobody answered (weather and GPS help stop until it gets one), cleared once answered.
     */
    private suspend fun noteSignInRequest(deviceId: Long, answered: Boolean) {
        try {
            val key = SettingKeys.deviceSignInAsked(deviceId)
            if (answered) {
                if (db.settings.get(key) != null) db.settings.set(key, null)
            } else {
                db.settings.set(key, clock.nowSec().toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SwLog.e(TAG, "could not store the sign-in request", e)
        }
    }

    private suspend fun onState(md: ManagedDevice, state: ConnectionState) {
        val previous = md.driverState
        md.driverState = state
        if (state !is ConnectionState.Connecting && state !is ConnectionState.Disconnected) md.linkOpened?.complete(Unit)
        if (state is ConnectionState.Ready || state is ConnectionState.Syncing) {
            if (md.readyAtSec == null) md.readyAtSec = clock.nowSec()
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

    private fun pickActiveWatch(list: List<ManagedDevice>, chosen: Long?): Long? =
        pickActiveWatchId(list.filter { DeviceKinds.isWatch(it.info.kind) }.map { it.info.id }, chosen)

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

        /** When [reconnectIfStale] last dropped and reopened the link (0 = never). */
        @Volatile var lastStaleReconnectSec: Long = 0

        @Volatile var port: BlePort? = null

        @Volatile var driverState: ConnectionState = ConnectionState.Disconnected

        /** Set while waiting to reconnect. */
        @Volatile var retry: ConnectionState.Failed? = null

        @Volatile var reachedReady: Boolean = false

        /** When the current link reached READY (null until it does). */
        @Volatile var readyAtSec: Long? = null

        /** Completed once the current link is past connecting (frees [connectGate]). */
        @Volatile var linkOpened: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        /** Spots another phone or app taking the watch away. */
        val contest = ContestDetector()

        /** Ends the wait before the next connection early ("Sync now" while contested). */
        val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

        @Volatile var attempt: Int = 0

        /** The user tapped this device off on the Devices page: it stays disconnected. */
        @Volatile var userOff: Boolean = false

        /** The watch's Exclusive switch: no stepping back when another phone or app takes it. */
        @Volatile var exclusive: Boolean = false

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
        /** No watch file stored for this long (with a sync that brought nothing) means the link may be stuck. */
        internal const val STALE_DATA_SEC: Long = 4 * 3600

        /** At most one stale-link reconnect per this many seconds. */
        internal const val STALE_RECONNECT_GAP_SEC: Long = 2 * 3600

        /** Local hours in which a missing file counts as stale (the watch writes little while you sleep). */
        internal val STALE_DAY_HOURS: IntRange = 9..22

        /** How long a sync-only cycle waits for the watch to be ready. */
        public const val CONNECT_TIMEOUT_MS: Long = 120_000

        /** Hours between scheduled syncs when the setting is missing. */
        public const val DEFAULT_SYNC_HOURS: Int = 4

        /** Upper bound for `connection.sync_only_hours`. */
        public const val MAX_SYNC_HOURS: Int = 24

        /** Setting: id of the watch that connects. Missing or not a paired watch → the first watch in order. */
        public const val ACTIVE_WATCH_KEY: String = "devices.active_watch"

        /** Longest time one device may hold the connect gate (ms). */
        public const val CONNECT_GATE_MS: Long = 30_000

        private const val TAG = "DeviceManager"

        /** The watch that connects: [chosen] if it is one of the watches in [order], else the first watch. */
        internal fun pickActiveWatchId(watchIdsInOrder: List<Long>, chosen: Long?): Long? =
            chosen?.takeIf { it in watchIdsInOrder } ?: watchIdsInOrder.firstOrNull()
    }
}
