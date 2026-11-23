/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ble.log

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.random.Random

class BleLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val utc = ZoneOffset.UTC
    private fun ms(day: LocalDate, sec: Long = 0): Long = day.atStartOfDay(utc).toEpochSecond() * 1000 + sec * 1000

    private val day = LocalDate.of(2026, 11, 23)

    @Test
    fun encodesTheDocumentedLayout() {
        val bytes = BleLogFormat.encode(BleLogRecord(0x0102030405060708, BleLogFormat.DIR_IN, 3, byteArrayOf(0x55, 0x66)))
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 1, 3, 0, 2, 0x55, 0x66),
            bytes,
        )
    }

    @Test
    fun roundTripsThroughDayFiles() {
        var now = ms(day, 10)
        val log = BleLog(tmp.root, clockMs = { now }, zone = utc)
        log.record(BleLogFormat.DIR_OUT, 0, byteArrayOf(1)) // disabled: dropped
        log.enabled = true
        val payloads = List(200) { i -> Random(i).nextBytes(i * 3) }
        payloads.forEachIndexed { i, p ->
            now = ms(day, 10L + i)
            log.record(if (i % 2 == 0) BleLogFormat.DIR_OUT else BleLogFormat.DIR_IN, i % 5, p)
        }
        now = ms(day.plusDays(1))
        log.record(BleLogFormat.DIR_IN, 1, byteArrayOf(2))
        log.close()

        val files = log.files()
        assertEquals(listOf("2026-11-23.bin", "2026-11-24.bin"), files.map { it.name })
        val read = BleLogFormat.decode(files[0].readBytes())
        assertEquals(0, read.trailingBytes)
        assertEquals(200, read.records.size)
        read.records.forEachIndexed { i, r ->
            assertEquals(ms(day, 10L + i), r.timeMs)
            assertEquals(i % 5, r.pipe)
            assertArrayEquals(payloads[i], r.bytes)
        }
        // the streaming reader agrees with the in-memory one
        assertEquals(read.records, BleLogFormat.read(files[0].inputStream()).toList())
        val second = BleLogFormat.decode(files[1].readBytes()).records.single()
        assertEquals(BleLogFormat.DIR_IN, second.direction)
        assertEquals(1, second.pipe)
    }

    @Test
    fun toleratesACutOffOrGarbageTail() {
        val good = (0 until 5).map { BleLogRecord(it.toLong(), BleLogFormat.DIR_IN, 0, ByteArray(10) { b -> b.toByte() }) }
        val whole = good.fold(ByteArray(0)) { acc, r -> acc + BleLogFormat.encode(r) }
        // every possible cut point keeps all whole records before it
        for (cut in 0..whole.size) {
            val part = whole.copyOf(cut)
            val read = BleLogFormat.decode(part)
            val wholeRecords = cut / 22
            assertEquals(good.take(wholeRecords), read.records)
            assertEquals(cut - wholeRecords * 22, read.trailingBytes)
            assertEquals(good.take(wholeRecords), BleLogFormat.read(ByteArrayInputStream(part)).toList())
        }
        // a header claiming more bytes than remain is treated as a cut-off tail
        val bogus = whole + byteArrayOf(0, 0, 0, 0, 0, 0, 0, 9, 1, 0, 0x7f, 0x7f, 1, 2, 3)
        val read = BleLogFormat.decode(bogus)
        assertEquals(good, read.records)
        assertEquals(15, read.trailingBytes)
    }

    @Test
    fun randomBytesNeverCrashTheReaders() {
        val rnd = Random(42)
        repeat(500) {
            val junk = rnd.nextBytes(rnd.nextInt(0, 300))
            val read = BleLogFormat.decode(junk)
            assertTrue(read.trailingBytes in 0..junk.size)
            assertEquals(read.records, BleLogFormat.read(ByteArrayInputStream(junk)).toList())
        }
    }

    @Test
    fun appendsAfterACrashLeftAPartialRecord() {
        val now = ms(day)
        val log = BleLog(tmp.root, clockMs = { now }, zone = utc)
        log.enabled = true
        log.record(BleLogFormat.DIR_OUT, 0, byteArrayOf(1, 2, 3))
        log.close()
        val f = log.files().single()
        f.appendBytes(byteArrayOf(0, 0, 0)) // torn write
        val read = BleLogFormat.decode(f.readBytes())
        assertEquals(1, read.records.size)
        assertEquals(3, read.trailingBytes)
    }

    @Test
    fun deletesFilesOlderThanSevenDays() {
        for (d in 0..10L) File(tmp.root, "${day.minusDays(d)}.bin").writeBytes(byteArrayOf())
        File(tmp.root, "notes.txt").writeText("keep")
        val log = BleLog(tmp.root, clockMs = { ms(day) }, zone = utc)
        log.enabled = true // runs the cleanup
        assertEquals((0..7L).map { "${day.minusDays(it)}.bin" }.sorted(), log.files().map { it.name })
        assertTrue(File(tmp.root, "notes.txt").exists())
    }

    /** garmin's replay harness reads captures with DataInputStream (big-endian): both sides must agree. */
    @Test
    fun matchesDataStreamBigEndianLayout() {
        val log = BleLog(tmp.root, clockMs = { 0x0102030405060708 }, zone = utc)
        log.enabled = true
        log.record(BleLogFormat.DIR_OUT, 0, ByteArray(300) { it.toByte() })
        log.record(BleLogFormat.DIR_IN, 1, byteArrayOf(9))
        log.close()
        DataInputStream(log.files().single().inputStream()).use { inp ->
            assertEquals(0x0102030405060708, inp.readLong())
            assertEquals(0, inp.readUnsignedByte()) // phone write to watch
            assertEquals(0, inp.readUnsignedByte()) // GFDI pipe
            assertEquals(300, inp.readUnsignedShort())
            inp.skipBytes(300)
            assertEquals(0x0102030405060708, inp.readLong())
            assertEquals(1, inp.readUnsignedByte()) // notification from watch
            assertEquals(1, inp.readUnsignedByte()) // watch-app pipe
            assertEquals(1, inp.readUnsignedShort())
            assertEquals(9, inp.readUnsignedByte())
            assertEquals(-1, inp.read())
        }
        // and our writer agrees with a DataOutputStream writer
        val viaData = ByteArrayOutputStream().also { bos ->
            DataOutputStream(bos).use { it.writeLong(5); it.writeByte(1); it.writeByte(0); it.writeShort(2); it.write(byteArrayOf(7, 8)) }
        }.toByteArray()
        assertArrayEquals(viaData, BleLogFormat.encode(BleLogRecord(5, BleLogFormat.DIR_IN, 0, byteArrayOf(7, 8))))
    }
}
