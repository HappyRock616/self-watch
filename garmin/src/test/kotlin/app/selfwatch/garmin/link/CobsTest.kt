/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.link

import app.selfwatch.garmin.gfdi.Bytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CobsTest {
    private fun hex(s: String) = Bytes.fromHex(s)

    /** Classic COBS vectors, wrapped in Garmin's leading and trailing 0x00. */
    @Test
    fun encodesKnownVectors() {
        val vectors = listOf(
            "00" to "00 01 01 00",
            "0000" to "00 01 01 01 00",
            "001100" to "00 01 02 11 01 00",
            "11220033" to "00 03 11 22 02 33 00",
            "11223344" to "00 05 11 22 33 44 00",
            "11000000" to "00 02 11 01 01 01 00",
        )
        for ((plain, enc) in vectors) {
            assertArrayEquals("encode $plain", hex(enc), Cobs.encode(hex(plain)))
            val body = hex(enc).let { it.copyOfRange(1, it.size - 1) }
            assertArrayEquals("decode $plain", hex(plain), Cobs.decodeBody(body))
        }
    }

    @Test
    fun longRunsUseFfBlocks() {
        val run254 = ByteArray(254) { (it + 1).toByte() }
        val enc = Cobs.encode(run254)
        assertEquals(0, enc[0].toInt())
        assertEquals(0xFF, enc[1].toInt() and 0xFF)
        // 254 bytes then the reference's trailing empty block 01, then the delimiter.
        assertEquals(254 + 4, enc.size)
        assertEquals(1, enc[enc.size - 2].toInt())
        assertArrayEquals(run254, Cobs.decodeBody(enc, 1, enc.size - 1))
        val run600 = ByteArray(600) { if (it % 300 == 299) 0 else 7 }
        val e2 = Cobs.encode(run600)
        assertArrayEquals(run600, Cobs.decodeBody(e2, 1, e2.size - 1))
    }

    @Test
    fun roundTripsRandomData() {
        val rnd = Random(42)
        repeat(500) {
            val data = ByteArray(rnd.nextInt(1, 1200)) { if (rnd.nextInt(5) == 0) 0 else rnd.nextInt(256).toByte() }
            val enc = Cobs.encode(data)
            assertEquals(0, enc.first().toInt())
            assertEquals(0, enc.last().toInt())
            assertTrue((1 until enc.size - 1).none { enc[it].toInt() == 0 })
            assertArrayEquals(data, Cobs.decodeBody(enc, 1, enc.size - 1))
        }
    }

    @Test
    fun decoderReassemblesSplitAndJoinedFrames() {
        val a = byteArrayOf(1, 0, 2, 3)
        val b = byteArrayOf(9, 9, 0, 0)
        val stream = Cobs.encode(a) + Cobs.encode(b)
        val d = CobsDecoder()
        val out = ArrayList<ByteArray>()
        // Feed in odd-sized notifications.
        var i = 0
        while (i < stream.size) {
            val n = minOf(3, stream.size - i)
            out.addAll(d.feed(stream.copyOfRange(i, i + n)))
            i += n
        }
        assertEquals(2, out.size)
        assertArrayEquals(a, out[0])
        assertArrayEquals(b, out[1])
        // Both frames in one notification.
        val both = CobsDecoder().feed(stream)
        assertEquals(2, both.size)
    }

    @Test
    fun decoderRecoversFromLostStartAndGarbage() {
        val good = byteArrayOf(5, 6, 7)
        val enc = Cobs.encode(good)
        val d = CobsDecoder()
        // A frame whose code byte overruns, then a good frame.
        val bad = byteArrayOf(0, 0x09, 1, 2, 0)
        val out = d.feed(bad) + d.feed(enc)
        assertEquals(1, out.size)
        assertArrayEquals(good, out[0])
        // A tail without its start (lost notification) still decodes as a frame or is dropped, never wedges.
        val tail = enc.copyOfRange(2, enc.size)
        d.feed(tail)
        assertArrayEquals(good, d.feed(enc).single())
    }

    @Test
    fun decoderDropsOversizedFrames() {
        val d = CobsDecoder(maxFrame = 100)
        val big = Cobs.encode(ByteArray(500) { 1 })
        assertTrue(d.feed(big).isEmpty())
        val small = byteArrayOf(1, 2)
        assertArrayEquals(small, d.feed(Cobs.encode(small)).single())
    }
}
