/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.model.fake

import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.device.SyncReason
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.net.HttpRequest
import app.selfwatch.model.net.NetFeature
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.time.Day
import app.selfwatch.model.watch.IngestBatch
import app.selfwatch.model.watch.SyncFileKey
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.util.UUID

class FixturesTest {
    private val svc = UUID.fromString("6a4e2800-667b-11e3-949a-0800200c9a66")
    private val rx = UUID.fromString("6a4e2810-667b-11e3-949a-0800200c9a66")
    private val tx = UUID.fromString("6a4e2820-667b-11e3-949a-0800200c9a66")

    @Test fun fakeClock() {
        val c = FakeClock()
        assertEquals(Day(20261123), c.today())
        c.advance(12 * 3600)
        assertEquals(Day(20261124), Day.of(c.nowSec(), ZoneId.of("UTC")))
        assertEquals(Day(20261123), c.today()) // still the 23rd in Detroit (19:00)
        c.setZone(ZoneId.of("Asia/Tokyo"))
        assertEquals(Day(20261124), c.today())
        c.set(0)
        assertEquals(0L, c.nowSec())
    }

    @Test fun fakeBlePortScriptsPipes() = runTest {
        val port = FakeBlePort(services = setOf(svc), maxWrite = 8)
        assertEquals(setOf(svc), port.serviceUuids())
        val pipe = port.pipe(svc, rx, tx)
        pipe.onWrite = { p, bytes -> p.queueIncoming(byteArrayOf(0x7F) + bytes) } // echo with a prefix
        pipe.queueIncoming(byteArrayOf(1))

        val opened = (port.openPipe(svc, rx, tx) as Outcome.Ok).value
        assertTrue(opened === pipe)
        assertEquals(Outcome.Ok(Unit), opened.write(byteArrayOf(2, 3)))
        assertTrue(opened.write(ByteArray(9)) is Outcome.Fail) // over maxWrite

        val got = opened.incoming.take(2).toList()
        assertArrayEquals(byteArrayOf(1), got[0])
        assertArrayEquals(byteArrayOf(0x7F, 2, 3), got[1])
        assertEquals(1, pipe.writes().size)
        assertArrayEquals(byteArrayOf(2, 3), pipe.writes()[0])

        port.setHighSpeed(true)
        assertEquals(listOf(true), port.highSpeedCalls)

        pipe.writeError = SwError.Timeout("write")
        assertEquals(Outcome.Fail(SwError.Timeout("write")), opened.write(byteArrayOf(1)))
        opened.close()
        assertTrue(pipe.closed)
        assertTrue(opened.write(byteArrayOf(1)) is Outcome.Fail)
        assertEquals(emptyList<ByteArray>(), opened.incoming.toList()) // closed flow completes

        port.openPipeError = SwError.NotConnected(1)
        assertEquals(Outcome.Fail(SwError.NotConnected(1)), port.openPipe(svc, rx, tx))
    }

    @Test fun fakeWatchDriverRunsUntilDisconnect() = runTest {
        val driver = FakeWatchDriver()
        val sink = RecordingHealthSink()
        val files = FakeFileStore()
        val port = FakeBlePort()
        val job = launch { driver.run(port, sink, files) }
        driver.state.first { it == ConnectionState.Ready }
        assertTrue(driver.lastPort === port)

        assertTrue(driver.sync(SyncReason.USER) is Outcome.Ok)
        val cmd = WatchCommand.FindWatch(ring = true)
        assertEquals(Outcome.Ok(Unit), driver.send(cmd))
        assertEquals(listOf(SyncReason.USER), driver.syncCalls)
        assertEquals(listOf<WatchCommand>(cmd), driver.sent)

        val events = launch { assertEquals(WatchEvent.Battery(80), driver.events.first()) }
        yield()
        assertTrue(driver.emit(WatchEvent.Battery(80)))
        events.join()

        driver.disconnect()
        job.join()
        assertEquals(ConnectionState.Disconnected, driver.state.value)
        assertEquals(1, driver.runCount)
    }

    @Test fun recordingHealthSink() = runTest {
        val sink = RecordingHealthSink()
        val key = SyncFileKey(1, 5, 32, 3, 1_795_000_000, 1024)
        assertFalse(sink.alreadyHave(key))
        val batch = IngestBatch(key, metrics = listOf(MetricSample(1, MetricKind.HEART_RATE, 1_795_000_000, 60)))

        sink.failNext = SwError.Io("disk full")
        assertTrue(sink.ingest(batch) is Outcome.Fail)
        assertFalse(sink.alreadyHave(key))

        assertEquals(Outcome.Ok(Unit), sink.ingest(batch))
        assertTrue(sink.alreadyHave(key))
        assertEquals(listOf(batch), sink.batches())

        val files = FakeFileStore()
        val path = files.saveWorkoutFit(1, 99, byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), files.saved()[path])
    }

    @Test fun fakeHttp() = runTest {
        val http = FakeHttp()
        http.on("https://food.example/", Outcome.Ok(app.selfwatch.model.net.HttpResponse(200, emptyMap(), "route".toByteArray())))
        http.enqueue(201, "queued")
        val req = HttpRequest(NetFeature.FOOD_DATA, "GET", "https://food.example/a")

        val first = (http.send(req) as Outcome.Ok).value
        assertEquals(201, first.status)
        assertEquals("route", String((http.send(req) as Outcome.Ok).value.body))
        assertEquals(404, (http.send(req.copy(url = "https://other.example/")) as Outcome.Ok).value.status)

        http.blocked += NetFeature.WATCH_APP_WEB
        val blockedReq = req.copy(feature = NetFeature.WATCH_APP_WEB)
        assertEquals(Outcome.Fail(SwError.Blocked(NetFeature.WATCH_APP_WEB)), http.send(blockedReq))
        assertEquals(4, http.requests().size)
        assertEquals(req, http.requests()[0])

        val f = File.createTempFile("fakehttp", ".bin")
        try {
            var progress = 0L
            assertEquals(Outcome.Ok(5L), http.download(req, f) { done, _ -> progress = done })
            assertEquals(5L, progress)
            assertEquals("route", f.readText())
        } finally {
            f.delete()
        }
    }
}
