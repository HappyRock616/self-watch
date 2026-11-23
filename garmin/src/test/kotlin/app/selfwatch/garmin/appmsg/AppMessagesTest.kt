/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.selfwatch.garmin.appmsg

import app.selfwatch.garmin.gfdi.Bytes
import app.selfwatch.model.crc.Crc16
import app.selfwatch.model.fake.FakeBytePipe
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.CiqValue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** A scripted watch-app endpoint: acknowledges the phone's packets and decrypts what it receives. */
internal class ScriptedWatchApp(val pipe: FakeBytePipe) {
    var openStatus = 0
    var lastAckStatus = 0
    var silent = false
    val log = ArrayList<String>()
    val received = ArrayList<Pair<UUID, CiqValue>>()
    private var app: UUID? = null
    private var key: ByteArray? = null
    private var length = 0
    private val data = java.io.ByteArrayOutputStream()

    init {
        pipe.onWrite = { _, b -> onWrite(b) }
    }

    private fun ack(code: Int, status: Int = 0) = pipe.queueIncoming(byteArrayOf(0xF1.toByte(), 0x02, code.toByte(), status.toByte()))

    private fun onWrite(b: ByteArray) {
        if (silent) return
        when {
            b[0].toInt() == 0x00 && b[1].toInt() == 0x01 -> {
                log.add("OPEN")
                app = uuid(b, 2)
                pipe.queueIncoming(byteArrayOf(0x00, 0x01, openStatus.toByte()))
            }
            b[0].toInt() == 0x00 && b[1].toInt() == 0x02 -> {
                log.add("CLOSE")
                pipe.queueIncoming(byteArrayOf(0x00, 0x02, 0x00))
            }
            b[0] == 0xF1.toByte() && b[1].toInt() == 0x05 -> {
                log.add("KEY")
                key = b.copyOfRange(2, 18)
                assertEquals(0, b[18].toInt())
                ack(0x05)
            }
            b[0] == 0xF1.toByte() && b[1].toInt() == 0x00 -> {
                log.add("START")
                assertEquals(2, b[2].toInt())
                length = (b[3].toInt() and 0xFF) or ((b[4].toInt() and 0xFF) shl 8)
                data.reset()
                ack(0x00)
            }
            b[0] == 0xF1.toByte() && b[1].toInt() == 0x01 -> {
                log.add("LAST")
                val cipher = data.toByteArray()
                assertEquals(length, cipher.size)
                assertEquals(b[2].toInt(), (cipher.size + pipe.maxWrite - 2) / (pipe.maxWrite - 1))
                assertEquals(Crc16.compute(cipher), (b[3].toInt() and 0xFF) or ((b[4].toInt() and 0xFF) shl 8))
                val plain = Xxtea.decrypt(cipher, key!!)
                val pad = plain[0].toInt()
                received.add(app!! to (CiqCodec.decode(plain.copyOfRange(1, plain.size - pad)) as Outcome.Ok).value)
                ack(0x02, lastAckStatus)
            }
            else -> {
                val index = (b[0].toInt() and 0xFF) shr 4
                assertEquals(1, b[0].toInt() and 0x0F)
                assertEquals(data.size() / (pipe.maxWrite - 1), index)
                data.write(b, 1, b.size - 1)
            }
        }
    }

    companion object {
        fun uuid(b: ByteArray, off: Int): UUID {
            val bb = java.nio.ByteBuffer.wrap(b, off, 16)
            return UUID(bb.long, bb.long)
        }
    }
}

class AppMessagesTest {
    private val app = UUID.fromString("12345678-1234-5678-9abc-def012345678")
    private val other = UUID.fromString("87654321-4321-8765-cba9-876543210fed")
    private val msg = CiqValue.Dict(mapOf(CiqValue.Str("m") to CiqValue.Arr(listOf(CiqValue.Num(1), CiqValue.Num(2))), CiqValue.Str("a") to CiqValue.Num(7)))

    private fun fixedRandom(): (ByteArray) -> Unit {
        var n = 0
        return { b -> for (i in b.indices) b[i] = (n++ * 31 + 7).toByte() }
    }

    @Test
    fun sendsOneMessage() = runTest {
        val pipe = FakeBytePipe(20)
        val watch = ScriptedWatchApp(pipe)
        val am = AppMessages(pipe, backgroundScope, fixedRandom())
        assertEquals(Outcome.Ok(Unit), am.send(app, msg))
        assertEquals(listOf("OPEN", "KEY", "START", "LAST", "CLOSE"), watch.log)
        assertEquals(listOf(app to msg), watch.received)
        val open = pipe.writes()[0]
        assertEquals("0001" + "12345678123456789abcdef012345678" + "00", Bytes.hex(open))
        assertEquals("000201", Bytes.hex(pipe.writes().last()))
    }

    @Test
    fun reusesTheConnectionForTheSameApp() = runTest {
        val pipe = FakeBytePipe(20)
        val watch = ScriptedWatchApp(pipe)
        val am = AppMessages(pipe, backgroundScope, fixedRandom())
        val a = async { am.send(app, msg) }
        val b = async { am.send(app, CiqValue.Str("two")) }
        val c = async { am.send(other, CiqValue.Num(3)) }
        assertEquals(Outcome.Ok(Unit), a.await())
        assertEquals(Outcome.Ok(Unit), b.await())
        assertEquals(Outcome.Ok(Unit), c.await())
        assertEquals(
            listOf("OPEN", "KEY", "START", "LAST", "KEY", "START", "LAST", "CLOSE", "OPEN", "KEY", "START", "LAST", "CLOSE"),
            watch.log,
        )
        assertEquals(listOf(app, app, other), watch.received.map { it.first })
    }

