/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.link

import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The multi-link reliable layer (MLR) for one registered handle: a go-back-N protocol with 6-bit
 * sequence numbers, cumulative acknowledgements, a send window and exponential retransmission backoff.
 *
 * Packet header (2 bytes): `1hhh rrrr | rrss ssss` — flag bit, 3-bit handle, 6-bit request number
 * (the next sequence number this side expects, i.e. the cumulative ack), 6-bit sequence number.
 * A packet with no data after the header is a pure acknowledgement.
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/communicator/v2/MlrCommunicator.java (header layout,
 * masks, window 32, retransmit 1 s doubling to 20 s, ack after 5 packets or 20 ms, window halving on timeout).
 *
 * @param handle the registered handle (only its low 3 bits travel in the header).
 * @param maxPacket largest Bluetooth write; data per packet is `maxPacket - 2`.
 * @param scope owns the timers; cancel it to stop everything.
 * @param sendPacket writes one packet to the pipe.
 * @param onData receives in-order payload bytes (the service's byte stream).
 * @param onFailure called once if the peer stops acknowledging for [MAX_CONSECUTIVE_TIMEOUTS] timeouts.
 */
public class Mlr(
    handle: Int,
    maxPacket: Int,
    private val scope: CoroutineScope,
    private val sendPacket: suspend (ByteArray) -> Unit,
    private val onData: suspend (ByteArray) -> Unit,
    private val onFailure: suspend () -> Unit = {},
) {
    private val h = handle and 0x07
    private val lock = Mutex()

    @Volatile
    private var maxPacketSize = maxPacket
    private var lastSendAck = 0
    private var nextSendSeq = 0
    private var nextRcvSeq = 0
    private var lastRcvAck = 0
    private var maxUnacked = MAX_UNACKED
    private var rtoMs = INITIAL_RTO_MS
    private var consecutiveTimeouts = 0
    private val queue = ArrayDeque<ByteArray>()
    private val sentData = arrayOfNulls<ByteArray>(SEQ_MOD)
    private val sentReq = IntArray(SEQ_MOD)
    private var rtoJob: Job? = null
    private var ackJob: Job? = null

    @Volatile
    private var closed = false

    /** Current retransmission timeout in ms (for tests and diagnostics). */
    public val retransmitTimeoutMs: Int get() = rtoMs

    /** Current send window (for tests and diagnostics). */
    public val window: Int get() = maxUnacked

    /** Packets sent and not yet acknowledged. */
    public val inFlight: Int get() = (nextSendSeq - lastRcvAck + SEQ_MOD) % SEQ_MOD

    /** Fragments waiting for window space. */
    public val queued: Int get() = queue.size

    /** Changes the packet size used for messages queued from now on. */
    public fun setMaxPacket(size: Int) {
        maxPacketSize = size
    }

    /** Queues [message] (any length) for reliable delivery. Fails if closed or the queue is full. */
    public suspend fun send(message: ByteArray): Outcome<Unit> {
        if (message.isEmpty()) return Outcome.Ok(Unit)
        return lock.withLock {
            if (closed) return@withLock Outcome.Fail(SwError.Io("MLR closed"))
            val chunk = maxOf(1, maxPacketSize - HEADER)
            val pieces = (message.size + chunk - 1) / chunk
            if (queue.size + pieces > MAX_QUEUED_FRAGMENTS) return@withLock Outcome.Fail(SwError.Io("MLR queue full"))
            var off = 0
            while (off < message.size) {
                val end = minOf(message.size, off + chunk)
                queue.addLast(message.copyOfRange(off, end))
                off = end
            }
            pumpLocked()
            Outcome.Ok(Unit)
        }
    }

    /** Handles one packet from the watch whose flag bit is set and whose handle matches. */
    public suspend fun onPacket(packet: ByteArray) {
        if (packet.size < HEADER) return
        val b0 = packet[0].toInt() and 0xFF
        val b1 = packet[1].toInt() and 0xFF
        if (b0 and FLAG_MASK == 0) return
        if ((b0 and HANDLE_MASK) shr HANDLE_SHIFT != h) {
            SwLog.w(TAG, "MLR packet for handle ${(b0 and HANDLE_MASK) shr HANDLE_SHIFT}, expected $h")
            return
        }
        val req = ((b0 and REQ_MASK) shl 2) or ((b1 shr 6) and 0x03)
        val seq = b1 and SEQ_MASK
        val data = lock.withLock { if (closed) null else processLocked(req, seq, packet) }
        if (data != null) {
            try {
                onData(data)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.e(TAG, "MLR receiver failed", e)
            }
        }
        lock.withLock {
            if (closed) return@withLock
            if (data != null) scheduleAckLocked()
            pumpLocked()
        }
    }

    /** Stops timers and drops queued data. */
    public fun close() {
        closed = true
        rtoJob?.cancel()
        ackJob?.cancel()
    }

    private suspend fun processLocked(req: Int, seq: Int, packet: ByteArray): ByteArray? {
        if (req != lastRcvAck) processAckLocked(req)
        if (packet.size <= HEADER) return null
        if (seq != nextRcvSeq) {
            // Out of order or duplicate: drop it; the sender retransmits from our cumulative ack.
            SwLog.d(TAG) { "MLR out-of-sequence: expected $nextRcvSeq got $seq" }
            sendAckLocked()
            return null
        }
        nextRcvSeq = (nextRcvSeq + 1) % SEQ_MOD
        return packet.copyOfRange(HEADER, packet.size)
    }

    private fun processAckLocked(req: Int) {
        val acked = (req - lastRcvAck + SEQ_MOD) % SEQ_MOD
        val unacked = (nextSendSeq - lastRcvAck + SEQ_MOD) % SEQ_MOD
        if (acked > unacked) {
            SwLog.w(TAG, "MLR ack $req outside window [$lastRcvAck, $nextSendSeq)")
            return
        }
        rtoJob?.cancel()
        var i = lastRcvAck
        while (i != req) {
            sentData[i] = null
            i = (i + 1) % SEQ_MOD
        }
        lastRcvAck = req
        rtoMs = INITIAL_RTO_MS
        consecutiveTimeouts = 0
        maxUnacked = minOf(maxUnacked + acked, MAX_UNACKED)
        if (lastRcvAck != nextSendSeq) startRtoLocked()
    }

    private suspend fun scheduleAckLocked() {
        ackJob?.cancel()
        ackJob = null
        val unacked = (nextRcvSeq - lastSendAck + SEQ_MOD) % SEQ_MOD
        if (unacked >= ACK_AFTER_PACKETS) {
            sendAckLocked()
        } else {
            ackJob = scope.launch {
                delay(ACK_TIMEOUT_MS)
                lock.withLock {
                    ackJob = null
                    if (!closed) sendAckLocked()
                }
            }
        }
    }

    private suspend fun sendAckLocked() {
        ackJob?.cancel()
        ackJob = null
        sendPacket(packet(nextRcvSeq, 0, null))
        lastSendAck = nextRcvSeq
    }

    private suspend fun pumpLocked() {
        while (queue.isNotEmpty()) {
            val inFlightNow = (nextSendSeq - lastRcvAck + SEQ_MOD) % SEQ_MOD
            if (inFlightNow >= maxUnacked) return
            val data = queue.removeFirst()
            sentData[nextSendSeq] = data
            sentReq[nextSendSeq] = nextRcvSeq
            val pkt = packet(nextRcvSeq, nextSendSeq, data)
            nextSendSeq = (nextSendSeq + 1) % SEQ_MOD
            // Carrying the ack in a data packet counts as sending it.
            lastSendAck = nextRcvSeq
            if (inFlightNow == 0) startRtoLocked()
            sendPacket(pkt)
        }
    }

    private fun startRtoLocked() {
        rtoJob?.cancel()
        val wait = rtoMs.toLong()
        rtoJob = scope.launch {
            delay(wait)
            onRetransmitTimeout()
        }
    }

    private suspend fun onRetransmitTimeout() {
        var failed = false
        lock.withLock {
            if (closed || lastRcvAck == nextSendSeq) return@withLock
            consecutiveTimeouts++
            if (consecutiveTimeouts > MAX_CONSECUTIVE_TIMEOUTS) {
                SwLog.w(TAG, "MLR peer stopped acknowledging; giving up")
                closed = true
                failed = true
                return@withLock
            }
            rtoMs = minOf(rtoMs * 2, MAX_RTO_MS)
            maxUnacked = maxOf(1, maxUnacked / 2)
            var i = lastRcvAck
            while (i != nextSendSeq) {
                val d = sentData[i]
                if (d != null) sendPacket(packet(sentReq[i], i, d))
                i = (i + 1) % SEQ_MOD
            }
            startRtoLocked()
        }
        if (failed) onFailure()
    }

    private fun packet(req: Int, seq: Int, data: ByteArray?): ByteArray {
        val out = ByteArray(HEADER + (data?.size ?: 0))
        out[0] = (FLAG_MASK or (h shl HANDLE_SHIFT) or ((req shr 2) and REQ_MASK)).toByte()
        out[1] = (((req and 0x03) shl 6) or (seq and SEQ_MASK)).toByte()
        data?.copyInto(out, HEADER)
        return out
    }

    /** Protocol constants. */
    public companion object {
        /** Header flag bit marking an MLR packet. */
        public const val FLAG_MASK: Int = 0x80

        /** Handle bits in the first header byte. */
        public const val HANDLE_MASK: Int = 0x70

        /** Shift of the handle bits. */
        public const val HANDLE_SHIFT: Int = 4

        /** High 4 bits of the request number in the first byte. */
        public const val REQ_MASK: Int = 0x0F

        /** Sequence number bits in the second byte. */
        public const val SEQ_MASK: Int = 0x3F

        /** Sequence numbers wrap at 64. */
        public const val SEQ_MOD: Int = 64

        /** Header bytes per packet. */
        public const val HEADER: Int = 2

        /** Largest send window. */
        public const val MAX_UNACKED: Int = 0x20

        /** First retransmission timeout. */
        public const val INITIAL_RTO_MS: Int = 1_000

        /** Retransmission timeout cap. */
        public const val MAX_RTO_MS: Int = 20_000

        /** Delay before a lone acknowledgement is sent. */
        public const val ACK_TIMEOUT_MS: Long = 20

        /** Send an acknowledgement at once after this many unacknowledged packets. */
        public const val ACK_AFTER_PACKETS: Int = 5

        /** Our own bound: retransmission rounds without progress before the link is declared dead. */
        public const val MAX_CONSECUTIVE_TIMEOUTS: Int = 12

        /** Our own bound on queued fragments (bounded memory). */
        public const val MAX_QUEUED_FRAGMENTS: Int = 4_096

        private const val TAG = "garmin.mlr"
    }
}
