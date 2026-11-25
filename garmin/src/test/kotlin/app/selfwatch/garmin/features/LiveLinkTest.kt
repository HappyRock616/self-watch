/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.selfwatch.garmin.features

import app.selfwatch.garmin.gfdi.ByteWriter
import app.selfwatch.garmin.link.MlControl
import app.selfwatch.garmin.link.MlService
import app.selfwatch.garmin.link.MultiLink
import app.selfwatch.model.fake.FakeBytePipe
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.watch.WatchEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLinkTest {
    private fun resp(type: Int) = ByteWriter().u8(0).u8(type).i64(MlControl.DEFAULT_CLIENT_ID)

    @Test
    fun parsesHeartRate() {
        assertEquals(WatchEvent.LiveHeartRate(62, 51), LiveReadings.heartRate(byteArrayOf(3, 62, 51, -1, -1)))
        assertEquals(WatchEvent.LiveHeartRate(140, null), LiveReadings.heartRate(byteArrayOf(3, 140.toByte())))
        assertEquals(WatchEvent.LiveHeartRate(200, null), LiveReadings.heartRate(byteArrayOf(0, 200.toByte(), 0)))
        assertNull("no contact", LiveReadings.heartRate(byteArrayOf(3, 0, 51)))
        assertNull("too short", LiveReadings.heartRate(byteArrayOf(3)))
        assertNull(LiveReadings.heartRate(ByteArray(0)))
    }

    @Test
    fun parsesSteps() {
        val b = ByteWriter().i32(8_421).i32(10_000).toByteArray()
        assertEquals(WatchEvent.LiveSteps(8_421, 10_000), LiveReadings.steps(b))
        assertEquals(WatchEvent.LiveSteps(12, null), LiveReadings.steps(ByteWriter().i32(12).toByteArray()))
        assertEquals(WatchEvent.LiveSteps(12, null), LiveReadings.steps(ByteWriter().i32(12).i32(0).toByteArray()))
        assertNull(LiveReadings.steps(byteArrayOf(1, 2, 3)))
        assertNull("absurd count", LiveReadings.steps(ByteWriter().i32(-1).toByteArray()))
    }

    @Test
    fun turnsTheServicesOnAndOff() = runTest {
        val pipe = FakeBytePipe(20)
        val registered = ArrayList<Int>()
        val closed = ArrayList<Pair<Int, Int>>()
        pipe.onWrite = { p, b ->
            if (b[0].toInt() == 0) {
                val service = (b[10].toInt() and 0xFF) or ((b[11].toInt() and 0xFF) shl 8)
                when (b[1].toInt()) {
                    MlControl.CLOSE_ALL_REQ -> p.queueIncoming(resp(MlControl.CLOSE_ALL_RESP).u16(0).toByteArray())
                    MlControl.REGISTER_REQ -> {
                        registered += service
                        p.queueIncoming(resp(MlControl.REGISTER_RESP).u16(service).u8(0).u8(service).u8(0).toByteArray())
                    }
                    MlControl.CLOSE_HANDLE_REQ -> {
                        closed += service to (b[12].toInt() and 0xFF)
                        p.queueIncoming(resp(MlControl.CLOSE_HANDLE_RESP).u16(service).u8(b[12].toInt()).u8(0).toByteArray())
                    }
                }
            }
        }
        val link = MultiLink(pipe, backgroundScope)
        link.start()
        val events = ArrayList<WatchEvent>()
        val live = LiveLink(link, backgroundScope) { events += it }

        assertTrue(live.set(true) is Outcome.Ok)
        assertTrue(live.isOn)
        assertEquals(listOf(MlService.REALTIME_HR, MlService.REALTIME_STEPS), registered)
        // A second "on" registers nothing new.
        assertTrue(live.set(true) is Outcome.Ok)
        assertEquals(2, registered.size)

        // Data on the services' handles (handle byte = the service code in this fake) becomes events.
        pipe.queueIncoming(byteArrayOf(MlService.REALTIME_HR.toByte(), 3, 64, 52))
        pipe.queueIncoming(byteArrayOf(MlService.REALTIME_STEPS.toByte()) + ByteWriter().i32(5_012).i32(10_000).toByteArray())
        delay(100)
        assertEquals(listOf(WatchEvent.LiveHeartRate(64, 52), WatchEvent.LiveSteps(5_012, 10_000)), events)

        assertTrue(live.set(false) is Outcome.Ok)
        assertFalse(live.isOn)
        assertEquals(listOf(MlService.REALTIME_HR to MlService.REALTIME_HR, MlService.REALTIME_STEPS to MlService.REALTIME_STEPS), closed)
        // After closing, the watch's close answers do not re-register the services, and late data is dropped.
        delay(1_000)
        assertEquals(2, registered.size)
        pipe.queueIncoming(byteArrayOf(MlService.REALTIME_HR.toByte(), 3, 70, 52))
        delay(100)
        assertEquals(2, events.size)

        // On again registers both services again.
        assertTrue(live.set(true) is Outcome.Ok)
        assertEquals(4, registered.size)
        link.close()
    }

    @Test
    fun aRefusedHeartRateServiceFails() = runTest {
        val pipe = FakeBytePipe(20)
        pipe.onWrite = { p, b ->
            if (b[0].toInt() == 0 && b[1].toInt() == MlControl.CLOSE_ALL_REQ) p.queueIncoming(resp(MlControl.CLOSE_ALL_RESP).toByteArray())
            if (b[0].toInt() == 0 && b[1].toInt() == MlControl.REGISTER_REQ) p.queueIncoming(resp(MlControl.REGISTER_RESP).u16(MlService.REALTIME_HR).u8(1).toByteArray())
        }
        val link = MultiLink(pipe, backgroundScope)
        link.start()
        val live = LiveLink(link, backgroundScope) {}
        assertTrue(live.set(true) is Outcome.Fail)
        assertFalse(live.isOn)
        link.close()
    }
}
