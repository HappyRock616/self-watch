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
import app.selfwatch.activity.ActivityStatsLoader
import app.selfwatch.activity.ActivityWeek
import app.selfwatch.activity.ExerciseSleepDetailFragment
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardStatusColors
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.CardVisuals
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.ui.components.BarsView
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.RingView
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.R as UiR

/**
 * Intensity minutes this week (C10, card design standard): moderate minutes plus vigorous minutes
 * counted twice, Monday to Sunday, against the weekly active-minutes goal (default 150). Bars per day,
 * met / short against the daily share of the goal, which is the goal line. Small form: a ring toward the
 * weekly goal. Opens the exercise-and-sleep overlay (O5).
 */
object IntensityMinutesCard : Card<IntensityMinutesCard.Data>(
    "intensity_minutes", CardTab.ACTIVITY, R.string.card_intensity_minutes, R.string.card_intensity_minutes_summary,
) {

    /**
     * @property days the week, Monday first.
     * @property perDay intensity minutes per day of [days] (null: nothing recorded or still to come).
     * @property target weekly target: the weekly active-minutes goal.
     * @property hasData false when there is no activity data in the last 4 weeks at all (empty state).
     */
    data class Data(val days: List<Day>, val perDay: List<Int?>, val target: Int, val hasData: Boolean) {
        /** Intensity minutes this week. */
        val weekMin: Int get() = perDay.sumOf { it ?: 0 }

        /** The goal line: the daily share of [target]. */
        val dailyShare: Int get() = ActivityGoals.dailyShare(target)
    }

    override val tables: Set<Table> = setOf(Table.ACTIVITY, Table.DAY_SUMMARY, Table.GOAL)

    override suspend fun load(env: CardEnv): Data {
        val loader = ActivityStatsLoader(env.db, env.clock)
        val days = ActivityWeek.days(env.day)
        val sofar = loader.intensityPerDay(days.filter { it <= env.day })
        val perDay = days.mapIndexed { i, _ -> sofar.getOrNull(i) }
        val hasData = perDay.any { it != null } || loader.hasActivity(DayRange(env.day.plusDays(-HISTORY_DAYS), env.day))
        return Data(days, perDay, ActivityGoals.activeMinWeek(env.db, env.day), hasData)
    }

    override val needs: Set<DataKind> = setOf(DataKind.STEPS, DataKind.ACTIVE_TIME)

    override fun small(ctx: Context, data: Data): SmallFace? {
        if (!data.hasData) return null
        val week = data.weekMin
        return SmallFace(
            value = Fmt.number(week.toLong()),
            label = ctx.getString(R.string.activity_of_goal, Fmt.number(data.target.toLong())),
            unit = ctx.getString(R.string.unit_min),
            status = CardStatusColors.ofProgress(week.toDouble(), data.target.toDouble()),
            progress = if (data.target > 0) week.toFloat() / data.target else null,
        )
    }

    override fun createSmallGraphic(ctx: Context): View = CardVisuals.smallRing(ctx)

    override val smallGraphicShowsValue: Boolean = true

    override fun bindSmallGraphic(graphic: View, data: Data) {
        val week = data.weekMin
        (graphic as RingView).apply {
            progress = if (data.target > 0) week.toFloat() / data.target else 0f
            color = ActivityVisuals.goalColor(context, week, data.target)
            centerText = Fmt.number(week.toLong())
            subText = context.getString(R.string.activity_of_goal, Fmt.number(data.target.toLong()))
        }
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(ExerciseSleepDetailFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    private const val HISTORY_DAYS = 27

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_activity_intensity_minutes)) {
        private val tile = find<StatTile>(R.id.im_tile)
        private val bars = find<BarsView>(R.id.im_bars)
        private val empty = find<EmptyState>(R.id.im_empty)

        init {
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
            root.setOnClickListener { host.open(ExerciseSleepDetailFragment()) }
        }

        override fun bind(data: Data) {
            val ctx = root.context
            ActivityVisuals.show(data.hasData, tile, bars)
            ActivityVisuals.show(!data.hasData, empty)
            if (!data.hasData) {
                root.contentDescription = empty.message
                return
            }
            val target = Fmt.number(data.target.toLong())
            ActivityVisuals.number(tile, Fmt.number(data.weekMin.toLong()), ctx.getString(R.string.unit_min), ctx.getString(R.string.activity_of_goal, target))
            ActivityVisuals.weekBars(
                bars, ctx.getString(R.string.card_intensity_minutes), data.perDay.map { it ?: 0 },
                StatCardView.weekdayLabels(data.days.map { it.yyyymmdd }), data.dailyShare,
            )
            root.contentDescription = ctx.getString(R.string.design_intensity_minutes_description, tile.value, target)
        }
    }
}
