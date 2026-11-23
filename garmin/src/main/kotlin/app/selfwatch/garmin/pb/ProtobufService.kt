/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.pb

import app.selfwatch.garmin.gfdi.Bytes
import app.selfwatch.garmin.gfdi.FeatureReplies.ProtobufStatus
import app.selfwatch.garmin.gfdi.GfdiId
import app.selfwatch.garmin.gfdi.GfdiMessage
import app.selfwatch.garmin.gfdi.GfdiStatus
import app.selfwatch.garmin.gfdi.MalformedException
import app.selfwatch.garmin.gfdi.ProtobufPacket
import app.selfwatch.garmin.pb.proto.SmartProto.Smart
import app.selfwatch.garmin.session.GarminSessionContext
import app.selfwatch.garmin.session.GfdiHandler
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What a [PbHandler] did with a message. */
public sealed interface PbResult {
    /** Handled; answer with [smart] as a PROTOBUF_RESPONSE. */
    public data class Reply(val smart: Smart) : PbResult

    /** Handled; nothing to answer (the chunk status says "kept"). */
    public data object Done : PbResult
}

/** A protobuf service feature (core, find my watch, later apps, settings, web, ...). */
public fun interface PbHandler {
    /**
     * Looks at a complete message from the watch. [isResponse] is true for a PROTOBUF_RESPONSE that
     * answers no request of ours (for example a find-my-watch result). Returns null when not its message.
     */
    public suspend fun onMessage(smart: Smart, isResponse: Boolean, session: GarminSessionContext): PbResult?

    /**
     * As [onMessage], also given the watch's protobuf [requestId], for handlers that answer later with
     * [ProtobufService.respond]. The service calls this one; by default it calls [onMessage].
     */
    public suspend fun onMessage(smart: Smart, isResponse: Boolean, requestId: Int, session: GarminSessionContext): PbResult? =
        onMessage(smart, isResponse, session)
}

/**
 * Protobuf services over GFDI (WP-06 §4): PROTOBUF_REQUEST / PROTOBUF_RESPONSE (5043/5044), each a
 * `Smart` envelope split into chunks of `maxPacketSize − 20` bytes.
 *
 * Incoming: chunks are put back together by request id (offset must follow on; at most [maxMessageBytes]
 * per message, [maxPartial] unfinished messages); each unfinished chunk is answered with a protobuf status
 * "kept". A whole message either answers one of our [request]s or goes to the [handlers] in order; the
 * first that returns non-null wins. Status replies follow the reference: a one-chunk message that gets a
 * reply is acknowledged with a plain ACK, otherwise "kept, no error"; a message nobody handles gets
 * "discarded, unknown request id" and one that does not parse gets "discarded, parse error".
 *
 * Outgoing: one-chunk messages are sent without waiting; longer ones wait for the watch's status after
 * each chunk.
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/ProtocolBufferHandler.java (chunking, request ids mod 65536,
 * maxChunkSize = maxPacketSize − 20, status choices) and messages/status/ProtobufStatusMessage.java
 */
