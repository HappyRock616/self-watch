/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.selfwatch.garmin

import app.selfwatch.garmin.files.FitDecoders
import app.selfwatch.garmin.files.FitFileKind
import app.selfwatch.garmin.gfdi.Bytes
import app.selfwatch.garmin.gfdi.GfdiId
import app.selfwatch.garmin.gfdi.GfdiMessage
import app.selfwatch.garmin.gfdi.GfdiReplies
import app.selfwatch.garmin.gfdi.GfdiStatus
import app.selfwatch.garmin.gfdi.SystemEventType
import app.selfwatch.garmin.models.GarminModels
import app.selfwatch.garmin.session.GarminCapability
import app.selfwatch.garmin.session.GarminConfig
import app.selfwatch.garmin.session.SessionState
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.device.Feature
import app.selfwatch.model.device.SupportTier
import app.selfwatch.model.device.SyncReason
import app.selfwatch.model.fake.FakeBlePort
import app.selfwatch.model.fake.FakeClock
import app.selfwatch.model.fake.FakeFileStore
import app.selfwatch.model.fake.RecordingHealthSink
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.Workout
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.result.valueOrNull
import app.selfwatch.model.watch.IngestBatch
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchEvent
import app.selfwatch.model.ble.BlePort
import app.selfwatch.model.ble.CloseReason
import app.selfwatch.model.ble.HasCloseReason
import app.selfwatch.model.ble.PortState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import kotlin.coroutines.EmptyCoroutineContext

/** Decoders that record what they got and produce one activity minute per file. */
class FakeDecoders(private val failKinds: Set<FitFileKind> = emptySet()) : FitDecoders {
    val decoded: MutableList<Pair<FitFileKind, Int>> = Collections.synchronizedList(ArrayList())
    override fun supports(kind: FitFileKind): Boolean = true
    override fun decode(kind: FitFileKind, bytes: ByteArray, deviceId: Long, fitPath: String?): Outcome<IngestBatch> {
        decoded.add(kind to bytes.size)
        if (kind in failKinds) return Outcome.Fail(SwError.BadInput("test"))
        val minute = ActivityMinute(deviceId, bytes.size.toLong(), bytes.size, 0, 0, 0, null)
        val workout = if (kind == FitFileKind.ACTIVITY) {
            Workout(0, deviceId, 1, null, 0, 60, 60, null, null, null, null, null, null, false, fitPath, null, null, DataSource.WATCH)
        } else {
            null
        }
        return Outcome.Ok(IngestBatch(key = null, activity = listOf(minute), workout = workout))
    }
}

class GarminDriverTest {
    private val t0 = 1_795_435_200L

    private fun files(): List<FakeFile> = listOf(
        FakeFile(3, FitFileKind.MONITOR.subType, FakeWatch.gt(t0 - 3_600), ByteArray(450) { (it * 7).toByte() }),
        FakeFile(4, FitFileKind.SLEEP.subType, FakeWatch.gt(t0 - 7_200), ByteArray(130) { (it * 3).toByte() }),
        FakeFile(5, FitFileKind.ACTIVITY.subType, FakeWatch.gt(t0 - 900), ByteArray(1_000) { (it xor 0x5A).toByte() }),
        FakeFile(6, 99, FakeWatch.gt(t0), ByteArray(50)), // a FIT type we do not fetch
        FakeFile(7, 0, FakeWatch.gt(t0), ByteArray(50), dataType = 255), // not FIT
    )

    private class Rig(
        val port: FakeBlePort,
        val watch: FakeWatch,
        val driver: GarminDriver,
        val sink: RecordingHealthSink,
        val store: FakeFileStore,
        val decoders: FakeDecoders,
    )

