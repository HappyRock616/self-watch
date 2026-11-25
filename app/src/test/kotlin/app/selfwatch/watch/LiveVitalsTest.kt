/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.selfwatch.watch

import app.selfwatch.service.live.LiveReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live readings are asked for only while a card holds them and a watch is connected (battery). */
class LiveVitalsTest {
    private class FakeRequests : LiveRequests {
        val calls = ArrayList<Boolean>()
        override fun request(enable: Boolean) {
            calls += enable
        }
    }

    @Test
    fun asksWhileHeldAndStopsWhenReleased() = runTest {
        val requests = FakeRequests()
        val connected = MutableStateFlow(true)
        val vitals = LiveVitals(backgroundScope, requests, connected, renewMs = 30_000)
        runCurrent()
        assertTrue("nothing held, nothing asked", requests.calls.isEmpty())

        val card = launch { vitals.hold() }
        runCurrent()
        assertEquals(listOf(true), requests.calls)
        assertTrue(vitals.asking.value)
        // Renewed every 30 s while held.
        advanceTimeBy(65_000)
        assertEquals(listOf(true, true, true), requests.calls)

        card.cancel()
        runCurrent()
        assertEquals(listOf(true, true, true, false), requests.calls)
        assertFalse(vitals.asking.value)
        advanceTimeBy(120_000)
        assertEquals("nothing more once released", 4, requests.calls.size)
    }

    @Test
    fun twoHoldersShareOneRequest() = runTest {
        val requests = FakeRequests()
        val vitals = LiveVitals(backgroundScope, requests, MutableStateFlow(true), renewMs = 30_000)
        val body = launch { vitals.hold() }
        val activity = launch { vitals.hold() }
        runCurrent()
        assertEquals(listOf(true), requests.calls)
        body.cancel()
        runCurrent()
        assertEquals("one holder left: still on", listOf(true), requests.calls)
        activity.cancel()
        runCurrent()
        assertEquals(listOf(true, false), requests.calls)
    }

    @Test
    fun followsTheWatchConnection() = runTest {
        val requests = FakeRequests()
        val connected = MutableStateFlow(false)
        val vitals = LiveVitals(backgroundScope, requests, connected, renewMs = 30_000)
        val card = launch { vitals.hold() }
        runCurrent()
        assertTrue("no watch connected: no request", requests.calls.isEmpty())
        connected.value = true
        runCurrent()
        assertEquals(listOf(true), requests.calls)
        connected.value = false
        runCurrent()
        assertEquals(listOf(true, false), requests.calls)
        connected.value = true
        runCurrent()
        assertEquals(listOf(true, false, true), requests.calls)
        card.cancel()
        runCurrent()
        assertEquals(listOf(true, false, true, false), requests.calls)
    }

    @Test
    fun keepsTheNewestReading() = runTest {
        val vitals = LiveVitals(backgroundScope, FakeRequests(), MutableStateFlow(false))
        val r = LiveReading(1, 64, 52, 4_000, 10_000, 1_700_000_000)
        vitals.onReading(r)
        assertEquals(r, vitals.reading.value)
    }
}
