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
import app.selfwatch.activity.ActivityStatsLoader
import app.selfwatch.activity.ActivityWeek
import app.selfwatch.activity.StrengthRules
import app.selfwatch.activity.WorkoutListFragment
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
 * Strength (and balance) days this week (C13, card design standard): seven day dots, Monday to Sunday,
 * filled on days with a counting session, from the workouts' FIT sport types: strength training counts;
 * from age 65 yoga, pilates and flexibility sessions count too and the aim is 3 days instead of 2 (WHO
 * 2020). Empty when no counting session was recorded in the last 3 months. Small form: the dots.
 */
object StrengthSessionsCard : Card<StrengthSessionsCard.Data>(
    "strength_sessions", CardTab.ACTIVITY, R.string.card_strength_sessions, R.string.card_strength_sessions_summary,
) {

    /**
     * @property days distinct days this week (Monday to today) with a counting session.
     * @property target days a week.
     * @property withBalance true from age 65 (balance sessions count).
     * @property lastDay the day of the latest counting session in the last 3 months, or null (empty state).
     * @property week the days of this week, Monday first; [sessionDays] those with a counting session.
     */
    data class Data(
        val days: Int,
        val target: Int,
        val withBalance: Boolean,
        val lastDay: Day?,
        val week: List<Day> = emptyList(),
        val sessionDays: Set<Day> = emptySet(),
    ) {
        /** One dot per day of [week]: true with a counting session. */
        val dots: BooleanArray get() = BooleanArray(week.size) { week[it] in sessionDays }
    }

    override val tables: Set<Table> = setOf(Table.WORKOUT, Table.SETTING)

    override suspend fun load(env: CardEnv): Data {
        val loader = ActivityStatsLoader(env.db, env.clock)
        val age = loader.age()
        val withBalance = age != null && age >= StrengthRules.OLDER_AGE
        val zone = env.clock.zone()
        val recent = loader.workouts(DayRange(env.day.plusDays(-HISTORY_DAYS), env.day)).filter { StrengthRules.counts(it, withBalance) }
        val week = recent.filter { Day.of(it.startSec, zone) >= ActivityWeek.start(env.day) }
        return Data(
            StrengthRules.days(week, withBalance, zone),
            if (withBalance) StrengthRules.OLDER_TARGET else StrengthRules.TARGET,
            withBalance,
            recent.maxByOrNull { it.startSec }?.let { Day.of(it.startSec, zone) },
            ActivityWeek.days(env.day),
            week.map { Day.of(it.startSec, zone) }.toSet(),
        )
    }

    override val needs: Set<DataKind> = setOf(DataKind.WORKOUTS)

    override fun small(ctx: Context, data: Data): SmallFace? {
        if (data.lastDay == null) return null
        return SmallFace(
            value = Fmt.number(data.days.toLong()),
            label = ctx.getString(R.string.activity_of_goal, Fmt.number(data.target.toLong())),
            status = CardStatusColors.ofProgress(data.days.toDouble(), data.target.toDouble()),
            progress = if (data.target > 0) data.days.toFloat() / data.target else null,
        )
    }

    override fun createSmallGraphic(ctx: Context): View = ActivityVisuals.smallDots(ctx)

    override fun bindSmallGraphic(graphic: View, data: Data) = dots(graphic as DotsView, data)

    private fun dots(view: DotsView, data: Data) {
        view.filled = data.dots
        view.labels = StatCardView.weekdayLabels(data.week.map { it.yyyymmdd })
        view.color = LevelColors.of(view.context).good
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(WorkoutListFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    private const val HISTORY_DAYS = 90

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_activity_strength)) {
        private val tile = find<StatTile>(R.id.ss_tile)
        private val dotsView = find<DotsView>(R.id.ss_dots)
        private val empty = find<EmptyState>(R.id.ss_empty)

        init {
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
            root.setOnClickListener { host.open(WorkoutListFragment()) }
        }

        override fun bind(data: Data) {
            val ctx = root.context
            val shown = data.lastDay != null
            ActivityVisuals.show(shown, tile, dotsView)
            ActivityVisuals.show(!shown, empty)
            if (!shown) {
                root.contentDescription = empty.message
                return
            }
            val target = Fmt.number(data.target.toLong())
            ActivityVisuals.number(tile, Fmt.number(data.days.toLong()), null, ctx.getString(R.string.activity_of_goal, target))
            dots(dotsView, data)
            root.contentDescription = ctx.getString(R.string.design_strength_description, tile.value, target)
        }
    }
}
