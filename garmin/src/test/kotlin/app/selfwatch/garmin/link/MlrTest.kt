/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.selfwatch.garmin.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import kotlin.random.Random

class MlrTest {
    private class Wire(val scope: CoroutineScope, handle: Int = 3, maxPacket: Int = 20) {
        val sent: MutableList<ByteArray> = Collections.synchronizedList(ArrayList())
        val got: MutableList<ByteArray> = Collections.synchronizedList(ArrayList())
        var failed = false
        val mlr = Mlr(handle, maxPacket, scope, sendPacket = { sent.add(it) }, onData = { got.add(it) }, onFailure = { failed = true })
    }

    /** A packet from the peer with request number [req] and sequence number [seq]. */
    private fun peer(req: Int, seq: Int, data: ByteArray = byteArrayOf(0x42), handle: Int = 3): ByteArray {
        val out = ByteArray(2 + data.size)
        out[0] = (0x80 or (handle shl 4) or ((req shr 2) and 0x0F)).toByte()
        out[1] = (((req and 3) shl 6) or (seq and 0x3F)).toByte()
        data.copyInto(out, 2)
        return out
    }

    private fun req(p: ByteArray): Int = ((p[0].toInt() and 0x0F) shl 2) or ((p[1].toInt() and 0xFF) shr 6)
    private fun seq(p: ByteArray): Int = p[1].toInt() and 0x3F

    @Test
    fun headerLayout() = runTest {
        val w = Wire(backgroundScope)
        w.mlr.send(byteArrayOf(1, 2, 3))
        val p = w.sent.single()
        assertEquals(0xB0, p[0].toInt() and 0xFF) // flag, handle 3, req 0
        assertEquals(0x00, p[1].toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(1, 2, 3), p.copyOfRange(2, p.size))
        // After 5 in-order packets from the peer the ack goes out at once, carrying req 5.
        for (s in 0 until 5) w.mlr.onPacket(peer(1, s))
        val ack = w.sent.last()
        assertEquals(2, ack.size)
        assertEquals(5, req(ack))
        assertEquals(0xB1, ack[0].toInt() and 0xFF)
        assertEquals(0x40, ack[1].toInt() and 0xFF)
        assertEquals(5, w.got.size)
    }

    @Test
    fun splitsLongMessagesIntoPacketSizedFragments() = runTest {
        val w = Wire(backgroundScope, maxPacket = 10)
        val msg = ByteArray(25) { it.toByte() }
        w.mlr.send(msg)
        assertEquals(listOf(10, 10, 10, 3), w.sent.map { it.size }) // 8 data bytes per packet
        assertEquals(listOf(0, 1, 2, 3), w.sent.map { seq(it) })
        assertArrayEquals(msg, w.sent.fold(ByteArray(0)) { acc, p -> acc + p.copyOfRange(2, p.size) })
    }

    @Test
    fun sendWindowIs32AndAcksOpenIt() = runTest {
        val w = Wire(backgroundScope, maxPacket = 3) // one data byte per packet
        w.mlr.send(ByteArray(40) { it.toByte() })
        assertEquals(32, w.sent.size)
        assertEquals(8, w.mlr.queued)
        assertEquals(32, w.mlr.inFlight)
        w.mlr.onPacket(peer(10, 0, ByteArray(0))) // pure ack of 0..9
        assertEquals(40, w.sent.size)
        assertEquals(30, w.mlr.inFlight)
        assertEquals(0, w.mlr.queued)
    }

