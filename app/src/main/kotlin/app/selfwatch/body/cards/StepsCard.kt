/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body.cards

import android.view.View
import android.view.ViewGroup
import app.selfwatch.R
import app.selfwatch.body.BodyLoader
import app.selfwatch.body.StepsInfo
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.db.Table
import app.selfwatch.shell.Tab
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import com.google.android.material.progressindicator.LinearProgressIndicator
import app.selfwatch.ui.R as UiR

/** Steps and active minutes today vs goals; opens Activity. */
object StepsCard : Card<StepsInfo>("steps", CardTab.BODY, R.string.card_steps, R.string.card_steps_summary) {

    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY, Table.ACTIVITY, Table.GOAL)

    override suspend fun load(env: CardEnv): StepsInfo = BodyLoader(env.db, env.clock).steps()

    override fun createView(host: CardHost, parent: ViewGroup): CardView<StepsInfo> = Holder(host, parent)

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<StepsInfo>(CardView.inflate(parent, R.layout.card_body_steps)) {
        init {
            root.setOnClickListener { host.openTab(Tab.ACTIVITY) }
        }

        override fun bind(data: StepsInfo) {
            val ctx = root.context
            val steps = find<StatTile>(R.id.steps_tile)
            steps.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
            steps.value = data.steps?.let { Fmt.number(it.toLong()) } ?: ctx.getString(R.string.dash)
            steps.label = data.stepsGoal?.let { ctx.getString(R.string.steps_goal, Fmt.number(it.toLong())) } ?: ctx.getString(R.string.steps_label)
            val active = find<StatTile>(R.id.active_min_tile)
            active.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
            active.value = data.activeMin?.let { Fmt.number(it.toLong()) } ?: ctx.getString(R.string.dash)
            active.label = data.activeMinGoal?.let { ctx.getString(R.string.active_min_goal, Fmt.number(it.toLong())) }
                ?: ctx.getString(R.string.active_min_label)
            progress(find(R.id.steps_bar), data.steps, data.stepsGoal)
            progress(find(R.id.active_min_bar), data.activeMin, data.activeMinGoal)
            find<EmptyState>(R.id.steps_empty).visibility = if (data.isEmpty) View.VISIBLE else View.GONE
            find<View>(R.id.steps_numbers).visibility = if (data.isEmpty) View.GONE else View.VISIBLE
            root.contentDescription = ctx.getString(R.string.steps_description, steps.value, active.value)
        }
    }

    /** Shows [value] of [goal] on [bar]; hidden without a goal. */
    internal fun progress(bar: LinearProgressIndicator, value: Int?, goal: Int?) {
        bar.visibility = if (goal == null || goal <= 0) View.GONE else View.VISIBLE
        bar.max = maxOf(goal ?: 1, 1)
        bar.setProgressCompat((value ?: 0).coerceIn(0, bar.max), false)
    }
}
