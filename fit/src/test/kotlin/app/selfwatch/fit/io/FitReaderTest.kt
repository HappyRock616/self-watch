/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.fit.io

import app.selfwatch.fit.F
import app.selfwatch.fit.Synth
import app.selfwatch.fit.fit
import app.selfwatch.fit.fixFileCrc
import app.selfwatch.fit.ft
import app.selfwatch.fit.io.FitBaseType.ENUM
import app.selfwatch.fit.io.FitBaseType.UINT16
import app.selfwatch.fit.io.FitBaseType.UINT32
import app.selfwatch.fit.io.FitBaseType.UINT8
import app.selfwatch.fit.msg
import app.selfwatch.fit.readAll
import app.selfwatch.model.crc.Crc16
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.result.errorOrNull
import app.selfwatch.model.result.valueOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class FitReaderTest {
    private val t = Synth.T0

    private fun simple(): ByteArray = fit {
        Synth.fileId(this, 4, t)
        msg(1, 20, F(253, UINT32, ft(t)), F(3, UINT8, 120))
        msg(1, 20, F(253, UINT32, ft(t + 1)), F(3, UINT8, 121))
    }

    @Test
    fun readsSummaryAndRecords() {
        val (r, recs) = readAll(simple())
        val s = r.valueOrNull()!!
        assertEquals(4, s.fileType)
        assertEquals(1, s.manufacturer)
        assertEquals(4426, s.product)
        assertEquals(3, s.records)
        assertTrue(s.crcOk)
        assertEquals(listOf(0, 20, 20), recs.map { it.global })
        assertEquals(FitValue.I(121), recs[2].fields[3])
        assertEquals(t + 1, recs[2].tSec)
        assertTrue(recs[2].ownTime)
    }

    @Test
    fun twelveByteHeaderIsAccepted() {
        val b = simple()
        val h12 = ByteArray(b.size - 2)
        h12[0] = 12
        System.arraycopy(b, 1, h12, 1, 11)
        System.arraycopy(b, 14, h12, 12, b.size - 14)
        val (r, recs) = readAll(fixFileCrc(h12))
        assertTrue(r.valueOrNull()!!.crcOk)
        assertEquals(3, recs.size)
    }

    @Test
    fun zeroHeaderCrcMeansNone() {
        val b = simple()
        b[12] = 0; b[13] = 0
        assertTrue(readAll(fixFileCrc(b)).first.valueOrNull()!!.crcOk)
    }

    @Test
    fun badHeaderCrcStillReadsButFlags() {
        val b = simple()
        b[12] = (b[12].toInt() xor 0x55).toByte()
        val (r, recs) = readAll(fixFileCrc(b))
        assertFalse(r.valueOrNull()!!.crcOk)
        assertEquals(3, recs.size)
    }

    @Test
    fun badFileCrcStillReadsButFlags() {
        val b = simple()
        b[b.size - 1] = (b[b.size - 1].toInt() xor 1).toByte()
        val (r, recs) = readAll(b)
        assertFalse(r.valueOrNull()!!.crcOk)
        assertEquals(3, recs.size)
    }

    @Test
    fun corruptValueGivesCrcMismatch() {
        val b = simple()
        b[b.size - 3] = 99 // last heart rate
        val (r, recs) = readAll(b)
        assertFalse(r.valueOrNull()!!.crcOk)
        assertEquals(FitValue.I(99), recs[2].fields[3])
    }

    @Test
    fun notFitFails() {
        assertTrue(readAll(ByteArray(0)).first is Outcome.Fail)
        assertTrue(readAll("hello, this is not a FIT file".toByteArray()).first.errorOrNull() is SwError.BadInput)
        val b = simple()
        b[9] = 'X'.code.toByte()
        assertTrue(readAll(b).first is Outcome.Fail)
    }

    @Test
    fun truncatedFailsAfterDeliveringEarlierRecords() {
        val b = simple()
        val (r, recs) = readAll(b.copyOf(b.size - 4))
        assertTrue(r.errorOrNull() is SwError.BadInput)
        assertEquals(2, recs.size)
    }

    @Test
    fun dataForUndefinedLocalTypeFails() {
        val b = fit { Synth.fileId(this, 4, t) }
        // Append a data header for local type 5 inside the data area.
        val body = b.copyOfRange(14, b.size - 2) + byteArrayOf(0x05, 1, 2, 3)
        val out = rebuild(body)
        assertTrue(readAll(out).first.errorOrNull() is SwError.BadInput)
    }

    @Test
    fun badArchitectureFails() {
        val b = simple()
        // Definition of file_id starts at 14: header, reserved, architecture.
        b[16] = 7
        assertTrue(readAll(fixFileCrc(b)).first is Outcome.Fail)
    }

    @Test
    fun fileLargerThanMaxBytesFailsBeforeReading() {
        val b = Synth.activity(500)
        val (r, recs) = readAll(b, maxBytes = 1000)
        assertTrue(r.errorOrNull() is SwError.BadInput)
        assertTrue(recs.isEmpty())
    }

    @Test
    fun endlessInputIsBounded() {
        val h = ByteArray(14)
        h[0] = 14
        h[4] = 0xFF.toByte(); h[5] = 0xFF.toByte(); h[6] = 0xFF.toByte(); h[7] = 0x7F
        h[8] = '.'.code.toByte(); h[9] = 'F'.code.toByte(); h[10] = 'I'.code.toByte(); h[11] = 'T'.code.toByte()
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int = if (served < 14) h[(served++).toInt()].toInt() and 0xFF else { served++; 0 }
        }
        val r = FitReader(endless, maxBytes = 1 shl 20).read { }
        assertTrue(r is Outcome.Fail)
        assertTrue(served < 100_000)
        // Also when the header claims a small size but the records never end: limited by the data size.
        val small = fit { Synth.fileId(this, 4, t) }
        val r2 = FitReader(ByteArrayInputStream(small + ByteArray(5000)), 1 shl 20).read { }
        assertTrue(r2.valueOrNull()!!.crcOk)
    }

    @Test
    fun bigEndianDefinitionsDecode() {
        val b = fit {
            msg(
                1, 20,
                F(253, UINT32, ft(t)), F(2, UINT16, 3000), F(0, FitBaseType.SINT32, -123456789),
                F(13, FitBaseType.SINT16, -40), F(9, FitBaseType.FLOAT32, 1.25),
                bigEndian = true,
            )
        }
        val (r, recs) = readAll(b)
        assertTrue(r.valueOrNull()!!.crcOk)
        val f = recs[0].fields
        assertEquals(t, recs[0].tSec)
        assertEquals(FitValue.I(3000), f[2])
        assertEquals(FitValue.I(-123456789), f[0])
        assertEquals(FitValue.I(-40), f[13])
        assertEquals(FitValue.D(1.25), f[9])
    }

    @Test
    fun developerFieldsUseFieldDescriptionTypes() {
        val (r, recs) = readAll(Synth.oddities())
        assertTrue(r.valueOrNull()!!.crcOk)
        val recsOf20 = recs.filter { it.global == 20 }
        assertEquals(20, recsOf20.size)
        val first = recsOf20[0]
        assertEquals(FitValue.I(120), first.fields[3])
        assertEquals(FitValue.I(200), first.dev[FitRecord.devKey(0, 7)])
        assertEquals(FitValue.A(listOf(FitValue.I(1), FitValue.I(2), FitValue.I(3))), first.dev[FitRecord.devKey(1, 9)])
        val desc = recs.first { it.global == 206 }
        assertEquals(FitValue.S("power2"), desc.fields[3])
        val unknown = recs.first { it.global == 0xFF01 }
        assertEquals(FitValue.A(listOf(FitValue.I(1), FitValue.Invalid, FitValue.I(3))), unknown.fields[0])
        assertEquals(FitValue.S("unknown msg"), unknown.fields[1])
    }

    @Test
    fun compressedTimestampsWithRollover() {
        val base = (ft(t) and 0x1FL.inv()) + 30 // low 5 bits = 30
        val b = fit {
            define(0, 20, listOf(FieldDef(3, 1, UINT8)))
            compressed(0, 5, listOf(1)) // before any timestamp: no time
            msg(1, 20, F(253, UINT32, base), F(3, UINT8, 2))
            compressed(0, 31, listOf(3))
            compressed(0, 2, listOf(4)) // 2 < 31: rolls over
            compressed(0, 2, listOf(5)) // same second
        }
        val (r, recs) = readAll(b)
        assertTrue(r.valueOrNull()!!.crcOk)
        assertNull(recs[0].tSec)
        assertFalse(recs[0].ownTime)
        val e = FitTime.toEpoch(base)
        assertEquals(listOf(e, e + 1, e + 4, e + 4), recs.drop(1).map { it.tSec })
        assertTrue(recs[2].ownTime)
    }

    @Test
    fun recordsWithoutTimestampInheritTheLastOne() {
        val b = fit {
            msg(1, 20, F(253, UINT32, ft(t)), F(3, UINT8, 2))
            msg(2, 227, F(0, FitBaseType.SINT16, 30))
        }
        val recs = readAll(b).second
        assertEquals(t, recs[1].tSec)
        assertFalse(recs[1].ownTime)
    }

    @Test
    fun chainedFilesAreReadAndTrailingBytesIgnored() {
        val a = simple()
        val b = fit { Synth.fileId(this, 32, t); msg(1, 55, F(253, UINT32, ft(t)), F(3, UINT32, 10)) }
        val (r, recs) = readAll(a + b + "junk".toByteArray())
        val s = r.valueOrNull()!!
        assertTrue(s.crcOk)
        assertEquals(5, s.records)
        assertEquals(4, s.fileType)
        assertEquals(55, recs.last().global)
    }

    @Test
    fun callbackExceptionsPropagate() {
        try {
            FitReader(ByteArrayInputStream(simple())).read { throw IllegalStateException("boom") }
            fail("expected exception")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
    }

    @Test
    fun oddFieldSizes() {
        val b = fit {
            define(1, 20, listOf(FieldDef(253, 4, UINT32), FieldDef(6, 1, UINT16), FieldDef(7, 3, UINT16), FieldDef(8, 0, UINT32)))
            data(1, listOf(ft(t), 7, null, null))
        }
        val f = readAll(b).second[0].fields
        assertEquals(FitValue.I(7), f[6]) // uint16 declared with size 1: read as uint8
        assertEquals(FitValue.Invalid, f[7]) // 3 bytes of uint16: unusable
        assertEquals(FitValue.Invalid, f[8]) // size 0
    }

    @Test
    fun redefinitionOfLocalTypeWins() {
        val b = fit {
            msg(1, 20, F(3, UINT8, 1))
            msg(1, 55, F(27, UINT8, 60), F(5, ENUM, 6))
        }
        val recs = readAll(b).second
        assertEquals(listOf(20, 55), recs.map { it.global })
        assertEquals(FitValue.I(6), recs[1].fields[5])
    }

    /** A 14-byte-header file around [body] with correct sizes and CRCs. */
    private fun rebuild(body: ByteArray): ByteArray {
        val h = ByteArray(14)
        h[0] = 14; h[1] = 0x20; h[2] = 0x54; h[3] = 0x08
        for (i in 0 until 4) h[4 + i] = ((body.size ushr (8 * i)) and 0xFF).toByte()
        h[8] = '.'.code.toByte(); h[9] = 'F'.code.toByte(); h[10] = 'I'.code.toByte(); h[11] = 'T'.code.toByte()
        val hc = Crc16.compute(h, 0, 12)
        h[12] = hc.toByte(); h[13] = (hc shr 8).toByte()
        return fixFileCrc(h + body + byteArrayOf(0, 0))
    }
}
