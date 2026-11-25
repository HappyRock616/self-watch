/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity.cards

import android.content.Context
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
import app.selfwatch.cards.CardStatusColors
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.GoalLine
import app.selfwatch.cards.GoalLinesView
import app.selfwatch.cards.GoalMath
import app.selfwatch.cards.GoalMetric
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.me.GoalsFragment
import app.selfwatch.model.time.DayRange
import app.selfwatch.ui.format.Units

/**
 * Activity goals (the first card on Activity): every activity goal at a glance as "done / goal" with a
 * bar: steps today, active minutes this week (intensity minutes: a vigorous minute counts twice), active
 * calories today, workouts this week, active days this week, distance today and, on watches with a
 * barometer, floors. Weeks start on Monday, like the rest of Activity.
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

    private class Holder private constructor(private val lines: GoalLinesView) : CardView<Data>(lines.root) {
        constructor(host: CardHost, parent: ViewGroup) :
            this(GoalLinesView(parent, R.string.card_activity_goals, { host.open(GoalsFragment()) }, R.id.activity_goals_card))

        override fun bind(data: Data) {
            lines.show(data.lines, data.units, root.context.getString(R.string.activity_goals_empty))
        }
    }
}
