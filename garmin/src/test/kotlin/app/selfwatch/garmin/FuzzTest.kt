/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.selfwatch.garmin

import app.selfwatch.garmin.gfdi.GfdiFrame
import app.selfwatch.garmin.gfdi.GfdiId
import app.selfwatch.garmin.gfdi.GfdiMessage
import app.selfwatch.garmin.link.CobsDecoder
import app.selfwatch.garmin.link.GarminUuids
import app.selfwatch.garmin.link.Mlr
import app.selfwatch.garmin.models.GarminModels
import app.selfwatch.garmin.session.GarminConfig
import app.selfwatch.garmin.session.SessionState
import app.selfwatch.model.device.SyncReason
import app.selfwatch.model.fake.FakeBlePort
import app.selfwatch.model.fake.FakeClock
import app.selfwatch.model.fake.FakeFileStore
import app.selfwatch.model.fake.RecordingHealthSink
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.valueOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random

/** Random and truncated input never crashes or hangs anything (README rule: corrupt input must never crash). */
class FuzzTest {
    private val ids = listOf(
        GfdiId.RESPONSE, GfdiId.DOWNLOAD_REQUEST, GfdiId.FILE_TRANSFER_DATA, GfdiId.CREATE_FILE, GfdiId.FILE_AVAILABLE,
        GfdiId.BATTERY_STATUS, GfdiId.DEVICE_INFORMATION, GfdiId.DEVICE_SETTINGS, GfdiId.SYSTEM_EVENT,
        GfdiId.SYNCHRONIZATION, GfdiId.CONFIGURATION, GfdiId.CURRENT_TIME_REQUEST, GfdiId.AUTH_NEGOTIATION,
        5105, 5043, 5999, 0, 0xFFFF,
    )

    @Test
    fun decodingRandomFramesNeverThrows() {
        val rnd = Random(1)
        repeat(20_000) {
            val payload = ByteArray(rnd.nextInt(0, 40)) { rnd.nextInt(256).toByte() }
            val id = ids[rnd.nextInt(ids.size)]
            val frame = GfdiFrame.build(id, payload)
            val m = (GfdiMessage.decodeFrame(frame) as Outcome.Ok).value
            assertEquals(if (id and 0x8000 != 0) (id and 0xFF) + 5000 else id, m.id)
            // Truncated or bit-flipped frames are rejected, not thrown.
            GfdiMessage.decodeFrame(frame.copyOf(rnd.nextInt(frame.size)))
            val flipped = frame.copyOf().also { it[rnd.nextInt(it.size)] = rnd.nextInt(256).toByte() }
            GfdiMessage.decodeFrame(flipped)
            GfdiMessage.decodeFrame(ByteArray(rnd.nextInt(0, 30)) { rnd.nextInt(256).toByte() })
        }
    }

    @Test
    fun cobsDecoderSurvivesGarbage() {
        val rnd = Random(2)
        val d = CobsDecoder(maxFrame = 1024)
        repeat(20_000) { d.feed(ByteArray(rnd.nextInt(0, 64)) { rnd.nextInt(256).toByte() }) }
    }

    @Test
    fun mlrSurvivesRandomPackets() = runTest {
        val rnd = Random(3)
        val m = Mlr(1, 20, backgroundScope, sendPacket = {}, onData = {})
        repeat(5_000) { m.onPacket(ByteArray(rnd.nextInt(0, 24)) { rnd.nextInt(256).toByte() }) }
        m.close()
    }

    @Test
    fun sessionSurvivesRandomMessagesAndStillSyncs() = runTest {
        val rnd = Random(4)
        val port = FakeBlePort(maxWrite = 64)
        val files = listOf(FakeFile(3, 32, 1_100_000_000L, ByteArray(300) { it.toByte() }))
        val watch = FakeWatch(backgroundScope, port, FakeWatch.Mode.V2_MLR, files)
        watch.start()
        val sink = RecordingHealthSink()
        val driver = GarminDriver(1, GarminConfig(syncOnConnect = false), FakeClock(), GarminModels.load().valueOrNull(), FakeDecoders(), EmptyCoroutineContext)
        val job = launch { driver.run(port, sink, FakeFileStore()) }
        withTimeout(60_000) { driver.sessionState.first { it == SessionState.READY } }
        repeat(500) {
            val id = ids[rnd.nextInt(ids.size)]
            // Leave out the handshake messages: a second DEVICE_INFORMATION would legitimately restart things.
            if (id == GfdiId.DEVICE_INFORMATION || id == GfdiId.CONFIGURATION) return@repeat
            val payload = ByteArray(rnd.nextInt(0, 30)) { rnd.nextInt(256).toByte() }
            watch.sendFrame(GfdiFrame.build(id, payload))
            if (rnd.nextInt(10) == 0) watch.sendFrame(ByteArray(rnd.nextInt(1, 20)) { rnd.nextInt(256).toByte() })
        }
        advanceUntilIdle()
        assertEquals(SessionState.READY, driver.sessionState.value)
        // Fuzzed SYNCHRONIZATION / FILE_AVAILABLE may already have started a sync; either way the file ends up stored once.
        assertTrue(driver.sync(SyncReason.USER) is Outcome.Ok)
        assertEquals(listOf(3), sink.batches().map { it.key?.fileIndex })
        port.allPipes().forEach { it.endIncoming() }
        job.join()
    }

