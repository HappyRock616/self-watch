/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.appmsg

import app.selfwatch.model.ble.BytePipe
import app.selfwatch.model.crc.Crc16
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.CiqValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.UUID

/** A message from a Connect IQ app on the watch. */
public data class AppMessage(val app: UUID, val value: CiqValue)

/**
 * Connect IQ app messages over the watch-app pipe (service `6a4e8022-…`), both directions.
 *
 * Phone → watch, one message at a time: OPEN(app uuid) → KEY(random 16-byte key) → START(cipher length) →
 * DATA fragments `(i shl 4) or 1` → LAST(count, CRC-16) → the watch ACKs OPEN, KEY, START and LAST →
 * CLOSE, unless the next queued message is for the same app (then KEY again on the open connection).
 * The payload is `XXTEA(pad(CiqCodec.encode(value)), key)` where pad puts the padding length first and
 * pads to a multiple of 4. Watch → phone mirrors it: INBOUND_OPEN, KEY_REQUEST (we send the key),
 * START, fragments, LAST.
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/communicator/GarminWatchAppCommunicator.java (packet types
 * 0x00/0xF1, command and data codes, ACK codes, key flags, max 14 fragments, 5 s timeout, pad()).
 * Note: unlike the findings note, only OPEN, KEY, START and LAST are acknowledged, not each fragment.
 *
 * @param random fills key bytes (tests pass a deterministic one).
 */
