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
import app.selfwatch.activity.ActivityGoals
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
import app.selfwatch.ui.components.BarsView
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import app.selfwatch.ui.format.Units
import app.selfwatch.ui.R as UiR

/**
 * Distance today with the last 7 days as bars (card design standard); the goal line and met / short
 * colors only when the user set a distance goal: how far is context, not a test. Small form: the bars.
 */
object DistanceTodayCard : Card<DistanceTodayCard.Data>("distance_today", CardTab.ACTIVITY, R.string.card_distance_today, R.string.card_distance_today_summary) {

    /** Distance in metres per day (today last), the display units and the user's daily distance goal (null: none). */
    data class Data(val week: WeekValues, val units: Units, val goalM: Int? = null)

    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY, Table.ACTIVITY, Table.SETTING)

    override suspend fun load(env: CardEnv): Data =
        Data(TodayLoader(env.db, env.clock).distanceM(env.day), env.units, ActivityGoals.distanceM(env.db))

    override val needs: Set<DataKind> = setOf(DataKind.DISTANCE)

    override fun openDetail(host: CardHost): Boolean {
        host.open(app.selfwatch.activity.DailyActivityDetailFragment())
        return true
    }

    /** Today's distance as the number and its unit apart ("4.6", "mi"). */
    fun parts(ctx: Context, data: Data): Pair<String, String?> {
        val text = Fmt.distance((data.week.today ?: 0).toDouble(), data.units, labels = FmtLabels.from(ctx))
        val split = text.lastIndexOf(NBSP)
        return if (split > 0) text.substring(0, split) to text.substring(split + 1) else text to null
    }

    override fun small(ctx: Context, data: Data): SmallFace? {
        if (data.week.isEmpty) return null
        val (value, unit) = parts(ctx, data)
        return SmallFace(value = value, label = ctx.getString(R.string.dt_label), unit = unit)
    }

    override fun createSmallGraphic(ctx: Context): View = ActivityVisuals.smallBars(ctx)

    override fun bindSmallGraphic(graphic: View, data: Data) {
        val w = data.week
        ActivityVisuals.weekBars(
            graphic as BarsView, graphic.context.getString(R.string.card_distance_today), w.values.map { it ?: 0 },
            StatCardView.weekdayLabels(w.days.map { it.yyyymmdd }), data.goalM,
        )
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(parent)

    private const val NBSP = "\u00A0"

    private class Holder(parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_activity_distance_today)) {
        private val tile = find<StatTile>(R.id.dt_tile)
        private val bars = find<BarsView>(R.id.dt_bars)
        private val empty = find<EmptyState>(R.id.dt_empty)

        init {
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
        }

        override fun bind(data: Data) {
            val ctx = root.context
            val w = data.week
            ActivityVisuals.show(!w.isEmpty, tile, bars)
            ActivityVisuals.show(w.isEmpty, empty)
            if (w.isEmpty) {
                root.contentDescription = empty.message
                return
            }
            val (value, unit) = parts(ctx, data)
            ActivityVisuals.number(tile, value, unit, ctx.getString(R.string.dt_label))
            ActivityVisuals.weekBars(
                bars, ctx.getString(R.string.card_distance_today), w.values.map { it ?: 0 },
                StatCardView.weekdayLabels(w.days.map { it.yyyymmdd }), data.goalM,
            )
            root.contentDescription = ctx.getString(R.string.design_distance_description, listOfNotNull(value, unit).joinToString(" "))
        }
    }
}
