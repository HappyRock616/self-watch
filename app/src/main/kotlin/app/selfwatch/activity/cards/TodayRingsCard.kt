/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity.cards

import android.content.Context
import android.view.ViewGroup
import app.selfwatch.R
import app.selfwatch.activity.ActivityLoader
import app.selfwatch.activity.Rings
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardStatusColors
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.components.RingView
import app.selfwatch.ui.format.Fmt

/** The rings: steps and active kcal today, and active minutes this week against the weekly goal. */
object TodayRingsCard : Card<Rings>("today_rings", CardTab.ACTIVITY, R.string.card_today_rings, R.string.card_today_rings_summary) {

    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY, Table.GOAL, Table.SETTING)

    override suspend fun load(env: CardEnv): Rings = ActivityLoader(env.db, env.clock).rings()

    override val needs: Set<DataKind> = setOf(DataKind.STEPS, DataKind.ACTIVE_TIME, DataKind.ACTIVE_CALORIES)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override fun small(ctx: Context, data: Rings): SmallFace? {
        if (data.steps <= 0 && data.activeMin <= 0 && data.activeKcal <= 0) return null
        return SmallFace(
            value = Fmt.number(data.steps.toLong()),
            label = ctx.getString(R.string.small_of_steps, Fmt.number(data.stepGoal.toLong())),
            status = CardStatusColors.ofProgress(data.steps.toDouble(), data.stepGoal.toDouble()),
            note = ctx.getString(R.string.small_rings_note, Fmt.number(data.activeMin.toLong()), Fmt.number(data.activeKcal.toLong())),
            progress = if (data.stepGoal > 0) data.progress(data.steps, data.stepGoal) else null,
        )
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Rings> = Holder(parent)

    private class Holder(parent: ViewGroup) : CardView<Rings>(CardView.inflate(parent, R.layout.card_activity_rings)) {
        override fun bind(data: Rings) {
            val ctx = root.context
            find<RingView>(R.id.ring_steps).show(data.steps, data.stepGoal, ctx.getString(R.string.activity_of_goal, Fmt.number(data.stepGoal.toLong())))
            find<RingView>(R.id.ring_minutes).show(data.activeMin, data.activeMinGoal, ctx.getString(R.string.activity_active_week))
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