    private fun TestScope.rig(
        mode: FakeWatch.Mode = FakeWatch.Mode.V2_MLR,
        files: List<FakeFile> = files(),
        config: GarminConfig = GarminConfig(syncOnConnect = false),
        decoders: FakeDecoders = FakeDecoders(),
        withApp: Boolean = false,
        caps: Set<GarminCapability>? = null,
        scope: CoroutineScope = backgroundScope,
    ): Rig {
        val port = FakeBlePort(maxWrite = 64)
        val watch = if (caps == null) {
            FakeWatch(scope, port, mode, files, withWatchAppService = withApp)
        } else {
            FakeWatch(scope, port, mode, files, capabilities = caps, withWatchAppService = withApp)
        }
        watch.start()
        val models = GarminModels.load().valueOrNull()
        val driver = GarminDriver(7, config, FakeClock(t0), models, decoders, EmptyCoroutineContext)
        return Rig(port, watch, driver, RecordingHealthSink(), FakeFileStore(), decoders)
    }

    private suspend fun Rig.awaitReady() = withTimeout(60_000) { driver.sessionState.first { it == SessionState.READY } }

    private fun Rig.disconnect() = port.allPipes().forEach { it.endIncoming() }

    private fun TestScope.connect(r: Rig) = launch { r.driver.run(r.port, r.sink, r.store) }

    @Test
    fun reachesReadyOverReliableMultiLink() = runTest { readyFor(FakeWatch.Mode.V2_MLR) }

    @Test
    fun reachesReadyOverPlainMultiLink() = runTest { readyFor(FakeWatch.Mode.V2_PLAIN) }

    @Test
    fun reachesReadyOverV1() = runTest { readyFor(FakeWatch.Mode.V1) }

    private suspend fun TestScope.readyFor(mode: FakeWatch.Mode) {
        val r = rig(mode)
        val job = connect(r)
        r.awaitReady()
        advanceUntilIdle()
        val info = r.driver.info.value
        assertNotNull(info)
        info!!
        assertEquals(4426, info.productId)
        assertEquals("12.34", info.firmware)
        assertEquals(3_456_789_012L, info.serial)
        assertEquals("vívoactive 5", info.model)
        assertEquals(SupportTier.VERIFIED, info.tier)
        assertTrue(Feature.TIME_SYNC in info.features)
        assertTrue(Feature.HEALTH_SLEEP in info.features)
        assertTrue("GNCS flag (A16)", Feature.NOTIFICATIONS in info.features)
        assertTrue("FIND_MY_PHONE flag (A19)", Feature.FIND_PHONE in info.features)
        assertFalse("no music flag", Feature.MUSIC in info.features)
        assertFalse("no watch-app pipe", Feature.WATCH_APP_MESSAGES in info.features)
        assertEquals(ConnectionState.Ready, r.driver.state.value)

        // Handshake order as in the reference: ACK + info reply to DEVICE_INFORMATION, ACK + our CONFIGURATION.
        val sent = r.watch.fromPhone.toList()
        val infoReplies = sent.filterIsInstance<GfdiMessage.Response>().filter { it.originalId == GfdiId.DEVICE_INFORMATION }
        assertEquals(2, infoReplies.size)
        val reply = GfdiReplies.DeviceInfoReply.parse(infoReplies[1].extra.toByteArray())
        assertEquals(150, reply.protocolVersion)
        assertEquals("Self Watch", reply.manufacturer)
        assertEquals("Android", reply.model)
        assertEquals(1, reply.protocolFlags)
        val ourConfig = sent.filterIsInstance<GfdiMessage.Configuration>().single()
        assertEquals(GarminCapability.SELF_WATCH, GarminCapability.fromBits(ourConfig.capabilityBits.toByteArray()))
        val events = sent.filterIsInstance<GfdiMessage.SystemEvent>().map { it.type }
        assertEquals(listOf(SystemEventType.TIME_UPDATED, SystemEventType.SYNC_READY), events)
        assertTrue(sent.any { it is GfdiMessage.SupportedFileTypesRequest })
        assertTrue(sent.any { it is GfdiMessage.DeviceSettings })

        r.disconnect()
        job.join()
        assertEquals(SessionState.DISCONNECTED, r.driver.sessionState.value)
        assertEquals(ConnectionState.Disconnected, r.driver.state.value)
    }

    @Test
    fun multiLinkStartsWithCloseAllThenRegistersGfdiReliably() = runTest {
        val r = rig()
        val job = connect(r)
        r.awaitReady()
        val c = r.watch.controlRequests
        assertEquals("0005" + "0200000000000000" + "0000" + "00", Bytes.hex(c[0]))
        assertEquals("0000" + "0200000000000000" + "0100" + "02", Bytes.hex(c[1]))
        r.disconnect()
        job.join()
    }

