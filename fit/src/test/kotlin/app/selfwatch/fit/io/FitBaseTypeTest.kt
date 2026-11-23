/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.fit.io

import app.selfwatch.fit.F
import app.selfwatch.fit.fit
import app.selfwatch.fit.io.FitBaseType.BYTE
import app.selfwatch.fit.io.FitBaseType.ENUM
import app.selfwatch.fit.io.FitBaseType.FLOAT32
import app.selfwatch.fit.io.FitBaseType.FLOAT64
import app.selfwatch.fit.io.FitBaseType.SINT16
import app.selfwatch.fit.io.FitBaseType.SINT32
import app.selfwatch.fit.io.FitBaseType.SINT64
import app.selfwatch.fit.io.FitBaseType.SINT8
import app.selfwatch.fit.io.FitBaseType.STRING
import app.selfwatch.fit.io.FitBaseType.UINT16
import app.selfwatch.fit.io.FitBaseType.UINT16Z
import app.selfwatch.fit.io.FitBaseType.UINT32
import app.selfwatch.fit.io.FitBaseType.UINT32Z
import app.selfwatch.fit.io.FitBaseType.UINT64
import app.selfwatch.fit.io.FitBaseType.UINT64Z
import app.selfwatch.fit.io.FitBaseType.UINT8
import app.selfwatch.fit.io.FitBaseType.UINT8Z
import app.selfwatch.fit.msg
import app.selfwatch.fit.readAll
import org.junit.Assert.assertEquals
import org.junit.Test

class FitBaseTypeTest {

    /** Writes [value] as one field of [type] (both byte orders) and returns what the reader decodes. */
    private fun roundTrip(type: Int, value: Any?, size: Int = FitBaseType.size(type)): List<FitValue> =
        listOf(false, true).map { be ->
            val b = fit { msg(0, 0xFF00, F(1, type, value, size), bigEndian = be) }
            readAll(b).second.single().fields[1]!!
        }

    private fun check(type: Int, value: Any?, expected: FitValue, size: Int = FitBaseType.size(type)) {
        for (v in roundTrip(type, value, size)) assertEquals("type 0x${type.toString(16)} value $value", expected, v)
    }

    @Test
    fun integersAndTheirInvalidValues() {
        check(ENUM, 5, FitValue.I(5)); check(ENUM, null, FitValue.Invalid); check(ENUM, 0xFF, FitValue.Invalid)
        check(SINT8, -5, FitValue.I(-5)); check(SINT8, 0x7F, FitValue.Invalid); check(SINT8, -128, FitValue.I(-128))
        check(UINT8, 200, FitValue.I(200)); check(UINT8, null, FitValue.Invalid); check(UINT8, 0, FitValue.I(0))
        check(SINT16, -1234, FitValue.I(-1234)); check(SINT16, null, FitValue.Invalid)
        check(UINT16, 65000, FitValue.I(65000)); check(UINT16, 0xFFFF, FitValue.Invalid)
        check(SINT32, -100_000, FitValue.I(-100_000)); check(SINT32, 0x7FFFFFFF, FitValue.Invalid)
        check(UINT32, 4_000_000_000L, FitValue.I(4_000_000_000L)); check(UINT32, null, FitValue.Invalid)
        check(UINT8Z, 7, FitValue.I(7)); check(UINT8Z, 0, FitValue.Invalid); check(UINT8Z, 0xFF, FitValue.I(255))
        check(UINT16Z, 513, FitValue.I(513)); check(UINT16Z, null, FitValue.Invalid)
        check(UINT32Z, 3_400_000_001L, FitValue.I(3_400_000_001L)); check(UINT32Z, 0, FitValue.Invalid)
        check(SINT64, -5_000_000_000L, FitValue.I(-5_000_000_000L)); check(SINT64, Long.MAX_VALUE, FitValue.Invalid)
        check(UINT64, 5_000_000_000L, FitValue.I(5_000_000_000L)); check(UINT64, -1L, FitValue.Invalid)
        check(UINT64, Long.MIN_VALUE + 5, FitValue.I(Long.MIN_VALUE + 5)) // above Long.MAX_VALUE: raw bits
        check(UINT64Z, 9L, FitValue.I(9)); check(UINT64Z, null, FitValue.Invalid)
    }

    @Test
    fun floats() {
        check(FLOAT32, 1.5, FitValue.D(1.5)); check(FLOAT32, null, FitValue.Invalid)
        check(FLOAT64, -2.25, FitValue.D(-2.25)); check(FLOAT64, null, FitValue.Invalid)
        // A NaN that is not all bits set is a value, not the invalid marker.
        val nan = FitBaseType.decode(byteArrayOf(0, 0, 0xC0.toByte(), 0x7F), 0, 4, FLOAT32, false)
        assertEquals(true, (nan as FitValue.D).v.isNaN())
    }

    @Test
    fun strings() {
        check(STRING, "héllo", FitValue.S("héllo"), 16)
        check(STRING, "", FitValue.Invalid, 8)
        check(STRING, null, FitValue.Invalid, 8)
        // No terminator: the whole field is the string.
        assertEquals(FitValue.S("abcd"), FitBaseType.decode("abcd".toByteArray(), 0, 4, STRING, false))
        // Bad UTF-8 never throws.
        val bad = FitBaseType.decode(byteArrayOf(0xC3.toByte(), 0x28, 0), 0, 3, STRING, false)
        assertEquals(true, bad is FitValue.S)
    }

    @Test
    fun bytesAndArrays() {
        check(BYTE, byteArrayOf(1, 2, 3), FitValue.A(listOf(FitValue.I(1), FitValue.I(2), FitValue.I(3))), 3)
        check(BYTE, null, FitValue.Invalid, 3)
        check(BYTE, 0x42, FitValue.I(0x42))
        check(UINT16, listOf(1, null, 3), FitValue.A(listOf(FitValue.I(1), FitValue.Invalid, FitValue.I(3))), 6)
        check(UINT16, listOf(null, null), FitValue.Invalid, 4)
        check(SINT8, listOf(-1, 2), FitValue.A(listOf(FitValue.I(-1), FitValue.I(2))), 2)
    }

    @Test
    fun unknownBaseTypeReadsAsBytes() {
        assertEquals(FitValue.A(listOf(FitValue.I(9), FitValue.I(8))), FitBaseType.decode(byteArrayOf(9, 8), 0, 2, 0x1F, false))
        assertEquals(1, FitBaseType.size(0x1F))
    }

    @Test
    fun typeNumberIgnoresTheEndianBit() {
        // uint16 written with code 0x04 (endian bit missing) still decodes as uint16.
        assertEquals(FitValue.I(0x0201), FitBaseType.decode(byteArrayOf(1, 2), 0, 2, 0x04, false))
        assertEquals(FitValue.I(0x0102), FitBaseType.decode(byteArrayOf(1, 2), 0, 2, 0x04, true))
    }
}
