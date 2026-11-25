/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.debug

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import app.selfwatch.R
import app.selfwatch.SelfWatchApp
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.dao.WatchAppRow
import app.selfwatch.db.dao.WatchAppSettings
import app.selfwatch.devices.WatchAppSettingsFragment
import app.selfwatch.devices.WatchSettingsFragment
import app.selfwatch.me.MeTestSupport
import app.selfwatch.me.SettingsActivity
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.device.SupportTier
import app.selfwatch.service.device.WatchStatus
import app.selfwatch.shell.MainActivity
import app.selfwatch.shell.Tab
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.runBlocking
import android.os.Looper
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.util.UUID

/**
 * Opens the screens that carry developer tools in debug builds (Devices, Body, Watch settings, watch
 * app settings, Me › Settings) with a paired watch and a stored "on" for every debug setting, and
 * returns every visible text and toolbar menu title per screen. Shared by the debug and release
 * variant tests (`src/testDebug`, `src/testRelease`).
 */
object DebugUiProbe {
    /** Labels of the developer tools (exact texts). */
    val DEBUG_LABELS: List<String> = listOf(
        "Debug", "Bluetooth capture", "Share Bluetooth capture", "Share logs", "Advanced",
        "Show raw keys", "Load demo data", "Clear demo data",
    )

    /** Debug string resources that release builds must not contain. */
    val DEBUG_STRINGS: List<String> = listOf(
        "devices_debug", "devices_ble_capture", "devices_share_capture", "devices_share_logs", "settings_advanced",
        "settings_ble_capture", "menu_settings_raw", "demo_load",
    )

    private val appUuid: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")

    /** Not a screen: the titles of the switches that are on in Watch settings, as [screens] found them. */
    const val WATCH_SETTINGS_ON: String = "Watch settings (switched on)"

    /** Title of the per-watch Garmin sign-in switch on Watch settings (a normal setting in every build). */
    const val FAKE_AUTH_TITLE: String = "Answer the watch's Garmin sign-in requests itself"

    /** Screen name to its visible texts and menu titles. Call with a fresh app from [MeTestSupport.freshApp]. */
    fun screens(app: SelfWatchApp): Map<String, List<String>> {
        val id = runBlocking {
            val db = app.graph.db
            db.settings.setBool(SettingKeys.ONBOARDING_DONE, true)
            // Left on by a debug build: a release build must ignore it.
            db.settings.setBool(SettingKeys.DEBUG_BLE_CAPTURE, true)
            val id = db.devices.upsert(
                DeviceInfo(0, DeviceKind.GARMIN_WATCH, "AA:BB:CC:DD:EE:01", "vívoactive 5", "vívoactive 5", null, null, null, SupportTier.VERIFIED, emptySet(), null),
            )
            db.settings.set(WatchStatus.stateKey(id), "ready")
            db.watchApps.upsert(WatchAppRow(id, appUuid, "Calories field", 3, 4, true, 1))
            db.watchApps.saveSettings(WatchAppSettings(appUuid, 100, """{"goal":2000}""", null))
            id
        }
        val out = LinkedHashMap<String, List<String>>()
        val mainController = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val main = mainController.get()
            out["Body"] = wait(main, "Body") { true }
            main.select(Tab.DEVICES)
            out["Devices"] = wait(main, "Devices") { it.contains("vívoactive 5") && it.contains("Data so far") }
            open(main, WatchSettingsFragment())
            out["Watch settings"] = wait(main, "Watch settings") { it.contains("Find my watch") }
            out[WATCH_SETTINGS_ON] = switchedOn(main.window.decorView)
            open(main, WatchAppSettingsFragment.newInstance(id, appUuid, "Calories field"))
            out["Watch app settings"] = wait(main, "Watch app settings") { t -> t.any { it.contains("no settings file") } }
        } finally {
            close(mainController)
        }
        val settingsController = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            out["Settings"] = wait(settingsController.get(), "Settings") { it.contains("Always connected") }
        } finally {
            close(settingsController)
        }
        return out
    }

    /** Ends the activity so its views and collectors don't leak into the next test. */
    private fun close(c: ActivityController<out Activity>) {
        c.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun open(a: MainActivity, f: Fragment) {
        a.supportFragmentManager.beginTransaction().replace(R.id.detail_container, f).commitNow()
    }

    private fun wait(a: Activity, what: String, ready: (List<String>) -> Boolean): List<String> {
        MeTestSupport.waitFor(what, { labels(a).joinToString(" | ") }) { ready(labels(a)) }
        return labels(a)
    }

    private fun labels(a: Activity): List<String> = texts(a.window.decorView) + menus(a.window.decorView)

    private fun switchedOn(v: View): List<String> = when {
        v.visibility != View.VISIBLE -> emptyList()
        v is CompoundButton -> if (v.isChecked) listOf(v.text.toString()) else emptyList()
        v is ViewGroup -> (0 until v.childCount).flatMap { switchedOn(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun texts(v: View): List<String> = when {
        v.visibility != View.VISIBLE -> emptyList()
        v is TextView -> listOf(v.text.toString())
        v is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun menus(v: View): List<String> = when (v) {
        is MaterialToolbar -> (0 until v.menu.size()).map { v.menu.getItem(it) }.filter { it.isVisible }.map { it.title.toString() }
        is ViewGroup -> (0 until v.childCount).flatMap { menus(v.getChildAt(it)) }
        else -> emptyList()
    }
}
