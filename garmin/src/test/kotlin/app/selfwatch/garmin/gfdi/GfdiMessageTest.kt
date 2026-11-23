/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.gfdi

import app.selfwatch.garmin.files.DirEntry
import app.selfwatch.model.crc.Crc16
import app.selfwatch.model.result.Outcome
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GfdiMessageTest {
    private val samples: List<GfdiMessage> = listOf(
        GfdiMessage.Response(GfdiId.DOWNLOAD_REQUEST, GfdiStatus.ACK, Bytes(GfdiReplies.DownloadStatus(0, 1234).encode())),
        GfdiMessage.Response(5999, GfdiStatus.UNSUPPORTED),
        GfdiMessage.DownloadRequest(17),
        GfdiMessage.DownloadRequest(3, offset = 512, newTransfer = false, crcSeed = 0xBEEF, size = 4096, flags = 1),
        GfdiMessage.UploadRequest(9, 2048, 0, 0),
        GfdiMessage.FileTransferData(0, 0x1234, 100, Bytes(byteArrayOf(0, 1, 2, 0, 0))),
        GfdiMessage.CreateFile(9_999, 255, 17, 0, 0, 0xFFFF, "", 0x0102030405060708L),
        GfdiMessage.CreateFile(10, 128, 2, 4, 1, 3, "GARMIN/SETTINGS", -1L),
        GfdiMessage.Filter(GfdiMessage.Filter.ONLY_NEW),
        GfdiMessage.SetFileFlag(12, GfdiMessage.SetFileFlag.ARCHIVE),
        GfdiMessage.FileAvailable(DirEntry(5, 128, 4, 5, 0, 0, 1_000, 1_100_000_000L), Bytes(byteArrayOf(1))),
        GfdiMessage.BatteryStatus(0x10, 41, 77, Bytes(byteArrayOf(0, 0, 0, 0))),
        GfdiMessage.DeviceInformation(150, 4426, 3_456_789_012L, 1234, 375, "vívoactive 5", "vívoactive 5", "006-B4426-00", Bytes(byteArrayOf(0, 0))),
        GfdiMessage.DeviceSettings(
            listOf(
                DeviceSettingId.DEVICE_NAME to GfdiMessage.SettingValue.Str("Phone"),
                DeviceSettingId.AUTO_UPLOAD_ENABLED to GfdiMessage.SettingValue.Bool(true),
                1 to GfdiMessage.SettingValue.Int32(-7),
            ),
        ),
        GfdiMessage.SystemEvent(SystemEventType.SYNC_READY, 0),
        GfdiMessage.SystemEvent(SystemEventType.TIME_UPDATED, "zone"),
        GfdiMessage.SupportedFileTypesRequest,
        GfdiMessage.Synchronization(0, (1L shl 26) or (1L shl 3), 8),
        GfdiMessage.Synchronization(1, 0x20, 4),
        GfdiMessage.Configuration(Bytes(byteArrayOf(0x7F, 0, 0x10))),
        GfdiMessage.CurrentTimeRequest(0x0A0B0C0D),
        GfdiMessage.AuthNegotiation(0, 0x01),
        GfdiMessage.SecurePairing(5107, Bytes(byteArrayOf(1, 2))),
        GfdiMessage.Unknown(5999, Bytes(byteArrayOf(8, 1))),
    )

    @Test
    fun everyMessageRoundTripsThroughAFrame() {
        for (m in samples) {
            val frame = m.toFrame()
            val back = (GfdiMessage.decodeFrame(frame) as Outcome.Ok).value
            assertEquals("round trip of ${m.id}", m, back)
        }
    }

    @Test
    fun frameLayout() {
        val f = GfdiFrame.build(5031, ByteArray(0))
        // length 6 (LE), type 5031 = 0x13A7 (LE), CRC over the first 4 bytes (LE).
        assertEquals("0600a713", Bytes.hex(f.copyOfRange(0, 4)))
        val crc = Crc16.compute(f, 0, 4)
        assertEquals(crc and 0xFF, f[4].toInt() and 0xFF)
        assertEquals(crc shr 8, f[5].toInt() and 0xFF)
    }

    @Test
    fun responseLayout() {
        val p = GfdiMessage.Response(GfdiId.DEVICE_INFORMATION, GfdiStatus.ACK).encode()
        assertEquals("a013" + "00", Bytes.hex(p)) // 5024 = 0x13A0
    }

    @Test
    fun compactIdsAreExpanded() {
        // type 0x8000 | seq 5 << 8 | (5024 - 5000)
        val payload = GfdiMessage.CurrentTimeRequest(1).encode()
        val w = ByteWriter().u16(0).u16(0x8000 or (5 shl 8) or 52).bytes(payload)
        w.putU16At(0, w.size + 2)
        val body = w.toByteArray()
        val frame = w.u16(Crc16.compute(body)).toByteArray()
        val f = (GfdiFrame.parse(frame) as Outcome.Ok).value
        assertEquals(5052, f.id)
        assertEquals(5, f.compactSeq)
        assertEquals(GfdiMessage.CurrentTimeRequest(1), (GfdiMessage.decodeFrame(frame) as Outcome.Ok).value)
    }

    @Test
    fun badCrcAndLengthAreRejected() {
        val f = GfdiMessage.Filter(3).toFrame()
        val badCrc = f.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertTrue(GfdiFrame.parse(badCrc) is Outcome.Fail)
        assertTrue(GfdiFrame.parse(f.copyOf(f.size - 1)) is Outcome.Fail)
        assertTrue(GfdiFrame.parse(f + byteArrayOf(0)) is Outcome.Fail)
        assertTrue(GfdiFrame.parse(ByteArray(3)) is Outcome.Fail)
    }

    @Test
    fun shortPayloadIsMalformedNotACrash() {
        val m = GfdiMessage.decode(GfdiId.DEVICE_INFORMATION, byteArrayOf(1, 2, 3))
        assertTrue(m is GfdiMessage.Malformed)
        assertEquals(GfdiId.DEVICE_INFORMATION, m.id)
    }

    @Test
    fun synchronizationDecision() {
        assertTrue(GfdiMessage.Synchronization(0, 0).shouldSync) // manual
        assertTrue(GfdiMessage.Synchronization(1, 1L shl 26).shouldSync) // sleep
        assertTrue(!GfdiMessage.Synchronization(1, 1L shl 9).shouldSync) // device settings only
    }

    @Test
    fun repliesRoundTrip() {
        val di = GfdiReplies.DeviceInfoReply(bluetoothName = "Self Watch", manufacturer = "Self Watch", model = "Android", protocolFlags = 1)
        assertEquals(di, GfdiReplies.DeviceInfoReply.parse(di.encode()))
        // Reference defaults: protocol 150, product/unit/max packet all-ones, software 7791.
        assertEquals("9600" + "ffff" + "ffffffff" + "6f1e" + "ffff", Bytes.hex(di.encode().copyOfRange(0, 12)))
        val t = GfdiReplies.TimeReply(1, 2, -18_000, 4, 5)
        assertEquals(t, GfdiReplies.TimeReply.parse(t.encode()))
        val a = GfdiReplies.AuthReply(0, 0, 1)
        assertEquals(a, GfdiReplies.AuthReply.parse(a.encode()))
        val ts = GfdiReplies.TransferStatus(0, 300)
        assertEquals(ts, GfdiReplies.TransferStatus.parse(ts.encode()))
        val ds = GfdiReplies.DownloadStatus(0, 99)
        assertEquals(ds, GfdiReplies.DownloadStatus.parse(ds.encode()))
        val fs = GfdiReplies.FlagsStatus(0, 7, 0x10)
        assertEquals(fs, GfdiReplies.FlagsStatus.parse(fs.encode()))
        val st = GfdiReplies.SupportedFileTypes(listOf(GfdiReplies.FileTypeInfo(128, 4, "FIT_TYPE_4")))
        assertEquals(st, GfdiReplies.SupportedFileTypes.parse(st.encode()))
        val cs = GfdiReplies.CreateStatus(0, 12, 255, 17, 3)
        assertEquals(cs, GfdiReplies.CreateStatus.parse(cs.encode()))
        val us = GfdiReplies.UploadStatus(0, 0, 100_000, 0)
        assertEquals(us, GfdiReplies.UploadStatus.parse(us.encode()))
    }

    @Test
    fun stringsAreCutOnCharacterBoundaries() {
        val long = "é".repeat(200) // 400 UTF-8 bytes
        val b = ByteWriter().str8(long).toByteArray()
        assertEquals(254, b[0].toInt() and 0xFF)
        assertEquals("é".repeat(127), ByteReader(b).str8())
    }

    @Test
    fun readerRejectsOverruns() {
        val r = ByteReader(byteArrayOf(1, 2))
        assertEquals(0x0201, r.u16())
        try {
            r.u8()
            throw AssertionError("expected MalformedException")
        } catch (e: MalformedException) {
            assertTrue(e.message!!.contains("need 1"))
        }
        assertArrayEquals(byteArrayOf(0x34, 0x12), ByteWriter().u16(0x1234).toByteArray())
    }
}
