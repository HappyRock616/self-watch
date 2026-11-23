/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch

import app.selfwatch.ui.SwTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessRoleTest {
    private val pkg = "app.selfwatch.debug"

    @Test
    fun mainProcessIsThePackageName() {
        assertEquals(ProcessRole.MAIN, ProcessRole.of(pkg, pkg))
        assertTrue(ProcessRole.of(pkg, pkg).doesAppSetup)
    }

    @Test
    fun watchProcessSkipsAppSetup() {
        val role = ProcessRole.of("$pkg:watch", pkg)
        assertEquals(ProcessRole.WATCH, role)
        assertFalse(role.doesAppSetup)
        assertEquals("watch", role.logName)
    }

    @Test
    fun unknownNameCountsAsMain() {
        assertEquals(ProcessRole.MAIN, ProcessRole.of(null, pkg))
        assertEquals(ProcessRole.MAIN, ProcessRole.of(" ", pkg))
    }

    @Test
    fun otherProcessesSkipAppSetup() {
        assertEquals(ProcessRole.OTHER, ProcessRole.of("$pkg:other", pkg))
        assertEquals(ProcessRole.OTHER, ProcessRole.of("app.selfwatch:watch", pkg)) // release app's process
        assertFalse(ProcessRole.OTHER.doesAppSetup)
    }

    @Test
    fun appearanceParsesSettings() {
        assertEquals(Appearance(), Appearance.parse(null, null))
        assertEquals(SwTheme.NightMode.DARK, Appearance.nightOf("dark"))
        assertEquals(SwTheme.NightMode.LIGHT, Appearance.nightOf("light"))
        assertEquals(SwTheme.NightMode.SYSTEM, Appearance.nightOf("SYSTEM"))
        assertEquals(SwTheme.NightMode.DARK, Appearance.nightOf("purple"))
        assertEquals(SwTheme.Theme.ORANGE, Appearance.themeOf("#FF8A1F"))
        assertEquals(SwTheme.Theme.TURQUOISE, Appearance.themeOf("#19d3c5"))
        assertEquals(SwTheme.Theme.ORANGE, Appearance.themeOf("#123456"))
    }
}