    @Test
    fun refusedOpenFailsTheMessage() = runTest {
        val pipe = FakeBytePipe(20)
        val watch = ScriptedWatchApp(pipe).apply { openStatus = 3 }
        val am = AppMessages(pipe, backgroundScope, fixedRandom())
        val r = am.send(app, msg)
        assertTrue(r is Outcome.Fail)
        assertEquals(listOf("OPEN"), watch.log)
    }

    @Test
    fun ackErrorFailsAndCloses() = runTest {
        val pipe = FakeBytePipe(20)
        val watch = ScriptedWatchApp(pipe).apply { lastAckStatus = 1 }
        val am = AppMessages(pipe, backgroundScope, fixedRandom())
        val r = am.send(app, msg) as Outcome.Fail
        assertTrue(r.error is SwError.Protocol)
        assertEquals("CLOSE", watch.log.last())
    }

    @Test
    fun silentWatchTimesOutAfter5s() = runTest {
        val pipe = FakeBytePipe(20)
        ScriptedWatchApp(pipe).apply { silent = true }
        val am = AppMessages(pipe, backgroundScope, fixedRandom())
        val start = testScheduler.currentTime
        val r = am.send(app, msg) as Outcome.Fail
        assertTrue(r.error is SwError.Timeout)
        assertTrue(testScheduler.currentTime - start >= 5_000)
    }

    @Test
    fun tooLargeMessageIsRefusedBeforeSending() = runTest {
        val pipe = FakeBytePipe(20)
        val watch = ScriptedWatchApp(pipe)
        val am = AppMessages(pipe, backgroundScope, fixedRandom())
        assertEquals(266, am.maxCipherBytes)
        val big = CiqValue.Str("x".repeat(400))
        val r = am.send(app, big) as Outcome.Fail
        assertTrue(r.error is SwError.BadInput)
        assertEquals(listOf("OPEN", "CLOSE"), watch.log)
        // The next message still goes through.
        assertEquals(Outcome.Ok(Unit), am.send(app, msg))
    }

    @Test
    fun receivesAMessageFromTheWatch() = runTest {
        val pipe = FakeBytePipe(20)
        val am = AppMessages(pipe, backgroundScope, fixedRandom())
        val got = async { am.received.first() }
        // INBOUND_OPEN → phone ACKs; KEY_REQUEST → phone sends its key.
        pipe.queueIncoming(byteArrayOf(0x00, 0x03) + AppMessages.uuidBytes(app))
        pipe.queueIncoming(byteArrayOf(0xF1.toByte(), 0x06))
        testScheduler.runCurrent()
        val keyPkt = pipe.writes().first { it[0] == 0xF1.toByte() && it[1].toInt() == 0x05 }
        assertEquals(2, keyPkt[18].toInt()) // inbound key flag
        assertEquals("00030001", Bytes.hex(pipe.writes()[0]))
        val key = keyPkt.copyOfRange(2, 18)
        val cipher = Xxtea.encrypt(AppMessages.pad(CiqCodec.encode(msg)), key)
        val len = cipher.size
        pipe.queueIncoming(byteArrayOf(0xF1.toByte(), 0x00, 0x02, len.toByte(), (len shr 8).toByte(), 0, 0))
        val frag = 19
        val count = (len + frag - 1) / frag
        for (i in 0 until count) {
            pipe.queueIncoming(byteArrayOf(((i shl 4) or 1).toByte()) + cipher.copyOfRange(i * frag, minOf(len, (i + 1) * frag)))
        }
        val crc = Crc16.compute(cipher)
        pipe.queueIncoming(byteArrayOf(0xF1.toByte(), 0x01, count.toByte(), crc.toByte(), (crc shr 8).toByte()))
        assertEquals(AppMessage(app, msg), got.await())
        val acks = pipe.writes().filter { it[0] == 0xF1.toByte() && it[1].toInt() == 0x02 }.map { Bytes.hex(it) }
        assertEquals(listOf("f1020000ff", "f1020200" + "%02x".format(count)), acks)
    }

    @Test
    fun corruptInboundMessageIsDropped() = runTest {
        val pipe = FakeBytePipe(20)
        val am = AppMessages(pipe, backgroundScope, fixedRandom())
        pipe.queueIncoming(byteArrayOf(0x00, 0x03) + AppMessages.uuidBytes(app))
        pipe.queueIncoming(byteArrayOf(0xF1.toByte(), 0x06))
        pipe.queueIncoming(byteArrayOf(0xF1.toByte(), 0x00, 0x02, 8, 0, 0, 0))
        pipe.queueIncoming(byteArrayOf(0x01, 1, 2, 3, 4, 5, 6, 7, 8))
        pipe.queueIncoming(byteArrayOf(0xF1.toByte(), 0x01, 1, 0x12, 0x34)) // wrong CRC
        // Then a fragment with no transfer, and junk.
        pipe.queueIncoming(byteArrayOf(0x21, 9))
        pipe.queueIncoming(byteArrayOf(0x7))
        kotlinx.coroutines.delay(1_000)
        assertTrue(am.received.let { f -> kotlinx.coroutines.withTimeoutOrNull(10) { f.first() } } == null)
        assertArrayEquals(byteArrayOf(0x00, 0x03, 0x00, 0x01), pipe.writes()[0])
    }
}
