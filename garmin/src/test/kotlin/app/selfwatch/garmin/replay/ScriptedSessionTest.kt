/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.selfwatch.garmin.replay

import app.selfwatch.garmin.FakeFile
import app.selfwatch.garmin.FeatureRig.Companion.settle
import app.selfwatch.garmin.FeatureRig.Companion.withRig
import app.selfwatch.garmin.TestPrg
import app.selfwatch.garmin.appmsg.CiqCodec
import app.selfwatch.garmin.appmsg.ScriptedWatchApp
import app.selfwatch.garmin.gfdi.FindMyPhoneRequest
import app.selfwatch.garmin.gfdi.GfdiId
import app.selfwatch.garmin.gfdi.GfdiMessage
import app.selfwatch.garmin.gfdi.GfdiStatus
import app.selfwatch.garmin.gfdi.MusicControl
import app.selfwatch.garmin.gfdi.MusicEntityUpdate
import app.selfwatch.garmin.gfdi.NotificationSubscription
import app.selfwatch.garmin.gfdi.NotificationUpdate
import app.selfwatch.garmin.gfdi.WeatherRequest
import app.selfwatch.garmin.link.GarminUuids
import app.selfwatch.garmin.pb.proto.AppSettingsProto
import app.selfwatch.garmin.pb.proto.HttpProto
import app.selfwatch.garmin.pb.proto.InstalledAppsProto
import app.selfwatch.garmin.pb.proto.SmartProto.Smart
import app.selfwatch.garmin.session.Features
import app.selfwatch.garmin.session.GarminCapability
import app.selfwatch.model.device.Feature
import app.selfwatch.model.device.SyncReason
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.time.Day
import app.selfwatch.model.watch.Alarm
import app.selfwatch.model.watch.CiqValue
import app.selfwatch.model.watch.MusicAction
import app.selfwatch.model.watch.MusicState
import app.selfwatch.model.watch.NotificationCategory
import app.selfwatch.model.watch.PhoneNotification
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchEvent
import app.selfwatch.model.watch.WeatherCondition
import app.selfwatch.model.watch.WeatherDay
import app.selfwatch.model.watch.WeatherNow
import com.google.protobuf.ByteString
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * A26: one scripted connection that uses every implemented feature in turn, the way a day with the
 * vívoactive 5 would (until recorded sessions from T-A08 exist for [ReplayTest]). Every message the watch
 * sends must be answered, and none with UNSUPPORTED.
 */
class ScriptedSessionTest {
    private val jg5k = UUID.fromString("5a1e0b2c-3d4e-4f60-8a7b-9c0d1e2f3a4b")
    private val tcs = UUID.fromString("7c5d0000-0000-4000-8000-00000000a11c")

    private fun ub(u: UUID) = ByteString.copyFrom(java.nio.ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array())

    private val allCaps = setOf(
        GarminCapability.GNCS, GarminCapability.BASIC_MUSIC_CONTROLS, GarminCapability.ADVANCED_MUSIC_CONTROLS,
        GarminCapability.FIND_MY_PHONE, GarminCapability.FIND_MY_WATCH, GarminCapability.WEATHER_CONDITIONS,
        GarminCapability.CURRENT_TIME_REQUEST_SUPPORT, GarminCapability.CONNECTIQ_WATCH_APP_DOWNLOAD,
        GarminCapability.CONNECTIQ_APP_MANAGEMENT, GarminCapability.CONNECTIQ_SETTINGS, GarminCapability.CONNECTIQ_HTTP,
        GarminCapability.LAUNCH_REMOTE_CIQ_APP_SUPPORT,
    )

    private val stored = HashMap<UUID, ByteString>()

