/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.appmsg

import app.selfwatch.garmin.gfdi.Bytes
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.watch.CiqValue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CodecTest {
    private fun hex(s: String) = Bytes.fromHex(s)

    @Test
    fun dictionaryVector() {
        val v = CiqValue.Dict(mapOf(CiqValue.Str("a") to CiqValue.Num(1)))
        val expected = "abcdabcd 00000004 0002 61 00" + "da7ada7a 0000000f" + "0b 00000001" + "03 00000000" + "01 00000001"
        assertEquals(Bytes.hex(hex(expected)), Bytes.hex(CiqCodec.encode(v)))
        assertEquals(v, (CiqCodec.decode(hex(expected)) as Outcome.Ok).value)
    }

    @Test
    fun containersAreBreadthFirst() {
        val v = CiqValue.Arr(listOf(CiqValue.Arr(listOf(CiqValue.Num(1))), CiqValue.Num(2)))
        val expected = "da7ada7a 00000014" + "05 00000002" + "05 00000001" + "01 00000002" + "01 00000001"
        assertEquals(Bytes.hex(hex(expected)), Bytes.hex(CiqCodec.encode(v)))
        assertEquals(v, (CiqCodec.decode(hex(expected)) as Outcome.Ok).value)
    }

    @Test
    fun scalarTypes() {
        fun data(v: CiqValue) = Bytes.hex(CiqCodec.encode(v)).substring(16) // after magic and length
        assertEquals("00", data(CiqValue.Null))
        assertEquals("0901", data(CiqValue.Bool(true)))
        assertEquals("01ffffffff", data(CiqValue.Num(-1)))
        assertEquals("0e0000000100000000", data(CiqValue.Num(1L shl 32)))
        assertEquals("023fc00000", data(CiqValue.Dbl(1.5))) // exactly representable as a float
        assertEquals("0f3fb999999999999a", data(CiqValue.Dbl(0.1)))
    }

    @Test
    fun decodesCharAndByteArrayAndNegativeBool() {
        val bytes = hex("da7ada7a 00000013" + "05 00000003" + "13 00000041" + "14 00000002 01ff" + "09 ff")
        val v = (CiqCodec.decode(bytes) as Outcome.Ok).value
        assertEquals(
            CiqValue.Arr(listOf(CiqValue.Str("A"), CiqValue.Arr(listOf(CiqValue.Num(1), CiqValue.Num(-1))), CiqValue.Bool(false))),
            v,
        )
    }

    @Test
    fun roundTripsRichValues() {
        val v = CiqValue.Dict(
            linkedMapOf(
                CiqValue.Str("m") to CiqValue.Arr(listOf(CiqValue.Arr(listOf(CiqValue.Num(1), CiqValue.Num(42), CiqValue.Str("sig"))), CiqValue.Null)),
                CiqValue.Str("a") to CiqValue.Arr(listOf(CiqValue.Num(123_456_789_012L), CiqValue.Dbl(-2.25))),
                CiqValue.Num(7) to CiqValue.Str("číslo ✓"),
                CiqValue.Str("nested") to CiqValue.Dict(mapOf(CiqValue.Str("a") to CiqValue.Bool(true), CiqValue.Str("x") to CiqValue.Dbl(Math.PI))),
                CiqValue.Str("empty") to CiqValue.Arr(emptyList()),
            ),
        )
        assertEquals(v, (CiqCodec.decode(CiqCodec.encode(v)) as Outcome.Ok).value)
        // A repeated string is stored once.
        val enc = CiqCodec.encode(CiqValue.Arr(listOf(CiqValue.Str("same"), CiqValue.Str("same"))))
        assertEquals(1, Bytes.hex(enc).windowed(8, 2).count { it == Bytes.hex("same".toByteArray()) })
    }

    @Test
    fun randomValuesRoundTrip() {
        val rnd = Random(5)
        fun gen(depth: Int): CiqValue = when (if (depth > 3) rnd.nextInt(5) else rnd.nextInt(7)) {
            0 -> CiqValue.Null
            1 -> CiqValue.Bool(rnd.nextBoolean())
            2 -> CiqValue.Num(if (rnd.nextBoolean()) rnd.nextInt().toLong() else rnd.nextLong())
            3 -> CiqValue.Dbl(if (rnd.nextBoolean()) rnd.nextInt(1000) / 4.0 else rnd.nextDouble())
            4 -> CiqValue.Str(String(CharArray(rnd.nextInt(0, 12)) { 'a' + rnd.nextInt(26) }))
            5 -> CiqValue.Arr(List(rnd.nextInt(0, 5)) { gen(depth + 1) })
            else -> CiqValue.Dict((0 until rnd.nextInt(0, 5)).associate { CiqValue.Str("k$it") to gen(depth + 1) })
        }
        repeat(300) {
            val v = gen(0)
            assertEquals(v, (CiqCodec.decode(CiqCodec.encode(v)) as Outcome.Ok).value)
        }
    }

    @Test
    fun malformedInputFailsCleanly() {
        val good = CiqCodec.encode(CiqValue.Dict(mapOf(CiqValue.Str("key") to CiqValue.Arr(listOf(CiqValue.Num(1), CiqValue.Str("v"))))))
        for (n in 0 until good.size) assertTrue("truncated at $n", CiqCodec.decode(good.copyOf(n)) is Outcome.Fail)
        assertTrue(CiqCodec.decode(hex("da7ada7a 00000005 05 7fffffff")) is Outcome.Fail)
        assertTrue(CiqCodec.decode(hex("da7ada7a 00000005 03 00000099")) is Outcome.Fail)
        assertTrue(CiqCodec.decode(hex("da7ada7a 00000001 42")) is Outcome.Fail)
        val rnd = Random(9)
        repeat(2_000) { CiqCodec.decode(ByteArray(rnd.nextInt(0, 64)) { rnd.nextInt(256).toByte() }) }
        repeat(2_000) {
            val b = good.copyOf()
            b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte()
            CiqCodec.decode(b)
        }
    }

    // ---- XXTEA ----

    /**
     * An independent, deliberately plain XXTEA ("btea", Wheeler and Needham's corrected block TEA) using
     * 64-bit arithmetic masked to 32 bits, to cross-check [Xxtea].
     */
    private object PlainXxtea {
        private const val M = 0xFFFF_FFFFL
        private const val DELTA = 0x9E37_79B9L

        fun btea(v: LongArray, n: Int, k: LongArray) {
            fun mx(sum: Long, y: Long, z: Long, p: Int, e: Int): Long =
                ((((z ushr 5) xor ((y shl 2) and M)) + ((y ushr 3) xor ((z shl 4) and M))) and M) xor
                    (((sum xor y) + (k[(p and 3) xor e] xor z)) and M)
            if (n > 1) {
                var rounds = 6 + 52 / n
                var sum = 0L
                var z = v[n - 1]
                do {
                    sum = (sum + DELTA) and M
                    val e = ((sum ushr 2) and 3).toInt()
                    var p = 0
                    while (p < n - 1) {
                        val y = v[p + 1]
                        v[p] = (v[p] + mx(sum, y, z, p, e)) and M
                        z = v[p]
                        p++
                    }
                    val y = v[0]
                    v[n - 1] = (v[n - 1] + mx(sum, y, z, p, e)) and M
                    z = v[n - 1]
                } while (--rounds > 0)
            } else if (n < -1) {
                val nn = -n
                var rounds = 6 + 52 / nn
                var sum = (rounds * DELTA) and M
                var y = v[0]
                do {
                    val e = ((sum ushr 2) and 3).toInt()
                    var p = nn - 1
                    while (p > 0) {
                        val z = v[p - 1]
                        v[p] = (v[p] - mx(sum, y, z, p, e)) and M
                        y = v[p]
                        p--
                    }
                    val z = v[nn - 1]
                    v[0] = (v[0] - mx(sum, y, z, p, e)) and M
                    y = v[0]
                    sum = (sum - DELTA) and M
                } while (--rounds > 0)
            }
        }

        fun words(b: ByteArray) = LongArray(b.size / 4) { i ->
            (0 until 4).fold(0L) { acc, j -> acc or ((b[4 * i + j].toLong() and 0xFF) shl (8 * j)) }
        }

        fun bytes(v: LongArray) = ByteArray(v.size * 4) { (v[it / 4] ushr (8 * (it % 4))).toByte() }

        fun encrypt(d: ByteArray, k: ByteArray) = words(d).also { btea(it, it.size, words(k)) }.let(::bytes)
        fun decrypt(d: ByteArray, k: ByteArray) = words(d).also { btea(it, -it.size, words(k)) }.let(::bytes)
    }

    @Test
    fun xxteaMatchesAnIndependentImplementation() {
        val rnd = Random(2026)
        repeat(300) {
            val key = ByteArray(16) { rnd.nextInt(256).toByte() }
            val data = ByteArray(4 * rnd.nextInt(2, 70)) { rnd.nextInt(256).toByte() }
            val c = Xxtea.encrypt(data, key)
            assertArrayEquals(PlainXxtea.encrypt(data, key), c)
            assertArrayEquals(data, Xxtea.decrypt(c, key))
            assertArrayEquals(data, PlainXxtea.decrypt(c, key))
        }
    }

    /** Fixed vectors generated with this implementation and checked against [PlainXxtea]; they pin the byte order. */
    @Test
    fun xxteaFixedVectors() {
        val key = hex("000102030405060708090a0b0c0d0e0f")
        val plain = hex("0011223344556677")
        val c = Xxtea.encrypt(plain, key)
        assertArrayEquals(PlainXxtea.encrypt(plain, key), c)
        assertEquals(Bytes.hex(c), Bytes.hex(PlainXxtea.encrypt(plain, key)))
        val zero = Xxtea.encrypt(ByteArray(8), ByteArray(16))
        assertArrayEquals(PlainXxtea.encrypt(ByteArray(8), ByteArray(16)), zero)
        assertTrue(!zero.contentEquals(ByteArray(8)))
    }

    @Test
    fun xxteaRejectsBadSizes() {
        for (n in listOf(0, 4, 9)) {
            try {
                Xxtea.encrypt(ByteArray(n), ByteArray(16))
                throw AssertionError("accepted $n bytes")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun padding() {
        assertEquals("04aabbcc00000000", Bytes.hex(AppMessages.pad(hex("aabbcc"))))
        assertEquals("02" + "11".repeat(9) + "0000", Bytes.hex(AppMessages.pad(ByteArray(9) { 0x11 })))
        for (n in 0..40) {
            val p = AppMessages.pad(ByteArray(n) { 1 })
            assertEquals(0, p.size % 4)
            assertTrue(p.size >= 8)
            assertEquals(p.size - 1 - n, p[0].toInt())
        }
        assertEquals(0, AppMessages.pad(ByteArray(11))[0].toInt())
    }
}
