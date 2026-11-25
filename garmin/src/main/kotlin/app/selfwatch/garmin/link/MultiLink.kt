/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.link

import app.selfwatch.garmin.gfdi.ByteReader
import app.selfwatch.garmin.gfdi.ByteWriter
import app.selfwatch.garmin.gfdi.MalformedException
import app.selfwatch.model.ble.BytePipe
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** Multi-link service codes (registered on handle 0). */
public object MlService {
    // Ref: Gadgetbridge 160beab41 service/devices/garmin/communicator/v2/CommunicatorV2.java (Service enum codes)
    /** The GFDI message service. */
    public const val GFDI: Int = 1

    /** Realtime heart rate. */
    public const val REALTIME_HR: Int = 6

    /** Realtime steps. */
    public const val REALTIME_STEPS: Int = 7

    /** Realtime calories. */
    public const val REALTIME_CALORIES: Int = 8

    /** Realtime HRV (RR intervals). */
    public const val REALTIME_HRV: Int = 12

    /** Realtime stress. */
    public const val REALTIME_STRESS: Int = 13

    /** Realtime SpO2. */
    public const val REALTIME_SPO2: Int = 19

    /** Realtime respiration. */
    public const val REALTIME_RESPIRATION: Int = 21

    /** Keep-alive service (the reference never registers it). */
    public const val KEEP_ALIVE: Int = 22
}

/**
 * Control message types on handle 0. The numbers are the ordinals of the reference's `RequestType`
 * enum: REGISTER_ML_REQ 0, REGISTER_ML_RESP 1, CLOSE_HANDLE_REQ 2, CLOSE_HANDLE_RESP 3, UNK_HANDLE 4,
 * CLOSE_ALL_REQ 5, CLOSE_ALL_RESP 6, UNK_REQ 7, UNK_RESP 8.
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/communicator/v2/CommunicatorV2.java (RequestType order)
 */
public object MlControl {
    /** Register a service. */
    public const val REGISTER_REQ: Int = 0

    /** Registration result. */
    public const val REGISTER_RESP: Int = 1

    /** Close one handle. */
    public const val CLOSE_HANDLE_REQ: Int = 2

    /** Close-handle result (also sent unprompted when the watch closes a handle). */
    public const val CLOSE_HANDLE_RESP: Int = 3

    /** Close every handle (link reset). */
    public const val CLOSE_ALL_REQ: Int = 5

    /** Close-all result. */
    public const val CLOSE_ALL_RESP: Int = 6

    /** Registration flag asking for the reliable layer. */
    public const val RELIABLE_REQUEST_FLAG: Int = 2

    // Ref: Gadgetbridge 160beab41 service/devices/garmin/communicator/v2/CommunicatorV2.java (GADGETBRIDGE_CLIENT_ID = 2)
    /** Client id sent in every control message; the watch echoes it. The reference's value. */
    public const val DEFAULT_CLIENT_ID: Long = 2L

    /** Request: `00 type clientId(u64) service(u16) extra(u8)`, 13 bytes, little-endian. */
    public fun request(type: Int, clientId: Long, service: Int, extra: Int): ByteArray =
        ByteWriter(13).u8(0).u8(type).i64(clientId).u16(service).u8(extra).toByteArray()
}

/** A parsed control response from handle 0. */
public sealed interface MlResponse {
    /** Registration result; [reliable] is nonzero when the reliable layer is on. */
    public data class Registered(val service: Int, val status: Int, val handle: Int, val reliable: Int) : MlResponse

    /** A handle was closed. */
    public data class Closed(val service: Int, val handle: Int, val status: Int) : MlResponse

    /** All handles were closed. */
    public data object ClosedAll : MlResponse

    /** Anything else. */
    public data class Other(val type: Int) : MlResponse

