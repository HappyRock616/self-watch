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
import app.selfwatch.activity.TodayLoader
import app.selfwatch.activity.WeekValues
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.ui.charts.LevelColors
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.SplitBarView
import app.selfwatch.ui.components.SplitPart
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.R as UiR

/**
 * Calories today (card design standard): the total burned so far with a split bar of its two parts,
 * resting and active. Estimates; active calories alone are `active_kcal`. Small form: the split bar.
 */
object CaloriesTodayCard : Card<CaloriesTodayCard.Data>("calories_today", CardTab.ACTIVITY, R.string.card_calories_today, R.string.card_calories_today_summary) {

    /** Total kcal per day (today last) and today's active and resting parts (null: none). */
    data class Data(val week: WeekValues, val activeKcal: Int?, val restingKcal: Int?)

    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY, Table.ACTIVITY)

    override suspend fun load(env: CardEnv): Data {
        val (week, parts) = TodayLoader(env.db, env.clock).totalKcal(env.day)
        return Data(week, parts.first, parts.second)
    }

    override val needs: Set<DataKind> = setOf(DataKind.ACTIVE_CALORIES, DataKind.RESTING_CALORIES)

    override fun openDetail(host: CardHost): Boolean {
        host.open(app.selfwatch.activity.DailyActivityDetailFragment())
        return true
    }

    override fun small(ctx: Context, data: Data): SmallFace? {
        val w = data.week
        if (w.isEmpty) return null
        return SmallFace(
            value = Fmt.number((w.today ?: 0).toLong()),
            label = ctx.getString(R.string.small_burned_so_far),
            unit = ctx.getString(R.string.unit_kcal),
        )
    }

    override fun createSmallGraphic(ctx: Context): View = ActivityVisuals.smallSplit(ctx)

    override fun bindSmallGraphic(graphic: View, data: Data) = split(graphic as SplitBarView, data, labels = false)

    /** Resting (neutral) then active (good), labeled with their kcal when [labels]. */
    private fun split(bar: SplitBarView, data: Data, labels: Boolean) {
        val ctx = bar.context
        val c = LevelColors.of(ctx)
        val resting = data.restingKcal ?: 0
        val active = data.activeKcal ?: 0
        fun label(name: Int, v: Int) = if (labels) ctx.getString(R.string.part_value, ctx.getString(name), Fmt.number(v.toLong())) else null
        bar.parts = listOf(
            SplitPart(resting.toFloat(), c.neutral, label(R.string.part_resting, resting)),
            SplitPart(active.toFloat(), c.good, label(R.string.part_active, active)),
        )
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(parent)

    private class Holder(parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_activity_calories_today)) {
        private val tile = find<StatTile>(R.id.ct_tile)
        private val bar = find<SplitBarView>(R.id.ct_split)
        private val empty = find<EmptyState>(R.id.ct_empty)

        init {
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
        }

        override fun bind(data: Data) {
            val ctx = root.context
            val w = data.week
            ActivityVisuals.show(!w.isEmpty, tile, bar)
            ActivityVisuals.show(w.isEmpty, empty)
            if (w.isEmpty) {
                root.contentDescription = empty.message
                return
            }
            ActivityVisuals.number(tile, Fmt.number((w.today ?: 0).toLong()), ctx.getString(R.string.unit_kcal), ctx.getString(R.string.tag_today))
            split(bar, data, labels = true)
            ActivityVisuals.show((data.restingKcal ?: 0) + (data.activeKcal ?: 0) > 0, bar)
            root.contentDescription = ctx.getString(
                R.string.design_calories_description, tile.value,
                Fmt.number((data.restingKcal ?: 0).toLong()), Fmt.number((data.activeKcal ?: 0).toLong()),
            )
        }
    }
}