    @Test
    fun rawGarbageOnTheMultiLinkPipeNeverHangs() = runTest {
        val rnd = Random(5)
        val port = FakeBlePort(services = setOf(GarminUuids.ML_SERVICE), maxWrite = 64)
        val pipe = port.pipe(GarminUuids.ML_SERVICE, GarminUuids.ML_RX[0], GarminUuids.ML_TX[0])
        repeat(2_000) { pipe.queueIncoming(ByteArray(rnd.nextInt(0, 30)) { rnd.nextInt(256).toByte() }) }
        val driver = GarminDriver(1, GarminConfig(handshakeTimeoutMs = 5_000), FakeClock(), null, FakeDecoders(), EmptyCoroutineContext)
        driver.run(port, RecordingHealthSink(), FakeFileStore()) // must return (registration or handshake fails)
        assertTrue(driver.sessionState.value == SessionState.DISCONNECTED)
    }
}

/** Random and malformed Connect IQ protobuf messages (apps, settings, web, data transfer; A21–A23) never break the session. */
class CiqProtobufFuzzTest {
    private fun rb(rnd: Random, max: Int) = com.google.protobuf.ByteString.copyFrom(ByteArray(rnd.nextInt(0, max)) { rnd.nextInt(256).toByte() })

    @Test
    fun randomCiqServiceMessagesNeverCrash() = runTest {
        val rnd = Random(21)
        val caps = setOf(
            app.selfwatch.garmin.session.GarminCapability.CONNECTIQ_HTTP, app.selfwatch.garmin.session.GarminCapability.CONNECTIQ_SETTINGS,
            app.selfwatch.garmin.session.GarminCapability.CONNECTIQ_APP_MANAGEMENT, app.selfwatch.garmin.session.GarminCapability.HTTP_RAW_RESOURCE_REQUEST,
        )
        with(FeatureRig.Companion) {
            withRig(caps, webTimeoutMs = 1_000) { r ->
                repeat(300) { i ->
                    val smart = app.selfwatch.garmin.pb.proto.SmartProto.Smart.newBuilder()
                    when (rnd.nextInt(5)) {
                        0 -> smart.setConnectIqHttpService(
                            app.selfwatch.garmin.pb.proto.HttpProto.HttpService.newBuilder().setConnectIqHttpRequest(
                                app.selfwatch.garmin.pb.proto.HttpProto.WebRequest.newBuilder().setUrl(String(CharArray(rnd.nextInt(0, 20)) { (32 + rnd.nextInt(95)).toChar() }))
                                    .setHttpHeaderFields(rb(rnd, 40)).setHttpBody(rb(rnd, 60)).setMaxResponseLength(rnd.nextInt()),
                            ),
                        )
                        1 -> smart.setConnectIqAppSettingsService(
                            app.selfwatch.garmin.pb.proto.AppSettingsProto.AppSettingsService.newBuilder().setSendAppSettingsRequest(
                                app.selfwatch.garmin.pb.proto.AppSettingsProto.SendAppSettingsRequest.newBuilder().setAppIdentifier(rb(rnd, 20)).setSettingsValues(rb(rnd, 60)),
                            ),
                        )
                        2 -> smart.setDataTransferService(
                            app.selfwatch.garmin.pb.proto.DataTransferProto.DataTransferService.newBuilder().setDataDownloadRequest(
                                app.selfwatch.garmin.pb.proto.DataTransferProto.DataDownloadRequest.newBuilder().setId(rnd.nextInt()).setOffset(rnd.nextInt()).setMaxChunkSize(rnd.nextInt()),
                            ),
                        )
                        3 -> smart.setConnectIqInstalledAppsService(
                            app.selfwatch.garmin.pb.proto.InstalledAppsProto.InstalledAppsService.newBuilder().setGetInstalledAppsResponse(
                                app.selfwatch.garmin.pb.proto.InstalledAppsProto.GetInstalledAppsResponse.newBuilder().setAvailableSpace(1).setAvailableSlots(1).addInstalledApp(
                                    app.selfwatch.garmin.pb.proto.InstalledAppsProto.GetInstalledAppsResponse.InstalledApp.newBuilder().setStoreAppId(rb(rnd, 20))
                                        .setAppType(app.selfwatch.garmin.pb.proto.InstalledAppsProto.AppType.WATCH_APP).setName("x").setDisabled(false).setFilename("x"),
                                ),
                            ),
                        )
                        else -> Unit
                    }
                    val bytes = if (rnd.nextInt(4) == 0) ByteArray(rnd.nextInt(1, 50)) { rnd.nextInt(256).toByte() } else smart.build().toByteArray()
                    if (bytes.isNotEmpty()) r.watch.sendProtobuf(if (rnd.nextBoolean()) GfdiId.PROTOBUF_REQUEST else GfdiId.PROTOBUF_RESPONSE, 1_000 + i, bytes)
                    if (i % 20 == 0) settle()
                }
                settle(5_000)
                assertEquals(SessionState.READY, r.driver.sessionState.value)
            }
        }
    }
}
