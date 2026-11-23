/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.me

import android.os.Bundle
import androidx.activity.viewModels
import app.selfwatch.R
import app.selfwatch.graph
import app.selfwatch.ui.SwTheme
import app.selfwatch.ui.components.ThemeSwatch
import app.selfwatch.watch.WatchSettings
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Settings (C20): connection mode and sync hours, food tracking, units, light/dark and color theme, then the
 * advanced options: Bluetooth capture (debug builds) and the fake Garmin sign-in (off by default;
 * turning it on shows the plain COMPATIBILITY R1 warning first).
 */
class SettingsActivity : MeScreenActivity() {

    private val vm: SettingsViewModel by viewModels { SettingsViewModel.factory(graph, WatchSettings.isDebuggable(applicationContext)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen(R.string.me_settings)

        val conn = MeViews.card(column, getString(R.string.settings_connection))
        val mode = MeViews.row(conn.content, getString(R.string.settings_connection_mode)) {
            val labels = listOf(getString(R.string.settings_always), getString(R.string.settings_sync_only))
            MeViews.choose(this, getString(R.string.settings_connection_mode), labels, if (vm.state.value.alwaysConnected) 0 else 1) {
                vm.setAlwaysConnected(it == 0)
            }
        }
        val hours = MeViews.row(conn.content, getString(R.string.settings_sync_every)) {
            val labels = SettingsViewModel.HOURS.map { h -> resources.getQuantityString(R.plurals.settings_hours, h, h) }
            MeViews.choose(this, getString(R.string.settings_sync_every), labels, SettingsViewModel.HOURS.indexOf(vm.state.value.syncHours)) {
                vm.setSyncHours(SettingsViewModel.HOURS[it])
            }
        }
        val modeHelp = MeViews.caption(this, null)
        MeViews.add(conn.content, modeHelp)

        val food = MeViews.card(column, getString(R.string.settings_food))
        val foodSwitch = SwitchRow(this, getString(R.string.settings_food_tracking), getString(R.string.settings_food_tracking_help))
        foodSwitch.onChange = { vm.setFoodEnabled(it) }
        MeViews.add(food.content, foodSwitch, gap = false)

        val units = MeViews.card(column, getString(R.string.settings_units))
        val unitsRow = MeViews.row(units.content, getString(R.string.settings_units)) {
            MeViews.choose(this, getString(R.string.settings_units), SettingsViewModel.UNITS.map { unitsLabel(it) }, SettingsViewModel.UNITS.indexOf(vm.state.value.units)) {
                vm.setUnits(SettingsViewModel.UNITS[it])
            }
        }

        val look = MeViews.card(column, getString(R.string.me_appearance))
        val theme = MeViews.row(look.content, getString(R.string.settings_theme)) {
            val modes = SwTheme.NightMode.entries
            MeViews.choose(this, getString(R.string.settings_theme), modes.map { getString(MeFragment.nightLabel(it)) }, modes.indexOf(vm.state.value.appearance.night)) {
                vm.setTheme(modes[it])
            }
        }
        val colorRow = MeViews.row(look.content, getString(R.string.settings_color_theme)) {
            val themes = SwTheme.Theme.entries
            val labels = themes.map { ThemeSwatch.label(this, it, getString(MeFragment.themeLabel(it))) }
            MeViews.choose(this, getString(R.string.settings_color_theme), labels, themes.indexOf(vm.state.value.appearance.theme)) {
                vm.setColorTheme(themes[it])
            }
        }

        val adv = MeViews.card(column, getString(R.string.settings_advanced))
        val capture = SwitchRow(this, getString(R.string.settings_ble_capture), getString(R.string.settings_ble_capture_help))
        capture.onChange = { vm.setBleCapture(it) }
        MeViews.add(adv.content, capture, gap = false)
        val fake = SwitchRow(this, getString(R.string.settings_fake_auth), getString(R.string.settings_fake_auth_help))
        fake.onChange = { on ->
            if (on) {
                fake.checked = false
                confirmFakeAuth()
            } else {
                vm.setFakeAuth(false)
            }
        }
        MeViews.add(adv.content, fake)

        collect(vm.state) { s ->
            mode.trailing = getString(if (s.alwaysConnected) R.string.settings_always else R.string.settings_sync_only)
            hours.trailing = resources.getQuantityString(R.plurals.settings_hours, s.syncHours, s.syncHours)
            hours.visibility = if (s.alwaysConnected) android.view.View.GONE else android.view.View.VISIBLE
            modeHelp.text = getString(if (s.alwaysConnected) R.string.settings_always_help else R.string.settings_sync_only_help)
            foodSwitch.checked = s.foodEnabled
            unitsRow.trailing = unitsLabel(s.units)
            theme.trailing = getString(MeFragment.nightLabel(s.appearance.night))
            colorRow.trailing = getString(MeFragment.themeLabel(s.appearance.theme))
            capture.visibility = if (s.debuggable) android.view.View.VISIBLE else android.view.View.GONE
            capture.checked = s.bleCapture
            fake.checked = s.fakeAuth
        }
    }

    private fun confirmFakeAuth() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_fake_auth)
            .setMessage(R.string.settings_fake_auth_warning)
            .setPositiveButton(R.string.settings_fake_auth_accept) { _, _ -> vm.setFakeAuth(true) }
            .setNegativeButton(R.string.settings_fake_auth_keep_off, null)
            .show()
    }

    private fun unitsLabel(value: String): String = getString(
        when (value) {
            SettingsViewModel.UNITS_METRIC -> R.string.settings_units_metric
            SettingsViewModel.UNITS_IMPERIAL -> R.string.settings_units_imperial
            else -> R.string.settings_units_phone
        },
    )
}
