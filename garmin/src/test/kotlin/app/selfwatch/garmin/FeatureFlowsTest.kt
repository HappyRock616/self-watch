/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.selfwatch.garmin

import app.selfwatch.garmin.features.NotificationsHandler
import app.selfwatch.garmin.gfdi.AttributeRequest
import app.selfwatch.garmin.gfdi.ByteReader
import app.selfwatch.garmin.gfdi.Bytes
import app.selfwatch.garmin.gfdi.FeatureReplies
import app.selfwatch.garmin.gfdi.FindMyPhoneCancel
import app.selfwatch.garmin.gfdi.FindMyPhoneRequest
import app.selfwatch.garmin.gfdi.GfdiFrame
import app.selfwatch.garmin.gfdi.GfdiId
import app.selfwatch.garmin.gfdi.GfdiMessage
import app.selfwatch.garmin.gfdi.GfdiStatus
import app.selfwatch.garmin.gfdi.MusicCommand
import app.selfwatch.garmin.gfdi.MusicControl
import app.selfwatch.garmin.gfdi.MusicControlCapabilities
import app.selfwatch.garmin.gfdi.MusicEntity
import app.selfwatch.garmin.gfdi.MusicEntityId
import app.selfwatch.garmin.gfdi.MusicEntityUpdate
import app.selfwatch.garmin.gfdi.NotificationActionCode
import app.selfwatch.garmin.gfdi.NotificationActionRequest
import app.selfwatch.garmin.gfdi.NotificationAppAttributesRequest
import app.selfwatch.garmin.gfdi.NotificationAttribute
import app.selfwatch.garmin.gfdi.NotificationAttributesRequest
import app.selfwatch.garmin.gfdi.NotificationSubscription
import app.selfwatch.garmin.gfdi.NotificationUpdate
import app.selfwatch.garmin.gfdi.ProtobufPacket
import app.selfwatch.garmin.gfdi.WeatherRequest
import app.selfwatch.garmin.pb.proto.CoreProto
import app.selfwatch.garmin.pb.proto.CredentialsProto
import app.selfwatch.garmin.pb.proto.DeviceStatusProto
import app.selfwatch.garmin.pb.proto.FindMyWatchProto
import app.selfwatch.garmin.pb.proto.SmartProto.Smart
import app.selfwatch.garmin.session.GarminCapability
import app.selfwatch.garmin.session.GarminConfig
import app.selfwatch.garmin.session.GarminSessionContext
import app.selfwatch.garmin.session.GfdiHandler
import app.selfwatch.garmin.session.SessionState
import app.selfwatch.model.device.Feature
import app.selfwatch.model.fake.FakeBlePort
import app.selfwatch.model.fake.FakeClock
import app.selfwatch.model.fake.FakeFileStore
import app.selfwatch.model.fake.RecordingHealthSink
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.time.Day
import app.selfwatch.model.watch.MusicAction
import app.selfwatch.model.watch.MusicState
import app.selfwatch.model.watch.NotificationCategory
import app.selfwatch.model.watch.PhoneNotification
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchEvent
import app.selfwatch.model.watch.WeatherCondition
import app.selfwatch.model.watch.WeatherDay
import app.selfwatch.model.watch.WeatherNow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random

/** Scripted FakeWatch flows for the M5 features (A15–A19, A24) and their capability gating. */
class FeatureFlowsTest {
    private val t0 = 1_795_435_200L // 2026-11-23 12:00 UTC (07:00 in the fake clock's America/Detroit), a Monday

    /** Grabs the live session so tests can call the protobuf service directly. */
    private class Grabber : GfdiHandler {
        @Volatile var session: GarminSessionContext? = null
        override suspend fun handle(m: GfdiMessage, session: GarminSessionContext): Boolean {
            this.session = session
            return false
        }
    }

    private class Rig(val watch: FakeWatch, val driver: GarminDriver, val features: GarminFeatures, val grabber: Grabber, val port: FakeBlePort) {
        val events: MutableList<WatchEvent> = Collections.synchronizedList(ArrayList())
        val session: GarminSessionContext get() = grabber.session!!
        fun sent() = watch.fromPhone.toList()
        fun replies(id: Int) = sent().filterIsInstance<GfdiMessage.Response>().filter { it.originalId == id }
    }

    private suspend fun TestScope.rig(
        caps: Set<GarminCapability>,
        maxPacket: Int = 375,
        config: GarminConfig = GarminConfig(syncOnConnect = false),
        block: suspend TestScope.(Rig) -> Unit,
    ) {
        val port = FakeBlePort(maxWrite = 64)
        val watch = FakeWatch(backgroundScope, port, FakeWatch.Mode.V2_MLR, emptyList(), capabilities = caps + GarminCapability.SYNC, maxPacket = maxPacket)
        watch.start()
        val clock = FakeClock(t0)
        val features = GarminFeatures(clock)
        val grabber = Grabber()
        val driver = GarminDriver(7, config, clock, null, FakeDecoders(), EmptyCoroutineContext, listOf(grabber) + features.handlers)
        val r = Rig(watch, driver, features, grabber, port)
        backgroundScope.launch { driver.events.collect { r.events.add(it) } }
        val job = launch { driver.run(port, RecordingHealthSink(), FakeFileStore()) }
        withTimeout(60_000) { driver.sessionState.first { it == SessionState.READY } }
        advanceUntilIdle()
        block(r)
        port.allPipes().forEach { it.endIncoming() }
        job.join()
    }

