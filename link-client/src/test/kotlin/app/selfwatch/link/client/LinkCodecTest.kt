/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package app.selfwatch.link.client

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class LinkCodecTest {

    private fun roundTrip(v: Any?): Any? = LinkCodec.decode(LinkCodec.encode(v))

    private fun assertMalformed(bytes: ByteArray) {
        try {
            LinkCodec.decode(bytes)
            fail("expected LinkCodecException for ${bytes.joinToString { "%02x".format(it) }}")
        } catch (_: LinkCodecException) {
            // expected
        }
    }

    private fun assertEncodeRejected(v: Any?) {
        try {
            LinkCodec.encode(v)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    private fun bytes(vararg b: Int): ByteArray = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun scalarsRoundTrip() {
        assertNull(roundTrip(null))
        assertEquals(true, roundTrip(true))
        assertEquals(false, roundTrip(false))
        for (n in listOf(0L, 1L, -1L, 63L, -64L, 64L, 300L, Int.MAX_VALUE.toLong(), Int.MIN_VALUE.toLong(),
            Long.MAX_VALUE, Long.MIN_VALUE)) {
            assertEquals(n, roundTrip(n))
        }
        for (d in listOf(0.0, -0.0, 1.5, -2.25e300, Double.MIN_VALUE, Double.MAX_VALUE,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(d.toRawBits(), (roundTrip(d) as Double).toRawBits())
        }
        assertTrue((roundTrip(Double.NaN) as Double).isNaN())
        assertEquals("", roundTrip(""))
        assertEquals("hello", roundTrip("hello"))
    }

    @Test
    fun javaNumberTypesWidenToLongAndDouble() {
        assertEquals(7L, roundTrip(7))
        assertEquals(7L, roundTrip(7.toShort()))
        assertEquals(7L, roundTrip(7.toByte()))
        assertEquals(0.5, roundTrip(0.5f))
        assertEquals("x", roundTrip('x'))
        assertEquals("sb", roundTrip(StringBuilder("sb")))
    }

    @Test
    fun unicodeRoundTrips() {
        val s = "vívoactive 5 · 日本語 · emoji 🚀🏃 · null\u0000char"
        assertEquals(s, roundTrip(s))
        assertEquals(mapOf(s to s), roundTrip(mapOf(s to s)))
    }

    @Test
    fun containersRoundTripInOrder() {
        val msg = linkedMapOf<Any?, Any?>(
            "z" to 1L,
            "a" to listOf(1L, 2.5, "three", null, true, listOf<Any?>(), mapOf<Any?, Any?>()),
            7L to "seven",
            null to false,
            true to 0.25,
            1.5 to linkedMapOf("inner" to listOf(listOf(listOf("deep")))),
        )
        val back = roundTrip(msg) as Map<*, *>
        assertEquals(msg, back)
        assertEquals(msg.keys.toList(), back.keys.toList())
    }

    @Test
    fun goldenBytesFreezeTheFormat() {
        // {"a": 1, "b": [true, null, -1]}
        val enc = LinkCodec.encode(linkedMapOf("a" to 1, "b" to listOf(true, null, -1)))
        assertArrayEquals(
            bytes(0x01, 0x07, 0x02, 0x05, 0x01, 'a'.code, 0x03, 0x02, 0x05, 0x01, 'b'.code, 0x06, 0x03, 0x02, 0x00, 0x03, 0x01),
            enc,
        )
        assertArrayEquals(bytes(0x01, 0x04, 0x3F, 0xF8, 0, 0, 0, 0, 0, 0), LinkCodec.encode(1.5))
        assertArrayEquals(bytes(0x01, 0x03, 0xAC, 0x02), LinkCodec.encode(150)) // zigzag 300
    }

    private fun nestedLists(depth: Int): Any? {
        var v: Any? = "leaf"
        repeat(depth) { v = listOf(v) }
        return v
    }

    @Test
    fun depthLimit() {
        assertEquals(nestedLists(LinkCodec.MAX_DEPTH), roundTrip(nestedLists(LinkCodec.MAX_DEPTH)))
        assertEncodeRejected(nestedLists(LinkCodec.MAX_DEPTH + 1))
        // Hand-made bytes: MAX_DEPTH + 1 one-element lists around a null.
        val deep = ByteArray(2 + (LinkCodec.MAX_DEPTH + 1) * 2)
        deep[0] = 1
        for (i in 0..LinkCodec.MAX_DEPTH) {
            deep[1 + i * 2] = 0x06
            deep[2 + i * 2] = 0x01
        }
        deep[deep.size - 1] = 0x00
        assertMalformed(deep)
        // One level less decodes.
        val ok = byteArrayOf(1) + deep.copyOfRange(3, deep.size)
        assertEquals(nestedLists(LinkCodec.MAX_DEPTH).toString().replace("leaf", "null"), LinkCodec.decode(ok).toString())
    }

    @Test
    fun selfContainingListIsRejectedNotOverflowed() {
        val list = ArrayList<Any?>()
        list.add(list)
        assertEncodeRejected(list)
    }

    @Test
    fun sizeLimit() {
        val fits = "x".repeat(LinkCodec.MAX_MESSAGE_BYTES - 4) // 1 version + 1 tag + 2 length bytes
        assertEquals(LinkCodec.MAX_MESSAGE_BYTES, LinkCodec.encode(fits).size)
        assertEquals(fits, roundTrip(fits))
        assertEncodeRejected("x".repeat(LinkCodec.MAX_MESSAGE_BYTES))
        assertEncodeRejected(List(LinkCodec.MAX_MESSAGE_BYTES) { null })
        assertMalformed(ByteArray(LinkCodec.MAX_MESSAGE_BYTES + 1).also { it[0] = 1 })
    }

    @Test
    fun unsupportedValuesAreRejected() {
        assertEncodeRejected(Any())
        assertEncodeRejected(java.math.BigInteger.ONE)
        assertEncodeRejected(listOf(1, Any()))
        assertEncodeRejected("bad \uD800 surrogate")
        assertEncodeRejected(mapOf(listOf(1) to 1))
        assertEncodeRejected(mapOf(mapOf(1 to 1) to 1))
        assertEncodeRejected(linkedMapOf<Any, Any>(1 to "int", 1L to "long"))
    }

    @Test
    fun malformedInputIsRejected() {
        assertMalformed(ByteArray(0))
        assertMalformed(bytes(0x02, 0x00)) // unknown version
        assertMalformed(bytes(0x01)) // no value
        assertMalformed(bytes(0x01, 0x08)) // unknown type
        assertMalformed(bytes(0x01, 0x00, 0x00)) // trailing byte
        assertMalformed(bytes(0x01, 0x03, 0x80)) // truncated varint
        assertMalformed(bytes(0x01, 0x03, 0x80, 0x00)) // overlong varint
        assertMalformed(bytes(0x01, 0x03, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x02)) // > 64 bits
        assertMalformed(bytes(0x01, 0x03, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01))
        assertMalformed(bytes(0x01, 0x04, 0, 0, 0)) // short double
        assertMalformed(bytes(0x01, 0x05, 0x05, 'a'.code)) // string longer than the message
        assertMalformed(bytes(0x01, 0x05, 0x02, 0xC3, 0x28)) // invalid UTF-8
        assertMalformed(bytes(0x01, 0x05, 0x03, 0xED, 0xA0, 0x80)) // encoded surrogate
        assertMalformed(bytes(0x01, 0x06, 0xFF, 0xFF, 0xFF, 0xFF, 0x07)) // huge list count
        assertMalformed(bytes(0x01, 0x07, 0x01, 0x06, 0x00, 0x00)) // list as key
        assertMalformed(bytes(0x01, 0x07, 0x02, 0x03, 0x02, 0x00, 0x03, 0x02, 0x00)) // repeated key 1
        assertMalformed(bytes(0x01, 0x07, 0x01, 0x00)) // key without value
    }

    @Test
    fun everyTruncationOfAValidMessageIsRejected() {
        val valid = LinkCodec.encode(
            linkedMapOf("name" to "TCS", "pos" to listOf(41.88, -87.62), "n" to 123456789L, "ok" to true),
        )
        for (len in 0 until valid.size) {
            assertMalformed(valid.copyOf(len))
        }
    }

    @Test
    fun fuzzNeverThrowsUnchecked() {
        val rnd = Random(20261123)
        val seeds = listOf(
            LinkCodec.encode(linkedMapOf("a" to listOf(1, 2.0, "x", null, true), "b" to mapOf(1 to "y"))),
            LinkCodec.encode(nestedLists(10)),
            LinkCodec.encode("日本語 🚀"),
        )
        repeat(50_000) { i ->
            val input = if (i % 2 == 0) {
                ByteArray(rnd.nextInt(0, 64)) { rnd.nextInt().toByte() }.also { if (it.isNotEmpty()) it[0] = 1 }
            } else {
                val s = seeds[i % seeds.size].copyOf()
                repeat(rnd.nextInt(1, 4)) { s[rnd.nextInt(s.size)] = rnd.nextInt().toByte() }
                s
            }
            try {
                val v = LinkCodec.decode(input)
                // Whatever decodes must re-encode to the same bytes (one canonical form).
                assertArrayEquals(input, LinkCodec.encode(v))
            } catch (_: LinkCodecException) {
                // fine
            }
        }
    }
}
