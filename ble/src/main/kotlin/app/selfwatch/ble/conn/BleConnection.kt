/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ble.conn

import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import app.selfwatch.ble.BACKOFF_SEC
import app.selfwatch.ble.BOND_TIMEOUT_MS
import app.selfwatch.ble.CCCD_UUID
import app.selfwatch.ble.CONNECT_TIMEOUT_MS
import app.selfwatch.ble.DEFAULT_MAX_WRITE
import app.selfwatch.ble.DEFAULT_MTU
import app.selfwatch.ble.DISCOVERY_DELAY_MS
import app.selfwatch.ble.DISCOVERY_TIMEOUT_MS
import app.selfwatch.ble.MAX_ATTRIBUTE_LENGTH
import app.selfwatch.ble.MTU_REQUEST
import app.selfwatch.ble.OP_TIMEOUT_MS
import app.selfwatch.ble.gatt.AndroidGattConnector
import app.selfwatch.ble.gatt.GattApi
import app.selfwatch.ble.gatt.GattCodes
import app.selfwatch.ble.gatt.GattConnector
import app.selfwatch.ble.gatt.GattEvent
import app.selfwatch.ble.gatt.GattListener
import app.selfwatch.ble.gatt.GattOp
import app.selfwatch.ble.gatt.GattQueue
import app.selfwatch.ble.log.BleLog
import app.selfwatch.ble.log.BleLogFormat
import app.selfwatch.model.ble.BlePort
import app.selfwatch.model.ble.BytePipe
import app.selfwatch.model.ble.HasCloseReason
import app.selfwatch.model.ble.PortState
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Timings and policies of a [BleConnection]; tests shorten or inspect them. */
internal data class BleTiming(
    val opTimeoutMs: Long = OP_TIMEOUT_MS,
    val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    val discoveryDelayMs: Long = DISCOVERY_DELAY_MS,
    val discoveryTimeoutMs: Long = DISCOVERY_TIMEOUT_MS,
    val bondTimeoutMs: Long = BOND_TIMEOUT_MS,
    val backoffSec: IntArray = BACKOFF_SEC,
    /** Insufficient-authentication errors on a bonded link before the bond is declared lost. */
    val authFailuresForRepair: Int = 3,
    /** Reconnects after a drop use `autoConnect` (low power, waits for the watch to come back). */
    val reconnectAuto: Boolean = true,
)

/**
 * One watch's Bluetooth LE link, implementing [BlePort] for `garmin`.
 *
 * - All GATT work runs through a single [GattQueue] (one operation in flight) on a single-threaded
 *   dispatcher; every GATT callback hops onto that dispatcher through one ordered channel.
 * - [connect] brings the link up (connect, 600 ms pause, discovery with one retry, MTU 517). After
 *   that the connection keeps itself up: on any drop it closes the `BluetoothGatt` and reconnects
 *   after 1, 2, 5, 10, 30, 60 s and then every 5 minutes, resetting after a success. When Bluetooth
 *   is turned off it waits for it to come back on and reconnects at once.
 * - Pipes belong to one link session: when the link drops, their [BytePipe.incoming] flows end and
 *   writes fail. Open them again after [state] is [PortState.CONNECTED] again.
 * - [PortState.CLOSED] is final; [closeReason] says why ([CloseReason.REPAIR_NEEDED] when the bond
 *   is lost, [CloseReason.PERMISSION_MISSING] when `BLUETOOTH_CONNECT` was revoked).
 *
 * Owned by the service, which must call [close] when done.
 */
