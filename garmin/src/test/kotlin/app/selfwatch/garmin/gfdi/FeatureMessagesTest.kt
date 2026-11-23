/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.gfdi

import app.selfwatch.model.result.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FeatureMessagesTest {
    private val samples: List<GfdiMessage> = listOf(
        NotificationUpdate(NotificationUpdate.ADD, 0x12, 12, 3, 0x12345678, NotificationUpdate.PHONE_NEW_ACTIONS),
        NotificationAttributesRequest(
            7,
            listOf(
                AttributeRequest(NotificationAttribute.APP_IDENTIFIER),
                AttributeRequest(NotificationAttribute.TITLE, 32),
                AttributeRequest(NotificationAttribute.MESSAGE, 200),
                AttributeRequest(NotificationAttribute.MESSAGE_SIZE),
                AttributeRequest(NotificationAttribute.DATE),
                AttributeRequest(NotificationAttribute.ACTIONS, 100, 1),
                AttributeRequest(NotificationAttribute.ATTACHMENTS),
            ),
        ),
        NotificationAppAttributesRequest("com.example.chat", listOf(NotificationAttribute.APP_NAME)),
        NotificationLegacyAction(9, 1),
        NotificationActionRequest(9, NotificationActionCode.REPLY_MESSAGES, "On my way"),
        NotificationActionRequest(-5, NotificationActionCode.DISMISS_NOTIFICATION, null),
        NotificationData(600, 0xBEEF, 300, Bytes(ByteArray(300) { it.toByte() })),
        NotificationSubscription(true, 0),
        NotificationSubscription(false, 1),
        FindMyPhoneRequest(30),
        FindMyPhoneCancel,
        MusicControl(MusicCommand.SKIP_TO_NEXT_ITEM),
        MusicControlCapabilities(null),
        MusicControlCapabilities(3),
        MusicEntityUpdate(listOf(MusicEntity(MusicEntityId.TRACK, MusicEntityId.TRACK_TITLE, "Song"), MusicEntity(MusicEntityId.PLAYER, MusicEntityId.PLAYER_VOLUME, "0.50"))),
        WeatherRequest(0, 0x1F000000, -0x0F000000, 24),
        FitDefinition(Bytes(byteArrayOf(0x40, 0, 1, 0, 128.toByte(), 1, 0, 1, 0))),
        FitData(Bytes(byteArrayOf(0, 1))),
        ProtobufPacket(GfdiId.PROTOBUF_REQUEST, 1, 0, 3, Bytes(byteArrayOf(0x6a, 1, 0))),
        ProtobufPacket(GfdiId.PROTOBUF_RESPONSE, 65_535, 40, 100, Bytes(ByteArray(40) { 7 })),
    )

    @Test
    fun everyFeatureMessageRoundTripsThroughAFrame() {
        for (m in samples) {
            val back = (GfdiMessage.decodeFrame(m.toFrame()) as Outcome.Ok).value
            assertEquals("round trip of ${m.id} $m", m, back)
        }
    }

    @Test
    fun layouts() {
        // Ref layouts: type, flags, category, count, u32 id, phone flags.
        assertEquals("00" + "12" + "0c" + "03" + "78563412" + "02", Bytes.hex(samples[0].encode()))
        // Music entity: length (value + 3), entity, attribute, 0, value.
        assertEquals("07" + "02" + "02" + "00" + "536f6e67", Bytes.hex(MusicEntityUpdate(listOf(MusicEntity(2, 2, "Song"))).encode()))
        // Protobuf: u16 request id, u32 offset, u32 total, u32 chunk length, chunk.
        assertEquals("0100" + "00000000" + "03000000" + "03000000" + "6a0100", Bytes.hex(samples[18].encode()))
        // Notification data: u16 size, u16 crc, u16 offset.
        assertEquals("0500" + "efbe" + "0000" + "01", Bytes.hex(NotificationData(5, 0xBEEF, 0, Bytes(byteArrayOf(1))).encode()))
    }

    @Test
    fun repliesRoundTrip() {
        assertEquals(FeatureReplies.SubscriptionStatus(0, true, 0), FeatureReplies.SubscriptionStatus.parse(FeatureReplies.SubscriptionStatus(0, true, 0).encode()))
        assertEquals(FeatureReplies.ControlStatus(1, 160), FeatureReplies.ControlStatus.parse(FeatureReplies.ControlStatus(1, 160).encode()))
        assertEquals(FeatureReplies.DataStatus(3), FeatureReplies.DataStatus.parse(FeatureReplies.DataStatus(3).encode()))
        assertEquals(FeatureReplies.FitStatus(2), FeatureReplies.FitStatus.parse(FeatureReplies.FitStatus(2).encode()))
        val ps = FeatureReplies.ProtobufStatus(513, 375, 1, 100)
        assertEquals(ps, FeatureReplies.ProtobufStatus.parse(ps.encode()))
        assertEquals("0102" + "77010000" + "01" + "64", Bytes.hex(ps.encode()))
        val mc = FeatureReplies.MusicCapabilities(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8))
        assertEquals(mc, FeatureReplies.MusicCapabilities.parse(mc.encode()))
        assertEquals("09000102030405060708", Bytes.hex(mc.encode()))
    }

    @Test
    fun badPayloadsBecomeMalformed() {
        assertTrue(GfdiMessage.decode(GfdiId.NOTIFICATION_CONTROL, byteArrayOf(0x55)) is GfdiMessage.Malformed) // unknown command
        assertTrue(GfdiMessage.decode(GfdiId.NOTIFICATION_CONTROL, byteArrayOf(0, 1, 0, 0, 0, 6)) is GfdiMessage.Malformed) // unknown attribute
        assertTrue(GfdiMessage.decode(GfdiId.MUSIC_CONTROL_ENTITY_UPDATE, byteArrayOf(2, 0)) is GfdiMessage.Malformed)
        assertTrue(GfdiMessage.decode(GfdiId.PROTOBUF_REQUEST, Bytes.fromHex("0100 00000000 05000000 05000000 01")) is GfdiMessage.Malformed)
        assertTrue(GfdiMessage.decode(GfdiId.PROTOBUF_REQUEST, Bytes.fromHex("0100 ffffffff 05000000 00000000")) is GfdiMessage.Malformed)
        assertTrue(GfdiMessage.decode(GfdiId.WEATHER_REQUEST, byteArrayOf(1, 2)) is GfdiMessage.Malformed)
        // Optional bytes may be missing.
        assertEquals(MusicControlCapabilities(null), GfdiMessage.decode(GfdiId.MUSIC_CONTROL_CAPABILITIES, ByteArray(0)))
        assertEquals(NotificationSubscription(true, 0), GfdiMessage.decode(GfdiId.NOTIFICATION_SUBSCRIPTION, byteArrayOf(1)))
        assertEquals(NotificationActionRequest(1, 96, null), GfdiMessage.decode(GfdiId.NOTIFICATION_CONTROL, Bytes.fromHex("80 01000000 60")))
    }

    @Test
    fun fuzzedFeaturePayloadsNeverThrow() {
        val rnd = Random(11)
        val ids = listOf(
            GfdiId.NOTIFICATION_UPDATE, GfdiId.NOTIFICATION_CONTROL, GfdiId.NOTIFICATION_DATA, GfdiId.NOTIFICATION_SUBSCRIPTION,
            GfdiId.FIND_MY_PHONE_REQUEST, GfdiId.FIND_MY_PHONE_CANCEL, GfdiId.MUSIC_CONTROL, GfdiId.MUSIC_CONTROL_CAPABILITIES,
            GfdiId.MUSIC_CONTROL_ENTITY_UPDATE, GfdiId.WEATHER_REQUEST, GfdiId.FIT_DEFINITION, GfdiId.FIT_DATA,
            GfdiId.PROTOBUF_REQUEST, GfdiId.PROTOBUF_RESPONSE,
        )
        repeat(30_000) {
            val id = ids[rnd.nextInt(ids.size)]
            val p = ByteArray(rnd.nextInt(0, 48)) { rnd.nextInt(256).toByte() }
            if (rnd.nextBoolean() && p.isNotEmpty()) p[0] = listOf(0, 1, 2, 128).random(rnd).toByte() // reach every control command
            val m = GfdiMessage.decode(id, p)
            assertEquals(id, m.id)
        }
    }

    @Test
    fun utf8PrefixNeverSplitsACharacter() {
        val s = "aé€😀".repeat(50)
        for (max in 0..60) {
            val b = utf8Prefix(s, max)
            assertTrue(b.size <= max)
            assertEquals(String(b, Charsets.UTF_8).toByteArray(Charsets.UTF_8).size, b.size)
        }
    }
}
