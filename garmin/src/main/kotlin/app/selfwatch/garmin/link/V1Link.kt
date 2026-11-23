/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.link

import app.selfwatch.model.ble.BytePipe
import app.selfwatch.model.result.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * V1 link: one pipe, no registration. COBS frames are split into writes of at most `maxWrite` bytes.
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/communicator/v1/CommunicatorV1.java (single pipe, COBS framing)
 */
public class V1Link(private val pipe: BytePipe, scope: CoroutineScope) : GfdiTransport {
    private val frames = Channel<ByteArray>(LinkLimits.FRAME_QUEUE)
    private val writeLock = Mutex()

    override val incoming: Flow<ByteArray> = frames.receiveAsFlow()
    override val maxPacket: Int get() = pipe.maxWrite
    override val version: LinkVersion = LinkVersion.V1
    override val reliable: Boolean = false

    init {
        scope.launch {
            val decoder = CobsDecoder()
            try {
                pipe.incoming.collect { bytes -> for (f in decoder.feed(bytes)) frames.send(f) }
            } finally {
                frames.close()
            }
        }
    }

    override suspend fun send(frame: ByteArray): Outcome<Unit> = writeLock.withLock {
        val enc = Cobs.encode(frame)
        val chunk = maxOf(1, pipe.maxWrite)
        var off = 0
        while (off < enc.size) {
            val end = minOf(enc.size, off + chunk)
            val r = pipe.write(enc.copyOfRange(off, end))
            if (r is Outcome.Fail) return@withLock r
            off = end
        }
        Outcome.Ok(Unit)
    }

    override suspend fun close() {
        pipe.close() // the reader then ends and closes [incoming]
    }
}

/** Bounds shared by the link classes. */
internal object LinkLimits {
    /** Decoded frames waiting for the GFDI layer; the pipe reader suspends when full (back-pressure). */
    const val FRAME_QUEUE: Int = 64
}
