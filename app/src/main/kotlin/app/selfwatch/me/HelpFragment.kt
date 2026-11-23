/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.me

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.StringRes
import app.selfwatch.R
import app.selfwatch.food.FoodViews
import app.selfwatch.model.log.SwLog
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.Nav
import app.selfwatch.shell.Views

/** One help section: a title and a short body, in reading order. */
enum class HelpTopic(@param:StringRes val title: Int, @param:StringRes val body: Int) {
    START(R.string.help_start_title, R.string.help_start_body),
    PAIRING(R.string.help_pair_title, R.string.help_pair_body),
    SYNC(R.string.help_sync_title, R.string.help_sync_body),
    CARDS(R.string.help_cards_title, R.string.help_cards_body),
    FOOD(R.string.help_food_title, R.string.help_food_body),
    DATA(R.string.help_data_title, R.string.help_data_body),
    PRIVACY(R.string.help_privacy_title, R.string.help_privacy_body),
    TROUBLESHOOTING(R.string.help_trouble_title, R.string.help_trouble_body),
    REPORT(R.string.help_report_title, R.string.help_report_body),
}

/**
 * Help (Me ⋮ Help): every [HelpTopic] as a short card, written into the app so it works offline.
 * Bluetooth help (Devices ⋮ Bluetooth help, [bluetooth]) shows only pairing and troubleshooting,
 * with quick fixes on top: Android's Bluetooth settings, the app's battery settings and, when opened
 * from Devices, "Pair again" (sends [REQUEST_PAIR] to the fragment manager and closes).
 */
class HelpFragment : DetailFragment() {

    private val focused: Boolean get() = arguments?.getBoolean(ARG_BLUETOOTH) ?: false

    override fun title(): CharSequence = getString(if (focused) R.string.menu_bluetooth_help else R.string.help_title)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        if (focused) {
            val fixes = Views.card(column, getString(R.string.help_actions), first = true)
            MeViews.add(fixes.content, MeViews.body(ctx, getString(R.string.help_bluetooth_intro)), gap = false)
            addSettingsButtons(fixes.content)
            if (arguments?.getBoolean(ARG_CAN_PAIR) == true) {
                MeViews.add(
                    fixes.content,
                    FoodViews.filledButton(ctx, getString(R.string.help_pair_again)) {
                        parentFragmentManager.setFragmentResult(REQUEST_PAIR, Bundle())
                        close()
                    },
                )
            }
            for (t in BLUETOOTH_TOPICS) topicCard(column, t)
            val more = Views.card(column, null)
            MeViews.row(more.content, getString(R.string.help_all_topics)) { Nav.open(this, HelpFragment()) }
        } else {
            column.addView(Views.caption(ctx, getString(R.string.help_intro)), Views.matchWrap(ctx))
            for (t in HelpTopic.entries) {
                val card = topicCard(column, t)
                if (t == HelpTopic.TROUBLESHOOTING) addSettingsButtons(card)
            }
        }
    }

    private fun topicCard(column: LinearLayout, t: HelpTopic): ViewGroup {
        val card = Views.card(column, getString(t.title))
        card.tag = t.name
        MeViews.add(card.content, MeViews.body(requireContext(), getString(t.body)), gap = false)
        return card.content
    }

    private fun addSettingsButtons(parent: ViewGroup) {
        val ctx = requireContext()
        MeViews.add(parent, FoodViews.outlinedButton(ctx, getString(R.string.help_open_bluetooth)) { HelpActions.start(ctx, HelpActions.bluetoothSettings()) })
        MeViews.add(parent, FoodViews.outlinedButton(ctx, getString(R.string.help_open_battery)) { HelpActions.start(ctx, HelpActions.batterySettings(ctx)) })
    }

    /** Factories and the pairing request key. */
    companion object {
        /** Fragment result sent by "Pair again"; Devices starts pairing when it gets it. */
        const val REQUEST_PAIR: String = "help.pair"

        private const val ARG_BLUETOOTH = "bluetooth"
        private const val ARG_CAN_PAIR = "can_pair"

        /** The topics Bluetooth help shows. */
        val BLUETOOTH_TOPICS: List<HelpTopic> = listOf(HelpTopic.TROUBLESHOOTING, HelpTopic.PAIRING, HelpTopic.SYNC)

        /** Bluetooth help; [canPair] adds "Pair again" (only when a listener for [REQUEST_PAIR] is set). */
        fun bluetooth(canPair: Boolean = true): HelpFragment = HelpFragment().apply {
            arguments = Bundle().apply {
                putBoolean(ARG_BLUETOOTH, true)
                putBoolean(ARG_CAN_PAIR, canPair)
            }
        }
    }
}

/** System settings screens the help can open. Each list is tried in order until one exists. */
object HelpActions {
    private const val TAG = "HelpActions"

    /** Android's Bluetooth settings. */
    fun bluetoothSettings(): List<Intent> = listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), Intent(Settings.ACTION_SETTINGS))

    /**
     * Battery settings for this app: its App info page (Battery is one tap away there on every
     * version), then the system list of battery optimisations.
     */
    fun batterySettings(ctx: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    /** Starts the first of [intents] that exists; says so if none does. */
    fun start(ctx: Context, intents: List<Intent>): Boolean {
        for (i in intents) {
            try {
                ctx.startActivity(i)
                return true
            } catch (e: ActivityNotFoundException) {
                SwLog.w(TAG, "no activity for ${i.action}", e)
            }
        }
        Toast.makeText(ctx, R.string.help_no_settings, Toast.LENGTH_SHORT).show()
        return false
    }
}
