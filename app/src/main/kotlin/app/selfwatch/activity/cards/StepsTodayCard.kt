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
import android.widget.TextView
import app.selfwatch.R
import app.selfwatch.activity.ActivityGoals
import app.selfwatch.activity.TodayLoader
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardStatusColors
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.SystemClock
import app.selfwatch.service.live.LiveReading
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import app.selfwatch.ui.format.Units
import app.selfwatch.watch.LiveVitals
import kotlin.math.roundToLong

/**
 * Steps (C11, the "steps now" card): today's steps against the user's step goal, else 10,000, with
 * distance and active calories and the last 7 days as bars. While the card is on screen and the watch is
 * connected, the watch's live step count replaces the last sync's ([LiveVitals]). "Every extra 1,000
 * helps", never pass or fail. The stored id stays `steps_today`.
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

    override fun small(ctx: Context, data: Data): SmallFace? {
        if (data.isEmpty) return null
        val steps = data.steps ?: 0
        return SmallFace(
            value = Fmt.number(steps.toLong()),
            label = ctx.getString(R.string.small_of_steps, Fmt.number(data.target.toLong())),
            status = CardStatusColors.ofProgress(steps.toDouble(), data.target.toDouble()),
            note = if (steps >= data.target) {
                ctx.getString(R.string.small_target_reached)
            } else {
                ctx.getString(R.string.small_to_go, Fmt.number((data.target - steps).toLong()))
            },
            progress = if (data.target > 0) steps.toFloat() / data.target else null,
        )
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

    private class Holder(host: CardHost, parent: ViewGroup) : StatCardView<Data>(
        parent, R.layout.card_activity_steps_today,
        StatIds(R.id.st_tile, R.id.st_bar, R.id.st_bars, R.id.st_caption, R.id.st_note, R.id.st_empty),
    ) {
        private val extras: TextView = find(R.id.st_extras)
        private var stored: Data? = null
        private var live: LiveReading? = null

        init {
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
            val empty = data.isEmpty && now == null
            showEmpty(empty)
            extras.visibility = if (empty) View.GONE else View.VISIBLE
            if (empty) return
            val steps = now ?: 0
            val target = Fmt.number(data.target.toLong())
            tile.value = Fmt.number(steps.toLong())
            tile.unit = null
            val label = when {
                data.ownGoal -> ctx.getString(R.string.st_label_own, target)
                else -> ctx.getString(R.string.st_label, target)
            }
            tile.label = if (isLive) ctx.getString(R.string.card_line_pair, label, ctx.getString(R.string.st_live)) else label
            progress(steps, data.target)
            caption.text = if (steps >= data.target) {
                ctx.getString(R.string.st_caption_done)
            } else {
                ctx.getString(R.string.st_caption_more, Fmt.number((data.target - steps).toLong()))
            }
            val distance = data.distanceM?.let { Fmt.distance(it.toDouble(), data.units, labels = FmtLabels.from(ctx)) }
            text(
                extras,
                when {
                    distance != null && data.activeKcal != null -> ctx.getString(R.string.st_extras, distance, Fmt.number(data.activeKcal.toLong()))
                    else -> distance
                },
            )
            val week = data.week.dropLast(1).map { it ?: 0 } + steps
            bars(ctx.getString(R.string.activity_steps), week, weekdayLabels(data.days.map { it.yyyymmdd }), data.target)
            val present = data.week.filterNotNull()
            text(note, if (present.isEmpty()) null else ctx.getString(R.string.st_note, Fmt.number(present.average().roundToLong())))
            root.contentDescription = ctx.getString(R.string.st_description, tile.value, target)
        }
    }
}
