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
import app.selfwatch.activity.ActivityRules
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
import app.selfwatch.me.GoalField
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.RingView
import app.selfwatch.ui.format.Fmt

/**
 * Active calories today (C12, card design standard): a ring toward about 400 kcal a day, what long-term
 * weight-loss maintainers report (WP-15, C12), or the user's own active-calorie goal, the kcal inside.
 * Small form: the same ring. Watch calories are estimates.
 */
object ActiveKcalCard : Card<ActiveKcalCard.Data>("active_kcal", CardTab.ACTIVITY, R.string.card_active_kcal, R.string.card_active_kcal_summary) {

    /** Reference active kcal a day. */
    const val REFERENCE_KCAL: Int = ActivityRules.DEFAULT_ACTIVE_KCAL

    /**
     * @property kcal today's active kcal (null: none).
     * @property target the reference or the user's goal ([ownGoal]).
     * @property days the last 7 days, today last; [week] their active kcal (null: none).
     */
    data class Data(val kcal: Int?, val target: Int, val ownGoal: Boolean, val days: List<Day>, val week: List<Int?>) {
        /** True when there are no active kcal in the last 7 days. */
        val isEmpty: Boolean get() = week.all { it == null }
    }

    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY, Table.ACTIVITY, Table.SETTING)

    override suspend fun load(env: CardEnv): Data {
        val days = (WEEK - 1 downTo 0).map { env.day.plusDays(-it) }
        val range = DayRange(days.first(), days.last())
        val summary = env.db.summaries.days(range).mapNotNull { s -> s.activeKcal?.let { s.day to it } }.toMap()
        val minutes = env.db.activity.totalsByDay(range, env.clock.zone()).associate { it.day to ((it.activeKcalX10 + HALF) / TENTHS).toInt() }
        val week = days.map { summary[it] ?: minutes[it] }
        val own = env.db.settings.getInt(GoalField.ACTIVE_KCAL_KEY)
        return Data(week.last(), own ?: REFERENCE_KCAL, own != null, days, week)
    }

    override val needs: Set<DataKind> = setOf(DataKind.ACTIVE_CALORIES)

    override fun openDetail(host: CardHost): Boolean {
        host.open(app.selfwatch.activity.DailyActivityDetailFragment())
        return true
    }

    override fun small(ctx: Context, data: Data): SmallFace? {
        if (data.isEmpty) return null
        val kcal = data.kcal ?: 0
        val target = Fmt.number(data.target.toLong())
        return SmallFace(
            value = Fmt.number(kcal.toLong()),
            label = ctx.getString(R.string.activity_of_goal, target),
            unit = ctx.getString(R.string.unit_kcal),
            status = CardStatusColors.ofProgress(kcal.toDouble(), data.target.toDouble()),
            progress = if (data.target > 0) kcal.toFloat() / data.target else null,
        )
    }

    override fun createSmallGraphic(ctx: Context): View = CardVisuals.smallRing(ctx)

    override val smallGraphicShowsValue: Boolean = true

    override fun bindSmallGraphic(graphic: View, data: Data) = ring(graphic as RingView, data)

    /** Shows today's kcal against the target on [ring]. */
    private fun ring(ring: RingView, data: Data) {
        val kcal = data.kcal ?: 0
        ring.progress = if (data.target > 0) kcal.toFloat() / data.target else 0f
        ring.color = ActivityVisuals.goalColor(ring.context, kcal, data.target)
        ring.centerText = Fmt.number(kcal.toLong())
        ring.subText = ring.context.getString(R.string.activity_of_goal, Fmt.number(data.target.toLong()))
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(parent)

    private const val WEEK = 7
    private const val HALF = 5L
    private const val TENTHS = 10L

    private class Holder(parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_activity_active_kcal)) {
        private val ringView = find<RingView>(R.id.ak_ring)
        private val empty = find<EmptyState>(R.id.ak_empty)

        override fun bind(data: Data) {
            val ctx = root.context
            ActivityVisuals.show(!data.isEmpty, ringView)
            ActivityVisuals.show(data.isEmpty, empty)
            if (data.isEmpty) {
                root.contentDescription = empty.message
                return
            }
            ring(ringView, data)
            root.contentDescription = ctx.getString(
                R.string.design_active_kcal_description, Fmt.number((data.kcal ?: 0).toLong()), Fmt.number(data.target.toLong()),
            )
        }
    }
}
