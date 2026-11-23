/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.session

import app.selfwatch.garmin.gfdi.GfdiMessage
import app.selfwatch.garmin.gfdi.GfdiReplies
import app.selfwatch.model.device.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SessionPartsTest {
    @Test
    fun capabilityBitPositionsMatchTheReference() {
        assertEquals(6, GarminCapability.GNCS.ordinal)
        assertEquals(12, GarminCapability.CONNECTIQ_WATCH_APP_DOWNLOAD.ordinal)
        assertEquals(71, GarminCapability.CURRENT_TIME_REQUEST_SUPPORT.ordinal)
        assertEquals(76, GarminCapability.MULTI_LINK_SERVICE.ordinal)
        assertEquals(99, GarminCapability.GENERIC_CREDENTIALS.ordinal)
        assertEquals(129, GarminCapability.entries.size)
    }

    @Test
    fun capabilityBitsRoundTrip() {
        val caps = setOf(GarminCapability.CONNECT_MOBILE_FIT_LINK, GarminCapability.GNCS, GarminCapability.CAPABILITY_128)
        val bits = GarminCapability.toBits(caps)
        assertEquals(17, bits.size)
        assertEquals(0x41, bits[0].toInt() and 0xFF)
        assertEquals(caps, GarminCapability.fromBits(bits))
        // Unknown extra bits are ignored.
        assertEquals(caps, GarminCapability.fromBits(bits + byteArrayOf(-1, -1)))
    }

    @Test
    fun referenceSetLeavesOutWhatItDoesNotImplement() {
        val r = GarminCapability.REFERENCE
        assertFalse(GarminCapability.GPS_EPHEMERIS_DOWNLOAD in r)
        assertFalse(GarminCapability.CAPABILITY_110 in r)
        assertTrue(GarminCapability.CAPABILITY_112 in r)
        assertEquals(129 - 3 - 14, r.size)
    }

    @Test
    fun garminEpoch() {
        assertEquals(631_065_600L, GarminTime.toUnix(0))
        assertEquals(1_000_000_000, GarminTime.fromUnix(1_631_065_600L))
        assertEquals(0xFFFF_FFFFL + 631_065_600L, GarminTime.toUnix(-1))
    }

    @Test
    fun timeReplyCarriesOffsetAndTransitions() {
        val zone = ZoneId.of("America/Detroit")
        val now = ZonedDateTime.of(2026, 11, 23, 12, 0, 0, 0, zone).toEpochSecond()
        val r = GarminTime.reply(7, now, zone)
        assertEquals(7, r.referenceId)
        assertEquals(GarminTime.fromUnix(now), r.garminTime)
        assertEquals(-18_000, r.tzOffsetSec)
        val nextDst = ZonedDateTime.of(2027, 3, 14, 3, 0, 0, 0, zone).toEpochSecond()
        val endDst = ZonedDateTime.of(2027, 11, 7, 1, 0, 0, 0, zone).toEpochSecond() + 3600
        assertEquals(GarminTime.fromUnix(nextDst), r.nextTransition)
        assertEquals(GarminTime.fromUnix(endDst), r.transitionAfterNext)
        val utc = GarminTime.reply(1, now, ZoneId.of("UTC"))
        assertEquals(0, utc.tzOffsetSec)
        assertEquals(0, utc.nextTransition)
    }

    @Test
    fun featuresAreCapabilitiesIntersectImplementedMinusOverrides() {
        val caps = setOf(GarminCapability.GNCS, GarminCapability.CONNECTIQ_WATCH_APP_DOWNLOAD, GarminCapability.CURRENT_TIME_REQUEST_SUPPORT)
        val types = listOf(GfdiReplies.FileTypeInfo(128, 32, "m"), GfdiReplies.FileTypeInfo(128, 49, "s"), GfdiReplies.FileTypeInfo(128, 73, "t"))
        val f = Features.resolve(caps, types, mapOf("spo2" to false, "trainingLoad" to false), appChannel = true)
        assertTrue(Feature.WATCH_APP_MESSAGES in f)
        assertTrue(Feature.TIME_SYNC in f)
        assertTrue(Feature.HEALTH_SKIN_TEMP in f)
        assertTrue(Feature.HEALTH_SLEEP in f)
        assertFalse("override", Feature.HEALTH_SPO2 in f)
        assertTrue("GNCS flag, implemented (A16)", Feature.NOTIFICATIONS in f)
        assertFalse("no music flag", Feature.MUSIC in f)
        assertFalse("no activity files listed", Feature.WORKOUTS in f)
        assertFalse(Feature.WATCH_APP_MESSAGES in Features.resolve(caps, types, emptyMap(), appChannel = false))
        // Without the GNCS flag no notification feature even before the implemented filter.
        assertFalse(Feature.NOTIFICATIONS in Features.fromCapabilities(emptySet()))
        assertTrue(Feature.NOTIFICATIONS in Features.fromCapabilities(caps))
    }

    @Test
    fun fakeAuthIsOffByDefault() {
        assertFalse(GarminConfig().fakeAuth)
        assertFalse(AuthPolicy(GarminConfig().fakeAuth).allowFakeOAuth)
        assertTrue(AuthPolicy(true).allowFakeOAuth)
        assertEquals(GfdiReplies.AuthReply(0, 3, 0x81), AuthPolicy(false).negotiationReply(GfdiMessage.AuthNegotiation(3, 0x81)))
    }
}
