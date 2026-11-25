/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body.cards

import android.content.Context
import android.view.ViewGroup
import app.selfwatch.R
import app.selfwatch.activity.ActivityGoals
import app.selfwatch.body.BodyLoader
import app.selfwatch.body.BodyTrendLoader
import app.selfwatch.body.HeartCardLoader
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
import app.selfwatch.food.WaterGlass
import app.selfwatch.food.budget.EnergyBudget
import app.selfwatch.food.diary.WaterService
import app.selfwatch.me.GoalsFragment
import app.selfwatch.model.food.GoalKind
import app.selfwatch.ui.format.Units

/**
 * Body today (the first card on Body): every body goal at a glance, each as "done / goal" with a bar in
 * the status color: calories eaten against the day's target, calories burned against resting plus the
 * active-calories goal, water, the energy level now (0–100), last night's sleep and the weight trend
 * against the goal weight. Lines without data are left out (energy, sleep, weight).
 */
object BodyTodayCard : Card<BodyTodayCard.Data>("body_today", CardTab.BODY, R.string.card_body_today, R.string.card_body_today_summary) {

    /** The goal lines and the display units. */
    data class Data(val lines: List<GoalLine>, val units: Units)

    /** Sleep goal before one is set: 8 hours (the middle of the 7–9 h guide). */
    const val DEFAULT_SLEEP_MIN: Int = 8 * 60

    override val tables: Set<Table> = setOf(
        Table.DIARY, Table.DAY_SUMMARY, Table.WATER, Table.METRIC, Table.SLEEP_SESSION, Table.BODY, Table.GOAL, Table.SETTING,
    )

    override val defaultSize: CardSize get() = CardSize.LARGE

    override suspend fun load(env: CardEnv): Data {
        val db = env.db
        val today = env.clock.today()
        val budget = EnergyBudget(db, env.clock).forDay(today)
        val water = WaterService(db, env.clock).load(today)
        val energy = HeartCardLoader(db, env.clock).energy()?.takeIf { it.latestSec >= today.startSec(env.clock.zone()) }
        val sleep = BodyTrendLoader(db, env.clock).sleep().lastMin
        val weight = BodyLoader(db, env.clock).weight()
        val lines = buildList {
            add(GoalLine(GoalMetric.KCAL_EATEN, budget.eatenKcal, budget.goalKcal))
            add(GoalLine(GoalMetric.KCAL_BURNED, budget.restingKcal + budget.activeKcal, budget.restingKcal + ActivityGoals.activeKcal(db)))
            add(GoalLine(GoalMetric.WATER, water.totalMl, WaterGlass.goalMl(water.goalMl, env.units)))
            if (energy != null) add(GoalLine(GoalMetric.ENERGY, energy.latest, null))
            if (sleep != null) add(GoalLine(GoalMetric.SLEEP, sleep, db.goals.goalOn(GoalKind.SLEEP_MIN, today)?.value ?: DEFAULT_SLEEP_MIN))
            if (weight != null) add(GoalLine(GoalMetric.WEIGHT, weight.spark.lastOrNull() ?: weight.latestKgX100, weight.goalKgX100))
        }
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
            this(GoalLinesView(parent, R.string.card_body_today, { host.open(GoalsFragment()) }, R.id.body_today_card))

        override fun bind(data: Data) {
            lines.show(data.lines, data.units, root.context.getString(R.string.body_today_empty))
        }
    }
}