public class ProtobufService(
    handlers: List<PbHandler> = emptyList(),
    private val chunkTimeoutMs: Long = 10_000,
    private val maxMessageBytes: Int = 1 shl 20,
    private val maxPartial: Int = 8,
) : GfdiHandler {
    private val handlers: MutableList<PbHandler> = java.util.concurrent.CopyOnWriteArrayList(handlers)
    private val lock = Any()
    private var state: State? = null

    /** Adds a handler after the existing ones. */
    public fun register(h: PbHandler) {
        handlers.add(h)
    }

    private class Partial(val total: Int) {
        val buf = java.io.ByteArrayOutputStream()
    }

    private class State(val session: GarminSessionContext) {
        var lastId = 0
        val pending = HashMap<Int, CompletableDeferred<Smart>>()
        val partial = LinkedHashMap<Pair<Int, Int>, Partial>()
    }

    private fun stateFor(session: GarminSessionContext): State = synchronized(lock) {
        val s = state
        if (s != null && s.session === session) return s
        // Requests still waiting on an old connection simply time out.
        State(session).also { state = it }
    }

    override suspend fun handle(m: GfdiMessage, session: GarminSessionContext): Boolean {
        if (m !is ProtobufPacket) return false
        val st = stateFor(session)
        val whole = assemble(m, st, session) ?: return true
        val smart = try {
            Smart.parseFrom(whole)
        } catch (e: InvalidProtocolBufferException) {
            SwLog.w(TAG, "protobuf #${m.requestId} did not parse: ${e.message}")
            status(session, m, ProtobufStatus.DISCARDED, ProtobufStatus.PROTOBUF_PARSE_ERROR)
            return true
        }
        SwLog.d(TAG) { "<- protobuf ${m.id} #${m.requestId}: $smart" }
        if (m.id == GfdiId.PROTOBUF_RESPONSE) {
            val d = synchronized(lock) { st.pending.remove(m.requestId) }
            if (d != null) {
                status(session, m, ProtobufStatus.KEPT, ProtobufStatus.NO_ERROR)
                d.complete(smart)
                return true
            }
        }
        var result: PbResult? = null
        for (h in handlers) {
            result = h.onMessage(smart, m.id == GfdiId.PROTOBUF_RESPONSE, m.requestId, session)
            if (result != null) break
        }
        when (result) {
            null -> {
                SwLog.w(TAG, "unhandled protobuf #${m.requestId}: $smart")
                status(session, m, ProtobufStatus.DISCARDED, ProtobufStatus.UNKNOWN_REQUEST_ID)
            }
            PbResult.Done -> status(session, m, ProtobufStatus.KEPT, ProtobufStatus.NO_ERROR)
            is PbResult.Reply -> {
                if (m.isComplete) session.channel.reply(m.id) else status(session, m, ProtobufStatus.KEPT, ProtobufStatus.NO_ERROR)
                val bytes = result.smart.toByteArray()
                if (bytes.size <= chunkSize(session)) {
                    sendMessage(session, GfdiId.PROTOBUF_RESPONSE, m.requestId, bytes)
                } else {
                    session.scope.launch { sendMessage(session, GfdiId.PROTOBUF_RESPONSE, m.requestId, bytes) }
                }
            }
        }
        return true
    }

    /** Returns the whole message once complete; null when a chunk was stored (or refused) and answered. */
    private suspend fun assemble(m: ProtobufPacket, st: State, session: GarminSessionContext): ByteArray? {
        if (m.isComplete) return m.data.toByteArray()
        val key = m.id to m.requestId
        val refuse: Int? = synchronized(lock) {
            when {
                m.totalLength > maxMessageBytes || m.dataOffset + m.data.size > m.totalLength -> {
                    st.partial.remove(key)
                    ProtobufStatus.EXCEEDED_TOTAL_PROTOBUF_LENGTH
                }
                m.dataOffset == 0 -> {
                    st.partial.remove(key)
                    while (st.partial.size >= maxPartial) st.partial.remove(st.partial.keys.first())
                    st.partial[key] = Partial(m.totalLength).also { it.buf.write(m.data.toByteArray()) }
                    null
                }
                else -> {
                    val p = st.partial[key]
                    if (p == null || p.total != m.totalLength || p.buf.size() != m.dataOffset) {
                        ProtobufStatus.MISSING_PACKET
                    } else {
                        p.buf.write(m.data.toByteArray())
                        null
                    }
                }
            }
        }
        if (refuse != null) {
            SwLog.w(TAG, "protobuf #${m.requestId} chunk at ${m.dataOffset} refused ($refuse)")
            status(session, m, ProtobufStatus.DISCARDED, refuse)
            return null
        }
        val done = synchronized(lock) {
            val p = st.partial[key]
            if (p != null && p.buf.size() == p.total) {
                st.partial.remove(key)
                p.buf.toByteArray()
            } else {
                null
            }
        }
        if (done == null) status(session, m, ProtobufStatus.KEPT, ProtobufStatus.NO_ERROR)
        return done
    }

    private suspend fun status(session: GarminSessionContext, m: ProtobufPacket, chunkStatus: Int, code: Int) {
        session.channel.reply(m.id, GfdiStatus.ACK, ProtobufStatus(m.requestId, m.dataOffset, chunkStatus, code).encode())
    }

    private fun chunkSize(session: GarminSessionContext): Int = maxOf(MIN_CHUNK, session.transfers.maxPacketSize - CHUNK_OVERHEAD)

    private fun nextId(st: State): Int = synchronized(lock) {
        st.lastId = (st.lastId + 1) % 65_536
        st.lastId
    }

    /**
     * Sends [smart] as a PROTOBUF_REQUEST and waits up to [timeoutMs] for the watch's PROTOBUF_RESPONSE
     * with the same request id.
     */
    public suspend fun request(session: GarminSessionContext, smart: Smart, timeoutMs: Long = 10_000): Outcome<Smart> {
        val st = stateFor(session)
        val id = nextId(st)
        val d = CompletableDeferred<Smart>()
        synchronized(lock) { st.pending[id] = d }
        try {
            val sent = sendMessage(session, GfdiId.PROTOBUF_REQUEST, id, smart.toByteArray())
            if (sent is Outcome.Fail) return sent
            val r = withTimeoutOrNull(timeoutMs) { d.await() }
            return if (r == null) Outcome.Fail(SwError.Timeout("protobuf response #$id")) else Outcome.Ok(r)
        } finally {
            synchronized(lock) { st.pending.remove(id) }
        }
    }

    /** Sends [smart] as a PROTOBUF_REQUEST without waiting for an answer (answers go to the handlers). */
    public suspend fun send(session: GarminSessionContext, smart: Smart): Outcome<Unit> =
        sendMessage(session, GfdiId.PROTOBUF_REQUEST, nextId(stateFor(session)), smart.toByteArray())

    /**
     * Sends [smart] as a PROTOBUF_RESPONSE to the watch's request [requestId], later than the handler call
     * (a [PbHandler] returned [PbResult.Done] and answers asynchronously, as the reference does for web
     * requests). Long messages wait for the watch's status after each chunk.
     *
     * Ref: Gadgetbridge 160beab41 service/devices/garmin/GarminSupport.java (ProtobufResponseEvent →
     * prepareProtobufResponse with the original request id)
     */
    public suspend fun respond(session: GarminSessionContext, requestId: Int, smart: Smart): Outcome<Unit> =
        sendMessage(session, GfdiId.PROTOBUF_RESPONSE, requestId, smart.toByteArray())

    /** Sends [bytes] as message [msgId] with [requestId], chunked to the watch's packet size. */
    private suspend fun sendMessage(session: GarminSessionContext, msgId: Int, requestId: Int, bytes: ByteArray): Outcome<Unit> {
        val chunk = chunkSize(session)
        if (bytes.size <= chunk) return session.channel.send(ProtobufPacket(msgId, requestId, 0, bytes.size, Bytes(bytes)))
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(bytes.size, offset + chunk)
            val p = ProtobufPacket(msgId, requestId, offset, bytes.size, Bytes(bytes.copyOfRange(offset, end)))
            val r = when (val rr = session.channel.request(p, chunkTimeoutMs)) {
                is Outcome.Ok -> rr.value
                is Outcome.Fail -> return rr
            }
            val st = if (r.extra.size == 0) null else try {
                ProtobufStatus.parse(r.extra.toByteArray())
            } catch (e: MalformedException) {
                null
            }
            if (r.status != GfdiStatus.ACK || (st != null && !st.isOk)) {
                return Outcome.Fail(SwError.Protocol("protobuf #$requestId chunk at $offset refused (${r.status}, $st)"))
            }
            offset = end
        }
        return Outcome.Ok(Unit)
    }

    private companion object {
        const val TAG = "garmin.pb"

        /** GFDI frame bytes around a chunk: length, id, request id, offset, total, chunk length, CRC. */
        const val CHUNK_OVERHEAD = 20
        const val MIN_CHUNK = 16
    }
}
