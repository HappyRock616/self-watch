/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.me

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.fragment.app.viewModels
import app.selfwatch.R
import app.selfwatch.data.DataActivity
import app.selfwatch.graph
import app.selfwatch.shell.Nav
import app.selfwatch.shell.TabFragment
import app.selfwatch.ui.SwTheme
import app.selfwatch.ui.components.ListRow
import app.selfwatch.ui.components.SwCard

/** Me: privacy summary card (requests today), then goals, profile, privacy, data, settings and about. */
class MeFragment : TabFragment(R.layout.fragment_me, R.menu.menu_me) {

    private val vm: MeViewModel by viewModels { MeViewModel.factory(requireContext().graph) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val privacy = view.findViewById<SwCard>(R.id.privacy_card)
        privacy.setOnClickListener { open(PrivacyActivity::class.java) }
        val requests = view.findViewById<TextView>(R.id.privacy_requests)

        fun row(id: Int, onClick: () -> Unit) {
            view.findViewById<ListRow>(id).onClick = onClick
        }
        row(R.id.row_goals) { Nav.open(this, GoalsFragment()) }
        row(R.id.row_profile) { Nav.open(this, ProfileFragment()) }
        row(R.id.row_network) { open(NetworkLogActivity::class.java) }
        row(R.id.row_permissions) { open(PrivacyActivity::class.java) }
        row(R.id.row_data) { open(DataActivity::class.java) }
        row(R.id.row_health_connect) { open(DataActivity::class.java) }
        row(R.id.row_food_data) { open(FoodDataActivity::class.java) }
        row(R.id.row_appearance) { open(SettingsActivity::class.java) }
        row(R.id.row_settings) { open(SettingsActivity::class.java) }
        row(R.id.row_about) { open(AboutActivity::class.java) }

        val appearanceRow = view.findViewById<ListRow>(R.id.row_appearance)
        collectWhileStarted(vm.appearance) { a ->
            appearanceRow.trailing = getString(R.string.appearance_summary, getString(nightLabel(a.night)), getString(themeLabel(a.theme)))
        }
        collectWhileStarted(vm.requests) { r ->
            requests.text = requestsText(r)
            privacy.contentDescription = getString(R.string.me_privacy_center) + ". " + requests.text + ". " + getString(R.string.me_privacy_summary)
        }
    }

    override fun onMenuItem(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_help -> {
            Nav.open(this, HelpFragment())
            true
        }
        R.id.menu_about -> {
            open(AboutActivity::class.java)
            true
        }
        else -> false
    }

    private fun requestsText(r: RequestsToday): String = PrivacyActivity.requestsText(requireContext(), r)

    private fun open(cls: Class<out Activity>) {
        startActivity(Intent(requireContext(), cls))
    }

    /** Labels. */
    companion object {
        /** The label for [mode]. */
        @StringRes
        fun nightLabel(mode: SwTheme.NightMode): Int = when (mode) {
            SwTheme.NightMode.DARK -> R.string.appearance_dark
            SwTheme.NightMode.LIGHT -> R.string.appearance_light
            SwTheme.NightMode.SYSTEM -> R.string.appearance_system
        }

        /** The name of color [theme]. */
        @StringRes
        fun themeLabel(theme: SwTheme.Theme): Int = when (theme) {
            SwTheme.Theme.TURQUOISE -> R.string.theme_turquoise
            SwTheme.Theme.PURPLE -> R.string.theme_purple
            SwTheme.Theme.ORANGE -> R.string.theme_orange
            SwTheme.Theme.YELLOW -> R.string.theme_yellow
            SwTheme.Theme.FOREST -> R.string.theme_forest
        }
    }
}
