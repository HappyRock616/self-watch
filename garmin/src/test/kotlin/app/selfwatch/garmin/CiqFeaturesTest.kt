/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.selfwatch.garmin

import app.selfwatch.fit.io.FitReader
import app.selfwatch.fit.io.FitRecord
import app.selfwatch.fit.io.FitValue
import app.selfwatch.garmin.FeatureRig.Companion.settle
import app.selfwatch.garmin.FeatureRig.Companion.withRig
import app.selfwatch.garmin.appmsg.CiqCodec
import app.selfwatch.garmin.features.AlarmsFit
import app.selfwatch.garmin.features.PrgFile
import app.selfwatch.garmin.gfdi.GfdiId
import app.selfwatch.garmin.gfdi.GfdiMessage
import app.selfwatch.garmin.gfdi.SystemEventType
import app.selfwatch.garmin.pb.proto.AppSettingsProto
import app.selfwatch.garmin.pb.proto.InstalledAppsProto
import app.selfwatch.garmin.pb.proto.InstalledAppsProto.AppType
import app.selfwatch.garmin.pb.proto.SmartProto.Smart
import app.selfwatch.garmin.session.GarminCapability
import app.selfwatch.model.crc.Crc16
import app.selfwatch.model.device.Feature
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.Alarm
import app.selfwatch.model.watch.CiqValue
import app.selfwatch.model.watch.WatchApp
import app.selfwatch.model.watch.WatchAppKind
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchEvent
import com.google.protobuf.ByteString
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.util.UUID

/** Scripted FakeWatch flows for watch apps (A21), app settings (A22) and alarms (A20). */
class CiqFeaturesTest {
    private val jg5k = UUID.fromString("5a1e0b2c-3d4e-4f60-8a7b-9c0d1e2f3a4b")
    private val face = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
    private val system = UUID.fromString("ffffffff-0000-0000-0000-000000000001")

    private fun ub(u: UUID): ByteString = ByteString.copyFrom(
        java.nio.ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array(),
    )

    /** A fake watch app manager: lists [apps], deletes, launches; records requests. */
    private inner class AppsWatch(var apps: MutableList<InstalledAppsProto.GetInstalledAppsResponse.InstalledApp> = defaultApps()) {
        val requests = ArrayList<InstalledAppsProto.InstalledAppsService>()
        var deleteStatus = InstalledAppsProto.DeleteAppResponse.Status.OK
        var launchStatus = InstalledAppsProto.LaunchAppResponse.Status.LAUNCH_STATUS_SUCCESS

        fun respond(bytes: ByteArray): ByteArray? {
            val smart = Smart.parseFrom(bytes)
            if (!smart.hasConnectIqInstalledAppsService()) return null
            val s = smart.connectIqInstalledAppsService
            requests.add(s)
            val out = InstalledAppsProto.InstalledAppsService.newBuilder()
            when {
                s.hasGetInstalledAppsRequest() -> out.setGetInstalledAppsResponse(
                    InstalledAppsProto.GetInstalledAppsResponse.newBuilder().setAvailableSpace(1_000_000).setAvailableSlots(20).addAllInstalledApp(apps),
                )
                s.hasDeleteAppRequest() -> {
                    if (deleteStatus == InstalledAppsProto.DeleteAppResponse.Status.OK) apps.removeAll { it.storeAppId == s.deleteAppRequest.storeAppId }
                    out.setDeleteAppResponse(InstalledAppsProto.DeleteAppResponse.newBuilder().setStatus(deleteStatus))
                }
                s.hasLaunchAppRequest() -> out.setLaunchAppResponse(InstalledAppsProto.LaunchAppResponse.newBuilder().setStatus(launchStatus))
                else -> return null
            }
            return Smart.newBuilder().setConnectIqInstalledAppsService(out).build().toByteArray()
        }
    }

    private fun app(u: UUID, type: AppType, name: String, file: String?, version: Int = 0) =
        InstalledAppsProto.GetInstalledAppsResponse.InstalledApp.newBuilder().setStoreAppId(ub(u)).setAppType(type).setName(name)
            .setDisabled(false).setVersion(version).apply { if (file != null) setFilename(file) }.build()

    private fun defaultApps() = mutableListOf(
        app(jg5k, AppType.WATCH_APP, "Just Go 5K", "JG5K.PRG", 12),
        app(face, AppType.WATCH_FACE, "Plain Face", "FACE.PRG"),
        app(system, AppType.WATCH_APP, "Clock", null),
    )