    /** Parser. */
    public companion object {
        /** Parses a handle-0 packet (including the leading 0x00); null if malformed or for another client. */
        public fun parse(pkt: ByteArray, clientId: Long): MlResponse? = try {
            val r = ByteReader(pkt)
            r.u8() // handle 0
            val type = r.u8()
            val cid = r.i64()
            if (cid != clientId) {
                SwLog.w(TAG, "control message for client $cid ignored")
                null
            } else {
                when (type) {
                    MlControl.REGISTER_RESP -> {
                        val service = r.u16()
                        val status = r.u8()
                        // A failed registration may omit the handle; older watches omit the reliable byte.
                        val handle = if (r.remaining > 0) r.u8() else 0
                        val reliable = if (r.remaining > 0) r.u8() else 0
                        Registered(service, status, handle, reliable)
                    }
                    MlControl.CLOSE_HANDLE_RESP -> Closed(r.u16(), r.u8(), if (r.remaining > 0) r.u8() else 0)
                    MlControl.CLOSE_ALL_RESP -> ClosedAll
                    else -> Other(type)
                }
            }
        } catch (e: MalformedException) {
            SwLog.w(TAG, "malformed control message: ${e.message}")
            null
        }

        private const val TAG = "garmin.ml"
    }
}

/** One registered multi-link service: raw bytes in and out (no COBS). */
public interface MlServiceChannel {
    /** The service code. */
    public val service: Int

    /** True when it runs over the reliable layer. */
    public val reliable: Boolean

    /** Bytes from the watch for this service, in order. */
    public val incoming: Flow<ByteArray>

    /** Sends bytes to the watch on this service. */
    public suspend fun send(bytes: ByteArray): Outcome<Unit>
}

/**
 * V2 multi-link over one pipe: handle 0 is the control channel; each registered service gets a handle.
 * Packets for a service start with its handle byte, or with an MLR header when the reliable layer is on.
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/communicator/v2/CommunicatorV2.java (close-all on start,
 * register GFDI on close-all response, handle byte prefix, MLR flag dispatch, re-register a closed GFDI handle).
 */
