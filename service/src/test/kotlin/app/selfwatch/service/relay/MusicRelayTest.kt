/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.relay

import android.media.session.PlaybackState
import app.selfwatch.model.watch.MusicState
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** B21: media-session mapping and the 1-per-second throttle. */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MusicRelayTest {

    private fun s(title: String, playing: Boolean = true) = MusicState(playing, "A", null, title, null, null, null)

    @Test
    fun mapping() {
        val m = MusicMapping.state(PlaybackState.STATE_PLAYING, "Artist", " ", "Song", 180_000, 5_000, 7, 15)
        assertTrue(m.playing)
        assertEquals("Artist", m.artist)
        assertNull("blank album is unknown", m.album)
        assertEquals("Song", m.title)
        assertEquals(180_000L, m.durationMs)
        assertEquals(5_000L, m.positionMs)
        assertEquals(46, m.volumePct)
        val paused = MusicMapping.state(PlaybackState.STATE_PAUSED, null, null, null, 0, -1, null, 0)
        assertFalse(paused.playing)
        assertNull(paused.durationMs)
        assertNull(paused.positionMs)
        assertNull(paused.volumePct)
        assertTrue(MusicMapping.isPlaying(PlaybackState.STATE_BUFFERING))
        assertFalse(MusicMapping.isPlaying(null))
    }

    @Test
    fun throttleSendsAtMostOncePerSecondWithTheLatestState() = runTest {
        val sent = ArrayList<MusicState>()
        val t = MusicThrottle(backgroundScope, { testScheduler.currentTime }, { sent.add(it) })
        t.offer(s("1"))
        runCurrent()
        assertEquals(listOf(s("1")), sent)
        t.offer(s("2"))
        t.offer(s("3"))
        advanceTimeBy(500)
        runCurrent()
        assertEquals("nothing more within the second", 1, sent.size)
        advanceTimeBy(600)
        runCurrent()
        assertEquals(listOf(s("1"), s("3")), sent)
        // The same state again is not sent.
        advanceTimeBy(2_000)
        t.offer(s("3"))
        runCurrent()
        assertEquals(2, sent.size)
    }
}