    private val appCaps = setOf(GarminCapability.CONNECTIQ_APP_MANAGEMENT, GarminCapability.CONNECTIQ_WATCH_APP_DOWNLOAD)

    // ---- A21 list ----

    @Test
    fun appListIsRequestedOnReadyAndSystemAppsAreLeftOut() = runTest {
        val w = AppsWatch()
        withRig(appCaps, responder = w::respond) { r ->
            assertEquals(AppType.ALL, w.requests.single().getInstalledAppsRequest.appType)
            val apps = r.events.filterIsInstance<WatchEvent.InstalledApps>().single().apps
            assertEquals(
                listOf(WatchApp(jg5k, "Just Go 5K", 12, WatchAppKind.APP), WatchApp(face, "Plain Face", null, WatchAppKind.WATCH_FACE)),
                apps,
            )
            // Refresh on demand gives the same list and another event.
            assertEquals(Outcome.Ok(apps), r.features.apps.refresh())
            settle()
            assertEquals(2, r.events.filterIsInstance<WatchEvent.InstalledApps>().size)
        }
    }

    @Test
    fun noAppListWithoutConnectIqFlags() = runTest {
        val w = AppsWatch()
        withRig(setOf(GarminCapability.GNCS), responder = w::respond) { r ->
            assertTrue(w.requests.isEmpty())
            assertEquals(Outcome.Fail(SwError.Unsupported(Feature.WATCH_APP_MANAGE)), r.features.apps.refresh())
        }
    }

    // ---- A21 install ----

