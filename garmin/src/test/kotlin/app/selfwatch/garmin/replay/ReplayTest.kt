/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.replay

import app.selfwatch.garmin.FakeDecoders
import app.selfwatch.garmin.GarminDriver
import app.selfwatch.garmin.models.GarminModels
import app.selfwatch.garmin.session.GarminConfig
import app.selfwatch.garmin.session.SessionState
import app.selfwatch.model.fake.FakeClock
import app.selfwatch.model.fake.FakeFileStore
import app.selfwatch.model.fake.RecordingHealthSink
import app.selfwatch.model.result.valueOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Replays every recorded session in `testdata/sessions/` (BleLog `.bin` files, T-A08) through the driver:
 * it must reach READY and answer the watch. There are no recordings yet, so this passes vacuously.
 */
class ReplayTest {
    private fun sessionsDir(): File? =
        listOf(File("../testdata/sessions"), File("testdata/sessions")).firstOrNull { it.isDirectory }

    @Test
    fun replaysRecordedSessions() {
        val files = sessionsDir()?.listFiles { f -> f.name.endsWith(".bin") }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) {
            println("ReplayTest: no recorded sessions in testdata/sessions/ yet (T-A08); nothing to replay.")
            return
        }
        for (f in files) replay(f)
    }

    private fun replay(file: File) = runTest {
        val records = BleLogReader.read(file)
        val replay = SessionReplay(records, v1 = file.name.contains(".v1."))
        val driver = GarminDriver(1, GarminConfig(syncOnConnect = true), FakeClock(), GarminModels.load().valueOrNull(), FakeDecoders(), EmptyCoroutineContext)
        val job = launch { driver.run(replay.port, RecordingHealthSink(), FakeFileStore()) }
        var ready = false
        backgroundScope.launch {
            driver.sessionState.first { it >= SessionState.READY }
            ready = true
        }
        val late = replay.play()
        withTimeoutOrNull(30_000) { while (!ready) kotlinx.coroutines.delay(100) }
        replay.end()
        job.join()
        assertTrue("${file.name}: driver did not reach READY ($late notifications arrived before our writes caught up)", ready)
    }

    @Test
    fun bleLogReaderReadsTheWp05Layout() {
        val f = File.createTempFile("blelog", ".bin")
        try {
            DataOutputStream(f.outputStream()).use { o ->
                o.writeLong(1_000); o.writeByte(0); o.writeByte(0); o.writeShort(3); o.write(byteArrayOf(1, 2, 3))
                o.writeLong(1_020); o.writeByte(1); o.writeByte(1); o.writeShort(1); o.write(byteArrayOf(9))
                o.writeLong(1_040); o.writeByte(1) // truncated record
            }
            val r = BleLogReader.read(f)
            assertEquals(2, r.size)
            assertEquals(1_020L, r[1].timeMs)
            assertEquals(BleRecord.FROM_WATCH, r[1].direction)
            assertEquals(1, r[1].pipe)
            assertEquals(listOf<Byte>(1, 2, 3), r[0].bytes.toList())
        } finally {
            f.delete()
        }
    }
}