    private fun watchServices(bytes: ByteArray): ByteArray? {
        val smart = Smart.parseFrom(bytes)
        val out = Smart.newBuilder()
        when {
            smart.hasConnectIqInstalledAppsService() -> {
                val s = smart.connectIqInstalledAppsService
                val r = InstalledAppsProto.InstalledAppsService.newBuilder()
                when {
                    s.hasGetInstalledAppsRequest() -> r.setGetInstalledAppsResponse(
                        InstalledAppsProto.GetInstalledAppsResponse.newBuilder().setAvailableSpace(10_000_000).setAvailableSlots(30).addInstalledApp(
                            InstalledAppsProto.GetInstalledAppsResponse.InstalledApp.newBuilder().setStoreAppId(ub(jg5k))
                                .setAppType(InstalledAppsProto.AppType.WATCH_APP).setName("Just Go 5K").setDisabled(false).setFilename("JG5K.PRG").setVersion(3),
                        ),
                    )
                    s.hasLaunchAppRequest() -> r.setLaunchAppResponse(
                        InstalledAppsProto.LaunchAppResponse.newBuilder().setStatus(InstalledAppsProto.LaunchAppResponse.Status.LAUNCH_STATUS_SUCCESS),
                    )
                    else -> return null
                }
                out.setConnectIqInstalledAppsService(r)
            }
            smart.hasConnectIqAppSettingsService() -> {
                val s = smart.connectIqAppSettingsService
                val r = AppSettingsProto.AppSettingsService.newBuilder()
                when {
                    s.hasSaveAppSettingsRequest() -> {
                        stored[jg5k] = s.saveAppSettingsRequest.settingsValues
                        r.setSaveAppSettingsResponse(
                            AppSettingsProto.SaveAppSettingsResponse.newBuilder().setAppIdentifier(s.saveAppSettingsRequest.appIdentifier)
                                .setResponse(AppSettingsProto.SaveAppSettingsResponse.Response.SUCCESS),
                        )
                    }
                    s.hasGetAppSettingsRequest() -> r.setGetAppSettingsResponse(
                        AppSettingsProto.GetAppSettingsResponse.newBuilder().setAppIdentifier(s.getAppSettingsRequest.appIdentifier)
                            .setResponse(AppSettingsProto.GetAppSettingsResponse.Response.SUCCESS),
                    )
                    else -> return null
                }
                out.setConnectIqAppSettingsService(r)
            }
            else -> return null
        }
        return out.build().toByteArray()
    }

