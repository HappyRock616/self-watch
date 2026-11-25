/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.debug

import android.app.Activity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import app.selfwatch.R
import app.selfwatch.SelfWatchApp
import app.selfwatch.ble.log.BleLog
import app.selfwatch.devices.DevicesUi
import app.selfwatch.devices.FileShare
import app.selfwatch.log.LogSinks
import app.selfwatch.me.MeViews
import app.selfwatch.me.SwitchRow
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File

/**
 * Developer tools, debug builds only. `src/release` has a stub with the same functions that adds
 * nothing, and [ENABLED] is a compile-time constant, so release builds hold neither this code nor its
 * strings and layouts (`src/debug/res`). Each `add…` function returns an updater for the state it
 * shows, or null when nothing was added.
 */
object DebugTools {
    /** True in debug builds. The stored Bluetooth capture setting counts only when true. */
    const val ENABLED: Boolean = true

    /** Devices page "Debug" card (Bluetooth capture, share capture, share logs), added to [parent] at [index]. */
    fun addDevicesCard(fragment: Fragment, parent: ViewGroup, index: Int, onCapture: (Boolean) -> Unit): ((Boolean) -> Unit)? {
        val card = LayoutInflater.from(parent.context).inflate(R.layout.devices_debug_card, parent, false)
        parent.addView(card, index)
        val capture: MaterialSwitch = card.findViewById(R.id.debug_capture)
        capture.setOnCheckedChangeListener { button, checked -> if (button.isPressed) onCapture(checked) }
        card.findViewById<MaterialButton>(R.id.debug_share_capture).setOnClickListener {
            share(fragment, BleLog.forContext(fragment.requireContext()).dir, "application/octet-stream", "Self Watch Bluetooth capture")
        }
        card.findViewById<MaterialButton>(R.id.debug_share_logs).setOnClickListener {
            (fragment.requireContext().applicationContext as? SelfWatchApp)?.logFile?.flush()
            share(fragment, LogSinks.dir(fragment.requireContext()), "text/plain", "Self Watch logs")
        }
        return { on -> if (capture.isChecked != on) capture.isChecked = on }
    }

    /** Watch settings "Bluetooth capture" switch, added to [parent]. */
    fun addCaptureSwitch(parent: LinearLayout, onCapture: (Boolean) -> Unit): ((Boolean) -> Unit)? {
        val capture = DevicesUi.switch(parent, parent.context.getString(R.string.devices_ble_capture))
        var updating = false
        capture.setOnCheckedChangeListener { _, on -> if (!updating) onCapture(on) }
        return { on ->
            updating = true
            capture.isChecked = on
            updating = false
        }
    }

    /** Me › Settings "Advanced" card: Bluetooth capture. */
    fun addSettingsAdvanced(activity: Activity, column: LinearLayout, onCapture: (Boolean) -> Unit): ((capture: Boolean) -> Unit)? {
        val adv = MeViews.card(column, activity.getString(R.string.settings_advanced))
        val capture = SwitchRow(activity, activity.getString(R.string.settings_ble_capture), activity.getString(R.string.settings_ble_capture_help))
        capture.onChange = onCapture
        MeViews.add(adv.content, capture, gap = false)
        return { c -> capture.checked = c }
    }

    /** Watch app settings: the checkable "Show raw keys" menu item. */
    fun addRawKeysItem(toolbar: MaterialToolbar, onToggle: () -> Unit): ((Boolean) -> Unit)? {
        val item = toolbar.menu.add(R.string.menu_settings_raw).setCheckable(true)
        item.setOnMenuItemClickListener {
            onToggle()
            true
        }
        return { on -> item.isChecked = on }
    }

    private fun share(fragment: Fragment, dir: File, mime: String, subject: String) {
        val ctx = fragment.requireContext()
        val intent = FileShare.intent(ctx, FileShare.filesIn(dir), mime, subject, ctx.getString(R.string.devices_share_title))
        if (intent == null || !FileShare.start(ctx, intent)) DevicesUi.toast(ctx, ctx.getString(R.string.devices_share_none))
    }
}