    @Test
    fun installUploadsThePrgWithRunningCrcsAndOffsetsThenRefreshes() = runTest {
        val w = AppsWatch()
        val prg = TestPrg.build(jg5k, PrgFile.TYPE_WATCH_APP, codeBytes = 1_000)
        withRig(appCaps, maxPacket = 120, responder = w::respond) { r ->
            val before = r.events.filterIsInstance<WatchEvent.InstalledApps>().size
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.InstallApp(prg, "{}")))
            settle()
            val up = r.watch.uploads.single()
            assertEquals(255, up.dataType)
            assertEquals(17, up.subType)
            assertArrayEquals(prg, up.bytes)
            val create = r.sent().filterIsInstance<GfdiMessage.CreateFile>().single()
            assertEquals(prg.size, create.size)
            // Chunks: maxPacket − 13 bytes each, contiguous offsets, each CRC the running CRC from byte 0.
            var off = 0
            for (c in r.watch.uploadChunks) {
                assertEquals(off, c.offset)
                assertTrue(c.size <= 120 - 13)
                off += c.size
                assertEquals(Crc16.compute(prg, 0, off), c.crc)
            }
            assertEquals(prg.size, off)
            assertTrue(r.watch.uploadChunks.size > 5)
            assertTrue(r.sent().any { it is GfdiMessage.SystemEvent && it.type == SystemEventType.SYNC_COMPLETE })
            assertEquals(before + 1, r.events.filterIsInstance<WatchEvent.InstalledApps>().size)
        }
    }

    @Test
    fun installRefusesNonPrgAndMissingFlagsAndWatchRefusal() = runTest {
        withRig(setOf(GarminCapability.CONNECTIQ_WATCH_APP_DOWNLOAD)) { r ->
            val notPrg = r.driver.send(WatchCommand.InstallApp(ByteArray(300) { 1 }, null))
            assertTrue((notPrg as Outcome.Fail).error is SwError.BadInput)
            val watchFace = TestPrg.build(face, PrgFile.TYPE_WATCH_FACE)
            assertEquals(Outcome.Fail(SwError.Unsupported(Feature.WATCH_APP_INSTALL)), r.driver.send(WatchCommand.InstallApp(watchFace, null)))
            assertTrue(r.sent().none { it is GfdiMessage.CreateFile })
            r.watch.createStatus = 2 // no space
            val full = r.driver.send(WatchCommand.InstallApp(TestPrg.build(jg5k, PrgFile.TYPE_WATCH_APP), null))
            assertTrue((full as Outcome.Fail).error is SwError.Protocol)
            assertTrue(r.watch.uploads.isEmpty())
        }
    }

    // ---- A21 delete / launch ----

    @Test
    fun deleteSendsTheListedStoreIdAndTypeThenRefreshes() = runTest {
        val w = AppsWatch()
        withRig(appCaps, responder = w::respond) { r ->
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.DeleteApp(face)))
            settle()
            val del = w.requests.single { it.hasDeleteAppRequest() }.deleteAppRequest
            assertEquals(ub(face), del.storeAppId)
            assertEquals(AppType.WATCH_FACE, del.appType)
            val last = r.events.filterIsInstance<WatchEvent.InstalledApps>().last().apps
            assertEquals(listOf(jg5k), last.map { it.uuid })
            // Unknown app: listed again, then refused.
            val unknown = r.driver.send(WatchCommand.DeleteApp(UUID.randomUUID()))
            assertTrue((unknown as Outcome.Fail).error is SwError.BadInput)
            w.deleteStatus = InstalledAppsProto.DeleteAppResponse.Status.FAILED_TO_DELETE
            assertTrue(((r.driver.send(WatchCommand.DeleteApp(jg5k)) as Outcome.Fail).error) is SwError.Protocol)
        }
    }

    @Test
    fun deleteNeedsAppManagement() = runTest {
        withRig(setOf(GarminCapability.CONNECTIQ_WATCH_APP_DOWNLOAD)) { r ->
            assertEquals(Outcome.Fail(SwError.Unsupported(Feature.WATCH_APP_MANAGE)), r.driver.send(WatchCommand.DeleteApp(jg5k)))
        }
    }

    @Test
    fun launchMapsTheWatchStatus() = runTest {
        val w = AppsWatch()
        withRig(appCaps + GarminCapability.LAUNCH_REMOTE_CIQ_APP_SUPPORT, responder = w::respond) { r ->
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.LaunchApp(jg5k)))
            val req = w.requests.single { it.hasLaunchAppRequest() }.launchAppRequest
            assertEquals(ub(jg5k), req.appUuid)
            assertEquals(InstalledAppsProto.LaunchAppRequest.StartUpMode.APP_STARTUP_MODE_NORMAL, req.appStartupMode)
            w.launchStatus = InstalledAppsProto.LaunchAppResponse.Status.LAUNCH_STATUS_APP_ALREADY_RUNNING
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.LaunchApp(jg5k)))
            w.launchStatus = InstalledAppsProto.LaunchAppResponse.Status.LAUNCH_STATUS_APP_NOT_INSTALLED
            assertTrue(((r.driver.send(WatchCommand.LaunchApp(jg5k)) as Outcome.Fail).error) is SwError.BadInput)
            w.launchStatus = InstalledAppsProto.LaunchAppResponse.Status.LAUNCH_STATUS_UNKNOWN_ERROR
            assertTrue(((r.driver.send(WatchCommand.LaunchApp(jg5k)) as Outcome.Fail).error) is SwError.Protocol)
        }
        withRig(appCaps) { r ->
            assertEquals(Outcome.Fail(SwError.Unsupported(Feature.WATCH_APP_LAUNCH)), r.driver.send(WatchCommand.LaunchApp(jg5k)))
        }
    }

    // ---- A22 settings ----

    /** A fake settings store on the watch, keyed by app. */
    private inner class SettingsWatch {
        val stored = HashMap<UUID, ByteArray>()
        var saveStatus = AppSettingsProto.SaveAppSettingsResponse.Response.SUCCESS
        val installed = mutableSetOf(jg5k)

        fun respond(bytes: ByteArray): ByteArray? {
            val smart = Smart.parseFrom(bytes)
            if (!smart.hasConnectIqAppSettingsService()) return null
            val s = smart.connectIqAppSettingsService
            val out = AppSettingsProto.AppSettingsService.newBuilder()
            when {
                s.hasSaveAppSettingsRequest() -> {
                    val app = uuid(s.saveAppSettingsRequest.appIdentifier)
                    if (saveStatus == AppSettingsProto.SaveAppSettingsResponse.Response.SUCCESS) stored[app] = s.saveAppSettingsRequest.settingsValues.toByteArray()
                    out.setSaveAppSettingsResponse(
                        AppSettingsProto.SaveAppSettingsResponse.newBuilder().setAppIdentifier(s.saveAppSettingsRequest.appIdentifier).setResponse(saveStatus),
                    )
                }
                s.hasGetAppSettingsRequest() -> out.setGetAppSettingsResponse(
                    AppSettingsProto.GetAppSettingsResponse.newBuilder().setAppIdentifier(s.getAppSettingsRequest.appIdentifier).setResponse(
                        if (uuid(s.getAppSettingsRequest.appIdentifier) in installed) {
                            AppSettingsProto.GetAppSettingsResponse.Response.SUCCESS
                        } else {
                            AppSettingsProto.GetAppSettingsResponse.Response.APP_NOT_INSTALLED
                        },
                    ),
                )
                else -> return null
            }
            return Smart.newBuilder().setConnectIqAppSettingsService(out).build().toByteArray()
        }

        fun valuesMessage(app: UUID): ByteArray = Smart.newBuilder().setConnectIqAppSettingsService(
            AppSettingsProto.AppSettingsService.newBuilder().setSendAppSettingsRequest(
                AppSettingsProto.SendAppSettingsRequest.newBuilder().setAppIdentifier(ub(app)).setSettingsValues(ByteString.copyFrom(stored.getValue(app))),
            ),
        ).build().toByteArray()

        private fun uuid(b: ByteString): UUID = java.nio.ByteBuffer.wrap(b.toByteArray()).let { UUID(it.long, it.long) }
    }

    private val allTypes: Map<String, CiqValue> = linkedMapOf(
        "nothing" to CiqValue.Null,
        "voice" to CiqValue.Bool(true),
        "weeks" to CiqValue.Num(9),
        "bigNumber" to CiqValue.Num(5_000_000_000L),
        "pace" to CiqValue.Dbl(0.5),
        "precise" to CiqValue.Dbl(1.0 / 3.0),
        "name" to CiqValue.Str("HappyRock's plan ✓"),
        "days" to CiqValue.Arr(listOf(CiqValue.Num(1), CiqValue.Num(3), CiqValue.Str("sat"))),
        "nested" to CiqValue.Dict(mapOf(CiqValue.Str("warmup") to CiqValue.Bool(false), CiqValue.Num(2) to CiqValue.Arr(emptyList()))),
    )

    @Test
    fun settingsWriteThenReadRoundTripsEveryValueType() = runTest {
        val w = SettingsWatch()
        withRig(setOf(GarminCapability.CONNECTIQ_SETTINGS), maxPacket = 80, responder = w::respond) { r ->
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.WriteAppSettings(jg5k, allTypes)))
            // What the watch stored is the Monkey C dictionary of the values.
            val stored = (CiqCodec.decode(w.stored.getValue(jg5k)) as Outcome.Ok).value as CiqValue.Dict
            assertEquals(allTypes, stored.v.mapKeys { (it.key as CiqValue.Str).v })
            // Read: the watch accepts, then sends the values in its own request.
            assertEquals(Outcome.Ok(Unit), r.features.appSettings.request(jg5k))
            r.watch.sendProtobuf(GfdiId.PROTOBUF_REQUEST, 501, w.valuesMessage(jg5k))
            settle()
            assertEquals(WatchEvent.AppSettings(jg5k, allTypes), r.events.filterIsInstance<WatchEvent.AppSettings>().single())
            assertTrue(r.pbStatuses().last().isOk)
        }
    }

    @Test
    fun settingsFailuresAndGating() = runTest {
        val w = SettingsWatch()
        withRig(setOf(GarminCapability.CONNECTIQ_SETTINGS), responder = w::respond) { r ->
            w.saveStatus = AppSettingsProto.SaveAppSettingsResponse.Response.FAILED_TO_SAVE
            val failed = r.driver.send(WatchCommand.WriteAppSettings(jg5k, mapOf("a" to CiqValue.Num(1))))
            assertTrue((failed as Outcome.Fail).error is SwError.Protocol)
            val notInstalled = r.features.appSettings.request(face)
            assertTrue((notInstalled as Outcome.Fail).error is SwError.BadInput)
            // Values that are not a dictionary are dropped, not crashed on.
            val junk = Smart.newBuilder().setConnectIqAppSettingsService(
                AppSettingsProto.AppSettingsService.newBuilder().setSendAppSettingsRequest(
                    AppSettingsProto.SendAppSettingsRequest.newBuilder().setAppIdentifier(ub(jg5k)).setSettingsValues(ByteString.copyFrom(byteArrayOf(1, 2, 3))),
                ),
            ).build()
            r.watch.sendProtobuf(GfdiId.PROTOBUF_REQUEST, 502, junk.toByteArray())
            settle()
            assertTrue(r.events.none { it is WatchEvent.AppSettings })
        }
        withRig(setOf(GarminCapability.GNCS)) { r ->
            assertEquals(Outcome.Fail(SwError.Unsupported(Feature.WATCH_APP_SETTINGS)), r.driver.send(WatchCommand.WriteAppSettings(jg5k, emptyMap())))
            assertEquals(Outcome.Fail(SwError.Unsupported(Feature.WATCH_APP_SETTINGS)), r.features.appSettings.request(jg5k))
        }
    }

    // ---- A20 alarms ----

    private fun records(file: ByteArray): List<FitRecord> {
        val out = ArrayList<FitRecord>()
        val s = FitReader(file.inputStream()).read { out.add(it) }
        assertTrue((s as Outcome.Ok).value.crcOk)
        assertEquals(2, s.value.fileType)
        return out
    }

    private fun FitValue?.long(): Long = (this as FitValue.I).v
    private fun FitValue?.longs(): List<Long> = (this as FitValue.A).v.map { (it as FitValue.I).v }

    @Test
    fun alarmsGoAsASettingsFileUpload() = runTest {
        val alarms = listOf(
            Alarm(1, 6, 30, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), true, "Wake up"),
            Alarm(2, 21, 5, emptySet(), false, "Pick up kids"),
        )
        withRig(setOf(GarminCapability.GNCS)) { r ->
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.SetAlarms(alarms)))
            val up = r.watch.uploads.single()
            assertEquals(128, up.dataType)
            assertEquals(2, up.subType)
            val recs = records(up.bytes)
            val fileId = recs.first { it.global == 0 }
            assertEquals(1L, fileId.fields[1].long())
            assertEquals(65534L, fileId.fields[2].long())
            val a = recs.filter { it.global == AlarmsFit.GLOBAL_ALARM_SETTINGS }
            assertEquals(listOf(390L, 1265L), a.map { it.fields[0].long() })
            assertEquals(listOf(1L + 4 + 16, 128L), a.map { it.fields[1].long() })
            assertEquals(listOf(1L, 0L), a.map { it.fields[2].long() })
            assertEquals(listOf(1L, 0L), a.map { it.fields[8].long() }) // WAKE_UP, NONE
            assertEquals(listOf(0L, 1L), a.map { it.fields[254].long() })
            val ds = recs.single { it.global == AlarmsFit.GLOBAL_DEVICE_SETTINGS }
            assertEquals(listOf(390L, 1265L), ds.fields[8].longs())
            assertEquals(listOf(5L, 5L), ds.fields[9].longs())
            assertEquals(listOf(1L, 0L), ds.fields[28].longs())
            assertEquals(listOf(21L, 128L), ds.fields[92].longs())
        }
    }

    @Test
    fun alarmsRefusedOnRealtimeSettingsWatchesAndBeyondTenSlots() = runTest {
        withRig(setOf(GarminCapability.REALTIME_SETTINGS)) { r ->
            assertEquals(Outcome.Fail(SwError.Unsupported(Feature.ALARMS)), r.driver.send(WatchCommand.SetAlarms(emptyList())))
        }
        withRig(setOf(GarminCapability.GNCS)) { r ->
            val many = (1..11).map { Alarm(it, 7, 0, emptySet(), true, null) }
            assertTrue(((r.driver.send(WatchCommand.SetAlarms(many)) as Outcome.Fail).error) is SwError.BadInput)
            // An empty list clears the alarms: a settings file with only file_id.
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.SetAlarms(emptyList())))
            assertEquals(listOf(0), records(r.watch.uploads.single().bytes).map { it.global })
        }
    }

    @Test
    fun alarmHelpers() {
        assertEquals(128L, AlarmsFit.repeatBits(emptySet()))
        assertEquals(127L, AlarmsFit.repeatBits(DayOfWeek.entries.toSet()))
        assertEquals(64L, AlarmsFit.repeatBits(setOf(DayOfWeek.SUNDAY)))
        assertEquals(8, AlarmsFit.labelOf("bedtime"))
        assertEquals(0, AlarmsFit.labelOf("Something else"))
        assertEquals(0, AlarmsFit.labelOf(null))
    }
}
