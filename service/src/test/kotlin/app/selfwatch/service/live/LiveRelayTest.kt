/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.selfwatch.service.live

import app.selfwatch.model.fake.FakeWatchDriver
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchEvent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The live readings are on only while a screen holds them, with a fake watch driver. */
@RunWith(RobolectricTestRunner::class)
class LiveRelayTest {
    private class Rig(scope: TestScope) {
        val drivers = mapOf(1L to FakeWatchDriver(), 2L to FakeWatchDriver())
        var connected = listOf(1L)
        val published = ArrayList<LiveReading>()
        var now = 1_000L
        val relay = LiveRelay(
            scope = scope.backgroundScope,
            connected = { connected },
            send = { id, cmd -> drivers.getValue(id).send(cmd) },
            publish = { published += it },
            nowSec = { now },
        )

        fun live(id: Long): List<WatchCommand> = drivers.getValue(id).sent.filterIsInstance<WatchCommand.LiveData>()
    }

    @Test
    fun onWhileHeldOffWhenReleased() = runTest {
        val r = Rig(this)
        r.relay.request(true)
        assertTrue(r.relay.active)
        assertEquals(listOf(WatchCommand.LiveData(true)), r.live(1))
        // Renewals do not repeat the command.
        r.relay.request(true)
        assertEquals(1, r.live(1).size)
        r.relay.request(false)
        assertFalse(r.relay.active)
        assertEquals(listOf(WatchCommand.LiveData(true), WatchCommand.LiveData(false)), r.live(1))
        // Off twice sends nothing more.
        r.relay.request(false)
        assertEquals(2, r.live(1).size)
        assertTrue("never asked", r.live(2).isEmpty())
    }

    @Test
    fun aScreenThatVanishesLetsTheLeaseRunOut() = runTest {
        val r = Rig(this)
        r.relay.request(true)
        advanceTimeBy(LiveRelay.RENEW_MS)
        r.relay.request(true) // renewed: the lease starts again
        advanceTimeBy(LiveRelay.LEASE_MS - 1_000)
        runCurrent()
        assertTrue("still held", r.relay.active)
        assertEquals(1, r.live(1).size)
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse("lease ran out", r.relay.active)
        assertEquals(WatchCommand.LiveData(false), r.live(1).last())
    }

    @Test
    fun aWatchThatConnectsWhileHeldIsTurnedOn() = runTest {
        val r = Rig(this)
        r.relay.onConnected(listOf(1L, 2L))
        assertTrue("nothing asked yet", r.live(1).isEmpty() && r.live(2).isEmpty())
        r.connected = listOf(1L, 2L)
        r.relay.request(true)
        assertEquals(listOf(WatchCommand.LiveData(true)), r.live(1))
        assertEquals(listOf(WatchCommand.LiveData(true)), r.live(2))
        // Watch 2 drops and comes back: it is turned on again on the new connection.
        r.relay.onConnected(listOf(1L))
        r.relay.onConnected(listOf(1L, 2L))
        assertEquals(2, r.live(2).size)
        assertEquals(1, r.live(1).size)
        r.relay.stop()
        assertEquals(WatchCommand.LiveData(false), r.live(1).last())
        assertEquals(WatchCommand.LiveData(false), r.live(2).last())
    }

    @Test
    fun readingsAreMergedAndRepeatsThrottled() = runTest {
        val r = Rig(this)
        r.relay.onEvent(1, WatchEvent.LiveHeartRate(62, 51))
        r.relay.onEvent(1, WatchEvent.LiveSteps(4_000, 10_000))
        assertEquals(LiveReading(1, 62, 51, 4_000, 10_000, 1_000), r.published.last())
        // The same values within a few seconds are not sent again; a change is.
        r.now += 1
        r.relay.onEvent(1, WatchEvent.LiveHeartRate(62, null))
        assertEquals(2, r.published.size)
        r.relay.onEvent(1, WatchEvent.LiveHeartRate(63, null))
        assertEquals(LiveReading(1, 63, 51, 4_000, 10_000, 1_001), r.published.last())
        r.now += 10
        r.relay.onEvent(1, WatchEvent.LiveHeartRate(63, null))
        assertEquals(4, r.published.size)
        // Other events are ignored.
        r.relay.onEvent(1, WatchEvent.Battery(80))
        assertEquals(4, r.published.size)
    }

    @Test
    fun aConnectedStrapReplacesTheWatchHeartRate() = runTest {
        val published = ArrayList<LiveReading>()
        var now = 1_000L
        val relay = LiveRelay(
            scope = backgroundScope,
            connected = { listOf(1L) },
            send = { _, _ -> app.selfwatch.model.result.Outcome.Ok(Unit) },
            publish = { published += it },
            nowSec = { now },
            isSensor = { it == 9L },
        )
        // A strap that streams while no screen asks is not broadcast.
        relay.onEvent(9, WatchEvent.LiveHeartRate(140, null))
        assertTrue(published.isEmpty())
        relay.request(true)
        relay.onEvent(1, WatchEvent.LiveHeartRate(120, 50))
        assertEquals(LiveReading(1, 140, 50, null, null, 1_000, hrSourceId = 9), published.last())
        now += 1
        relay.onEvent(9, WatchEvent.LiveHeartRate(142, null))
        assertEquals(LiveReading(1, 142, 50, null, null, 1_001, hrSourceId = 9), published.last())
        // The strap goes quiet (disconnected): the watch's own value is back.
        now += LiveRelay.SENSOR_FRESH_SEC + 1
        relay.onEvent(1, WatchEvent.LiveHeartRate(118, null))
        assertEquals(LiveReading(1, 118, 50, null, null, now), published.last())
    }

    @Test
    fun readingSurvivesTheBroadcast() {
        val ctx = RuntimeEnvironment.getApplication()
        val reading = LiveReading(3, 70, null, 1_234, null, 1_700_000_000)
        val intent = LiveReading.toIntent(ctx, reading)
        assertEquals(ctx.packageName, intent.`package`)
        assertEquals(reading, LiveReading.fromIntent(intent))
        val fromStrap = reading.copy(hrSourceId = 9)
        assertEquals(fromStrap, LiveReading.fromIntent(LiveReading.toIntent(ctx, fromStrap)))
        assertNull(LiveReading.fromIntent(android.content.Intent("other")))
    }
}
