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
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.SystemClock
import app.selfwatch.service.live.LiveReading
import app.selfwatch.ui.components.BarsView
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.RingView
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import app.selfwatch.watch.LiveVitals
import app.selfwatch.ui.R as UiR

/**
 * Steps (C11, card design standard): today's steps against the user's step goal (else 7,500) with the
 * last 7 days as bars, met / short in color, and the goal line. Small form: a ring with today's steps.
 * While the card is on screen and the watch is connected, the watch's live step count replaces the last
 * sync's ([LiveVitals]). Never pass or fail. The stored id stays `steps_today`.
 */
object StepsTodayCard : Card<StepsTodayCard.Data>("steps_today", CardTab.ACTIVITY, R.string.card_steps_today, R.string.card_steps_today_summary) {

    /**
     * @property steps today's steps (null: none recorded).
     * @property target the daily target.
     * @property ownGoal true when [target] is the user's own step goal.
     * @property days the last 7 days, today last; [week] their steps (null: none).
     * @property distanceM today's distance in metres; [activeKcal] today's active calories (null: none).
     * @property units display units.
     */
    data class Data(
        val steps: Int?,
        val target: Int,
        val ownGoal: Boolean,
        val days: List<Day>,
        val week: List<Int?>,
        val distanceM: Int? = null,
        val activeKcal: Int? = null,
        val units: Units = Units.METRIC,
    ) {
        /** True when there are no steps in the last 7 days. */
        val isEmpty: Boolean get() = week.all { it == null }
    }

    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY, Table.ACTIVITY, Table.GOAL)

    override suspend fun load(env: CardEnv): Data {
        val days = (WEEK - 1 downTo 0).map { env.day.plusDays(-it) }
        val range = DayRange(days.first(), days.last())
        val summary = env.db.summaries.days(range).mapNotNull { s -> s.steps?.let { s.day to it } }.toMap()
        val minutes = env.db.activity.totalsByDay(range, env.clock.zone()).associate { it.day to it.steps.toInt() }
        val week = days.map { summary[it] ?: minutes[it] }
        val own = env.db.goals.goalOn(GoalKind.STEPS, env.day)?.value
        val today = TodayLoader(env.db, env.clock)
        return Data(
            week.last(), own ?: ActivityGoals.DEFAULT_STEPS, own != null, days, week,
            distanceM = today.distanceM(env.day).today,
            activeKcal = today.activeKcal(env.day).today,
            units = env.units,
        )
    }

    override val needs: Set<DataKind> = setOf(DataKind.STEPS)

    override fun openDetail(host: CardHost): Boolean {
        host.open(app.selfwatch.activity.DailyActivityDetailFragment())
        return true
    }

    override fun small(ctx: Context, data: Data): SmallFace? {
        if (data.isEmpty) return null
        val steps = data.steps ?: 0
        return SmallFace(
            value = Fmt.number(steps.toLong()),
            label = ctx.getString(R.string.small_of_steps, Fmt.number(data.target.toLong())),
            status = CardStatusColors.ofProgress(steps.toDouble(), data.target.toDouble()),
            progress = if (data.target > 0) steps.toFloat() / data.target else null,
        )
    }

    override fun createSmallGraphic(ctx: Context): View = CardVisuals.smallRing(ctx)

    override val smallGraphicShowsValue: Boolean = true

    override fun bindSmallGraphic(graphic: View, data: Data) {
        val steps = data.steps ?: 0
        (graphic as RingView).apply {
            progress = if (data.target > 0) steps.toFloat() / data.target else 0f
            color = ActivityVisuals.goalColor(context, steps, data.target)
            centerText = Fmt.number(steps.toLong())
            subText = context.getString(R.string.activity_of_goal, Fmt.number(data.target.toLong()))
        }
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    /**
     * Today's steps to show: the watch's live count when [live] is from today ([todayStartSec] on), never
     * lower than the stored count (that can be newer right after a sync); else [stored].
     */
    fun stepsNow(stored: Int?, live: LiveReading?, todayStartSec: Long): Int? {
        val l = live?.steps?.takeIf { live.tSec >= todayStartSec } ?: return stored
        return maxOf(l, stored ?: 0)
    }

    private const val WEEK = 7

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_activity_steps_today)) {
        private val tile = find<StatTile>(R.id.st_tile)
        private val bars = find<BarsView>(R.id.st_bars)
        private val empty = find<EmptyState>(R.id.st_empty)
        private var stored: Data? = null
        private var live: LiveReading? = null

        init {
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
            LiveVitals.follow(host, root) { r ->
                live = r
                stored?.let { show(it) }
            }
        }

        override fun bind(data: Data) {
            stored = data
            show(data)
        }

        private fun show(data: Data) {
            val ctx = root.context
            val now = stepsNow(data.steps, live, SystemClock.today().startSec(SystemClock.zone()))
            val isLive = now != null && now != data.steps
            val isEmpty = data.isEmpty && now == null
            ActivityVisuals.show(!isEmpty, tile, bars)
            ActivityVisuals.show(isEmpty, empty)
            if (isEmpty) {
                root.contentDescription = empty.message
                return
            }
            val steps = now ?: 0
            val target = Fmt.number(data.target.toLong())
            ActivityVisuals.number(
                tile, Fmt.number(steps.toLong()), null,
                if (isLive) ctx.getString(R.string.tag_live) else ctx.getString(R.string.tag_goal, target),
            )
            val week = data.week.dropLast(1).map { it ?: 0 } + steps
            ActivityVisuals.weekBars(bars, ctx.getString(R.string.activity_steps), week, StatCardView.weekdayLabels(data.days.map { it.yyyymmdd }), data.target)
            root.contentDescription = ctx.getString(R.string.design_steps_description, tile.value, target)
        }
    }
}
