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
import app.selfwatch.activity.ActivityStatsLoader
import app.selfwatch.activity.StepTargets
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.db.Table
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.ui.format.Fmt
import kotlin.math.roundToLong

/**
 * Steps today (C11) against the user's step goal, else 7,000 (6,000 to 8,000 from age 60, from the
 * profile's birth year), with the last 7 days as bars. "Every extra 1,000 helps", never pass or fail.
 */
object StepsTodayCard : Card<StepsTodayCard.Data>("steps_today", CardTab.ACTIVITY, R.string.card_steps_today, R.string.card_steps_today_summary) {

    /**
     * @property steps today's steps (null: none recorded).
     * @property target the daily target.
     * @property rangeHigh the top of the 60+ range when the default 60+ target applies, else null.
     * @property ownGoal true when [target] is the user's own step goal.
     * @property days the last 7 days, today last; [week] their steps (null: none).
     */
    data class Data(val steps: Int?, val target: Int, val rangeHigh: Int?, val ownGoal: Boolean, val days: List<Day>, val week: List<Int?>) {
        /** True when there are no steps in the last 7 days. */
        val isEmpty: Boolean get() = week.all { it == null }
    }

    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY, Table.ACTIVITY, Table.GOAL, Table.SETTING)

    override suspend fun load(env: CardEnv): Data {
        val days = (WEEK - 1 downTo 0).map { env.day.plusDays(-it) }
        val range = DayRange(days.first(), days.last())
        val summary = env.db.summaries.days(range).mapNotNull { s -> s.steps?.let { s.day to it } }.toMap()
        val minutes = env.db.activity.totalsByDay(range, env.clock.zone()).associate { it.day to it.steps.toInt() }
        val week = days.map { summary[it] ?: minutes[it] }
        val own = env.db.goals.goalOn(GoalKind.STEPS, env.day)?.value
        val age = ActivityStatsLoader(env.db, env.clock).age()
        val older = own == null && age != null && age >= StepTargets.OLDER_AGE
        return Data(week.last(), own ?: StepTargets.defaultFor(age), if (older) StepTargets.OLDER_HIGH else null, own != null, days, week)
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(parent)

    private const val WEEK = 7

    private class Holder(parent: ViewGroup) : StatCardView<Data>(
        parent, R.layout.card_activity_steps_today,
        StatIds(R.id.st_tile, R.id.st_bar, R.id.st_bars, R.id.st_caption, R.id.st_note, R.id.st_empty),
    ) {
        override fun bind(data: Data) {
            val ctx = root.context
            showEmpty(data.isEmpty)
            if (data.isEmpty) return
            val steps = data.steps ?: 0
            val target = Fmt.number(data.target.toLong())
            tile.value = Fmt.number(steps.toLong())
            tile.unit = null
            tile.label = when {
                data.ownGoal -> ctx.getString(R.string.st_label_own, target)
                data.rangeHigh != null -> ctx.getString(R.string.st_label_range, target, Fmt.number((data.rangeHigh).toLong()))
                else -> ctx.getString(R.string.st_label, target)
            }
            progress(steps, data.target)
            caption.text = if (steps >= data.target) {
                ctx.getString(R.string.st_caption_done)
            } else {
                ctx.getString(R.string.st_caption_more, Fmt.number((data.target - steps).toLong()))
            }
            bars(ctx.getString(R.string.activity_steps), data.week.map { it ?: 0 }, weekdayLabels(data.days.map { it.yyyymmdd }), data.target)
            val present = data.week.filterNotNull()
            text(note, if (present.isEmpty()) null else ctx.getString(R.string.st_note, Fmt.number(present.average().roundToLong())))
            root.contentDescription = ctx.getString(R.string.st_description, tile.value, target)
        }
    }
}
