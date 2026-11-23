/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity.cards

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import app.selfwatch.R
import app.selfwatch.activity.ActivityLoader
import app.selfwatch.activity.WorkoutListFragment
import app.selfwatch.activity.WorkoutRows
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.db.Table
import app.selfwatch.model.health.Workout
import app.selfwatch.shell.Views
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Units

/** The latest workouts (tap one for its detail); "All" opens the workout list. */
object WorkoutsCard : Card<WorkoutsCard.Data>("workouts", CardTab.ACTIVITY, R.string.card_workouts, R.string.card_workouts_summary) {

    /** The workouts, newest first, in display [units]. */
    data class Data(val workouts: List<Workout>, val units: Units)

    override val tables: Set<Table> = setOf(Table.WORKOUT)

    override suspend fun load(env: CardEnv): Data = Data(ActivityLoader(env.db, env.clock).workouts(), env.units)

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    private class Holder(private val host: CardHost, parent: ViewGroup) :
        CardView<Data>(CardView.inflate(parent, R.layout.card_activity_workouts)) {
        init {
            (root as SwCard).onAction = { host.open(WorkoutListFragment()) }
        }

        override fun bind(data: Data) {
            val list = find<LinearLayout>(R.id.recent_list)
            list.removeAllViews()
            for (w in data.workouts) list.addView(WorkoutRows.row(host.fragment, w, data.units), Views.matchWrap(root.context))
            find<EmptyState>(R.id.recent_empty).visibility = if (data.workouts.isEmpty()) View.VISIBLE else View.GONE
        }
    }
}
