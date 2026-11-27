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
import app.selfwatch.activity.IntensityMath
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardStatusColors
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.ui.charts.LevelColors
import app.selfwatch.ui.components.DotsView
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.R as UiR

/**
 * Active days this week (C15, card design standard): seven day dots, Monday to Sunday, filled on days
 * with at least a seventh of the weekly active-minutes goal in intensity minutes (moderate once,
 * vigorous twice; [IntensityMath.activeDayMin]). The aim is "most days" (5). Small form: the dots.
 */
object ActiveDaysCard : Card<ActiveDaysCard.Data>("active_days", CardTab.ACTIVITY, R.string.card_active_days, R.string.card_active_days_summary) {

    /**
     * @property days the week, Monday first; [perDay] their intensity minutes (null: none or still to come).
     * @property hasData false without any activity data in the last 4 weeks (empty state).
     */
    data class Data(val days: List<Day>, val perDay: List<Int?>, val hasData: Boolean, val weekGoal: Int = ActivityGoals.DEFAULT_ACTIVE_MIN_WEEK) {
        /** Intensity minutes that make a day active. */
        val dayMin: Int get() = IntensityMath.activeDayMin(weekGoal)

        /** Days reaching the threshold. */
        val activeDays: Int get() = IntensityMath.activeDays(perDay, dayMin)

        /** One dot per day of [days]: true when the day was active. */
        val dots: BooleanArray get() = BooleanArray(days.size) { (perDay.getOrNull(it) ?: 0) >= dayMin }
    }

    override val tables: Set<Table> = setOf(Table.ACTIVITY, Table.DAY_SUMMARY, Table.GOAL)

    override suspend fun load(env: CardEnv): Data {
        val loader = ActivityStatsLoader(env.db, env.clock)
        val days = ActivityWeek.days(env.day)
        val sofar = loader.intensityPerDay(days.filter { it <= env.day })
        val perDay = days.mapIndexed { i, _ -> sofar.getOrNull(i) }
        val hasData = perDay.any { it != null } || loader.hasActivity(DayRange(env.day.plusDays(-HISTORY_DAYS), env.day))
        return Data(days, perDay, hasData, ActivityGoals.activeMinWeek(env.db, env.day))
    }

    override val needs: Set<DataKind> = setOf(DataKind.STEPS, DataKind.ACTIVE_TIME)

    override fun openDetail(host: CardHost): Boolean {
        host.open(app.selfwatch.activity.DailyActivityDetailFragment())
        return true
    }

    override fun small(ctx: Context, data: Data): SmallFace? {
        if (!data.hasData) return null
        val target = IntensityMath.ACTIVE_DAYS_TARGET
        return SmallFace(
            value = Fmt.number(data.activeDays.toLong()),
            label = ctx.getString(R.string.activity_of_goal, Fmt.number(target.toLong())),
            status = CardStatusColors.ofProgress(data.activeDays.toDouble(), target.toDouble()),
            progress = data.activeDays.toFloat() / target,
        )
    }

    override fun createSmallGraphic(ctx: Context): View = ActivityVisuals.smallDots(ctx)

    override fun bindSmallGraphic(graphic: View, data: Data) = bindDots(graphic as DotsView, data)

    private fun bindDots(dots: DotsView, data: Data) {
        dots.filled = data.dots
        dots.labels = StatCardView.weekdayLabels(data.days.map { it.yyyymmdd })
        dots.color = LevelColors.of(dots.context).good
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(parent)

    private const val HISTORY_DAYS = 27

    private class Holder(parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_activity_active_days)) {
        private val tile = find<StatTile>(R.id.ad_tile)
        private val dots = find<DotsView>(R.id.ad_dots)
        private val empty = find<EmptyState>(R.id.ad_empty)

        init {
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
        }

        override fun bind(data: Data) {
            val ctx = root.context
            ActivityVisuals.show(data.hasData, tile, dots)
            ActivityVisuals.show(!data.hasData, empty)
            if (!data.hasData) {
                root.contentDescription = empty.message
                return
            }
            val target = Fmt.number(IntensityMath.ACTIVE_DAYS_TARGET.toLong())
            ActivityVisuals.number(tile, Fmt.number(data.activeDays.toLong()), null, ctx.getString(R.string.activity_of_goal, target))
            bindDots(dots, data)
            root.contentDescription = ctx.getString(R.string.design_active_days_description, tile.value, target)
        }
    }
}
