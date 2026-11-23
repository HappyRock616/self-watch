/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.files

import app.selfwatch.garmin.FakeDecoders
import app.selfwatch.garmin.gfdi.Bytes
import app.selfwatch.garmin.session.GarminTime
import app.selfwatch.model.fake.RecordingHealthSink
import app.selfwatch.model.result.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlanTest {
    @Test
    fun directoryEntryLayout() {
        val bytes = Bytes.fromHex("0500 80 04 0700 01 10 e8030000 00ca9a3b")
        val e = (DirEntry.parseDirectory(bytes) as Outcome.Ok).value.single()
        assertEquals(DirEntry(5, 128, 4, 7, 1, 0x10, 1000, 1_000_000_000L), e)
        assertEquals(FitFileKind.ACTIVITY, e.kind)
        assertTrue(e.archived)
        assertEquals(1_000_000_000L + GarminTime.EPOCH_UNIX_SEC, e.tSec)
        assertEquals(Bytes.hex(bytes), Bytes.hex(e.encode()))
    }

    @Test
    fun zeroTimestampMeansNoDate() {
        assertNull(DirEntry(1, 128, 32, 1, 0, 0, 10, 0).tSec)
    }

    @Test
    fun badDirectoryLengthIsRejected() {
        assertTrue(DirEntry.parseDirectory(ByteArray(15)) is Outcome.Fail)
        assertEquals(0, (DirEntry.parseDirectory(ByteArray(0)) as Outcome.Ok).value.size)
    }

    @Test
    fun ordersByGroupThenAgeAndSkipsWhatItShould() = runTest {
        val entries = listOf(
            DirEntry(1, 128, 32, 1, 0, 0, 100, 500), // monitor, old
            DirEntry(2, 128, 32, 2, 0, 0, 100, 400), // monitor, older
            DirEntry(3, 128, 49, 3, 0, 0, 100, 600), // sleep
            DirEntry(4, 128, 4, 4, 0, 0, 100, 900), // activity
            DirEntry(5, 128, 68, 5, 0, 0, 100, 100), // hrv (other group, last)
            DirEntry(6, 128, 41, 6, 0, 0, 100, 100), // changelog: not fetched
            DirEntry(7, 255, 17, 7, 0, 0, 100, 100), // app: not FIT
            DirEntry(8, 128, 4, 8, 0, 0, SyncPlan.MAX_FILE_BYTES + 1, 100), // too big
            DirEntry(9, 128, 4, 9, 0, 0, 0, 100), // empty
            DirEntry(10, 128, 49, 10, 0, 0x10, 100, 700), // already had and archived
            DirEntry(11, 128, 49, 11, 0, 0, 100, 800), // already had, not archived
        )
        val sink = RecordingHealthSink()
        sink.markHave(SyncPlan.keyOf(1, entries[9]))
        sink.markHave(SyncPlan.keyOf(1, entries[10]))
        val plan = SyncPlan.build(entries, 1, sink, FakeDecoders())
        assertEquals(listOf(4, 3, 2, 1, 5), plan.files.map { it.entry.index })
        assertEquals(listOf(11), plan.toArchive.map { it.entry.index })
        assertEquals(4, plan.skipped)
    }

    @Test
    fun kindsWithoutDecodersAreSkipped() = runTest {
        val entries = listOf(DirEntry(1, 128, 73, 1, 0, 0, 100, 1), DirEntry(2, 128, 49, 2, 0, 0, 100, 1))
        val onlySleep = object : FitDecoders by FitDecoders.None {
            override fun supports(kind: FitFileKind) = kind == FitFileKind.SLEEP
        }
        val plan = SyncPlan.build(entries, 1, RecordingHealthSink(), onlySleep)
        assertEquals(listOf(2), plan.files.map { it.entry.index })
    }

    @Test
    fun keyCarriesUnixTime() {
        val e = DirEntry(4, 128, 4, 4, 0, 0, 321, 1_100_000_000L)
        val k = SyncPlan.keyOf(9, e)
        assertEquals(9, k.deviceId)
        assertEquals(4, k.fileIndex)
        assertEquals(128, k.fileType)
        assertEquals(4, k.fileSubtype)
        assertEquals(1_100_000_000L + 631_065_600L, k.tSec)
        assertEquals(321, k.size)
    }
}
