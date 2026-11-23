/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.gfdi

import app.selfwatch.garmin.link.GfdiTransport
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * GFDI messages over a [GfdiTransport]. RESPONSEs that answer an outstanding [request] complete it;
 * every other message (including unmatched responses) goes to [incoming]. Frames that fail the length
 * or CRC check are logged and dropped.
 *
 * @param inboxCapacity bound on undelivered messages; the transport reader waits when it is full.
 */
public class GfdiChannel(
    private val transport: GfdiTransport,
    scope: CoroutineScope,
    inboxCapacity: Int = 64,
) {
    private val inbox = Channel<GfdiMessage>(inboxCapacity)
    private val requestLock = Mutex()
    private val pendingLock = Any()
    private var pendingId: Int = -1
    private var pending: CompletableDeferred<GfdiMessage.Response?>? = null

    /** Messages from the watch that are not answers to [request]. Completes when the link closes. */
    public val incoming: Flow<GfdiMessage> = inbox.receiveAsFlow()

    /** The underlying transport. */
    public val link: GfdiTransport get() = transport

    init {
        scope.launch {
            try {
                transport.incoming.collect { frame ->
                    when (val m = GfdiMessage.decodeFrame(frame)) {
                        is Outcome.Fail -> SwLog.w(TAG, "dropped frame: ${m.error}")
                        is Outcome.Ok -> route(m.value)
                    }
                }
            } finally {
                inbox.close()
                synchronized(pendingLock) { pending }?.complete(null)
            }
        }
    }

    private suspend fun route(m: GfdiMessage) {
        if (m is GfdiMessage.Response) {
            val d = synchronized(pendingLock) {
                if (pendingId == m.originalId) pending.also { pending = null; pendingId = -1 } else null
            }
            if (d != null) {
                d.complete(m)
                return
            }
        }
        inbox.send(m)
    }

    /** Sends [m] without waiting for an answer. */
    public suspend fun send(m: GfdiMessage): Outcome<Unit> {
        SwLog.d(TAG) { "-> ${m.id} $m" }
        return transport.send(m.toFrame())
    }

    /** Sends [m] and waits up to [timeoutMs] for the RESPONSE to it. Requests run one at a time. */
    public suspend fun request(m: GfdiMessage, timeoutMs: Long = 5_000): Outcome<GfdiMessage.Response> = requestLock.withLock {
        val d = CompletableDeferred<GfdiMessage.Response?>()
        synchronized(pendingLock) {
            pendingId = m.id
            pending = d
        }
        val sent = send(m)
        if (sent is Outcome.Fail) {
            clearPending(d)
            return@withLock sent
        }
        val r = withTimeoutOrNull(timeoutMs) { d.await() ?: CLOSED }
        clearPending(d)
        when {
            r == null -> Outcome.Fail(SwError.Timeout("response to GFDI ${m.id}"))
            r === CLOSED -> Outcome.Fail(SwError.Io("link closed"))
            else -> Outcome.Ok(r)
        }
    }

    private fun clearPending(d: CompletableDeferred<GfdiMessage.Response?>) {
        synchronized(pendingLock) {
            if (pending === d) {
                pending = null
                pendingId = -1
            }
        }
    }

    /** Sends a status reply to message [id]. */
    public suspend fun reply(id: Int, status: GfdiStatus = GfdiStatus.ACK, extra: ByteArray = ByteArray(0)): Outcome<Unit> =
        send(GfdiMessage.Response(id, status, Bytes(extra)))

    private companion object {
        const val TAG = "garmin.gfdi"
        val CLOSED = GfdiMessage.Response(-1, GfdiStatus.UNKNOWN)
    }
}
