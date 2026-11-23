/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.components

import com.google.android.material.snackbar.Snackbar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwSnackbarTest {
    @Test
    fun undoStaysEightSecondsByDefault() {
        assertEquals(8_000, SwSnackbar.actionDuration(sdk = 35, touchExploration = false) { it })
        assertEquals(8_000, SwSnackbar.actionDuration(sdk = 26, touchExploration = false) { it })
        // Much longer than Material's LENGTH_LONG (2.75 s), which undo used before.
        assertTrue(SwSnackbar.ACTION_DURATION_MS >= 6_000)
    }

    @Test
    fun followsTheSystemRecommendedTimeoutOnAndroid10AndLater() {
        // The user set "Time to take action" to 2 minutes.
        assertEquals(120_000, SwSnackbar.actionDuration(sdk = 29, touchExploration = true) { 120_000 })
        // A recommendation shorter than ours never shortens it.
        assertEquals(8_000, SwSnackbar.actionDuration(sdk = 34, touchExploration = false) { 3_000 })
    }

    @Test
    fun staysUntilDismissedWithTalkBackBeforeAndroid10() {
        assertEquals(Snackbar.LENGTH_INDEFINITE, SwSnackbar.actionDuration(sdk = 28, touchExploration = true) { it })
    }
}