    @Test
    fun firstConnectSendsPairingEvents() = runTest {
        val r = rig(config = GarminConfig(syncOnConnect = false, firstConnect = true))
        val job = connect(r)
        r.awaitReady()
        advanceUntilIdle()
        val events = r.watch.fromPhone.filterIsInstance<GfdiMessage.SystemEvent>().map { it.type }
        assertEquals(
            listOf(SystemEventType.TIME_UPDATED, SystemEventType.SYNC_READY, SystemEventType.PAIR_COMPLETE,
                SystemEventType.SYNC_COMPLETE, SystemEventType.SETUP_WIZARD_COMPLETE),
            events,
        )
        r.disconnect()
        job.join()
    }

    @Test
    fun answersTimeRequest() = runTest {
        val r = rig()
        val job = connect(r)
        r.awaitReady()
        r.watch.requestTime(0x1234)
        advanceUntilIdle()
        val t = r.watch.timeReplies.single()
        assertEquals(0x1234, t.referenceId)
        assertEquals(FakeWatch.gt(t0).toInt(), t.garminTime)
        assertEquals(-5 * 3600, t.tzOffsetSec) // America/Detroit in November
        r.disconnect()
        job.join()
    }

    @Test
    fun batteryBecomesEventAndInfo() = runTest {
        val r = rig()
        val events = Collections.synchronizedList(ArrayList<WatchEvent>())
        backgroundScope.launch { r.driver.events.collect { events.add(it) } }
        val job = connect(r)
        r.awaitReady()
        r.watch.sendGfdi(GfdiMessage.BatteryStatus(0x10, 40, 87, Bytes(ByteArray(4))))
        advanceUntilIdle()
        assertTrue(WatchEvent.Battery(87) in events)
        assertEquals(87, r.driver.info.value?.batteryPct)
        assertTrue(r.watch.fromPhone.any { it is GfdiMessage.Response && it.originalId == GfdiId.BATTERY_STATUS && it.status == GfdiStatus.ACK })
        r.disconnect()
        job.join()
    }

    @Test
    fun syncDownloadsDecodesIngestsAndArchivesInOrder() = runTest {
        val r = rig()
        val job = connect(r)
        r.awaitReady()
        val report = r.driver.sync(SyncReason.USER)
        val rep = (report as Outcome.Ok).value
        assertEquals(3, rep.filesNew)
        assertEquals(0, rep.filesFailed)
        // Activities first, then sleep, then monitoring.
        assertEquals(listOf(FitFileKind.ACTIVITY to 1_000, FitFileKind.SLEEP to 130, FitFileKind.MONITOR to 450), r.decoders.decoded)
        val batches = r.sink.batches()
        assertEquals(listOf(5, 4, 3), batches.map { it.key?.fileIndex })
        assertEquals(7L, batches[0].key?.deviceId)
        assertEquals(t0 - 900, batches[0].key?.tSec)
        assertEquals(listOf(5, 4, 3), r.watch.archived)
        // The workout file was saved as received and its path handed to the decoder.
        val saved = r.store.saved()
        assertEquals(1, saved.size)
        assertTrue(saved.values.single().contentEquals(r.watch.files.first { it.index == 5 }.bytes))
        assertEquals(saved.keys.single(), batches[0].workout?.fitPath)
        advanceUntilIdle()
        assertTrue(r.watch.fromPhone.last { it is GfdiMessage.SystemEvent }.let { (it as GfdiMessage.SystemEvent).type == SystemEventType.SYNC_COMPLETE })
        assertEquals(ConnectionState.Ready, r.driver.state.value)

        // A second sync finds nothing new.
        val again = (r.driver.sync(SyncReason.USER) as Outcome.Ok).value
        assertEquals(0, again.filesNew)
        r.disconnect()
        job.join()
    }

