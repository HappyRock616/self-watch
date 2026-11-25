/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.debug

import app.selfwatch.SelfWatchApp
import app.selfwatch.db.SettingKeys
import app.selfwatch.me.MeTestSupport
import app.selfwatch.watch.GarminDriverFactory
import app.selfwatch.watch.WatchSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Release variant: no developer tools on any screen, no debug strings, debug settings forced off. */
@RunWith(RobolectricTestRunner::class)
class ReleaseHasNoDebugToolsTest {
    private lateinit var app: SelfWatchApp

    @Before
    fun setUp() {
        app = MeTestSupport.freshApp()
    }

    @After
    fun tearDown() = MeTestSupport.resetDb()

    @Test
    fun theGateIsOff() {
        assertFalse(DebugTools.ENABLED)
    }

    @Test
    fun noScreenShowsADebugTool() {
        val screens = DebugUiProbe.screens(app)
        assertEquals(
            setOf("Body", "Devices", "Watch settings", DebugUiProbe.WATCH_SETTINGS_ON, "Watch app settings", "Settings"),
            screens.keys,
        )
        for ((screen, labels) in screens) {
            val found = labels.filter { it in DebugUiProbe.DEBUG_LABELS }
            assertTrue("$screen shows debug tools: $found", found.isEmpty())
        }
    }

    @Test
    fun garminSignInAnswerIsANormalWatchSettingOffByDefault() {
        val screens = DebugUiProbe.screens(app)
        assertTrue(screens.getValue("Watch settings").contains(DebugUiProbe.FAKE_AUTH_TITLE))
        assertFalse(screens.getValue(DebugUiProbe.WATCH_SETTINGS_ON).contains(DebugUiProbe.FAKE_AUTH_TITLE))
        assertFalse("not on Me › Settings", screens.getValue("Settings").contains(DebugUiProbe.FAKE_AUTH_TITLE))
        runBlocking {
            val settings = app.graph.settings
            val id = app.graph.db.devices.active().first().id
            val scope = CoroutineScope(SupervisorJob())
            try {
                val f = GarminDriverFactory(settings, scope, { null })
                assertFalse(f.configFor("garmin.first_connect.X", id).fakeAuth)
                // Turned on for the watch, it works in release builds too.
                settings.setBool(SettingKeys.deviceFakeAuth(id), true)
                assertTrue(f.configFor("garmin.first_connect.X", id).fakeAuth)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun debugStringsAreNotShipped() {
        val res = app.resources
        for (name in DebugUiProbe.DEBUG_STRINGS) {
            assertEquals("string $name is in the release build", 0, res.getIdentifier(name, "string", app.packageName))
        }
        assertEquals(0, res.getIdentifier("devices_debug_card", "layout", app.packageName))
    }

    @Test
    fun storedDebugSettingsAreIgnored() = runBlocking {
        val settings = app.graph.settings
        settings.setBool(WatchSettings.BLE_CAPTURE, true)
        assertFalse(app.graph.devicesSource.bleCapture.first())
    }
}
