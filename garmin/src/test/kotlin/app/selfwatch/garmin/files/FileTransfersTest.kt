/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.files

import app.selfwatch.garmin.FakeFile
import app.selfwatch.garmin.FakeWatch
import app.selfwatch.garmin.gfdi.GfdiChannel
import app.selfwatch.garmin.gfdi.GfdiMessage
import app.selfwatch.garmin.gfdi.SystemEventType
import app.selfwatch.garmin.link.LinkSelector
import app.selfwatch.model.fake.FakeBlePort
import app.selfwatch.model.result.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTransfersTest {
    private class Rig(val watch: FakeWatch, val transfers: FileTransfers)

    /** A V1 link to a fake watch, with incoming FILE_TRANSFER_DATA routed to the transfers as the session does. */
    private suspend fun TestScope.rig(files: List<FakeFile>, scope: CoroutineScope = backgroundScope): Rig {
        val port = FakeBlePort(maxWrite = 40)
        val watch = FakeWatch(scope, port, FakeWatch.Mode.V1, files)
        watch.start()
        val transport = (LinkSelector.open(port, scope) as Outcome.Ok).value
        val ch = GfdiChannel(transport, scope)
        val t = FileTransfers(ch)
        scope.launch { ch.incoming.collect { if (it is GfdiMessage.FileTransferData) t.onChunk(it) } }
        return Rig(watch, t)
    }

    @Test
    fun downloadsAFileInChunks() = runTest {
        val bytes = ByteArray(1_234) { (it * 13).toByte() }
        val r = rig(listOf(FakeFile(9, 32, 1, bytes)))
        val progress = ArrayList<Int>()
        val got = (r.transfers.download(9, 10_000) { done, _ -> progress.add(done) } as Outcome.Ok).value
        assertArrayEquals(bytes, got)
        assertEquals((1..12).map { it * 100 } + 1_234, progress)
    }

    @Test
    fun refusesMissingAndOversizedFiles() = runTest {
        val r = rig(listOf(FakeFile(9, 32, 1, ByteArray(500))))
        assertTrue(r.transfers.download(8, 10_000) is Outcome.Fail)
        assertTrue(r.transfers.download(9, 100) is Outcome.Fail)
    }

    @Test
    fun crcMismatchFails() = runTest {
        val r = rig(listOf(FakeFile(9, 32, 1, ByteArray(500) { 1 })))
        r.watch.corruptChunk = 9 to 200
        assertTrue(r.transfers.download(9, 10_000) is Outcome.Fail)
        // The next download is clean.
        assertTrue(r.transfers.download(9, 10_000) is Outcome.Ok)
    }

    @Test
    fun archiveSetsTheFlag() = runTest {
        val r = rig(emptyList())
        assertEquals(Outcome.Ok(Unit), r.transfers.archive(12))
        assertEquals(listOf(12), r.watch.archived)
    }

    @Test
    fun uploadSkeletonFollowsTheReferenceFlow() = runTest {
        val r = rig(emptyList())
        r.transfers.maxPacketSize = 64
        val prg = ByteArray(700) { (it * 5).toByte() }
        val index = (r.transfers.upload(prg, 255, 17) as Outcome.Ok).value
        assertEquals(20, index)
        assertArrayEquals(prg, r.watch.uploaded.toByteArray())
        delay(100)
        val sent = r.watch.fromPhone
        assertTrue(sent.any { it is GfdiMessage.CreateFile && it.dataType == 255 && it.subType == 17 && it.size == 700 })
        assertTrue(sent.filterIsInstance<GfdiMessage.FileTransferData>().all { it.data.size <= 64 - 13 })
        assertEquals(SystemEventType.SYNC_COMPLETE, (sent.last { it is GfdiMessage.SystemEvent } as GfdiMessage.SystemEvent).type)
    }
}
