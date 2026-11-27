/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.shell

import kotlinx.coroutines.delay

/**
 * Keeps the data fresh while the user is looking at the app, so they never have to tap Sync now.
 *
 * - When the app comes on screen: sync if the last sync (or our last request) is older than
 *   [OPEN_STALE_SEC] (5 min).
 * - While it stays on screen: check every [CHECK_SEC] and sync when the last sync is older than
 *   [ON_SCREEN_STALE_SEC] (15 min).
 * - Off screen nothing extra runs: the watch still pushes its own syncs and the scheduled sync runs.
 *
 * Gadgetbridge does the same on screen unlock, with a minimum gap between fetches so they cannot
 * pile up (references/Gadgetbridge 160beab41 service/receivers/GBAutoFetchReceiver.java, issue #4165).
 * A sync is one directory read plus any new files: no high-speed connection is requested.
 */
class OpenAppSync(
    /** Seconds since the epoch. */
    private val nowSec: () -> Long,
    /** The newest `last_sync_sec` of the active watches, or null when none has synced or none is paired. */
    private val lastSyncSec: suspend () -> Long?,
    /** True when a watch is paired (no watch: nothing to sync). */
    private val hasWatch: suspend () -> Boolean,
    /** Asks the service for a sync; false when it could not be started. */
    private val requestSync: () -> Boolean,
) {
    private var lastRequestSec: Long = 0

    /** Runs until cancelled (call from `repeatOnLifecycle(STARTED)`). */
    suspend fun runWhileOnScreen() {
        syncIfOlderThan(OPEN_STALE_SEC)
        while (true) {
            delay(CHECK_SEC * 1000)
            syncIfOlderThan(ON_SCREEN_STALE_SEC)
        }
    }

    /** Requests a sync when both the last sync and our last request are older than [staleSec]. Returns true if requested. */
    suspend fun syncIfOlderThan(staleSec: Long): Boolean {
        if (!hasWatch()) return false
        val now = nowSec()
        val newest = maxOf(lastSyncSec() ?: 0, lastRequestSec)
        if (now - newest < staleSec) return false
        lastRequestSec = now
        return requestSync()
    }

    /** Timing. */
    companion object {
        /** On opening the app: sync when the data is older than this. */
        const val OPEN_STALE_SEC: Long = 5 * 60

        /** While the app stays on screen: sync when the data is older than this. */
        const val ON_SCREEN_STALE_SEC: Long = 15 * 60

        /** How often the on-screen check runs. */
        const val CHECK_SEC: Long = 60
    }
}
