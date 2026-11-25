/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.viewModels
import app.selfwatch.R
import app.selfwatch.cards.CardStack
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.EditCardsFragment
import app.selfwatch.debug.DebugTools
import app.selfwatch.graph
import app.selfwatch.shell.DemoLink
import app.selfwatch.shell.MainActivity
import app.selfwatch.shell.Nav
import app.selfwatch.shell.Tab
import app.selfwatch.shell.TabFragment
import app.selfwatch.ui.format.Fmt
import com.google.android.material.chip.Chip
import java.time.ZoneId

/** Body (home): watch chip, then the cards the user picked (see `BodyCards`) in their order; every card opens its detail. */
class BodyFragment : TabFragment(R.layout.fragment_body, R.menu.menu_body) {

    private val vm: BodyViewModel by viewModels { BodyViewModel.factory(requireContext().graph) }

    /** The card stack (tests). */
    var cards: CardStack? = null
        private set

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (DebugTools.ENABLED) toolbar.inflateMenu(R.menu.menu_body_debug)
        val chip = view.findViewById<Chip>(R.id.watch_chip)
        chip.setOnClickListener { (activity as? MainActivity)?.select(Tab.DEVICES) }
        val stack = CardStack(this, CardTab.BODY, view.findViewById<ViewGroup>(R.id.body_cards), requireContext().graph.db)
        cards = stack
        stack.start()
        collectWhileStarted(vm.state) { s ->
            toolbar.subtitle = DateUtils.formatDateTime(
                requireContext(),
                s.today.startSec(ZoneId.systemDefault()) * 1000L,
                DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_ABBREV_ALL,
            )
            bindWatch(chip, s.watch)
        }
    }

    private fun bindWatch(chip: Chip, w: WatchChip?) {
        if (w == null) {
            chip.text = getString(R.string.body_no_watch)
            chip.contentDescription = getString(R.string.body_no_watch_description)
            return
        }
        val parts = ArrayList<String>()
        parts += getString(R.string.body_watch)
        w.batteryPct?.let { parts += Fmt.percent(it / 100.0) }
        parts += when {
            w.lastSyncSec != null -> getString(
                R.string.body_synced,
                DateUtils.getRelativeTimeSpanString(w.lastSyncSec * 1000L, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            )
            w.connected -> getString(R.string.body_connected)
            else -> getString(R.string.body_not_synced)
        }
        chip.text = parts.joinToString(" · ")
        chip.contentDescription = getString(R.string.body_watch_description, w.name, chip.text)
    }

    override fun onMenuItem(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_sync_now -> {
            if (!requireContext().graph.service.syncNow()) {
                Toast.makeText(requireContext(), R.string.sync_unavailable, Toast.LENGTH_SHORT).show()
            }
            true
        }
        R.id.menu_edit_cards -> {
            Nav.open(this, EditCardsFragment.newInstance(CardTab.BODY))
            true
        }
        R.id.demo_load -> DebugTools.ENABLED && runDemo(DemoLink.ACTION_LOAD)
        R.id.demo_clear -> DebugTools.ENABLED && runDemo(DemoLink.ACTION_CLEAR)
        else -> false
    }

    private fun runDemo(action: String): Boolean {
        startActivity(DemoLink.intent(requireContext(), action).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        return true
    }
}
