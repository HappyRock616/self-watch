/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.features

import app.selfwatch.garmin.gfdi.MusicCommand
import app.selfwatch.garmin.pb.proto.CoreProto
import app.selfwatch.garmin.pb.proto.FindMyWatchProto
import app.selfwatch.garmin.pb.proto.SmartProto.Smart
import app.selfwatch.model.watch.MusicAction
import app.selfwatch.model.watch.NotificationCategory
import app.selfwatch.model.watch.WeatherCondition
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class FeatureUnitsTest {
    @Test
    fun textCutKeepsSurrogatePairs() {
        assertEquals("abc", NotificationsHandler.cut("abcdef", 3))
        assertEquals("abcdef", NotificationsHandler.cut("abcdef", 0))
        assertEquals("a", NotificationsHandler.cut("a😀b", 2)) // would split the emoji
        assertEquals("a😀", NotificationsHandler.cut("a😀b", 3))
    }

    @Test
    fun categoriesMatchTheReference() {
        assertEquals(1, NotificationsHandler.categoryValue(NotificationCategory.CALL))
        assertEquals(12, NotificationsHandler.categoryValue(NotificationCategory.MESSAGE))
        assertEquals(6, NotificationsHandler.categoryValue(NotificationCategory.EMAIL))
        assertEquals(0, NotificationsHandler.categoryValue(NotificationCategory.OTHER))
    }

    @Test
    fun musicCommandsMap() {
        assertEquals(MusicAction.TOGGLE, MusicHandler.actionOf(MusicCommand.TOGGLE_PLAY_PAUSE))
        assertNull(MusicHandler.actionOf(MusicCommand.SKIP_BACKWARDS))
        assertNull(MusicHandler.actionOf(200))
        val long = "x".repeat(400)
        val u = MusicHandler.update(app.selfwatch.model.watch.MusicState(false, long, long, long, null, null, null))
        assertTrue("fits one frame", u.encode().size + 6 <= 375)
        assertEquals("0,0.0,0.000", u.entries.single { it.attribute == 1 && it.entity == 0 }.value)
    }

    @Test
    fun weatherConditionsHaveFitCodes() {
        assertEquals(0L, WeatherFit.status(WeatherCondition.CLEAR))
        assertEquals(22L, WeatherFit.status(WeatherCondition.CLOUDY))
        assertNull(WeatherFit.status(WeatherCondition.UNKNOWN))
        for (c in WeatherCondition.entries) {
            val s = WeatherFit.status(c)
            assertTrue(s == null || s in 0L..22L)
        }
    }

    @Test
    fun gpsAssistFormats() {
        assertEquals(GpsAssist.Kind.SONY_CPE, GpsAssist.kindOf(byteArrayOf(0x2a, 0x12, 0xa0.toByte(), 0x02, 9)))
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(byteArrayOf(1, 0, 5, 5, 5, 5)) } }.toByteArray()
        assertEquals(GpsAssist.Kind.RXNETWORKS_GZ, GpsAssist.kindOf(gz))
        val otherGz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(byteArrayOf(7, 7)) } }.toByteArray()
        assertNull(GpsAssist.kindOf(otherGz))
        val tar = ByteArray(1024).also { "ustar".toByteArray().copyInto(it, 257) }
        assertEquals(GpsAssist.Kind.TAR, GpsAssist.kindOf(tar))
        assertNull(GpsAssist.kindOf(ByteArray(3)))
        assertNull(GpsAssist.kindOf(byteArrayOf(0x1f, 0x8b.toByte(), 0, 0))) // broken gzip
        assertTrue(GpsAssist.isEphemerisRequest("https://api.gcs.garmin.com/ephemeris/cpe/sony/lle?coverage=WEEKS_1"))
        assertTrue(GpsAssist.isEphemerisRequest("https://api.gcs.garmin.cn/ephemeris/x"))
        assertFalse(GpsAssist.isEphemerisRequest("https://api.gcs.garmin.com/weather/x"))
        assertFalse(GpsAssist.isEphemerisRequest("https://evil.example/ephemeris/"))
        assertFalse(GpsAssist.isEphemerisRequest("::not a url"))
    }

    @Test
    fun protoSubsetRoundTripsAndKeepsUnknownFields() {
        val s = Smart.newBuilder()
            .setCoreService(CoreProto.CoreService.newBuilder().setSyncResponse(CoreProto.SyncResponse.newBuilder().setStatus(CoreProto.SyncResponse.Status.OK)))
            .setFindMyWatchService(FindMyWatchProto.FindMyWatchService.newBuilder().setFindRequest(FindMyWatchProto.FindRequest.newBuilder().setTimeout(60)))
            .build()
        assertEquals(s, Smart.parseFrom(s.toByteArray()))
        // Field numbers on the wire: Smart.core_service = 13, find_my_watch_service = 12, timeout = 1.
        val find = Smart.newBuilder().setFindMyWatchService(FindMyWatchProto.FindMyWatchService.newBuilder().setFindRequest(FindMyWatchProto.FindRequest.newBuilder().setTimeout(60))).build()
        assertArrayEquals(byteArrayOf(0x62, 4, 0x0a, 2, 0x08, 60), find.toByteArray())
        // A service we do not model (calendar = 1) survives a parse and re-encode.
        val calendar = byteArrayOf(0x0a, 2, 0x08, 1)
        assertArrayEquals(calendar, Smart.parseFrom(calendar).toByteArray())
    }
}