    @Test
    fun syncOnConnectRunsByItself() = runTest {
        val r = rig(config = GarminConfig(syncOnConnect = true))
        val job = connect(r)
        r.awaitReady()
        advanceUntilIdle()
        assertEquals(3, r.sink.batches().size)
        r.disconnect()
        job.join()
    }

    @Test
    fun failedIngestIsNotArchived() = runTest {
        val r = rig()
        val job = connect(r)
        r.awaitReady()
        r.sink.failNext = SwError.Io("disk full")
        val rep = (r.driver.sync(SyncReason.USER) as Outcome.Ok).value
        assertEquals(2, rep.filesNew)
        assertEquals(1, rep.filesFailed)
        assertEquals(listOf(4, 3), r.watch.archived) // the activity (first) failed to store
        r.disconnect()
        job.join()
    }

    @Test
    fun failedDecodeIsNotIngestedOrArchived() = runTest {
        val r = rig(decoders = FakeDecoders(failKinds = setOf(FitFileKind.SLEEP)))
        val job = connect(r)
        r.awaitReady()
        val rep = (r.driver.sync(SyncReason.USER) as Outcome.Ok).value
        assertEquals(1, rep.filesFailed)
        assertEquals(listOf(5, 3), r.watch.archived)
        r.disconnect()
        job.join()
    }

    @Test
    fun corruptChunkFailsOnlyThatFile() = runTest {
        val r = rig()
        r.watch.corruptChunk = 3 to 100
        val job = connect(r)
        r.awaitReady()
        val rep = (r.driver.sync(SyncReason.USER) as Outcome.Ok).value
        assertEquals(2, rep.filesNew)
        assertEquals(1, rep.filesFailed)
        assertFalse(3 in r.watch.archived)
        r.disconnect()
        job.join()
    }

    @Test
    fun alreadyHadFilesAreSkippedAndArchived() = runTest {
        val r = rig()
        val f = r.watch.files.first { it.index == 4 }
        r.sink.markHave(app.selfwatch.garmin.files.SyncPlan.keyOf(7, f.entry()))
        val job = connect(r)
        r.awaitReady()
        val rep = (r.driver.sync(SyncReason.USER) as Outcome.Ok).value
        assertEquals(2, rep.filesNew)
        assertEquals(listOf(4, 5, 3), r.watch.archived) // already-had file archived first
        r.disconnect()
        job.join()
    }

    @Test
    fun unknownMessageGetsUnsupported() = runTest {
        val r = rig()
        val job = connect(r)
        r.awaitReady()
        r.watch.sendGfdi(GfdiMessage.Unknown(5999, Bytes(byteArrayOf(1, 2, 3))))
        r.watch.sendGfdi(GfdiMessage.Unknown(GfdiId.MUSIC_CONTROL, Bytes(byteArrayOf(1))))
        advanceUntilIdle()
        val unsupported = r.watch.fromPhone.filterIsInstance<GfdiMessage.Response>().filter { it.status == GfdiStatus.UNSUPPORTED }
        assertEquals(listOf(5999, GfdiId.MUSIC_CONTROL), unsupported.map { it.originalId })
        r.disconnect()
        job.join()
    }

    @Test
    fun authNegotiationGetsReferenceReply() = runTest {
        val r = rig()
        val job = connect(r)
        r.awaitReady()
        r.watch.sendGfdi(GfdiMessage.AuthNegotiation(0, 0x01))
        advanceUntilIdle()
        val resp = r.watch.fromPhone.filterIsInstance<GfdiMessage.Response>().single { it.originalId == GfdiId.AUTH_NEGOTIATION }
        assertEquals(GfdiStatus.ACK, resp.status)
        assertEquals(GfdiReplies.AuthReply(0, 0, 0x01), GfdiReplies.AuthReply.parse(resp.extra.toByteArray()))
        r.disconnect()
        job.join()
    }

