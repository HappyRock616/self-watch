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
import app.selfwatch.activity.ActivityRules
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.db.Table
import app.selfwatch.me.GoalField
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.ui.format.Fmt
import kotlin.math.roundToLong

/**
 * Active calories today (C12) against about 400 kcal a day, what long-term weight-loss maintainers report
 * (WP-15, C12), or the user's own active-calorie goal; the last 7 days as bars. Labelled as an estimate.
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

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(parent)

    private const val WEEK = 7
    private const val HALF = 5L
    private const val TENTHS = 10L

    private class Holder(parent: ViewGroup) : StatCardView<Data>(
        parent, R.layout.card_activity_active_kcal,
        StatIds(R.id.ak_tile, R.id.ak_bar, R.id.ak_bars, R.id.ak_caption, R.id.ak_note, R.id.ak_empty),
    ) {
        override fun bind(data: Data) {
            val ctx = root.context
            showEmpty(data.isEmpty)
            if (data.isEmpty) return
            val kcal = data.kcal ?: 0
            val target = Fmt.number(data.target.toLong())
            tile.value = Fmt.number(kcal.toLong())
            tile.unit = null
            tile.label = ctx.getString(if (data.ownGoal) R.string.ak_label_own else R.string.ak_label, target)
            progress(kcal, data.target)
            val present = data.week.filterNotNull()
            text(caption, if (present.isEmpty()) null else ctx.getString(R.string.ak_caption, Fmt.number(present.average().roundToLong())))
            bars(ctx.getString(R.string.card_active_kcal), data.week.map { it ?: 0 }, weekdayLabels(data.days.map { it.yyyymmdd }), data.target)
            note.setText(R.string.ak_note)
            root.contentDescription = ctx.getString(R.string.ak_description, tile.value, target)
        }
    }
}