public class MultiLink(
    private val pipe: BytePipe,
    private val scope: CoroutineScope,
    private val clientId: Long = MlControl.DEFAULT_CLIENT_ID,
    private val controlTimeoutMs: Long = 5_000,
) {
    private val writeLock = Mutex()
    private val controlLock = Mutex()
    private val control = Channel<MlResponse>(16, BufferOverflow.DROP_OLDEST)
    private val services = ConcurrentHashMap<Int, ServiceState>() // by service code
    private val byHandle = ConcurrentHashMap<Int, ServiceState>() // plain handle byte -> service
    private val byMlrHandle = ConcurrentHashMap<Int, ServiceState>() // low 3 bits -> service
    private var reader: Job? = null

    @Volatile
    private var closingAll = false

    private inner class ServiceState(val code: Int, val wantReliable: Boolean) : MlServiceChannel {
        val inbox = Channel<ByteArray>(LinkLimits.FRAME_QUEUE)

        @Volatile
        var handle: Int = -1

        @Volatile
        var mlr: Mlr? = null
        override val service: Int get() = code
        override val reliable: Boolean get() = mlr != null
        override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()

        override suspend fun send(bytes: ByteArray): Outcome<Unit> {
            mlr?.let { return it.send(bytes) }
            val hd = handle
            if (hd < 0) return Outcome.Fail(SwError.Io("service $code not registered"))
            return writeLock.withLock {
                val chunk = maxOf(1, pipe.maxWrite - 1)
                var off = 0
                while (off < bytes.size) {
                    val end = minOf(bytes.size, off + chunk)
                    val pkt = ByteArray(1 + end - off)
                    pkt[0] = hd.toByte()
                    bytes.copyInto(pkt, 1, off, end)
                    val r = pipe.write(pkt)
                    if (r is Outcome.Fail) return@withLock r
                    off = end
                }
                Outcome.Ok(Unit)
            }
        }
    }

    /** Largest single write on the pipe. */
    public val maxPacket: Int get() = pipe.maxWrite

    /** Starts the reader and resets the link with CLOSE_ALL. Call once before [openService]. */
    public suspend fun start(): Outcome<Unit> {
        if (reader == null) reader = scope.launch { readLoop() }
        return controlLock.withLock {
            closingAll = true
            val w = writeRaw(MlControl.request(MlControl.CLOSE_ALL_REQ, clientId, 0, 0))
            if (w is Outcome.Fail) return@withLock w
            val r = awaitControl { it is MlResponse.ClosedAll }
            closingAll = false
            if (r == null) SwLog.w(TAG, "no close-all response; registering anyway")
            Outcome.Ok(Unit)
        }
    }

    /** Registers [service], asking for the reliable layer when [reliable]. */
    public suspend fun openService(service: Int, reliable: Boolean): Outcome<MlServiceChannel> {
        val st = services.getOrPut(service) { ServiceState(service, reliable) }
        return register(st)
    }

    /**
     * Closes [service] (a no-op when it is not open): asks the watch to close its handle and waits for the
     * answer (at most the control timeout). The service's [MlServiceChannel.incoming] ends; a later
     * [openService] registers it again. Never use it for GFDI, which the session needs.
     */
    public suspend fun closeService(service: Int): Outcome<Unit> {
        val st = services.remove(service) ?: return Outcome.Ok(Unit)
        return controlLock.withLock {
            val hd = st.handle
            detach(st)
            if (hd < 0) return@withLock Outcome.Ok(Unit)
            // Ref: Gadgetbridge 160beab41 service/devices/garmin/communicator/v2/CommunicatorV2.java (closeService: CLOSE_HANDLE_REQ, service code, handle)
            val w = writeRaw(MlControl.request(MlControl.CLOSE_HANDLE_REQ, clientId, service, hd))
            if (w is Outcome.Fail) return@withLock w
            if (awaitControl { it is MlResponse.Closed && it.service == service } == null) {
                SwLog.w(TAG, "no close response for service $service")
            }
            Outcome.Ok(Unit)
        }
    }

    private fun detach(st: ServiceState) {
        st.mlr?.close()
        st.mlr = null
        if (st.handle >= 0) {
            byHandle.remove(st.handle)
            byMlrHandle.remove(st.handle and 0x07)
        }
        st.handle = -1
        st.inbox.close()
    }

    private suspend fun register(st: ServiceState): Outcome<MlServiceChannel> = controlLock.withLock {
        repeat(2) { attempt ->
            val flag = if (st.wantReliable) MlControl.RELIABLE_REQUEST_FLAG else 0
            val w = writeRaw(MlControl.request(MlControl.REGISTER_REQ, clientId, st.code, flag))
            if (w is Outcome.Fail) return@withLock w
            val resp = awaitControl { it is MlResponse.Registered && it.service == st.code } as MlResponse.Registered?
            if (resp == null) {
                SwLog.w(TAG, "no registration response for service ${st.code} (attempt ${attempt + 1})")
                return@repeat
            }
            if (resp.status != 0) {
                return@withLock Outcome.Fail(SwError.Protocol("registration of service ${st.code} refused, status ${resp.status}"))
            }
            // attach() already ran in the reader, before any data for the new handle could arrive.
            return@withLock Outcome.Ok(st)
        }
        Outcome.Fail(SwError.Timeout("multi-link registration of service ${st.code}"))
    }

    private fun attach(st: ServiceState, resp: MlResponse.Registered) {
        st.mlr?.close()
        st.mlr = null
        if (st.handle >= 0) {
            byHandle.remove(st.handle)
            byMlrHandle.remove(st.handle and 0x07)
        }
        st.handle = resp.handle
        byHandle[resp.handle] = st
        if (resp.reliable != 0) {
            st.mlr = Mlr(
                handle = resp.handle,
                maxPacket = pipe.maxWrite,
                scope = scope,
                sendPacket = { pkt -> writeRaw(pkt) },
                onData = { data -> st.inbox.send(data) },
                onFailure = { closeQuietly() },
            )
            byMlrHandle[resp.handle and 0x07] = st
        }
        SwLog.i(TAG, "service ${st.code} on handle ${resp.handle} reliable=${resp.reliable != 0}")
    }

    private suspend fun awaitControl(match: (MlResponse) -> Boolean): MlResponse? = withTimeoutOrNull(controlTimeoutMs) {
        var r: MlResponse
        do {
            r = control.receive()
        } while (!match(r))
        r
    }

    private suspend fun writeRaw(pkt: ByteArray): Outcome<Unit> = writeLock.withLock { pipe.write(pkt) }

    private suspend fun readLoop() {
        try {
            pipe.incoming.collect { pkt -> dispatch(pkt) }
        } finally {
            for (s in services.values) {
                s.mlr?.close()
                s.inbox.close()
            }
        }
    }

    private suspend fun dispatch(pkt: ByteArray) {
        if (pkt.isEmpty()) return
        val b0 = pkt[0].toInt() and 0xFF
        if (b0 and Mlr.FLAG_MASK != 0) {
            val st = byMlrHandle[(b0 and Mlr.HANDLE_MASK) shr Mlr.HANDLE_SHIFT]
            val m = st?.mlr
            if (m != null) {
                m.onPacket(pkt)
                return
            }
            // Non-reliable handles can have the top bit set too (reference #5476): fall through.
        }
        if (b0 == 0) {
            val r = MlResponse.parse(pkt, clientId) ?: return
            onControl(r)
            return
        }
        val st = byHandle[b0]
        if (st == null) {
            SwLog.w(TAG, "data for unknown handle $b0")
            return
        }
        try {
            st.inbox.send(pkt.copyOfRange(1, pkt.size))
        } catch (e: kotlinx.coroutines.channels.ClosedSendChannelException) {
            SwLog.d(TAG) { "data for closed service ${st.code}: ${e.message}" }
        }
    }

    private fun onControl(r: MlResponse) {
        if (r is MlResponse.Registered && r.status == 0) services[r.service]?.let { attach(it, r) }
        if (r is MlResponse.Closed && !closingAll) {
            val st = services[r.service]
            if (st != null && st.handle == r.handle) {
                SwLog.w(TAG, "watch closed service ${r.service}; re-registering")
                st.mlr?.close()
                st.mlr = null
                byHandle.remove(r.handle)
                byMlrHandle.remove(r.handle and 0x07)
                st.handle = -1
                scope.launch { register(st) }
            }
        }
        control.trySend(r)
    }

    private suspend fun closeQuietly() {
        try {
            pipe.close()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SwLog.w(TAG, "closing pipe failed", e)
        }
    }

    /** Closes every service and the pipe. */
    public suspend fun close() {
        for (s in services.values) s.mlr?.close()
        closeQuietly() // ends the reader, which closes the service channels
    }

    private companion object {
        const val TAG = "garmin.ml"
    }
}

/** A [GfdiTransport] over one multi-link service: COBS framing on top of the service's byte stream. */
public class MultiLinkGfdiTransport(
    /** The multi-link this transport runs on (other services, such as live readings, open on it too). */
    public val link: MultiLink,
    private val channel: MlServiceChannel,
    scope: CoroutineScope,
) : GfdiTransport {
    private val frames = Channel<ByteArray>(LinkLimits.FRAME_QUEUE)
    private val sendLock = Mutex()

    override val incoming: Flow<ByteArray> = frames.receiveAsFlow()
    override val maxPacket: Int get() = link.maxPacket
    override val version: LinkVersion = LinkVersion.V2
    override val reliable: Boolean get() = channel.reliable

    init {
        scope.launch {
            val decoder = CobsDecoder()
            try {
                channel.incoming.collect { bytes -> for (f in decoder.feed(bytes)) frames.send(f) }
            } finally {
                frames.close()
            }
        }
    }

    override suspend fun send(frame: ByteArray): Outcome<Unit> = sendLock.withLock { channel.send(Cobs.encode(frame)) }

    override suspend fun close() {
        link.close() // the reader then ends and closes [incoming]
    }
}