public class AppMessages(
    private val pipe: BytePipe,
    private val scope: CoroutineScope,
    private val random: (ByteArray) -> Unit = SecureRandom()::nextBytes,
    private val timeoutMs: Long = 5_000,
    private val maxQueued: Int = 32,
) {
    private class Pending(val app: UUID, val value: CiqValue, val result: CompletableDeferred<Outcome<Unit>>)

    private val writeLock = Mutex()
    private val queueLock = Mutex()
    private val queue = ArrayDeque<Pending>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val events = Channel<ByteArray>(16, BufferOverflow.DROP_OLDEST)
    private val inbox = Channel<AppMessage>(16)

    // Inbound transfer state; touched only by the reader coroutine.
    private var inboundApp: UUID? = null
    private var inboundKey: ByteArray? = null
    private var inboundLength = 0
    private val inboundData = java.io.ByteArrayOutputStream()

    /** Messages received from watch apps. */
    public val received: Flow<AppMessage> = inbox.receiveAsFlow()

    /** Fragment payload size: one byte of each write is the fragment header. */
    public val fragmentSize: Int get() = maxOf(1, pipe.maxWrite - 1)

    /** Largest encrypted message: [MAX_FRAGMENTS] fragments. */
    public val maxCipherBytes: Int get() = MAX_FRAGMENTS * fragmentSize

    init {
        scope.launch {
            try {
                pipe.incoming.collect { onPacket(it) }
            } finally {
                inbox.close()
                events.close()
            }
        }
        scope.launch { worker() }
    }

    /** Queues [value] for [app] and waits until the watch acknowledges it (or it fails). */
    public suspend fun send(app: UUID, value: CiqValue): Outcome<Unit> {
        val p = Pending(app, value, CompletableDeferred())
        val queued = queueLock.withLock {
            if (queue.size >= maxQueued) false else queue.addLast(p).let { true }
        }
        if (!queued) return Outcome.Fail(SwError.Io("app message queue full"))
        wake.trySend(Unit)
        return p.result.await()
    }

    private suspend fun next(sameApp: UUID?): Pending? = queueLock.withLock {
        val head = queue.firstOrNull() ?: return@withLock null
        if (sameApp != null && head.app != sameApp) null else queue.removeFirst()
    }

    private suspend fun worker() {
        while (true) {
            wake.receive()
            var p = next(null)
            while (p != null) {
                p = sendOnConnection(p)
            }
        }
    }

    /** Opens a connection for [first], sends it and any following messages for the same app; returns the next message for another app. */
    private suspend fun sendOnConnection(first: Pending): Pending? {
        drainEvents()
        if (!write(byteArrayOf(TYPE_COMMAND, CMD_OPEN) + uuidBytes(first.app) + byteArrayOf(0))) {
            first.result.complete(Outcome.Fail(SwError.Io("write failed")))
            return next(null)
        }
        val open = awaitEvent { it.size >= 2 && it[0] == TYPE_COMMAND && it[1] == CMD_OPEN }
        if (open == null || open.size < 3 || open[2].toInt() != 0) {
            SwLog.w(TAG, "watch app connection refused or timed out")
            val err = Outcome.Fail(if (open == null) SwError.Timeout("app connection") else SwError.Protocol("app connection refused"))
            first.result.complete(err)
            // The next messages for this app would fail as well.
            while (true) (next(first.app) ?: break).result.complete(err)
            return next(null)
        }
        var p: Pending? = first
        while (p != null) {
            val r = transfer(p)
            p.result.complete(r)
            if (r is Outcome.Fail) break
            p = next(first.app)
        }
        close()
        return next(null)
    }

    private suspend fun transfer(p: Pending): Outcome<Unit> {
        val cipher = try {
            val key = ByteArray(16).also(random)
            val c = Xxtea.encrypt(pad(CiqCodec.encode(p.value)), key)
            if (c.size > maxCipherBytes) return Outcome.Fail(SwError.BadInput("app message too large: ${c.size} > $maxCipherBytes bytes"))
            if (!write(byteArrayOf(TYPE_DATA, DATA_KEY) + key + byteArrayOf(KEY_FLAG_OUTBOUND))) return Outcome.Fail(SwError.Io("write failed"))
            c
        } catch (e: IllegalArgumentException) {
            return Outcome.Fail(SwError.BadInput("app message: ${e.message}"))
        }
        awaitAck(ACK_KEY)?.let { return it }
        val start = java.nio.ByteBuffer.allocate(7).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put(TYPE_DATA).put(DATA_START).put(0x02).putInt(cipher.size).array()
        if (!write(start)) return Outcome.Fail(SwError.Io("write failed"))
        awaitAck(ACK_START)?.let { return it }
        val frag = fragmentSize
        val count = (cipher.size + frag - 1) / frag
        for (i in 0 until count) {
            val from = i * frag
            val to = minOf(cipher.size, from + frag)
            if (!write(byteArrayOf(((i shl 4) or 0x01).toByte()) + cipher.copyOfRange(from, to))) return Outcome.Fail(SwError.Io("write failed"))
        }
        val crc = Crc16.compute(cipher)
        if (!write(byteArrayOf(TYPE_DATA, DATA_LAST, count.toByte(), crc.toByte(), (crc shr 8).toByte()))) {
            return Outcome.Fail(SwError.Io("write failed"))
        }
        awaitAck(ACK_LAST)?.let { return it }
        return Outcome.Ok(Unit)
    }

    /** Waits for the ACK of [code]; null when it arrived with status 0, else the failure. */
    private suspend fun awaitAck(code: Byte): Outcome.Fail? {
        val ack = awaitEvent { it.size >= 4 && it[0] == TYPE_DATA && it[1] == DATA_ACK && it[2] == code }
            ?: return Outcome.Fail(SwError.Timeout("app message ack 0x%02x".format(code)))
        if (ack[3].toInt() != 0) return Outcome.Fail(SwError.Protocol("app message ack 0x%02x error %d".format(code, ack[3].toInt() and 0xFF)))
        return null
    }

    private suspend fun close() {
        write(byteArrayOf(TYPE_COMMAND, CMD_CLOSE, 0x01))
        awaitEvent { it.size >= 2 && it[0] == TYPE_COMMAND && it[1] == CMD_CLOSE }
    }

    private suspend fun awaitEvent(match: (ByteArray) -> Boolean): ByteArray? = withTimeoutOrNull(timeoutMs) {
        var e: ByteArray
        do {
            e = events.receive()
        } while (!match(e))
        e
    }

    private fun drainEvents() {
        do { val more = events.tryReceive().isSuccess } while (more)
    }

    private suspend fun write(b: ByteArray): Boolean = writeLock.withLock {
        val r = pipe.write(b)
        if (r is Outcome.Fail) SwLog.w(TAG, "write failed: ${r.error}")
        r is Outcome.Ok
    }

    private suspend fun onPacket(v: ByteArray) {
        if (v.size < 2) return
        when (v[0]) {
            TYPE_COMMAND -> when (v[1]) {
                CMD_INBOUND_OPEN -> {
                    if (v.size < 18) return
                    inboundApp = uuidFrom(v, 2)
                    inboundKey = null
                    inboundData.reset()
                    write(byteArrayOf(TYPE_COMMAND, CMD_INBOUND_OPEN, 0x00, 0x01))
                }
                CMD_INBOUND_CLOSE -> write(byteArrayOf(TYPE_COMMAND, CMD_INBOUND_CLOSE, 0x00, 0x01))
                else -> events.trySend(v)
            }
            TYPE_DATA -> when (v[1]) {
                DATA_KEY_REQUEST -> {
                    val key = ByteArray(16).also(random)
                    inboundKey = key
                    write(byteArrayOf(TYPE_DATA, DATA_KEY) + key + byteArrayOf(KEY_FLAG_INBOUND))
                }
                DATA_START -> {
                    if (v.size < 7) return
                    inboundLength = (v[3].toInt() and 0xFF) or ((v[4].toInt() and 0xFF) shl 8) or
                        ((v[5].toInt() and 0xFF) shl 16) or ((v[6].toInt() and 0xFF) shl 24)
                    inboundData.reset()
                    write(byteArrayOf(TYPE_DATA, DATA_ACK, ACK_START, 0x00, 0xFF.toByte()))
                }
                DATA_LAST -> {
                    if (v.size < 5) return
                    write(byteArrayOf(TYPE_DATA, DATA_ACK, ACK_LAST, 0x00, v[2]))
                    onInboundComplete((v[3].toInt() and 0xFF) or ((v[4].toInt() and 0xFF) shl 8))
                }
                else -> events.trySend(v)
            }
            else -> {
                if (inboundKey == null) {
                    SwLog.w(TAG, "fragment without an inbound transfer")
                    return
                }
                if (inboundData.size() + v.size - 1 > MAX_INBOUND_BYTES) {
                    SwLog.w(TAG, "inbound app message too large; dropped")
                    inboundKey = null
                    inboundData.reset()
                    return
                }
                inboundData.write(v, 1, v.size - 1)
            }
        }
    }

    private suspend fun onInboundComplete(crc: Int) {
        val cipher = inboundData.toByteArray()
        inboundData.reset()
        val key = inboundKey
        inboundKey = null
        val app = inboundApp
        if (key == null || app == null || cipher.size != inboundLength || cipher.size < 8 || cipher.size % 4 != 0) {
            SwLog.w(TAG, "inbound app message has length ${cipher.size}, expected $inboundLength")
            return
        }
        if (Crc16.compute(cipher) != crc) {
            SwLog.w(TAG, "inbound app message CRC mismatch")
            return
        }
        val plain = Xxtea.decrypt(cipher, key)
        val padding = plain[0].toInt() and 0xFF
        if (padding > 4 || 1 + padding > plain.size) {
            SwLog.w(TAG, "inbound app message padding $padding invalid")
            return
        }
        when (val d = CiqCodec.decode(plain.copyOfRange(1, plain.size - padding))) {
            is Outcome.Ok -> inbox.send(AppMessage(app, d.value))
            is Outcome.Fail -> SwLog.w(TAG, "inbound app message undecodable: ${d.error}")
        }
    }

    /** Wire constants and helpers. */
    public companion object {
        private const val TAG = "garmin.appmsg"
        private const val TYPE_COMMAND: Byte = 0x00
        private const val TYPE_DATA: Byte = 0xF1.toByte()
        private const val CMD_OPEN: Byte = 0x01
        private const val CMD_CLOSE: Byte = 0x02
        private const val CMD_INBOUND_OPEN: Byte = 0x03
        private const val CMD_INBOUND_CLOSE: Byte = 0x04
        private const val DATA_START: Byte = 0x00
        private const val DATA_LAST: Byte = 0x01
        private const val DATA_ACK: Byte = 0x02
        private const val DATA_KEY: Byte = 0x05
        private const val DATA_KEY_REQUEST: Byte = 0x06
        private const val ACK_START: Byte = 0x00
        private const val ACK_LAST: Byte = 0x02
        private const val ACK_KEY: Byte = 0x05
        private const val KEY_FLAG_OUTBOUND: Byte = 0x00
        private const val KEY_FLAG_INBOUND: Byte = 0x02
        private const val MAX_INBOUND_BYTES = 64 * 1024

        /** The watch rejects a message of 15 fragments. */
        public const val MAX_FRAGMENTS: Int = 14

        /** Pads to a multiple of 4 with the padding length as the first byte; at least 8 bytes (XXTEA minimum). */
        public fun pad(blob: ByteArray): ByteArray {
            var padding = (4 - (1 + blob.size) % 4) % 4
            while (1 + blob.size + padding < 8) padding += 4
            val out = ByteArray(1 + blob.size + padding)
            out[0] = padding.toByte()
            blob.copyInto(out, 1)
            return out
        }

        /** The 16 bytes of [u], most significant first. */
        public fun uuidBytes(u: UUID): ByteArray {
            val out = ByteArray(16)
            for (i in 0 until 8) out[i] = (u.mostSignificantBits ushr (56 - 8 * i)).toByte()
            for (i in 0 until 8) out[8 + i] = (u.leastSignificantBits ushr (56 - 8 * i)).toByte()
            return out
        }

        private fun uuidFrom(b: ByteArray, off: Int): UUID {
            var msb = 0L
            var lsb = 0L
            for (i in 0 until 8) msb = (msb shl 8) or (b[off + i].toLong() and 0xFF)
            for (i in 0 until 8) lsb = (lsb shl 8) or (b[off + 8 + i].toLong() and 0xFF)
            return UUID(msb, lsb)
        }
    }
}
