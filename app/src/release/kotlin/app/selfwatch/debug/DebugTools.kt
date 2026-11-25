/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.debug

import android.app.Activity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar

/**
 * Release builds: no developer tools. Same functions as the debug `DebugTools` (src/debug); each adds
 * nothing and returns null, and [ENABLED] is false so the stored Bluetooth capture setting is ignored.
 */
@Suppress("UNUSED_PARAMETER")
object DebugTools {
    /** False in release builds. */
    const val ENABLED: Boolean = false

    /** Nothing in release builds. */
    fun addDevicesCard(fragment: Fragment, parent: ViewGroup, index: Int, onCapture: (Boolean) -> Unit): ((Boolean) -> Unit)? = null

    /** Nothing in release builds. */
    fun addCaptureSwitch(parent: LinearLayout, onCapture: (Boolean) -> Unit): ((Boolean) -> Unit)? = null

    /** Nothing in release builds. */
    fun addSettingsAdvanced(activity: Activity, column: LinearLayout, onCapture: (Boolean) -> Unit): ((capture: Boolean) -> Unit)? = null

    /** Nothing in release builds. */
    fun addRawKeysItem(toolbar: MaterialToolbar, onToggle: () -> Unit): ((Boolean) -> Unit)? = null
}