    @Test
    fun securePairingIsDetectedAndRefused() = runTest {
        val r = rig(config = GarminConfig(syncOnConnect = false, handshakeTimeoutMs = 10_000))
        val events = Collections.synchronizedList(ArrayList<WatchEvent>())
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { r.driver.events.collect { events.add(it) } }
        val job = connect(r)
        withTimeout(5_000) { r.driver.sessionState.first { it >= SessionState.LINK_UP } }
        r.watch.sendGfdi(GfdiMessage.SecurePairing(5103, Bytes(byteArrayOf(9))))
        r.watch.sendGfdi(GfdiMessage.SecurePairing(5104, Bytes(byteArrayOf(9))))
        withTimeout(5_000) { r.driver.securePairingRequested.first { it } }
        advanceUntilIdle()
        // The event goes out once per connection, however many secure-pairing messages arrive.
        assertEquals(listOf<WatchEvent>(WatchEvent.SecurePairingRequired), events.filter { it == WatchEvent.SecurePairingRequired })
        val resp = r.watch.fromPhone.filterIsInstance<GfdiMessage.Response>().single { it.originalId == 5103 }
        assertEquals(GfdiStatus.UNSUPPORTED, resp.status)
        r.disconnect()
        job.join()
    }

    @Test
    fun handshakeTimeoutFailsAndSaysSecurePairing() = runTest {
        val port = FakeBlePort(maxWrite = 64)
        // A watch that stops after DEVICE_INFORMATION and asks for secure pairing.
        val watch = FakeWatch(backgroundScope, port, FakeWatch.Mode.V2_PLAIN, emptyList(), sendConfiguration = false)
        watch.start()
        val driver = GarminDriver(1, GarminConfig(handshakeTimeoutMs = 3_000), FakeClock(t0), null, FakeDecoders(), EmptyCoroutineContext)
        val job = launch { driver.run(port, RecordingHealthSink(), FakeFileStore()) }
        withTimeout(2_000) { driver.sessionState.first { it == SessionState.INFO_EXCHANGED } }
        watch.sendGfdi(GfdiMessage.SecurePairing(5102, Bytes.EMPTY))
        job.join() // the handshake timeout closes the link
        val failed = driver.state.value as ConnectionState.Failed
        assertTrue((failed.error as SwError.Protocol).detail.contains("secure pairing"))
    }

    /** A port that can say why it closed, like `ble`'s connection. */
    private class ReasonPort(val inner: FakeBlePort) : BlePort by inner, HasCloseReason {
        override val closeReason: MutableStateFlow<CloseReason?> = MutableStateFlow(null)
    }

    @Test
    fun closedPortThatNeedsRepairFailsWithRepairNeeded() = runTest {
        val port = ReasonPort(FakeBlePort(initialState = PortState.CLOSED)).apply { closeReason.value = CloseReason.REPAIR_NEEDED }
        val driver = GarminDriver(1, GarminConfig(), FakeClock(t0), null, FakeDecoders(), EmptyCoroutineContext)
        driver.run(port, RecordingHealthSink(), FakeFileStore())
        assertEquals(ConnectionState.Failed(SwError.Protocol(GarminDriver.REPAIR_NEEDED_DETAIL), null), driver.state.value)
    }

    @Test
    fun closedPortWithoutReasonIsAPlainLinkFailure() = runTest {
        val driver = GarminDriver(1, GarminConfig(), FakeClock(t0), null, FakeDecoders(), EmptyCoroutineContext)
        driver.run(FakeBlePort(initialState = PortState.CLOSED), RecordingHealthSink(), FakeFileStore())
        assertEquals(ConnectionState.Failed(SwError.Io("Bluetooth connection failed"), null), driver.state.value)
    }

    @Test
    fun bondLostDuringASessionFailsWithRepairNeeded() = runTest {
        val r = rig()
        val port = ReasonPort(r.port)
        val job = launch { r.driver.run(port, r.sink, r.store) }
        r.awaitReady()
        port.closeReason.value = CloseReason.REPAIR_NEEDED
        r.port.mutableState.value = PortState.CLOSED
        r.disconnect()
        job.join()
        assertEquals(ConnectionState.Failed(SwError.Protocol("re-pair needed"), null), r.driver.state.value)
    }