    @Test
    fun retransmitsWithBackoffAndHalvesWindow() = runTest {
        val w = Wire(backgroundScope, maxPacket = 3)
        w.mlr.send(byteArrayOf(1, 2, 3))
        assertEquals(3, w.sent.size)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(3, w.sent.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(6, w.sent.size)
        assertEquals(listOf(0, 1, 2), w.sent.drop(3).map { seq(it) })
        assertEquals(2_000, w.mlr.retransmitTimeoutMs)
        assertEquals(16, w.mlr.window)
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(9, w.sent.size)
        assertEquals(4_000, w.mlr.retransmitTimeoutMs)
        // An ack resets the timeout and regrows the window.
        w.mlr.onPacket(peer(3, 0, ByteArray(0)))
        assertEquals(1_000, w.mlr.retransmitTimeoutMs)
        assertEquals(0, w.mlr.inFlight)
        assertEquals(11, w.mlr.window)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(9, w.sent.size) // nothing left to retransmit
    }

    @Test
    fun retransmitTimeoutIsCappedAt20s() = runTest {
        val w = Wire(backgroundScope)
        w.mlr.send(byteArrayOf(1))
        repeat(7) {
            advanceTimeBy(w.mlr.retransmitTimeoutMs.toLong() + 1)
            runCurrent()
        }
        assertEquals(20_000, w.mlr.retransmitTimeoutMs)
    }

    @Test
    fun givesUpWhenPeerNeverAcks() = runTest {
        val w = Wire(backgroundScope)
        w.mlr.send(byteArrayOf(1))
        advanceTimeBy(20_000L * (Mlr.MAX_CONSECUTIVE_TIMEOUTS + 2))
        runCurrent()
        assertTrue(w.failed)
    }

    @Test
    fun lonePacketIsAckedAfter20ms() = runTest {
        val w = Wire(backgroundScope)
        w.mlr.onPacket(peer(0, 0))
        assertEquals(1, w.got.size)
        assertTrue(w.sent.isEmpty())
        advanceTimeBy(19)
        runCurrent()
        assertTrue(w.sent.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, req(w.sent.single()))
    }

    @Test
    fun outOfOrderAndDuplicatePacketsAreDroppedAndReacked() = runTest {
        val w = Wire(backgroundScope)
        w.mlr.onPacket(peer(0, 1, byteArrayOf(1))) // early
        assertTrue(w.got.isEmpty())
        assertEquals(0, req(w.sent.last())) // re-ack: still expecting 0
        w.mlr.onPacket(peer(0, 0, byteArrayOf(0)))
        w.mlr.onPacket(peer(0, 0, byteArrayOf(0))) // duplicate
        assertEquals(1, w.got.size)
        assertEquals(1, req(w.sent.last()))
        w.mlr.onPacket(peer(0, 1, byteArrayOf(1)))
        assertEquals(2, w.got.size)
        assertArrayEquals(byteArrayOf(1), w.got[1])
    }

    @Test
    fun ackOutsideWindowIsIgnored() = runTest {
        val w = Wire(backgroundScope)
        w.mlr.send(byteArrayOf(1))
        w.mlr.onPacket(peer(40, 0, ByteArray(0)))
        assertEquals(1, w.mlr.inFlight)
    }

    @Test
    fun wrongHandleIsIgnored() = runTest {
        val w = Wire(backgroundScope)
        w.mlr.onPacket(peer(0, 0, handle = 2))
        assertTrue(w.got.isEmpty())
    }

    @Test
    fun sequenceNumbersWrapAt64() = runTest {
        val w = Wire(backgroundScope, maxPacket = 3)
        repeat(3) { round ->
            w.mlr.send(ByteArray(30) { 1 })
            w.mlr.onPacket(peer(((round + 1) * 30) % 64, 0, ByteArray(0)))
        }
        assertEquals(0, w.mlr.inFlight)
        assertEquals(listOf(62, 63, 0, 1), w.sent.subList(62, 66).map { seq(it) })
    }

    /** Two reliable endpoints over a link that drops, delays and reorders packets. */
    @Test
    fun deliversEverythingInOrderOverALossyReorderingLink() = runTest { lossyPair(Random(7)) }

    @Test
    fun lossyPairOtherSeeds() = runTest {
        for (seed in 1..5) lossyPair(Random(seed * 101))
    }

    private suspend fun TestScope.lossyPair(rnd: Random) {
        val bGot = Collections.synchronizedList(ArrayList<ByteArray>())
        val aGot = Collections.synchronizedList(ArrayList<ByteArray>())
        lateinit var a: Mlr
        lateinit var b: Mlr
        fun link(to: () -> Mlr): suspend (ByteArray) -> Unit = { p ->
            if (rnd.nextInt(100) >= 15) {
                val d = rnd.nextLong(1, 40)
                backgroundScope.launch {
                    delay(d)
                    to().onPacket(p)
                }
            }
        }
        a = Mlr(1, 20, backgroundScope, link { b }, { aGot.add(it) })
        b = Mlr(1, 20, backgroundScope, link { a }, { bGot.add(it) })
        val msgs = (0 until 150).map { i -> ByteArray(rnd.nextInt(1, 60)) { (i + it).toByte() } }
        for (m in msgs) a.send(m)
        b.send(ByteArray(100) { 9 })
        withTimeout(600_000) {
            while (bGot.sumOf { it.size } < msgs.sumOf { it.size } || aGot.sumOf { it.size } < 100) delay(100)
        }
        val all = bGot.fold(ByteArray(0)) { acc, x -> acc + x }
        assertArrayEquals(msgs.fold(ByteArray(0)) { acc, x -> acc + x }, all)
        a.close()
        b.close()
    }
}
