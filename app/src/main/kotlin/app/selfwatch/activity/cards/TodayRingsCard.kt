/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity.cards

import android.view.ViewGroup
import app.selfwatch.R
import app.selfwatch.activity.ActivityLoader
import app.selfwatch.activity.Rings
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.db.Table
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.components.RingView
import app.selfwatch.ui.format.Fmt

/** Today's rings: steps, active minutes and active kcal against their goals. */
object TodayRingsCard : Card<Rings>("today_rings", CardTab.ACTIVITY, R.string.card_today_rings, R.string.card_today_rings_summary) {

    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY, Table.GOAL, Table.SETTING)

    override suspend fun load(env: CardEnv): Rings = ActivityLoader(env.db, env.clock).rings()

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Rings> = Holder(parent)

    private class Holder(parent: ViewGroup) : CardView<Rings>(CardView.inflate(parent, R.layout.card_activity_rings)) {
        override fun bind(data: Rings) {
            val ctx = root.context
            find<RingView>(R.id.ring_steps).show(data.steps, data.stepGoal, ctx.getString(R.string.activity_of_goal, Fmt.number(data.stepGoal.toLong())))
            find<RingView>(R.id.ring_minutes).show(data.activeMin, data.activeMinGoal, ctx.getString(R.string.activity_active))
            // Rings: steps = series 1 (primary), minutes = series 2 (secondary, in the layout), kcal = series 3.
            find<RingView>(R.id.ring_kcal).color = ChartPalette.of(ctx).series(2)
            find<RingView>(R.id.ring_kcal).show(data.activeKcal, data.activeKcalGoal, ctx.getString(R.string.activity_active_kcal_short))
        }

        private fun RingView.show(value: Int, goal: Int, sub: String) {
            progress = if (goal > 0) value.toFloat() / goal else 0f
            centerText = Fmt.number(value.toLong())
            subText = sub
        }
    }
}
