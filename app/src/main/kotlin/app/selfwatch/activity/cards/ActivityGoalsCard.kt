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
import app.selfwatch.activity.TodayLoader
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardStatusColors
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.CardVisuals
import app.selfwatch.cards.GoalLine
import app.selfwatch.cards.GoalMath
import app.selfwatch.cards.GoalMetric
import app.selfwatch.cards.GoalText
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.me.GoalsFragment
import app.selfwatch.model.time.DayRange
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.LevelColors
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.RingItem
import app.selfwatch.ui.components.RingRowView
import app.selfwatch.ui.components.RingView
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units

/**
 * Activity goals (the first card on Activity, card design standard): a row of goal rings, one per
 * activity goal with its value inside and met / short as color: steps today, active minutes this week
 * (intensity minutes: a vigorous minute counts twice), active calories today, workouts this week, active
 * days this week and distance today (floors join when a device sends them). Weeks start on Monday, like
 * the rest of Activity. Small form: one ring, goals on track. Opens Goals.
 */
object ActivityGoalsCard : Card<ActivityGoalsCard.Data>(
    "activity_goals", CardTab.ACTIVITY, R.string.card_activity_goals, R.string.card_activity_goals_summary,
) {

    /** The goal lines and the display units. */
    data class Data(val lines: List<GoalLine>, val units: Units)

    override val tables: Set<Table> = setOf(Table.ACTIVITY, Table.DAY_SUMMARY, Table.WORKOUT, Table.GOAL, Table.SETTING)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override suspend fun load(env: CardEnv): Data {
        val db = env.db
        val day = env.clock.today()
        val week = ActivityWeek.days(day)
        val stats = ActivityStatsLoader(db, env.clock)
        val perDay = stats.intensityPerDay(week.filter { it <= day })
        val weekGoal = ActivityGoals.activeMinWeek(db, day)
        val today = TodayLoader(db, env.clock)
        val workouts = stats.workouts(DayRange(week.first(), day)).size
        val lines = listOf(
            GoalLine(GoalMetric.STEPS, today.steps(day).today, ActivityGoals.steps(db, day)),
            GoalLine(GoalMetric.ACTIVE_MIN, perDay.sumOf { it ?: 0 }, weekGoal),
            GoalLine(GoalMetric.ACTIVE_KCAL, today.activeKcal(day).today, ActivityGoals.activeKcal(db)),
            GoalLine(GoalMetric.WORKOUTS, workouts, ActivityGoals.workoutsWeek(db)),
            GoalLine(GoalMetric.ACTIVE_DAYS, IntensityMath.activeDays(perDay, IntensityMath.activeDayMin(weekGoal)), ActivityGoals.activeDays(db)),
            GoalLine(GoalMetric.DISTANCE, today.distanceM(day).today, ActivityGoals.distanceM(db)),
            // Floors: no device sends them yet; the line joins when one does (DataKind.FLOORS).
        )
        return Data(lines, env.units)
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    override fun small(ctx: Context, data: Data): SmallFace? {
        val (good, judged) = GoalMath.onTrack(data.lines)
        if (judged == 0) return null
        return SmallFace(
            value = good.toString(),
            unit = ctx.getString(R.string.goal_lines_of_count, judged),
            label = ctx.getString(R.string.goal_lines_label),
            status = CardStatusColors.ofProgress(good.toDouble(), judged.toDouble(), closeShare = 0.5),
            progress = good.toFloat() / judged,
        )
    }

    override fun createSmallGraphic(ctx: Context): View = CardVisuals.smallRing(ctx)

    override val smallGraphicShowsValue: Boolean = true

    override fun bindSmallGraphic(graphic: View, data: Data) {
        val (good, judged) = GoalMath.onTrack(data.lines)
        (graphic as RingView).apply {
            progress = if (judged > 0) good.toFloat() / judged else 0f
            color = ActivityVisuals.goalColor(context, good, judged)
            centerText = good.toString()
            subText = context.getString(R.string.goal_lines_of_count, judged)
        }
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(GoalsFragment())
        return true
    }

    /** One ring per goal line: progress, met / short color, the value inside and a one-word label. */
    fun rings(ctx: Context, lines: List<GoalLine>, units: Units): List<RingItem> {
        val c = LevelColors.of(ctx)
        return lines.map { line ->
            val done = line.done
            val value = when {
                done == null -> ctx.getString(R.string.dash)
                line.metric == GoalMetric.WORKOUTS || line.metric == GoalMetric.ACTIVE_DAYS ->
                    ctx.getString(R.string.ring_value_of, Fmt.number(done.toLong()), Fmt.number((line.goal ?: 0).toLong()))
                line.metric == GoalMetric.DISTANCE -> distance(done, units)
                else -> compact(done)
            }
            val color = when {
                done == null -> c.track
                line.status == CardStatus.GOOD -> c.good
                else -> c.close
            }
            RingItem(line.progress ?: 0f, color, value, ctx.getString(ringLabel(line.metric, units)))
        }
    }

    /** 10,072 as "10.1k"; smaller numbers in full. */
    fun compact(v: Int): String = if (v >= COMPACT_FROM) Fmt.number(v / THOUSAND, 1) + "k" else Fmt.number(v.toLong())

    private fun distance(m: Int, units: Units): String = Fmt.number(if (units == Units.METRIC) m / THOUSAND else m / METRES_PER_MILE, 1)

    private fun ringLabel(m: GoalMetric, units: Units): Int = when (m) {
        GoalMetric.STEPS -> R.string.ring_steps
        GoalMetric.ACTIVE_MIN -> R.string.ring_active
        GoalMetric.ACTIVE_KCAL -> R.string.ring_calories
        GoalMetric.WORKOUTS -> R.string.ring_workouts
        GoalMetric.ACTIVE_DAYS -> R.string.ring_days
        GoalMetric.DISTANCE -> if (units == Units.METRIC) R.string.ring_km else R.string.ring_mi
        else -> m.label
    }

    private const val COMPACT_FROM = 10_000
    private const val THOUSAND = 1_000.0
    private const val METRES_PER_MILE = 1_609.344

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<Data>(SwCard(parent.context)) {
        private val ctx = parent.context
        private val row = RingRowView(ctx).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val empty = EmptyState(ctx).apply { message = ctx.getString(R.string.activity_goals_empty) }

        init {
            val card = root as SwCard
            card.id = R.id.activity_goals_card
            card.title = ctx.getString(R.string.card_activity_goals)
            card.addView(row, Views.matchWrap(ctx, 4))
            card.addView(empty, Views.matchWrap(ctx))
            card.isClickable = true
            card.isFocusable = true
            card.setOnClickListener { host.open(GoalsFragment()) }
        }

        override fun bind(data: Data) {
            val has = data.lines.any { it.done != null }
            ActivityVisuals.show(has, row)
            ActivityVisuals.show(!has, empty)
            if (!has) {
                root.contentDescription = listOf(ctx.getString(R.string.card_activity_goals), empty.message).joinToString(". ")
                return
            }
            row.items = rings(ctx, data.lines, data.units)
            val (good, judged) = GoalMath.onTrack(data.lines)
            val spoken = data.lines.joinToString(". ") { line ->
                listOfNotNull(ctx.getString(line.metric.label), GoalText.doneOfGoal(ctx, line, data.units), CardStatusColors.spoken(ctx, line.status))
                    .joinToString(", ")
            }
            root.contentDescription = listOf(
                ctx.getString(R.string.card_activity_goals),
                ctx.resources.getQuantityString(R.plurals.goal_lines_on_track, judged, good, judged),
                spoken,
            ).joinToString(". ")
        }
    }
}
