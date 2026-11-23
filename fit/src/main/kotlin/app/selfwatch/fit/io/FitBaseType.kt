/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.fit.io

import java.nio.charset.StandardCharsets

/**
 * FIT base types. The code's low 5 bits are the type number; bit 7 says the type has a byte order.
 * The reader matches on the type number only, so files that set bit 7 wrongly still decode.
 */
object FitBaseType {
    /** enum, 1 byte, invalid 0xFF. */
    const val ENUM: Int = 0x00

    /** sint8, invalid 0x7F. */
    const val SINT8: Int = 0x01

    /** uint8, invalid 0xFF. */
    const val UINT8: Int = 0x02

    /** sint16, invalid 0x7FFF. */
    const val SINT16: Int = 0x83

    /** uint16, invalid 0xFFFF. */
    const val UINT16: Int = 0x84

    /** sint32, invalid 0x7FFFFFFF. */
    const val SINT32: Int = 0x85

    /** uint32, invalid 0xFFFFFFFF. */
    const val UINT32: Int = 0x86

    /** UTF-8 string, null-terminated; empty is invalid. */
    const val STRING: Int = 0x07

    /** float32, invalid all bits set. */
    const val FLOAT32: Int = 0x88

    /** float64, invalid all bits set. */
    const val FLOAT64: Int = 0x89

    /** uint8z, invalid 0. */
    const val UINT8Z: Int = 0x0A

    /** uint16z, invalid 0. */
    const val UINT16Z: Int = 0x8B

    /** uint32z, invalid 0. */
    const val UINT32Z: Int = 0x8C

    /** byte array, invalid when every byte is 0xFF. */
    const val BYTE: Int = 0x0D

    /** sint64, invalid 0x7FFFFFFFFFFFFFFF. */
    const val SINT64: Int = 0x8E

    /** uint64, invalid 0xFFFFFFFFFFFFFFFF. */
    const val UINT64: Int = 0x8F

    /** uint64z, invalid 0. */
    const val UINT64Z: Int = 0x90

    private val SIZES = intArrayOf(1, 1, 1, 2, 2, 4, 4, 1, 4, 8, 1, 2, 4, 1, 8, 8, 8)

    /** Size in bytes of one element of base type [code]; 1 for unknown types (read as bytes). */
    fun size(code: Int): Int {
        val n = code and 0x1F
        return if (n < SIZES.size) SIZES[n] else 1
    }

    /**
     * Decodes [size] bytes at [off] in [buf] as base type [code], in big-endian order if [bigEndian].
     * Never throws for any input; the caller guarantees the range is inside [buf].
     */
    internal fun decode(buf: ByteArray, off: Int, size: Int, code: Int, bigEndian: Boolean): FitValue {
        if (size <= 0) return FitValue.Invalid
        var n = code and 0x1F
        if (n > 16) n = 13 // unknown type: plain bytes
        if (n == 7) return decodeString(buf, off, size)
        if (n == 13) return decodeBytes(buf, off, size)
        var elem = SIZES[n]
        if (size % elem != 0) {
            // Some encoders declare a smaller size than the type (seen with COROS); read as an unsigned int.
            if (size == 1 || size == 2 || size == 4) {
                n = when (size) { 1 -> 2; 2 -> 4; else -> 6 }
                elem = size
            } else {
                return FitValue.Invalid
            }
        }
        val count = size / elem
        if (count == 1) return decodeOne(buf, off, n, bigEndian)
        val list = ArrayList<FitValue>(count)
        var anyValid = false
        for (i in 0 until count) {
            val v = decodeOne(buf, off + i * elem, n, bigEndian)
            if (v !== FitValue.Invalid) anyValid = true
            list.add(v)
        }
        return if (anyValid) FitValue.A(list) else FitValue.Invalid
    }

    private fun decodeOne(buf: ByteArray, off: Int, n: Int, be: Boolean): FitValue {
        return when (n) {
            0, 2 -> { val v = buf[off].toLong() and 0xFF; if (v == 0xFFL) FitValue.Invalid else FitValue.I(v) }
            1 -> { val v = buf[off].toLong(); if (v == 0x7FL) FitValue.Invalid else FitValue.I(v) }
            10 -> { val v = buf[off].toLong() and 0xFF; if (v == 0L) FitValue.Invalid else FitValue.I(v) }
            3 -> { val v = rd(buf, off, 2, be).toShort().toLong(); if (v == 0x7FFFL) FitValue.Invalid else FitValue.I(v) }
            4 -> { val v = rd(buf, off, 2, be); if (v == 0xFFFFL) FitValue.Invalid else FitValue.I(v) }
            11 -> { val v = rd(buf, off, 2, be); if (v == 0L) FitValue.Invalid else FitValue.I(v) }
            5 -> { val v = rd(buf, off, 4, be).toInt().toLong(); if (v == 0x7FFFFFFFL) FitValue.Invalid else FitValue.I(v) }
            6 -> { val v = rd(buf, off, 4, be); if (v == 0xFFFFFFFFL) FitValue.Invalid else FitValue.I(v) }
            12 -> { val v = rd(buf, off, 4, be); if (v == 0L) FitValue.Invalid else FitValue.I(v) }
            8 -> {
                val bits = rd(buf, off, 4, be).toInt()
                if (bits == -1) FitValue.Invalid else FitValue.D(java.lang.Float.intBitsToFloat(bits).toDouble())
            }
            9 -> {
                val bits = rd(buf, off, 8, be)
                if (bits == -1L) FitValue.Invalid else FitValue.D(java.lang.Double.longBitsToDouble(bits))
            }
            14 -> { val v = rd(buf, off, 8, be); if (v == Long.MAX_VALUE) FitValue.Invalid else FitValue.I(v) }
            15 -> { val v = rd(buf, off, 8, be); if (v == -1L) FitValue.Invalid else FitValue.I(v) }
            16 -> { val v = rd(buf, off, 8, be); if (v == 0L) FitValue.Invalid else FitValue.I(v) }
            else -> FitValue.Invalid
        }
    }

    private fun decodeString(buf: ByteArray, off: Int, size: Int): FitValue {
        var len = 0
        while (len < size && buf[off + len] != 0.toByte()) len++
        if (len == 0) return FitValue.Invalid
        return FitValue.S(String(buf, off, len, StandardCharsets.UTF_8))
    }

    private fun decodeBytes(buf: ByteArray, off: Int, size: Int): FitValue {
        var allFF = true
        for (i in 0 until size) if (buf[off + i] != 0xFF.toByte()) { allFF = false; break }
        if (allFF) return FitValue.Invalid
        if (size == 1) return FitValue.I(buf[off].toLong() and 0xFF)
        val list = ArrayList<FitValue>(size)
        for (i in 0 until size) list.add(FitValue.I(buf[off + i].toLong() and 0xFF))
        return FitValue.A(list)
    }

    /** Reads an unsigned [n]-byte integer (n ≤ 8; 8 keeps raw bits). */
    internal fun rd(buf: ByteArray, off: Int, n: Int, be: Boolean): Long {
        var v = 0L
        if (be) {
            for (i in 0 until n) v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
        } else {
            for (i in n - 1 downTo 0) v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
        }
        return v
    }
}