    @Test
    fun handshakeTimeoutWithoutConfiguration() = runTest {
        val port = FakeBlePort(maxWrite = 64)
        FakeWatch(backgroundScope, port, FakeWatch.Mode.V2_MLR, emptyList(), sendConfiguration = false).start()
        val driver = GarminDriver(1, GarminConfig(handshakeTimeoutMs = 3_000), FakeClock(t0), null, FakeDecoders(), EmptyCoroutineContext)
        driver.run(port, RecordingHealthSink(), FakeFileStore())
        assertEquals(ConnectionState.Failed(SwError.Timeout("Garmin handshake"), null), driver.state.value)
    }

    @Test
    fun appMessagesFlowThroughTheDriver() = runTest {
        val r = rig(withApp = true)
        val appPipe = r.port.pipe(
            app.selfwatch.garmin.link.GarminUuids.WATCH_APP_SERVICE,
            app.selfwatch.garmin.link.GarminUuids.WATCH_APP_RX,
            app.selfwatch.garmin.link.GarminUuids.WATCH_APP_TX,
        )
        val watchApp = app.selfwatch.garmin.appmsg.ScriptedWatchApp(appPipe)
        val job = connect(r)
        r.awaitReady()
        assertTrue(Feature.WATCH_APP_MESSAGES in r.driver.info.value!!.features)
        val uuid = java.util.UUID.fromString("0f0e0d0c-0b0a-0908-0706-050403020100")
        val value = app.selfwatch.model.watch.CiqValue.Arr(listOf(app.selfwatch.model.watch.CiqValue.Num(5)))
        assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.AppMessage(uuid, value)))
        assertEquals(listOf(uuid to value), watchApp.received)
        r.disconnect()
        job.join()
    }

    @Test
    fun appMessageCommandWithoutChannelIsUnsupported() = runTest {
        val r = rig()
        val job = connect(r)
        r.awaitReady()
        val res = r.driver.send(WatchCommand.AppMessage(java.util.UUID.randomUUID(), app.selfwatch.model.watch.CiqValue.Null))
        assertEquals(Outcome.Fail(SwError.Unsupported(Feature.WATCH_APP_MESSAGES)), res)
        assertEquals(Outcome.Fail(SwError.Unsupported(Feature.MUSIC)), r.driver.send(WatchCommand.Music(app.selfwatch.model.watch.MusicState(true, null, null, null, null, null, null))))
        r.disconnect()
        job.join()
    }

    @Test
    fun syncWhenNotConnectedFails() = runTest {
        val d = GarminDriver(1, GarminConfig(), FakeClock(t0), null, FakeDecoders(), EmptyCoroutineContext)
        assertEquals(Outcome.Fail(SwError.NotConnected(1)), d.sync(SyncReason.USER))
        assertEquals(Outcome.Fail(SwError.NotConnected(1)), d.send(WatchCommand.SyncTime))
    }

    @Test
    fun noGarminServiceFails() = runTest {
        val port = FakeBlePort(services = emptySet())
        val d = GarminDriver(1, GarminConfig(), FakeClock(t0), null, FakeDecoders(), EmptyCoroutineContext)
        d.run(port, RecordingHealthSink(), FakeFileStore())
        assertTrue(d.state.value is ConnectionState.Failed)
    }

    @Test
    fun v0WatchIsRefused() = runTest {
        val port = FakeBlePort(services = setOf(app.selfwatch.garmin.link.GarminUuids.V0_SERVICE))
        val d = GarminDriver(1, GarminConfig(), FakeClock(t0), null, FakeDecoders(), EmptyCoroutineContext)
        d.run(port, RecordingHealthSink(), FakeFileStore())
        val s = d.state.value as ConnectionState.Failed
        assertTrue(s.error is SwError.Protocol)
    }

    @Test
    fun portDisconnectEndsRun() = runTest {
        val r = rig()
        val job = connect(r)
        r.awaitReady()
        r.port.mutableState.value = app.selfwatch.model.ble.PortState.DISCONNECTED
        job.join()
        assertEquals(SessionState.DISCONNECTED, r.driver.sessionState.value)
    }

    @Test
    fun syncWhileSyncingJoinsTheRunningSync() = runTest {
        val r = rig()
        val job = connect(r)
        r.awaitReady()
        val first = async { r.driver.sync(SyncReason.CONNECT) }
        val second = async { r.driver.sync(SyncReason.USER) }
        val a = (first.await() as Outcome.Ok).value
        val b = (second.await() as Outcome.Ok).value
        // A second pass would have found nothing new; joining gives both callers the one report.
        assertEquals(3, a.filesNew)
        assertEquals(a, b)
        assertEquals(3, r.decoders.decoded.size)
        r.disconnect()
        job.join()
    }

    @Test
    fun syncAskedDuringSyncOnConnectJoinsIt() = runTest {
        val r = rig(config = GarminConfig(syncOnConnect = true))
        val job = connect(r)
        r.driver.state.first { it is ConnectionState.Syncing }
        val rep = (r.driver.sync(SyncReason.USER) as Outcome.Ok).value
        assertEquals(3, rep.filesNew)
        assertEquals(3, r.sink.batches().size)
        r.disconnect()
        job.join()
    }

    @Test
    fun runWaitsForThePortToConnect() = runTest {
        val r = rig()
        r.port.mutableState.value = PortState.DISCONNECTED
        val job = connect(r)
        advanceUntilIdle()
        assertEquals(ConnectionState.Connecting, r.driver.state.value)
        assertTrue("nothing sent before the link is up", r.port.allPipes().all { it.writes().isEmpty() })
        assertEquals(SessionState.DISCONNECTED, r.driver.sessionState.value)
        r.port.mutableState.value = PortState.CONNECTING
        advanceUntilIdle()
        r.port.mutableState.value = PortState.CONNECTED
        r.awaitReady()
        r.disconnect()
        job.join()
    }

    @Test
    fun failedConnectAttemptEndsRun() = runTest {
        val r = rig()
        r.port.mutableState.value = PortState.DISCONNECTED
        val job = connect(r)
        advanceUntilIdle()
        r.port.mutableState.value = PortState.CONNECTING
        advanceUntilIdle()
        r.port.mutableState.value = PortState.DISCONNECTED
        job.join()
        assertTrue(r.driver.state.value is ConnectionState.Failed)
        assertEquals(SessionState.DISCONNECTED, r.driver.sessionState.value)
    }

    @Test
    fun closedPortEndsRun() = runTest {
        val r = rig()
        r.port.mutableState.value = PortState.CLOSED
        r.driver.run(r.port, r.sink, r.store)
        assertTrue(r.driver.state.value is ConnectionState.Failed)
    }

    @Test
    fun linkLostWhileOpeningEndsRun() = runTest {
        // A multi-link watch that never answers: opening the link would wait for its reply.
        val port = FakeBlePort(services = setOf(app.selfwatch.garmin.link.GarminUuids.ML_SERVICE))
        val d = GarminDriver(1, GarminConfig(), FakeClock(t0), null, FakeDecoders(), EmptyCoroutineContext)
        val job = launch { d.run(port, RecordingHealthSink(), FakeFileStore()) }
        advanceTimeBy(100)
        assertTrue(job.isActive)
        port.mutableState.value = PortState.DISCONNECTED
        job.join()
        assertTrue(d.state.value is ConnectionState.Failed)
    }

    @Test
    fun connectConfigIsReadOnEveryConnection() = runTest {
        var asks = 0
        val port = FakeBlePort(maxWrite = 64)
        val watch = FakeWatch(backgroundScope, port, FakeWatch.Mode.V2_MLR, files())
        watch.start()
        val d = GarminDriver(
            7, GarminConfig(syncOnConnect = false), FakeClock(t0), GarminModels.load().valueOrNull(), FakeDecoders(),
            EmptyCoroutineContext, connectConfig = { asks++; GarminConfig(syncOnConnect = false, firstConnect = true) },
        )
        val job = launch { d.run(port, RecordingHealthSink(), FakeFileStore()) }
        withTimeout(60_000) { d.sessionState.first { it == SessionState.READY } }
        advanceUntilIdle()
        assertEquals(1, asks)
        assertTrue(watch.fromPhone.any { it is GfdiMessage.SystemEvent && it.type == SystemEventType.PAIR_COMPLETE })
        port.allPipes().forEach { it.endIncoming() }
        job.join()
    }
}