    @Test
    fun aDayWithEveryFeature() = runTest {
        val files = listOf(FakeFile(3, 32, 1_100_000_000L, ByteArray(700) { it.toByte() }))
        withRig(allCaps, files = files, responder = ::watchServices, withWatchAppService = true) { r ->
            // Handshake and features.
            val info = r.driver.info.value!!
            for (f in listOf(
                Feature.NOTIFICATIONS, Feature.CALLS, Feature.MUSIC, Feature.WEATHER, Feature.FIND_PHONE, Feature.FIND_WATCH, Feature.ALARMS,
                Feature.WATCH_APP_INSTALL, Feature.WATCH_APP_MANAGE, Feature.WATCH_APP_SETTINGS, Feature.WATCH_APP_WEB, Feature.WATCH_APP_LAUNCH,
                Feature.WATCH_APP_MESSAGES, Feature.TIME_SYNC, Feature.BATTERY,
            )) {
                assertTrue("$f", f in info.features && f in Features.IMPLEMENTED)
            }
            // App list on connect.
            assertEquals(listOf(jg5k), r.events.filterIsInstance<WatchEvent.InstalledApps>().single().apps.map { it.uuid })

            // Time and battery.
            r.watch.requestTime(77)
            r.watch.sendGfdi(GfdiMessage.BatteryStatus(0, 400, 81))
            settle()
            assertEquals(1, r.watch.timeReplies.size)
            assertTrue(WatchEvent.Battery(81) in r.events)

            // Health sync.
            assertTrue(r.driver.sync(SyncReason.USER) is Outcome.Ok)
            assertEquals(listOf(3), r.sink.batches().map { it.key?.fileIndex })
            assertEquals(listOf(3), r.watch.archived.toList())

            // Notification and call.
            r.watch.sendGfdi(NotificationSubscription(true))
            settle()
            val note = PhoneNotification(5, "com.example.chat", "Chat", "Alice", "Hi", FeatureRigT0 - 5, NotificationCategory.MESSAGE, true, emptyList())
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.Notify(note)))
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.IncomingCall("+15550100", "Bob")))
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.CallEnded))
            settle()
            assertTrue(r.sent().filterIsInstance<NotificationUpdate>().size >= 2)

            // Music.
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.Music(MusicState(true, "Artist", "Album", "Song", 200_000, 1_000, 50))))
            r.watch.sendGfdi(MusicControl(1))
            settle()
            assertTrue(r.sent().any { it is MusicEntityUpdate })
            assertTrue(WatchEvent.MusicControl(MusicAction.NEXT) in r.events)

            // Weather.
            val now = WeatherNow(FeatureRigT0, 57, null, WeatherCondition.CLEAR, 60, 100, 180, "Holland")
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.Weather(now, listOf(WeatherDay(Day.of(java.time.LocalDate.of(2026, 11, 23)), 10, 90, WeatherCondition.CLEAR, 0)))))
            r.watch.sendGfdi(WeatherRequest(0, 0, 0, 24))
            settle()
            assertEquals(2, r.watch.fitDefinitions.size)

            // Find phone / watch.
            r.watch.sendGfdi(FindMyPhoneRequest(30))
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.FindWatch(true)))
            settle()
            assertTrue(WatchEvent.FindPhone(true) in r.events)
            assertTrue(r.pbRequests().any { it.hasFindMyWatchService() })

            // Install Just Go 5K, write and read its settings, open it.
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.InstallApp(TestPrg.build(jg5k, 1), null)))
            assertEquals(255 to 17, r.watch.uploads.last().let { it.dataType to it.subType })
            val values = mapOf("weeks" to CiqValue.Num(9), "voice" to CiqValue.Bool(true))
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.WriteAppSettings(jg5k, values)))
            assertEquals(Outcome.Ok(Unit), r.features.appSettings.request(jg5k))
            val send = Smart.newBuilder().setConnectIqAppSettingsService(
                AppSettingsProto.AppSettingsService.newBuilder().setSendAppSettingsRequest(
                    AppSettingsProto.SendAppSettingsRequest.newBuilder().setAppIdentifier(ub(jg5k)).setSettingsValues(stored.getValue(jg5k)),
                ),
            ).build()
            r.watch.sendProtobuf(GfdiId.PROTOBUF_REQUEST, 900, send.toByteArray())
            settle()
            assertTrue(WatchEvent.AppSettings(jg5k, values) in r.events)
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.LaunchApp(jg5k)))

            // TCS asks the phone app at 127.0.0.1 and gets its answer.
            val web = Smart.newBuilder().setConnectIqHttpService(
                HttpProto.HttpService.newBuilder().setConnectIqHttpRequest(
                    HttpProto.WebRequest.newBuilder().setUrl("http://127.0.0.1:47800/tcs/marks").setHttpMethod(HttpProto.HttpMethod.GET).setMaxResponseLength(4_000),
                ),
            ).build()
            r.watch.sendProtobuf(GfdiId.PROTOBUF_REQUEST, 901, web.toByteArray())
            settle()
            val req = r.events.filterIsInstance<WatchEvent.WebRequest>().single()
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.WebResponse(req.requestId, 200, """{"mark":"A"}""".toByteArray(), "application/json")))
            settle()
            val resp = r.pbResponses().single { it.first == 901 }.second.connectIqHttpService.connectIqHttpResponse
            assertEquals(Outcome.Ok(CiqValue.Dict(mapOf(CiqValue.Str("mark") to CiqValue.Str("A")))), CiqCodec.decode(resp.httpBody.toByteArray()))

            // Alarms.
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.SetAlarms(listOf(Alarm(1, 6, 0, emptySet(), true, null)))))
            assertEquals(128 to 2, r.watch.uploads.last().let { it.dataType to it.subType })

            // App message to TCS on the watch-app channel.
            val watchApp = ScriptedWatchApp(r.port.pipe(GarminUuids.WATCH_APP_SERVICE, GarminUuids.WATCH_APP_RX, GarminUuids.WATCH_APP_TX))
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.AppMessage(tcs, CiqValue.Str("hello"))))
            assertEquals(listOf(tcs to CiqValue.Str("hello")), watchApp.received)

            // Every watch message was answered, none with UNSUPPORTED.
            val unsupported = r.sent().filterIsInstance<GfdiMessage.Response>().filter { it.status == GfdiStatus.UNSUPPORTED }
            assertTrue("unsupported replies: $unsupported", unsupported.isEmpty())
            for (id in listOf(
                GfdiId.CURRENT_TIME_REQUEST, GfdiId.BATTERY_STATUS, GfdiId.NOTIFICATION_SUBSCRIPTION, GfdiId.MUSIC_CONTROL,
                GfdiId.WEATHER_REQUEST, GfdiId.FIND_MY_PHONE_REQUEST, GfdiId.PROTOBUF_REQUEST,
            )) {
                assertTrue("reply to $id", r.replies(id).isNotEmpty())
            }
        }
    }

    private companion object {
        const val FeatureRigT0 = app.selfwatch.garmin.FeatureRig.T0
    }
}
