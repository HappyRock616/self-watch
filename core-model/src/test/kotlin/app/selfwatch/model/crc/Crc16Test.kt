/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.model.crc

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class Crc16Test {
    /** Independent bit-at-a-time CRC-16/ARC (reflected poly 0xA001, init 0). */
    private fun bitwise(bytes: ByteArray, init: Int = 0): Int {
        var crc = init
        for (b in bytes) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc and 0xFFFF
    }

    @Test fun standardCheckValue() {
        // The catalogued check value of CRC-16/ARC for "123456789".
        val data = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0xBB3D, Crc16.compute(data))
        assertEquals(0xBB3D, bitwise(data))
    }

    @Test fun emptyIsZero() {
        assertEquals(0, Crc16.compute(ByteArray(0)))
    }

    @Test fun shortAsciiMatchesBitwise() {
        for (s in listOf("A", "Self Watch", ".FIT", "The quick brown fox")) {
            val b = s.toByteArray(Charsets.US_ASCII)
            assertEquals(s, bitwise(b), Crc16.compute(b))
        }
    }

    @Test fun randomDataMatchesBitwise() {
        val rnd = Random(42)
        repeat(200) {
            val b = rnd.nextBytes(rnd.nextInt(0, 300))
            assertEquals(bitwise(b), Crc16.compute(b))
        }
    }

    @Test fun incrementalAndRangeAgree() {
        val b = "header-and-body".toByteArray(Charsets.US_ASCII)
        val part = Crc16.compute(b, 0, 6)
        assertEquals(Crc16.compute(b), Crc16.compute(b, 6, b.size - 6, initial = part))
        assertEquals(bitwise(b.copyOfRange(3, 9)), Crc16.compute(b, 3, 6))
    }

    @Test fun appendingCrcLittleEndianGivesZero() {
        // FIT property: CRC over data followed by its CRC (little-endian) is 0.
        val b = "123456789".toByteArray(Charsets.US_ASCII)
        val crc = Crc16.compute(b)
        val withCrc = b + byteArrayOf((crc and 0xFF).toByte(), (crc shr 8).toByte())
        assertEquals(0, Crc16.compute(withCrc))
    }
}
