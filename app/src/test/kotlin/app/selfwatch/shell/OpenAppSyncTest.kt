/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.shell

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAppSyncTest {
    private var now = 1_000_000L
    private var lastSync: Long? = null
    private var paired = true
    private var requests = 0

    private val sync = OpenAppSync(
        nowSec = { now },
        lastSyncSec = { lastSync },
        hasWatch = { paired },
        requestSync = { requests++; true },
    )

    @Test
    fun opensWithStaleDataSyncs() = runTest {
        lastSync = now - 6 * 60
        assertTrue(sync.syncIfOlderThan(OpenAppSync.OPEN_STALE_SEC))
        assertEquals(1, requests)
    }

    @Test
    fun freshDataOrARecentRequestDoesNotSyncAgain() = runTest {
        lastSync = now - 60
        assertFalse(sync.syncIfOlderThan(OpenAppSync.OPEN_STALE_SEC))
        lastSync = null
        assertTrue(sync.syncIfOlderThan(OpenAppSync.OPEN_STALE_SEC))
        now += 120 // reopened two minutes later, the sync has not finished yet
        assertFalse(sync.syncIfOlderThan(OpenAppSync.OPEN_STALE_SEC))
        assertEquals(1, requests)
    }

    @Test
    fun onScreenSyncsAtMostEvery15Minutes() = runTest {
        lastSync = now
        now += 14 * 60
        assertFalse(sync.syncIfOlderThan(OpenAppSync.ON_SCREEN_STALE_SEC))
        now += 60
        assertTrue(sync.syncIfOlderThan(OpenAppSync.ON_SCREEN_STALE_SEC))
    }

    @Test
    fun noWatchNoSync() = runTest {
        paired = false
        assertFalse(sync.syncIfOlderThan(OpenAppSync.OPEN_STALE_SEC))
        assertEquals(0, requests)
    }
}