    private fun note(id: Int = 42, text: String? = "Hello there", actions: List<String> = listOf("Mark read"), canReply: Boolean = true) =
        PhoneNotification(id, "com.example.chat", "Chat", "Alice", text, t0 - 60, NotificationCategory.MESSAGE, canReply, actions)

    /** Parses a notification attribute block into (attribute → bytes), keeping order. */
    private fun parseBlock(b: ByteArray): Pair<Int, List<Pair<Int, ByteArray>>> {
        val r = ByteReader(b)
        assertEquals(0, r.u8())
        val id = r.i32()
        val out = ArrayList<Pair<Int, ByteArray>>()
        while (r.remaining > 0) {
            val a = r.u8()
            out.add(a to r.bytes(r.u16()))
        }
        return id to out
    }

    // ---- A16 notifications ----

    @Test
    fun notificationRequestAttributesAndActions() = runTest {
        rig(setOf(GarminCapability.GNCS)) { r ->
            r.watch.sendGfdi(NotificationSubscription(true))
            advanceUntilIdle()
            val sub = r.replies(GfdiId.NOTIFICATION_SUBSCRIPTION).single()
            assertEquals(FeatureReplies.SubscriptionStatus(0, true, 0), FeatureReplies.SubscriptionStatus.parse(sub.extra.toByteArray()))

            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.Notify(note())))
            advanceUntilIdle()
            val up = r.sent().filterIsInstance<NotificationUpdate>().single()
            assertEquals(NotificationUpdate(NotificationUpdate.ADD, 0x12, 12, 1, 42, NotificationUpdate.PHONE_NEW_ACTIONS), up)

            r.watch.sendGfdi(
                NotificationAttributesRequest(
                    42,
                    listOf(
                        AttributeRequest(NotificationAttribute.APP_IDENTIFIER),
                        AttributeRequest(NotificationAttribute.MESSAGE_SIZE),
                        AttributeRequest(NotificationAttribute.TITLE, 3),
                        AttributeRequest(NotificationAttribute.MESSAGE, 5),
                        AttributeRequest(NotificationAttribute.DATE),
                        AttributeRequest(NotificationAttribute.ACTIONS, 100, 0),
                    ),
                ),
            )
            advanceUntilIdle()
            val ctl = r.replies(GfdiId.NOTIFICATION_CONTROL).single()
            assertEquals(FeatureReplies.ControlStatus(0, 0), FeatureReplies.ControlStatus.parse(ctl.extra.toByteArray()))
            val (id, attrs) = parseBlock(r.watch.notificationBlocks.single())
            assertEquals(42, id)
            val m = attrs.associate { it.first to String(it.second, Charsets.UTF_8) }
            assertEquals("com.example.chat", m[NotificationAttribute.APP_IDENTIFIER])
            assertEquals("Ali", m[NotificationAttribute.TITLE])
            assertEquals("Hello", m[NotificationAttribute.MESSAGE])
            assertEquals("11", m[NotificationAttribute.MESSAGE_SIZE])
            assertEquals("20261123T065900", m[NotificationAttribute.DATE]) // t0 - 60 s in Detroit (UTC-5)
            assertEquals(NotificationAttribute.MESSAGE_SIZE, attrs.last().first) // size always last
            val actions = ByteReader(attrs.first { it.first == NotificationAttribute.ACTIONS }.second)
            assertEquals(3, actions.u8())
            assertEquals(listOf(95, 1, 98), List(3) { val c = actions.u8(); actions.u8(); actions.str8(); c })

            r.watch.sendGfdi(NotificationActionRequest(42, NotificationActionCode.CUSTOM_1, null))
            r.watch.sendGfdi(NotificationActionRequest(42, NotificationActionCode.REPLY_MESSAGES, "On my way"))
            r.watch.sendGfdi(NotificationActionRequest(42, NotificationActionCode.DISMISS_NOTIFICATION, ""))
            r.watch.sendGfdi(NotificationActionRequest(42, NotificationActionCode.BLOCK_APPLICATION, null))
            r.watch.sendGfdi(NotificationActionRequest(999, NotificationActionCode.DISMISS_NOTIFICATION, null)) // unknown id: ignored
            advanceUntilIdle()
            assertEquals(
                listOf(
                    WatchEvent.NotificationAction(42, "Mark read", null),
                    WatchEvent.NotificationAction(42, NotificationsHandler.ACTION_REPLY, "On my way"),
                    WatchEvent.NotificationAction(42, NotificationsHandler.ACTION_DISMISS, null),
                    WatchEvent.NotificationAction(42, NotificationsHandler.ACTION_MUTE, null),
                ),
                r.events.filterIsInstance<WatchEvent.NotificationAction>(),
            )
            assertEquals(6, r.replies(GfdiId.NOTIFICATION_CONTROL).size) // every control message answered

