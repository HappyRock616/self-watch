/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import app.selfwatch.R
import app.selfwatch.activity.cards.TrendsCard
import app.selfwatch.cards.CardStack
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.EditCardsFragment
import app.selfwatch.graph
import app.selfwatch.me.GoalsFragment
import app.selfwatch.model.time.SystemClock
import app.selfwatch.shell.Nav
import app.selfwatch.shell.TabFragment
import app.selfwatch.ui.components.ListRow
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Activity (C12): the step-goal streak in the toolbar, then the cards the user picked (see
 * `ActivityCards`): today's rings, the long trends and the recent workouts by default.
 */
class ActivityFragment : TabFragment(R.layout.fragment_activity, R.menu.menu_activity) {

    private val vm: ActivityViewModel by viewModels { ActivityViewModel.factory(requireContext().graph) }

    /** The card stack (tests). */
    var cards: CardStack? = null
        private set

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val stack = CardStack(this, CardTab.ACTIVITY, view.findViewById<ViewGroup>(R.id.activity_cards), requireContext().graph.db)
        cards = stack
        stack.start()
        val streak = view.findViewById<Chip>(R.id.streak_chip)
        collectWhileStarted(vm.state) { s ->
            streak.visibility = if (s.streak > 0) View.VISIBLE else View.GONE
            streak.text = resources.getQuantityString(R.plurals.activity_streak, s.streak, s.streak)
        }
    }

    override fun onMenuItem(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_activity_goals -> {
            Nav.open(this, GoalsFragment())
            true
        }
        R.id.menu_edit_cards -> {
            Nav.open(this, EditCardsFragment.newInstance(CardTab.ACTIVITY))
            true
        }
        R.id.menu_range -> {
            pickRange()
            true
        }
        R.id.menu_export_activities -> {
            Nav.open(this, ActivityExportFragment())
            true
        }
        else -> false
    }

    private fun pickRange() {
        val ctx = requireContext()
        val names = arrayOf(getString(R.string.range_week), getString(R.string.range_month), getString(R.string.range_year), getString(R.string.range_all))
        viewLifecycleOwner.lifecycleScope.launch {
            val current = ActivityLoader(ctx.graph.db, SystemClock).trendRange()
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.menu_range)
                .setSingleChoiceItems(names, current.ordinal) { d, which ->
                    cards?.let { TrendsCard.setRange(it, TrendRange.entries[which]) }
                    d.dismiss()
                }
                .show()
        }
    }
}

/** Workout list rows shared by Activity and the workout list. */
object WorkoutRows {
    /** A row for [w] that opens its detail from [host]. */
    fun row(host: androidx.fragment.app.Fragment, w: app.selfwatch.model.health.Workout, units: Units): ListRow {
        val ctx = host.requireContext()
        return ListRow(ctx).apply {
            title = ctx.getString(Sports.label(w.sport))
            subtitle = Sports.subtitle(ctx, w, units)
            trailing = w.kcal?.let { Fmt.energy(it, Locale.getDefault(), app.selfwatch.ui.format.FmtLabels.from(ctx)) }
            onClick = { Nav.open(host, WorkoutDetailFragment.newInstance(w.id)) }
        }
    }
}
