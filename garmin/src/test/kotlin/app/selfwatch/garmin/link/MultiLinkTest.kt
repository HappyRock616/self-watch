/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.selfwatch.garmin.link

import app.selfwatch.garmin.gfdi.ByteWriter
import app.selfwatch.garmin.gfdi.Bytes
import app.selfwatch.model.fake.FakeBytePipe
import app.selfwatch.model.result.Outcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiLinkTest {
    private fun resp(type: Int, cid: Long = 2) = ByteWriter().u8(0).u8(type).i64(cid)

    @Test
    fun requestLayout() {
        assertEquals("0000" + "0200000000000000" + "0100" + "02", Bytes.hex(MlControl.request(MlControl.REGISTER_REQ, 2, 1, 2)))
        assertEquals("0002" + "0200000000000000" + "0600" + "05", Bytes.hex(MlControl.request(MlControl.CLOSE_HANDLE_REQ, 2, 6, 5)))
    }

    @Test
    fun parsesResponses() {
        assertEquals(MlResponse.Registered(1, 0, 3, 1), MlResponse.parse(resp(1).u16(1).u8(0).u8(3).u8(1).toByteArray(), 2))
        // Older watches omit the reliable byte.
        assertEquals(MlResponse.Registered(1, 0, 3, 0), MlResponse.parse(resp(1).u16(1).u8(0).u8(3).toByteArray(), 2))
        assertEquals(MlResponse.Closed(6, 4, 0), MlResponse.parse(resp(3).u16(6).u8(4).u8(0).toByteArray(), 2))
        assertEquals(MlResponse.ClosedAll, MlResponse.parse(resp(6).u16(0).toByteArray(), 2))
        assertNull("other client", MlResponse.parse(resp(1, cid = 9).u16(1).u8(0).u8(3).toByteArray(), 2))
        assertNull("truncated", MlResponse.parse(byteArrayOf(0, 1, 2), 2))
    }

    @Test
    fun registersAndCarriesPlainData() = runTest {
        val pipe = FakeBytePipe(20)
        pipe.onWrite = { p, b ->
            if (b[0].toInt() == 0 && b[1].toInt() == MlControl.CLOSE_ALL_REQ) p.queueIncoming(resp(6).u16(0).toByteArray())
            if (b[0].toInt() == 0 && b[1].toInt() == MlControl.REGISTER_REQ) p.queueIncoming(resp(1).u16(1).u8(0).u8(5).u8(0).toByteArray())
        }
        val link = MultiLink(pipe, backgroundScope)
        assertTrue(link.start() is Outcome.Ok)
        val ch = (link.openService(MlService.GFDI, reliable = true) as Outcome.Ok).value
        assertFalse(ch.reliable)
        pipe.clearWrites()
        ch.send(ByteArray(45) { 1 })
        // 19 data bytes per write after the handle byte.
        assertEquals(listOf(20, 20, 8), pipe.writes().map { it.size })
        assertTrue(pipe.writes().all { it[0].toInt() == 5 })
        pipe.queueIncoming(byteArrayOf(5, 9, 8, 7))
        assertEquals("090807", Bytes.hex(ch.incoming.first()))
        link.close()
    }

    @Test
    fun refusedRegistrationFails() = runTest {
        val pipe = FakeBytePipe(20)
        pipe.onWrite = { p, b ->
            if (b[1].toInt() == MlControl.CLOSE_ALL_REQ) p.queueIncoming(resp(6).toByteArray())
            if (b[1].toInt() == MlControl.REGISTER_REQ) p.queueIncoming(resp(1).u16(1).u8(1).toByteArray())
        }
        val link = MultiLink(pipe, backgroundScope)
        link.start()
        assertTrue(link.openService(MlService.GFDI, true) is Outcome.Fail)
        link.close()
    }

    @Test
    fun silentWatchTimesOut() = runTest {
        val pipe = FakeBytePipe(20)
        val link = MultiLink(pipe, backgroundScope, controlTimeoutMs = 1_000)
        assertTrue(link.start() is Outcome.Ok) // proceeds without a close-all answer
        val r = link.openService(MlService.GFDI, true)
        assertTrue(r is Outcome.Fail)
        // close-all, then two register attempts.
        assertEquals(3, pipe.writes().size)
        link.close()
    }

    @Test
    fun reRegistersGfdiWhenTheWatchClosesIt() = runTest {
        val pipe = FakeBytePipe(20)
        var registers = 0
        pipe.onWrite = { p, b ->
            if (b[1].toInt() == MlControl.CLOSE_ALL_REQ) p.queueIncoming(resp(6).toByteArray())
            if (b[1].toInt() == MlControl.REGISTER_REQ) {
                registers++
                p.queueIncoming(resp(1).u16(1).u8(0).u8(4 + registers).u8(0).toByteArray())
            }
        }
        val link = MultiLink(pipe, backgroundScope)
        link.start()
        val ch = (link.openService(MlService.GFDI, false) as Outcome.Ok).value
        pipe.queueIncoming(resp(3).u16(1).u8(5).u8(0).toByteArray())
        kotlinx.coroutines.delay(1_000)
        assertEquals(2, registers)
        pipe.clearWrites()
        ch.send(byteArrayOf(1))
        assertEquals(6, pipe.writes().single()[0].toInt())
        link.close()
    }
}