            // Same id again = MODIFY; remove = REMOVE.
            r.driver.send(WatchCommand.Notify(note(text = "Changed")))
            r.driver.send(WatchCommand.RemoveNotification(42))
            advanceUntilIdle()
            assertEquals(listOf(NotificationUpdate.ADD, NotificationUpdate.MODIFY, NotificationUpdate.REMOVE), r.sent().filterIsInstance<NotificationUpdate>().map { it.type })
        }
    }

    @Test
    fun longNotificationTextIsChunkedWithRunningCrc() = runTest {
        rig(setOf(GarminCapability.GNCS)) { r ->
            r.watch.sendGfdi(NotificationSubscription(true))
            advanceUntilIdle()
            val text = "ü".repeat(500) // 1000 bytes
            r.driver.send(WatchCommand.Notify(note(text = text)))
            r.watch.sendGfdi(NotificationAttributesRequest(42, listOf(AttributeRequest(NotificationAttribute.MESSAGE, 0))))
            advanceUntilIdle()
            val chunks = r.sent().filterIsInstance<app.selfwatch.garmin.gfdi.NotificationData>()
            assertEquals(4, chunks.size) // 1008 bytes in 300-byte chunks
            assertTrue(chunks.all { it.chunk.size <= NotificationsHandler.CHUNK_BYTES })
            val (_, attrs) = parseBlock(r.watch.notificationBlocks.single())
            assertEquals(text, String(attrs.single().second, Charsets.UTF_8))
        }
    }

    @Test
    fun appAttributesAreAnswered() = runTest {
        rig(setOf(GarminCapability.GNCS)) { r ->
            r.watch.sendGfdi(NotificationSubscription(true))
            advanceUntilIdle()
            r.driver.send(WatchCommand.Notify(note()))
            r.watch.sendGfdi(NotificationAppAttributesRequest("com.example.chat", listOf(NotificationAttribute.APP_NAME)))
            advanceUntilIdle()
            val b = ByteReader(r.watch.notificationBlocks.single())
            assertEquals(1, b.u8())
            assertEquals("com.example.chat", String(b.bytes(16), Charsets.UTF_8))
            assertEquals(0, b.u8())
            assertEquals(NotificationAttribute.APP_NAME, b.u8())
            assertEquals("Chat", b.str16())
        }
    }

    @Test
    fun notificationsBeforeSubscriptionAreDropped() = runTest {
        rig(setOf(GarminCapability.GNCS)) { r ->
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.Notify(note())))
            advanceUntilIdle()
            assertTrue(r.sent().none { it is NotificationUpdate })
        }
    }

    @Test
    fun callAcceptAndReject() = runTest {
        rig(setOf(GarminCapability.GNCS)) { r ->
            r.watch.sendGfdi(NotificationSubscription(true))
            advanceUntilIdle()
            r.driver.send(WatchCommand.IncomingCall("+15551234", "Bob"))
            advanceUntilIdle()
            val up = r.sent().filterIsInstance<NotificationUpdate>().single()
            assertEquals(1, up.category) // incoming call
            assertEquals(NotificationUpdate.PHONE_NEW_ACTIONS, up.phoneFlags)
            assertEquals("+15551234".hashCode(), up.notificationId)
            r.watch.sendGfdi(NotificationAttributesRequest(up.notificationId, listOf(AttributeRequest(NotificationAttribute.TITLE, 20), AttributeRequest(NotificationAttribute.ACTIONS, 50, 0))))
            advanceUntilIdle()
            val attrs = parseBlock(r.watch.notificationBlocks.single()).second
            assertEquals("Bob", String(attrs[0].second, Charsets.UTF_8))
            val a = ByteReader(attrs[1].second)
            assertEquals(3, a.u8())
            assertEquals(listOf(94, 97, 96), List(3) { val c = a.u8(); a.u8(); a.str8(); c })

            r.watch.sendGfdi(NotificationActionRequest(up.notificationId, NotificationActionCode.ACCEPT_INCOMING_CALL, null))
            r.watch.sendGfdi(NotificationActionRequest(up.notificationId, NotificationActionCode.REJECT_INCOMING_CALL, null))
            r.watch.sendGfdi(NotificationActionRequest(up.notificationId, NotificationActionCode.REPLY_INCOMING_CALL, "Busy"))
            advanceUntilIdle()
            assertEquals(
                listOf(WatchEvent.CallAction(true), WatchEvent.CallAction(false), WatchEvent.CallAction(false), WatchEvent.NotificationAction(up.notificationId, "reply", "Busy")),
                r.events.filter { it is WatchEvent.CallAction || it is WatchEvent.NotificationAction },
            )
            r.driver.send(WatchCommand.CallEnded)
            advanceUntilIdle()
            val last = r.sent().filterIsInstance<NotificationUpdate>().last()
            assertEquals(NotificationUpdate.REMOVE, last.type)
            assertEquals(up.notificationId, last.notificationId)
        }
    }

    @Test
    fun withoutGncsNoNotificationMessagesEver() = runTest {
        rig(setOf(GarminCapability.FIND_MY_PHONE)) { r ->
            r.watch.sendGfdi(NotificationSubscription(true))
            advanceUntilIdle()
            assertEquals(GfdiStatus.UNSUPPORTED, r.replies(GfdiId.NOTIFICATION_SUBSCRIPTION).single().status)
            assertEquals(Outcome.Fail(SwError.Unsupported(Feature.NOTIFICATIONS)), r.driver.send(WatchCommand.Notify(note())))
            assertEquals(Outcome.Fail(SwError.Unsupported(Feature.NOTIFICATIONS)), r.driver.send(WatchCommand.RemoveNotification(1)))
            assertEquals(Outcome.Fail(SwError.Unsupported(Feature.CALLS)), r.driver.send(WatchCommand.IncomingCall("1", null)))
            assertEquals(Outcome.Fail(SwError.Unsupported(Feature.CALLS)), r.driver.send(WatchCommand.CallEnded))
            advanceUntilIdle()
            assertTrue(r.sent().none { it.id in GfdiId.NOTIFICATION_UPDATE..GfdiId.NOTIFICATION_SUBSCRIPTION && it !is GfdiMessage.Response })
            assertFalse(Feature.NOTIFICATIONS in r.driver.info.value!!.features)
        }
    }

    // ---- A17 music ----

    @Test
    fun musicCapabilitiesEntityUpdateAndControl() = runTest {
        rig(setOf(GarminCapability.BASIC_MUSIC_CONTROLS)) { r ->
            assertTrue(Feature.MUSIC in r.driver.info.value!!.features)
            r.watch.sendGfdi(MusicControlCapabilities(null))
            advanceUntilIdle()
            val caps = r.replies(GfdiId.MUSIC_CONTROL_CAPABILITIES).single()
            assertEquals(GfdiStatus.ACK, caps.status)
            assertEquals((0 until 9).toList(), FeatureReplies.MusicCapabilities.parse(caps.extra.toByteArray()).commands)

            for (c in listOf(MusicCommand.TOGGLE_PLAY_PAUSE, MusicCommand.SKIP_TO_NEXT_ITEM, MusicCommand.SKIP_TO_PREVIOUS_ITEM,
                MusicCommand.VOLUME_UP, MusicCommand.VOLUME_DOWN, MusicCommand.PLAY, MusicCommand.PAUSE, MusicCommand.SKIP_FORWARD)) {
                r.watch.sendGfdi(MusicControl(c))
            }
            advanceUntilIdle()
            assertEquals(
                listOf(MusicAction.TOGGLE, MusicAction.NEXT, MusicAction.PREVIOUS, MusicAction.VOLUME_UP, MusicAction.VOLUME_DOWN, MusicAction.PLAY, MusicAction.PAUSE),
                r.events.filterIsInstance<WatchEvent.MusicControl>().map { it.action },
            )
            assertEquals(8, r.replies(GfdiId.MUSIC_CONTROL).count { it.status == GfdiStatus.ACK })

            val state = MusicState(true, "Artist", "Album", "Title", 215_000, 61_500, 40)
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.Music(state)))
            advanceUntilIdle()
            val up = r.sent().filterIsInstance<MusicEntityUpdate>().single()
            assertEquals(
                listOf(
                    MusicEntity(MusicEntityId.TRACK, MusicEntityId.TRACK_ARTIST, "Artist"),
                    MusicEntity(MusicEntityId.TRACK, MusicEntityId.TRACK_ALBUM, "Album"),
                    MusicEntity(MusicEntityId.TRACK, MusicEntityId.TRACK_TITLE, "Title"),
                    MusicEntity(MusicEntityId.TRACK, MusicEntityId.TRACK_DURATION, "215"),
                    MusicEntity(MusicEntityId.PLAYER, MusicEntityId.PLAYER_PLAYBACK_INFO, "1,1.0,61.500"),
                    MusicEntity(MusicEntityId.PLAYER, MusicEntityId.PLAYER_VOLUME, "0.40"),
                ),
                up.entries,
            )
        }
    }

    @Test
    fun musicWithoutCapabilityIsUnsupported() = runTest {
        rig(setOf(GarminCapability.GNCS)) { r ->
            assertEquals(Outcome.Fail(SwError.Unsupported(Feature.MUSIC)), r.driver.send(WatchCommand.Music(MusicState(false, null, null, null, null, null, null))))
            r.watch.sendGfdi(MusicControl(0))
            advanceUntilIdle()
            assertEquals(GfdiStatus.UNSUPPORTED, r.replies(GfdiId.MUSIC_CONTROL).single().status)
            assertTrue(r.events.none { it is WatchEvent.MusicControl })
        }
    }

    // ---- A18 weather ----

    private val weather = WatchCommand.Weather(
        WeatherNow(t0, 57, -10, WeatherCondition.PARTLY_CLOUDY, 81, 180, 270, "Ann Arbor, Michigan, United States"),
        listOf(
            WeatherDay(Day.of(t0 - 86_400, java.time.ZoneId.of("America/Detroit")), 0, 10, WeatherCondition.RAIN, 90), // yesterday: skipped
            WeatherDay(Day(20261123), 15, 92, WeatherCondition.CLOUDY, 20),
            WeatherDay(Day(20261124), -30, 21, WeatherCondition.SNOW, 70),
            WeatherDay(Day(20261125), -45, 4, WeatherCondition.CLEAR, null),
        ),
    )

    /** Decodes FIT definition + data payloads into records: field num → raw value (big-endian), per data record. */
    private fun decodeFit(defs: ByteArray, data: List<ByteArray>): List<Map<Int, Long>> {
        val layouts = HashMap<Int, List<Pair<Int, Int>>>()
        val d = ByteReader(defs)
        while (d.remaining > 0) {
            val h = d.u8()
            assertEquals(0x40, h and 0xF0)
            d.u8()
            assertEquals(1, d.u8()) // big-endian
            assertEquals(128, (d.u8() shl 8) or d.u8())
            layouts[h and 0x0F] = List(d.u8()) { val num = d.u8(); val size = d.u8(); d.u8(); num to size }
        }
        val out = ArrayList<Map<Int, Long>>()
        for (msg in data) {
            val r = ByteReader(msg)
            while (r.remaining > 0) {
                val layout = layouts.getValue(r.u8())
                out.add(layout.associate { (num, size) -> num to r.bytes(size).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) } })
            }
        }
        return out
    }

    @Test
    fun weatherIsSentAsFitRecordsAndOnRequest() = runTest {
        rig(setOf(GarminCapability.WEATHER_CONDITIONS), maxPacket = 80) { r ->
            assertTrue(Feature.WEATHER in r.driver.info.value!!.features)
            // Watch asks before the app has weather: ACK only.
            r.watch.sendGfdi(WeatherRequest(0, 0, 0, 24))
            advanceUntilIdle()
            assertEquals(GfdiStatus.ACK, r.replies(GfdiId.WEATHER_REQUEST).single().status)
            assertTrue(r.watch.fitDefinitions.isEmpty())

            assertEquals(Outcome.Ok(Unit), r.driver.send(weather))
            assertEquals(1, r.watch.fitDefinitions.size)
            assertTrue("packed by packet size", r.watch.fitData.size >= 2)
            assertTrue(r.watch.fitData.all { it.size + 6 <= 80 })
            val recs = decodeFit(r.watch.fitDefinitions.single(), r.watch.fitData.toList())
            assertEquals(4, recs.size) // current + 3 days from today
            val cur = recs[0]
            assertEquals(0L, cur[0]) // current
            assertEquals(FakeWatch.gt(t0), cur[253])
            assertEquals(6L, cur[1]) // 5.7 °C
            assertEquals(0xFFL, cur[6]) // -1 °C feels like (sint8)
            assertEquals(1L, cur[2]) // partly cloudy
            assertEquals(270L, cur[3])
            assertEquals(5000L, cur[4]) // 18 km/h = 5 m/s
            assertEquals(81L, cur[7])
            assertEquals(9L, cur[13]) // today's high 9.2
            assertEquals(2L, cur[14]) // today's low 1.5 → 2
            assertEquals(20L, cur[5])
            val day2 = recs[2]
            assertEquals(2L, day2[0]) // daily forecast
            assertEquals(4L, day2[2]) // snow
            assertEquals(0xFDL, day2[14]) // -3
            assertEquals(2L, day2[13])
            assertEquals(2L, day2[12]) // Tuesday
            assertEquals(0xFFL, recs[3][5]) // no precipitation value = invalid
            assertEquals(22L, recs[1][2]) // cloudy

            // A later watch request gets the stored forecast again.
            r.watch.sendGfdi(WeatherRequest(0, 0, 0, 24))
            advanceUntilIdle()
            assertEquals(2, r.watch.fitDefinitions.size)
            // Weather conditions setting is on.
            val settings = r.sent().filterIsInstance<GfdiMessage.DeviceSettings>().single().settings.toMap()
            assertEquals(GfdiMessage.SettingValue.Bool(true), settings[app.selfwatch.garmin.gfdi.DeviceSettingId.WEATHER_CONDITIONS_ENABLED])
        }
    }

    @Test
    fun weatherWithoutCapabilityIsUnsupported() = runTest {
        rig(setOf(GarminCapability.GNCS)) { r ->
            assertEquals(Outcome.Fail(SwError.Unsupported(Feature.WEATHER)), r.driver.send(weather))
            r.watch.sendGfdi(WeatherRequest(0, 0, 0, 24))
            advanceUntilIdle()
            assertEquals(GfdiStatus.UNSUPPORTED, r.replies(GfdiId.WEATHER_REQUEST).single().status)
            assertTrue(r.watch.fitDefinitions.isEmpty())
        }
    }

    // ---- A19 find phone / watch ----

    @Test
    fun findPhoneStartAndCancel() = runTest {
        rig(setOf(GarminCapability.FIND_MY_PHONE)) { r ->
            r.watch.sendGfdi(FindMyPhoneRequest(30))
            r.watch.sendGfdi(FindMyPhoneCancel)
            advanceUntilIdle()
            assertEquals(listOf(WatchEvent.FindPhone(true), WatchEvent.FindPhone(false)), r.events.filterIsInstance<WatchEvent.FindPhone>())
            assertEquals(GfdiStatus.ACK, r.replies(GfdiId.FIND_MY_PHONE_REQUEST).single().status)
            assertEquals(GfdiStatus.ACK, r.replies(GfdiId.FIND_MY_PHONE_CANCEL).single().status)
        }
    }

    @Test
    fun findPhoneWithoutCapabilityIsRefused() = runTest {
        rig(setOf(GarminCapability.GNCS)) { r ->
            r.watch.sendGfdi(FindMyPhoneRequest(30))
            advanceUntilIdle()
            assertTrue(r.events.none { it is WatchEvent.FindPhone })
            assertEquals(GfdiStatus.UNSUPPORTED, r.replies(GfdiId.FIND_MY_PHONE_REQUEST).single().status)
        }
    }

    @Test
    fun findWatchRingsAndCancelsOverProtobuf() = runTest {
        rig(setOf(GarminCapability.FIND_MY_WATCH)) { r ->
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.FindWatch(true)))
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.FindWatch(false)))
            advanceUntilIdle()
            val reqs = r.watch.protobufFromPhone.map { Smart.parseFrom(it.third) }
            assertEquals(60, reqs[0].findMyWatchService.findRequest.timeout)
            assertTrue(reqs[1].findMyWatchService.hasCancelRequest())
            assertEquals(listOf(1, 2), r.watch.protobufFromPhone.map { it.second }) // request ids count up
            // The watch's answer is accepted ("kept").
            val answer = Smart.newBuilder().setFindMyWatchService(
                FindMyWatchProto.FindMyWatchService.newBuilder().setFindResponse(
                    FindMyWatchProto.FindResponse.newBuilder().setStatus(FindMyWatchProto.FindResponse.Status.OK),
                ),
            ).build()
            r.watch.sendProtobuf(GfdiId.PROTOBUF_RESPONSE, 1, answer.toByteArray())
            advanceUntilIdle()
            val st = FeatureReplies.ProtobufStatus.parse(r.replies(GfdiId.PROTOBUF_RESPONSE).single().extra.toByteArray())
            assertTrue(st.isOk)
        }
    }

    @Test
    fun findWatchWithoutCapabilityIsUnsupported() = runTest {
        rig(setOf(GarminCapability.GNCS)) { r ->
            assertEquals(Outcome.Fail(SwError.Unsupported(Feature.FIND_WATCH)), r.driver.send(WatchCommand.FindWatch(true)))
            advanceUntilIdle()
            assertTrue(r.watch.protobufFromPhone.isEmpty())
        }
    }

    // ---- A15 protobuf service ----

    private fun bigSmart(n: Int): Smart = Smart.newBuilder().setCredentialsService(
        CredentialsProto.CredentialsService.newBuilder().setGcOauthResponse(
            CredentialsProto.OAuthResponse.newBuilder().setLocation(CredentialsProto.ServerLocation.TEST).setOauthCredentials(
                CredentialsProto.OAuthCredentials.newBuilder().setConsumerKey("k".repeat(n)).setConsumerSecret("s").setOauthToken("t").setOauthSecret("x"),
            ),
        ),
    ).build()

    @Test
    fun chunkedRequestAndChunkedResponse() = runTest {
        rig(setOf(GarminCapability.GNCS), maxPacket = 60) { r ->
            val question = bigSmart(150)
            val answer = bigSmart(333)
            r.watch.protobufResponder = { bytes -> if (Smart.parseFrom(bytes) == question) answer.toByteArray() else null }
            val got = r.features.protobuf.request(r.session, question)
            assertEquals(Outcome.Ok(answer), got)
            val chunks = r.sent().filterIsInstance<ProtobufPacket>().filter { it.id == GfdiId.PROTOBUF_REQUEST }
            assertTrue(chunks.size >= 4)
            assertTrue(chunks.all { it.data.size <= 40 }) // maxPacket - 20
            assertEquals(chunks.indices.map { it * 40 }, chunks.map { it.dataOffset })
            // Each unfinished incoming chunk was answered "kept" with its offset.
            val st = r.replies(GfdiId.PROTOBUF_RESPONSE).map { FeatureReplies.ProtobufStatus.parse(it.extra.toByteArray()) }
            assertTrue(st.size >= 9)
            assertTrue(st.all { it.isOk && it.requestId == 1 })
            assertEquals(st.indices.map { it * 40 }, st.map { it.dataOffset })
        }
    }

    @Test
    fun requestTimesOutWithoutAnswer() = runTest {
        rig(setOf(GarminCapability.GNCS)) { r ->
            val got = r.features.protobuf.request(r.session, Smart.getDefaultInstance(), timeoutMs = 2_000)
            assertTrue((got as Outcome.Fail).error is SwError.Timeout)
        }
    }

    @Test
    fun watchRequestsGetReferenceStatuses() = runTest {
        rig(setOf(GarminCapability.GNCS), maxPacket = 60) { r ->
            fun core(b: CoreProto.CoreService.Builder) = Smart.newBuilder().setCoreService(b).build().toByteArray()
            // Connection ready: kept, no answer.
            r.watch.sendProtobuf(GfdiId.PROTOBUF_REQUEST, 10, core(CoreProto.CoreService.newBuilder().setConnectionReadyNotification(CoreProto.ConnectionReadyNotification.getDefaultInstance())))
            // Location: plain ACK, then a response "no valid location".
            r.watch.sendProtobuf(GfdiId.PROTOBUF_REQUEST, 11, core(CoreProto.CoreService.newBuilder().setGetLocationRequest(CoreProto.GetLocationRequest.getDefaultInstance())))
            // Nobody handles an empty Smart: discarded, unknown request id.
            r.watch.sendProtobuf(GfdiId.PROTOBUF_REQUEST, 12, ByteArray(0))
            // Garbage: discarded, parse error.
            r.watch.sendProtobuf(GfdiId.PROTOBUF_REQUEST, 13, byteArrayOf(0x0a, 0x7f))
            // Fake sign-in is off: discarded.
            r.watch.sendProtobuf(GfdiId.PROTOBUF_REQUEST, 14, Smart.newBuilder().setCredentialsService(CredentialsProto.CredentialsService.newBuilder().setGcOauthRequest(CredentialsProto.OAuthRequest.getDefaultInstance())).build().toByteArray())
            // Battery over protobuf → event.
            r.watch.sendProtobuf(GfdiId.PROTOBUF_RESPONSE, 15, Smart.newBuilder().setDeviceStatusService(DeviceStatusProto.DeviceStatusService.newBuilder().setBatteryStatusResponse(DeviceStatusProto.BatteryStatusResponse.newBuilder().setStatus(DeviceStatusProto.BatteryStatusResponse.Status.OK).setCurrentBatteryLevel(66))).build().toByteArray())
            advanceUntilIdle()
            val replies = r.replies(GfdiId.PROTOBUF_REQUEST)
            fun st(i: Int) = FeatureReplies.ProtobufStatus.parse(replies[i].extra.toByteArray())
            assertEquals(5, replies.size)
            assertTrue(st(0).isOk && st(0).requestId == 10)
            assertEquals(0, replies[1].extra.size) // plain ACK before the reply
            assertEquals(FeatureReplies.ProtobufStatus(12, 0, 1, 100), st(2))
            assertEquals(FeatureReplies.ProtobufStatus(13, 0, 1, 200), st(3))
            assertEquals(FeatureReplies.ProtobufStatus(14, 0, 1, 100), st(4))
            val loc = r.watch.protobufFromPhone.single { it.first == GfdiId.PROTOBUF_RESPONSE }
            assertEquals(11, loc.second)
            assertEquals(CoreProto.GetLocationResponse.Status.NO_VALID_LOCATION, Smart.parseFrom(loc.third).coreService.getLocationResponse.status)
            assertTrue(WatchEvent.Battery(66) in r.events)
        }
    }

    @Test
    fun fakeSignInOnlyWithFakeAuth() = runTest {
        rig(setOf(GarminCapability.GNCS), config = GarminConfig(syncOnConnect = false, fakeAuth = true)) { r ->
            r.watch.sendProtobuf(GfdiId.PROTOBUF_REQUEST, 3, Smart.newBuilder().setCredentialsService(CredentialsProto.CredentialsService.newBuilder().setGcOauthRequest(CredentialsProto.OAuthRequest.getDefaultInstance())).build().toByteArray())
            advanceUntilIdle()
            val resp = Smart.parseFrom(r.watch.protobufFromPhone.single().third)
            val c = resp.credentialsService.gcOauthResponse.oauthCredentials
            assertEquals(35, c.consumerSecret.length)
            assertEquals(CredentialsProto.ServerLocation.PRODUCTION, resp.credentialsService.gcOauthResponse.location)
        }
    }

    @Test
    fun incomingChunksOutOfOrderAreRefused() = runTest {
        rig(setOf(GarminCapability.GNCS)) { r ->
            val bytes = bigSmart(100).toByteArray()
            r.watch.sendGfdi(ProtobufPacket(GfdiId.PROTOBUF_REQUEST, 20, 0, bytes.size, Bytes(bytes.copyOfRange(0, 30))))
            r.watch.sendGfdi(ProtobufPacket(GfdiId.PROTOBUF_REQUEST, 20, 60, bytes.size, Bytes(bytes.copyOfRange(60, 90))))
            r.watch.sendGfdi(ProtobufPacket(GfdiId.PROTOBUF_REQUEST, 21, 0, 10_000_000, Bytes(bytes.copyOfRange(0, 30))))
            advanceUntilIdle()
            val st = r.replies(GfdiId.PROTOBUF_REQUEST).map { FeatureReplies.ProtobufStatus.parse(it.extra.toByteArray()) }
            assertEquals(listOf(0 to 0, 1 to 102, 1 to 103), st.map { it.chunkStatus to it.code })
        }
    }

    // ---- A24 GPS assistance (skeleton) ----

    @Test
    fun gpsAssistUploadsOnlyWithCapabilityAndKnownFormat() = runTest {
        rig(setOf(GarminCapability.GNCS)) { r ->
            val sony = byteArrayOf(0x2a, 0x12, 0xa0.toByte(), 0x02) + ByteArray(500) { it.toByte() }
            assertEquals(Outcome.Fail(SwError.Unsupported(Feature.GPS_ASSIST)), r.features.gpsAssist.upload(sony, 255, 0))
        }
        rig(setOf(GarminCapability.GPS_EPHEMERIS_DOWNLOAD)) { r ->
            val sony = byteArrayOf(0x2a, 0x12, 0xa0.toByte(), 0x02) + ByteArray(500) { it.toByte() }
            assertTrue((r.features.gpsAssist.upload(ByteArray(10), 255, 0) as Outcome.Fail).error is SwError.BadInput)
            assertEquals(Outcome.Ok(Unit), r.features.gpsAssist.upload(sony, 255, 0))
            assertArrayEquals(sony, r.watch.uploaded.toByteArray())
            assertFalse("skeleton: not advertised as a feature", Feature.GPS_ASSIST in r.driver.info.value!!.features)
        }
    }

    // ---- fuzz: random feature traffic never ends the session ----

    @Test
    fun sessionSurvivesRandomFeatureMessages() = runTest {
        val all = setOf(
            GarminCapability.GNCS, GarminCapability.BASIC_MUSIC_CONTROLS, GarminCapability.WEATHER_CONDITIONS,
            GarminCapability.FIND_MY_PHONE, GarminCapability.FIND_MY_WATCH,
        )
        rig(all) { r ->
            val rnd = Random(21)
            val ids = listOf(
                GfdiId.NOTIFICATION_CONTROL, GfdiId.NOTIFICATION_SUBSCRIPTION, GfdiId.FIND_MY_PHONE_REQUEST, GfdiId.FIND_MY_PHONE_CANCEL,
                GfdiId.MUSIC_CONTROL, GfdiId.MUSIC_CONTROL_CAPABILITIES, GfdiId.WEATHER_REQUEST, GfdiId.PROTOBUF_REQUEST,
                GfdiId.PROTOBUF_RESPONSE, GfdiId.FIT_DATA, GfdiId.NOTIFICATION_DATA,
            )
            r.watch.sendGfdi(NotificationSubscription(true))
            r.driver.send(WatchCommand.Notify(note()))
            repeat(600) {
                val id = ids[rnd.nextInt(ids.size)]
                val p = ByteArray(rnd.nextInt(0, 40)) { rnd.nextInt(256).toByte() }
                if (p.isNotEmpty() && id == GfdiId.NOTIFICATION_CONTROL) p[0] = listOf(0, 1, 2, 128).random(rnd).toByte()
                if (p.size > 5 && id == GfdiId.NOTIFICATION_CONTROL) { p[1] = 42; p[2] = 0; p[3] = 0; p[4] = 0 }
                r.watch.sendFrame(GfdiFrame.build(id, p))
            }
            advanceUntilIdle()
            assertEquals(SessionState.READY, r.driver.sessionState.value)
            // Still working afterwards.
            r.watch.sendGfdi(FindMyPhoneRequest(5))
            advanceUntilIdle()
            assertNotNull(r.events.lastOrNull { it is WatchEvent.FindPhone })
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.Music(MusicState(false, null, null, null, null, null, null))))
        }
    }
}
