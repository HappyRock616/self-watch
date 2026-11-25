/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.debug

import app.selfwatch.SelfWatchApp
import app.selfwatch.me.MeTestSupport
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Debug variant: the developer tools are where testers expect them (the release test checks they are gone). */
@RunWith(RobolectricTestRunner::class)
class DebugToolsShownTest {
    private lateinit var app: SelfWatchApp

    @Before
    fun setUp() {
        app = MeTestSupport.freshApp()
    }

    @After
    fun tearDown() = MeTestSupport.resetDb()

    @Test
    fun debugBuildsShowTheTools() {
        assertTrue(DebugTools.ENABLED)
        val s = DebugUiProbe.screens(app)
        assertTrue(s.getValue("Body").containsAll(listOf("Load demo data", "Clear demo data")))
        assertTrue(s.getValue("Devices").containsAll(listOf("Debug", "Bluetooth capture", "Share Bluetooth capture", "Share logs")))
        assertTrue(s.getValue("Watch settings").contains("Bluetooth capture"))
        assertTrue(s.getValue("Watch app settings").contains("Show raw keys"))
        assertTrue(s.getValue("Settings").containsAll(listOf("Advanced", "Bluetooth capture")))
        // The Garmin sign-in answer is a normal per-watch setting, off by default.
        assertTrue(s.getValue("Watch settings").contains(DebugUiProbe.FAKE_AUTH_TITLE))
        assertFalse(s.getValue(DebugUiProbe.WATCH_SETTINGS_ON).contains(DebugUiProbe.FAKE_AUTH_TITLE))
    }
}
