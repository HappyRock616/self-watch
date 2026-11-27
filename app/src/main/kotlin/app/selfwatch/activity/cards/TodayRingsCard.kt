/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity.cards

import android.content.Context
import android.view.View
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
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.RingItem
import app.selfwatch.ui.components.RingRowView
import app.selfwatch.ui.format.Fmt

/**
 * The rings (card design standard): steps and active kcal today, and active minutes this week against
 * the weekly goal, as three rings with the value inside, met / short in color. Small form: the rings.
 */
object TodayRingsCard : Card<Rings>("today_rings", CardTab.ACTIVITY, R.string.card_today_rings, R.string.card_today_rings_summary) {
    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY, Table.GOAL, Table.SETTING)

    override suspend fun load(env: CardEnv): Rings = ActivityLoader(env.db, env.clock).rings()

    override val needs: Set<DataKind> = setOf(DataKind.STEPS, DataKind.ACTIVE_TIME, DataKind.ACTIVE_CALORIES)

    override fun openDetail(host: CardHost): Boolean {
        host.open(app.selfwatch.activity.DailyActivityDetailFragment())
        return true
    }

    override val defaultSize: CardSize get() = CardSize.LARGE

    private fun isEmpty(data: Rings) = data.steps <= 0 && data.activeMin <= 0 && data.activeKcal <= 0

    override fun small(ctx: Context, data: Rings): SmallFace? {
        if (isEmpty(data)) return null
        return SmallFace(
            value = Fmt.number(data.steps.toLong()),
            label = ctx.getString(R.string.small_of_steps, Fmt.number(data.stepGoal.toLong())),
            status = CardStatusColors.ofProgress(data.steps.toDouble(), data.stepGoal.toDouble()),
            progress = if (data.stepGoal > 0) data.progress(data.steps, data.stepGoal) else null,
        )
    }

    override fun createSmallGraphic(ctx: Context): View = ActivityVisuals.smallRingRow(ctx)

    override val smallGraphicShowsValue: Boolean = true

    override fun bindSmallGraphic(graphic: View, data: Rings) {
        (graphic as RingRowView).items = items(graphic.context, data)
    }

    /** Steps, active minutes and active kcal as rings. */
    fun items(ctx: Context, data: Rings): List<RingItem> {
        fun ring(value: Int, goal: Int, label: Int) = RingItem(
            data.progress(value, goal), ActivityVisuals.goalColor(ctx, value, goal), ActivityGoalsCard.compact(value), ctx.getString(label),
        )
        return listOf(
            ring(data.steps, data.stepGoal, R.string.ring_steps),
            ring(data.activeMin, data.activeMinGoal, R.string.ring_minutes),
            ring(data.activeKcal, data.activeKcalGoal, R.string.ring_calories),
        )
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Rings> = Holder(parent)

    private class Holder(parent: ViewGroup) : CardView<Rings>(CardView.inflate(parent, R.layout.card_activity_rings)) {
        private val row = find<RingRowView>(R.id.rings_row)
        private val empty = find<EmptyState>(R.id.rings_empty)

        override fun bind(data: Rings) {
            val ctx = root.context
            val none = isEmpty(data)
            ActivityVisuals.show(!none, row)
            ActivityVisuals.show(none, empty)
            row.items = items(ctx, data)
            fun n(v: Int) = Fmt.number(v.toLong())
            root.contentDescription = if (none) {
                empty.message
            } else {
                ctx.getString(
                    R.string.design_rings_description,
                    n(data.steps), n(data.stepGoal), n(data.activeMin), n(data.activeMinGoal), n(data.activeKcal), n(data.activeKcalGoal),
                )
            }
        }
    }
}