public class BleConnection internal constructor(
    override val address: String,
    private val log: BleLog?,
    private val connector: GattConnector,
    private val env: LinkEnvironment,
    private val dispatcher: CoroutineDispatcher,
    private val timing: BleTiming,
) : BlePort, HasCloseReason, CharacteristicReader {

    /**
     * A connection to the watch at [address] (`AA:BB:CC:DD:EE:FF`). [log], when given and enabled,
     * records every write and notification for replay tests. [reconnectDelaysSec] replaces the
     * built-in reconnect delays (an owner that has its own back-off passes its steps, so this link never
     * retries sooner than the owner would).
     */
    public constructor(context: Context, address: String, log: BleLog?, reconnectDelaysSec: IntArray = BACKOFF_SEC) : this(
        address = address,
        log = log,
        connector = AndroidGattConnector(context),
        env = AndroidLinkEnvironment(context, address),
        dispatcher = Dispatchers.IO.limitedParallelism(1),
        timing = BleTiming(backoffSec = reconnectDelaysSec),
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow(PortState.DISCONNECTED)
    override val state: StateFlow<PortState> = _state.asStateFlow()

    private val _closeReason = MutableStateFlow<CloseReason?>(null)

    /** Why the connection is [PortState.CLOSED]; null while it is not. */
    override val closeReason: StateFlow<CloseReason?> = _closeReason.asStateFlow()

    private val _mtu = MutableStateFlow(DEFAULT_MTU)

    /** The negotiated ATT MTU of the current link ([DEFAULT_MTU] until negotiated). */
    public val mtu: StateFlow<Int> = _mtu.asStateFlow()

    /** Callbacks tagged with the session generation they belong to, in arrival order. */
    private val events = Channel<Pair<Int, GattEvent>>(Channel.UNLIMITED)

    /** Wakes the link loop from a back-off pause (a new [connect] call). */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // Everything below is touched only on [dispatcher].
    private var session: Session? = null
    private var generation = 0
    private var linkJob: Job? = null
    private var lastServices: Set<UUID> = emptySet()
    private val backoff = Backoff(timing.backoffSec)
    private var authFailures = 0
    private val waiters = ArrayList<CompletableDeferred<Outcome<Unit>>>()

    init {
        scope.launch {
            for ((gen, event) in events) {
                try {
                    handleEvent(gen, event)
                } catch (e: RuntimeException) {
                    SwLog.e(TAG, "event handling failed", e)
                }
            }
        }
        scope.launch {
            env.adapterOn.collect { on ->
                if (!on) session?.let { endSession(it, End.ADAPTER_OFF) }
            }
        }
        scope.launch {
            env.bondLost.collect { closeFinal(CloseReason.REPAIR_NEEDED) }
        }
    }

    private enum class End { DROPPED, ADAPTER_OFF, TIMEOUT, FAILED, CLOSED }

    /** One `BluetoothGatt` from connect to close. */
    private inner class Session(val gen: Int, val gatt: GattApi) {
        val queue = GattQueue(scope) { name -> onFatalTimeout(this, name) }
        val linkUp = CompletableDeferred<Boolean>()
        val ended = CompletableDeferred<End>()
        val pipes = HashMap<Pair<UUID, UUID>, Pipe>()
        var services: Map<UUID, Set<UUID>> = emptyMap()
        var maxWrite: Int = DEFAULT_MAX_WRITE

        /** Pipes opened successfully on this session; the next pipe's index in the capture. */
        var pipesOpened: Int = 0

        init {
            queue.start()
        }
    }

    private fun listenerFor(gen: Int): GattListener = object : GattListener {
        private fun post(e: GattEvent) {
            events.trySend(gen to e)
        }

        override fun onConnectionStateChange(status: Int, newState: Int) = post(GattEvent.ConnectionState(status, newState))
        override fun onServicesDiscovered(status: Int) = post(GattEvent.ServicesDiscovered(status))
        override fun onMtuChanged(mtu: Int, status: Int) = post(GattEvent.MtuChanged(mtu, status))
        override fun onDescriptorWrite(service: UUID, characteristic: UUID, descriptor: UUID, status: Int) =
            post(GattEvent.DescriptorWritten(service, characteristic, descriptor, status))
        override fun onCharacteristicWrite(service: UUID, characteristic: UUID, status: Int) =
            post(GattEvent.CharacteristicWritten(service, characteristic, status))
        override fun onCharacteristicRead(service: UUID, characteristic: UUID, value: ByteArray, status: Int) =
            post(GattEvent.CharacteristicRead(service, characteristic, value, status))
        override fun onCharacteristicChanged(service: UUID, characteristic: UUID, value: ByteArray) =
            post(GattEvent.CharacteristicChanged(service, characteristic, value))
    }

    private fun handleEvent(gen: Int, event: GattEvent) {
        val s = session
        if (s == null || s.gen != gen || s.ended.isCompleted) return // a closed GATT's late callback
        when (event) {
            is GattEvent.ConnectionState -> {
                if (event.newState == GattCodes.STATE_CONNECTED && event.status == GattCodes.GATT_SUCCESS) {
                    s.linkUp.complete(true)
                } else {
                    SwLog.i(TAG, "link down: status ${event.status} state ${event.newState}")
                    endSession(s, End.DROPPED)
                }
            }
            is GattEvent.CharacteristicChanged -> {
                val pipe = s.pipes[event.service to event.characteristic]
                if (pipe == null) {
                    SwLog.d(TAG) { "notification on unopened ${event.characteristic}" }
                    return
                }
                log?.record(BleLogFormat.DIR_IN, pipe.index, event.value)
                pipe.inbox.trySend(event.value)
            }
            else -> if (!s.queue.onEvent(event)) SwLog.d(TAG) { "stray callback $event" }
        }
    }

    private fun setState(newState: PortState) {
        if (_state.value == newState || _state.value == PortState.CLOSED) return
        _state.value = newState
    }

    // ---- Link lifecycle -------------------------------------------------------------------------

    /**
     * Brings the link up and returns once it is [PortState.CONNECTED] or the first attempt failed.
     * Either way the connection keeps reconnecting in the background until [close]. Use
     * [auto] = true for background reconnects (Android's low-power `autoConnect`, no timeout) and
     * false when the user is waiting (direct connect, 30 s timeout). Calling it again while the
     * connection is waiting out a back-off pause retries at once. With [auto] = true it may suspend
     * until the watch comes into range; launch it if the caller should not wait.
     */
    public suspend fun connect(auto: Boolean): Outcome<Unit> = withContext(dispatcher) {
        when (_state.value) {
            PortState.CLOSED -> return@withContext Outcome.Fail(SwError.Io("connection closed"))
            PortState.CONNECTED -> return@withContext Outcome.Ok(Unit)
            else -> Unit
        }
        val waiter = CompletableDeferred<Outcome<Unit>>()
        waiters += waiter
        if (linkJob?.isActive != true) {
            env.start()
            linkJob = scope.launch { linkLoop(auto) }
        } else {
            wake.trySend(Unit)
        }
        if (!env.adapterOn.value) {
            waiters -= waiter
            return@withContext Outcome.Fail(SwError.Io("Bluetooth is off"))
        }
        waiter.await()
    }

    private fun completeWaiters(outcome: Outcome<Unit>) {
        val copy = waiters.toList()
        waiters.clear()
        copy.forEach { it.complete(outcome) }
    }

    private suspend fun linkLoop(firstAuto: Boolean) {
        var auto = firstAuto
        while (true) {
            if (!env.adapterOn.value) {
                setState(PortState.DISCONNECTED)
                env.adapterOn.first { it }
                backoff.reset()
            }
            val outcome = attempt(auto)
            completeWaiters(outcome)
            val s = session
            val end: End = if (outcome is Outcome.Ok && s != null) {
                backoff.reset()
                s.ended.await()
            } else {
                s?.let { endSession(it, End.FAILED) }
                End.FAILED
            }
            if (outcome is Outcome.Fail && outcome.error is SwError.PermissionMissing) {
                closeFinal(CloseReason.PERMISSION_MISSING)
                return
            }
            if (_state.value == PortState.CLOSED) return
            setState(PortState.DISCONNECTED)
            auto = timing.reconnectAuto || firstAuto
            if (end == End.ADAPTER_OFF || !env.adapterOn.value) continue // the loop top waits for STATE_ON
            val pauseMs = backoff.nextDelayMs()
            SwLog.i(TAG, "reconnecting in ${pauseMs / 1000} s")
            wake.tryReceive() // a connect() made during the attempt already got its result
            withTimeoutOrNull(pauseMs) { wake.receive() }
        }
    }

    /** One connection attempt. On success [session] is live and [state] is CONNECTED. */
    private suspend fun attempt(auto: Boolean): Outcome<Unit> {
        setState(PortState.CONNECTING)
        val gen = ++generation
        val gatt = try {
            connector.connect(address, auto, listenerFor(gen))
        } catch (e: SecurityException) {
            SwLog.w(TAG, "connect: permission missing", e)
            return Outcome.Fail(SwError.PermissionMissing(BLUETOOTH_CONNECT))
        } ?: return Outcome.Fail(SwError.Io("connectGatt refused"))
        val s = Session(gen, gatt)
        session = s

        val up = if (auto) s.linkUp.await() else withTimeoutOrNull(timing.connectTimeoutMs) { s.linkUp.await() }
        if (up != true) {
            if (up == null) SwLog.i(TAG, "connect timed out")
            return Outcome.Fail(if (up == null) SwError.Timeout("connect") else SwError.Io("connect failed"))
        }
        delay(timing.discoveryDelayMs)

        var discovered: Outcome<Unit> = Outcome.Fail(SwError.Timeout("discovery"))
        for (round in 0 until 2) {
            discovered = s.queue.submit(discoverOp(s, lastTry = round == 1))
            if (discovered is Outcome.Ok || s.ended.isCompleted) break
            SwLog.i(TAG, "discovery failed, retrying")
        }
        if (discovered is Outcome.Fail) return discovered
        s.services = guard { s.gatt.services() } ?: return Outcome.Fail(SwError.PermissionMissing(BLUETOOTH_CONNECT))
        lastServices = s.services.keys

        val mtu = s.queue.submit(mtuOp(s))
        val negotiated = (mtu as? Outcome.Ok)?.value ?: DEFAULT_MTU
        if (mtu is Outcome.Fail) SwLog.i(TAG, "MTU not negotiated (${mtu.error}); using $DEFAULT_MTU")
        if (s.ended.isCompleted) return Outcome.Fail(SwError.Io("link lost during setup"))
        _mtu.value = negotiated
        s.maxWrite = (negotiated - 3).coerceIn(DEFAULT_MAX_WRITE, MAX_ATTRIBUTE_LENGTH)
        SwLog.i(TAG, "connected, mtu $negotiated, ${s.services.size} services")
        setState(PortState.CONNECTED)
        return Outcome.Ok(Unit)
    }

    private fun endSession(s: Session, why: End) {
        if (s.ended.isCompleted) return
        s.ended.complete(why)
        s.linkUp.complete(false)
        s.queue.close(SwError.Io("link lost"))
        for (pipe in s.pipes.values) pipe.drop()
        s.pipes.clear()
        try {
            s.gatt.close() // always close; never reuse a BluetoothGatt
        } catch (e: SecurityException) {
            SwLog.w(TAG, "close: permission missing", e)
        }
        if (session === s) session = null
        _mtu.value = DEFAULT_MTU
        setState(PortState.DISCONNECTED)
    }

    private fun onFatalTimeout(s: Session, opName: String) {
        SwLog.w(TAG, "$opName timed out: dropping the link")
        endSession(s, End.TIMEOUT)
    }

    /** Moves to [PortState.CLOSED] for good. Must run on [dispatcher]. */
    private fun closeFinal(reason: CloseReason) {
        if (_state.value == PortState.CLOSED) return
        SwLog.i(TAG, "closed: $reason")
        _closeReason.value = reason
        session?.let { endSession(it, End.CLOSED) }
        setState(PortState.CLOSED)
        linkJob?.cancel()
        env.stop()
        completeWaiters(
            Outcome.Fail(
                if (reason == CloseReason.PERMISSION_MISSING) SwError.PermissionMissing(BLUETOOTH_CONNECT)
                else SwError.Io("connection closed: $reason"),
            ),
        )
    }

    /** Closes the link for good and releases everything. Safe to call more than once. */
    public suspend fun close() {
        withContext(dispatcher + NonCancellable) { closeFinal(CloseReason.CLOSED_BY_APP) }
        scope.cancel()
    }

    // ---- Operations -----------------------------------------------------------------------------

    private inline fun <T> guard(block: () -> T): T? = try {
        block()
    } catch (e: SecurityException) {
        SwLog.w(TAG, "permission missing", e)
        null
    }

    private fun statusResult(what: String, status: Int): Outcome<Unit> = when {
        status == GattCodes.GATT_SUCCESS -> Outcome.Ok(Unit)
        GattCodes.isAuthFailure(status) -> Outcome.Fail(SwError.Protocol("$AUTH_PREFIX$status"))
        else -> Outcome.Fail(SwError.Protocol("$what failed (GATT status $status)"))
    }

    private fun discoverOp(s: Session, lastTry: Boolean) = GattOp(
        name = "discoverServices",
        timeoutMs = timing.discoveryTimeoutMs,
        fatalOnTimeout = lastTry,
        start = { if (s.gatt.discoverServices()) GattApi.STATUS_OK else GattApi.STATUS_NOT_STARTED },
        complete = { e -> (e as? GattEvent.ServicesDiscovered)?.let { statusResult("discovery", it.status) } },
    )

    private fun mtuOp(s: Session) = GattOp(
        name = "requestMtu",
        timeoutMs = timing.opTimeoutMs,
        fatalOnTimeout = false, // some stacks never answer; carry on with 23
        start = { if (s.gatt.requestMtu(MTU_REQUEST)) GattApi.STATUS_OK else GattApi.STATUS_NOT_STARTED },
        complete = { e ->
            (e as? GattEvent.MtuChanged)?.let {
                if (it.status == GattCodes.GATT_SUCCESS && it.mtu >= DEFAULT_MTU) Outcome.Ok(it.mtu)
                else Outcome.Fail(SwError.Protocol("MTU status ${it.status}"))
            }
        },
    )

    private fun notifyFlagOp(s: Session, service: UUID, rx: UUID, enable: Boolean) = GattOp(
        name = "setNotification",
        timeoutMs = timing.opTimeoutMs,
        start = { if (s.gatt.setNotification(service, rx, enable)) GattApi.STATUS_OK else GattApi.STATUS_NOT_STARTED },
        immediate = { },
    )

    private fun cccdOp(s: Session, service: UUID, rx: UUID, value: ByteArray) = GattOp(
        name = "writeDescriptor",
        timeoutMs = timing.opTimeoutMs,
        start = { s.gatt.writeDescriptor(service, rx, CCCD_UUID, value) },
        complete = { e ->
            (e as? GattEvent.DescriptorWritten)
                ?.takeIf { it.service == service && it.characteristic == rx && it.descriptor == CCCD_UUID }
                ?.let { statusResult("descriptor write", it.status) }
        },
    )

    private fun writeOp(s: Session, pipe: Pipe, bytes: ByteArray) = GattOp(
        name = "writeCharacteristic",
        timeoutMs = timing.opTimeoutMs,
        start = {
            val status = s.gatt.writeCharacteristic(pipe.service, pipe.tx, bytes, pipe.noResponse)
            if (status == GattApi.STATUS_OK) log?.record(BleLogFormat.DIR_OUT, pipe.index, bytes)
            status
        },
        complete = { e ->
            (e as? GattEvent.CharacteristicWritten)
                ?.takeIf { it.service == pipe.service && it.characteristic == pipe.tx }
                ?.let { statusResult("write", it.status) }
        },
    )

    /**
     * Runs an operation; on an insufficient-authentication error bonds first if the watch is not
     * bonded (some watches only ask for a bond when needed) and retries once. On a bonded link,
     * repeated authentication errors mean the watch lost its keys: the connection closes with
     * [CloseReason.REPAIR_NEEDED].
     */
    private suspend fun <T> withAuth(s: Session, op: () -> GattOp<T>): Outcome<T> {
        val first = s.queue.submit(op())
        if (!isAuthFailure(first)) {
            if (first is Outcome.Ok) authFailures = 0
            return first
        }
        val bonded = guard { env.isBonded() } ?: return Outcome.Fail(SwError.PermissionMissing(BLUETOOTH_CONNECT))
        if (!bonded) {
            SwLog.i(TAG, "watch asked for a bond")
            val bond = withTimeoutOrNull(timing.bondTimeoutMs) { env.bond() } ?: Outcome.Fail(SwError.Timeout("bond"))
            if (bond is Outcome.Fail) return bond
            return s.queue.submit(op())
        }
        authFailures++
        SwLog.w(TAG, "authentication failed on a bonded link ($authFailures)")
        if (authFailures >= timing.authFailuresForRepair) closeFinal(CloseReason.REPAIR_NEEDED)
        return first
    }

    private fun isAuthFailure(o: Outcome<*>): Boolean =
        o is Outcome.Fail && (o.error as? SwError.Protocol)?.detail?.startsWith(AUTH_PREFIX) == true

    // ---- BlePort --------------------------------------------------------------------------------

    override suspend fun serviceUuids(): Set<UUID> = withContext(dispatcher) {
        session?.services?.keys?.toSet() ?: lastServices
    }

    override suspend fun openPipe(service: UUID, rx: UUID, tx: UUID): Outcome<BytePipe> =
        openPipe(service, rx, tx, writeNoResponse = false)

    /**
     * Opens a pipe like [BlePort.openPipe]; with [writeNoResponse] its writes use
     * `WRITE_TYPE_NO_RESPONSE` instead of writes with response.
     */
    public suspend fun openPipe(service: UUID, rx: UUID, tx: UUID, writeNoResponse: Boolean): Outcome<BytePipe> =
        withContext(dispatcher) {
            val s = session
            if (s == null || _state.value != PortState.CONNECTED) return@withContext Outcome.Fail(SwError.Io("not connected"))
            val chars = s.services[service] ?: return@withContext Outcome.Fail(SwError.Protocol("no service $service"))
            if (rx !in chars || tx !in chars) return@withContext Outcome.Fail(SwError.Protocol("missing characteristic in $service"))
            if (s.pipes.containsKey(service to rx)) return@withContext Outcome.Fail(SwError.Protocol("pipe on $rx already open"))
            val props = guard { s.gatt.properties(service, rx) } ?: return@withContext Outcome.Fail(SwError.PermissionMissing(BLUETOOTH_CONNECT))
            val enable = cccdValue(props) ?: return@withContext Outcome.Fail(SwError.Protocol("$rx cannot notify"))

            // Use the write type the send characteristic supports: some (the Garmin watch-app service on newer
            // watches) only accept writes without response and refuse writes with response (GATT status 3).
            val txProps = guard { s.gatt.properties(service, tx) } ?: 0
            val noResponse = writeNoResponse || (txProps and PROP_WRITE_NO_RESPONSE != 0 && txProps and PROP_WRITE == 0)
            SwLog.i(TAG, "pipe $service tx props 0x${Integer.toHexString(txProps)}${if (noResponse) " (write without response)" else ""}")
            val pipe = Pipe(s, service, rx, tx, noResponse, s.pipesOpened and 0xFF, s.maxWrite)
            s.pipes[service to rx] = pipe // registered first so no early notification is lost
            val flag = s.queue.submit(notifyFlagOp(s, service, rx, true))
            val cccd = if (flag is Outcome.Ok) withAuth(s) { cccdOp(s, service, rx, enable) } else flag
            if (cccd is Outcome.Fail) {
                if (s.pipes[service to rx] === pipe) s.pipes.remove(service to rx)
                pipe.drop()
                return@withContext cccd
            }
            s.pipesOpened++ // the capture's pipe index: 0 = first pipe opened (GFDI), 1 = next (watch app)
            Outcome.Ok(pipe)
        }

    override suspend fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean = withContext(dispatcher) {
        session?.services?.get(service)?.contains(characteristic) == true
    }

    override suspend fun read(service: UUID, characteristic: UUID): Outcome<ByteArray> = withContext(dispatcher) {
        val s = session
        if (s == null || _state.value != PortState.CONNECTED) return@withContext Outcome.Fail(SwError.Io("not connected"))
        if (s.services[service]?.contains(characteristic) != true) {
            return@withContext Outcome.Fail(SwError.Protocol("no characteristic $characteristic in $service"))
        }
        withAuth(s) { readOp(s, service, characteristic) }
    }

    private fun readOp(s: Session, service: UUID, characteristic: UUID) = GattOp(
        name = "readCharacteristic",
        timeoutMs = timing.opTimeoutMs,
        start = { if (s.gatt.readCharacteristic(service, characteristic)) GattApi.STATUS_OK else GattApi.STATUS_NOT_STARTED },
        complete = { e ->
            (e as? GattEvent.CharacteristicRead)
                ?.takeIf { it.service == service && it.characteristic == characteristic }
                ?.let { r -> statusResult("read", r.status).let { if (it is Outcome.Ok) Outcome.Ok(r.value) else it as Outcome.Fail } }
        },
    )

    private fun cccdValue(props: Int): ByteArray? = when {
        props < 0 -> null
        props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> byteArrayOf(0x01, 0x00)
        props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> byteArrayOf(0x02, 0x00)
        else -> null
    }

    override suspend fun setHighSpeed(on: Boolean) {
        withContext(dispatcher) {
            val s = session ?: return@withContext
            val r = s.queue.submit(
                GattOp(
                    name = "connectionPriority",
                    timeoutMs = timing.opTimeoutMs,
                    start = { if (s.gatt.requestConnectionPriority(on)) GattApi.STATUS_OK else GattApi.STATUS_NOT_STARTED },
                    immediate = { },
                ),
            )
            if (r is Outcome.Fail) SwLog.i(TAG, "connection priority not changed: ${r.error}")
            else SwLog.i(TAG, "connection priority ${if (on) "high" else "balanced"}")
        }
    }

    /** A notify/write characteristic pair on one session. */
    private inner class Pipe(
        val session: Session,
        val service: UUID,
        val rx: UUID,
        val tx: UUID,
        val noResponse: Boolean,
        val index: Int,
        override val maxWrite: Int,
    ) : BytePipe {
        val inbox = Channel<ByteArray>(Channel.UNLIMITED)

        @Volatile
        private var closed = false

        override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()

        override suspend fun write(bytes: ByteArray): Outcome<Unit> {
            if (closed) return Outcome.Fail(SwError.Io("pipe closed"))
            if (bytes.size > maxWrite) return Outcome.Fail(SwError.Protocol("write of ${bytes.size} > maxWrite $maxWrite"))
            val copy = bytes.copyOf()
            return withContext(dispatcher) { withAuth(session) { writeOp(session, this@Pipe, copy) } }
        }

        override suspend fun close() {
            withContext(dispatcher + NonCancellable) {
                if (closed) return@withContext
                closed = true
                if (!session.ended.isCompleted && session.pipes[service to rx] === this@Pipe) {
                    session.queue.submit(notifyFlagOp(session, service, rx, false))
                    session.queue.submit(cccdOp(session, service, rx, byteArrayOf(0x00, 0x00)))
                    session.pipes.remove(service to rx)
                }
                inbox.close()
            }
        }

        /** The link went away: end [incoming] and refuse writes. */
        fun drop() {
            closed = true
            inbox.close()
        }
    }

    private companion object {
        const val TAG = "BleConnection"
        const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
        const val AUTH_PREFIX = "GATT authentication status "

        // BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE and PROPERTY_WRITE.
        const val PROP_WRITE_NO_RESPONSE = 0x04
        const val PROP_WRITE = 0x08
    }
}
