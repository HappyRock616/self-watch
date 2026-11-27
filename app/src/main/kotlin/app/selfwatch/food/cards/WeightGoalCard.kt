/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.cards

import android.content.Context
import android.view.View
import android.view.ViewGroup
import app.selfwatch.R
import app.selfwatch.activity.ActivityWeek
import app.selfwatch.body.BodyUnits
import app.selfwatch.body.WeightMath
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.PictureFrame
import app.selfwatch.cards.SmallFace
import app.selfwatch.cards.TrendPicture
import app.selfwatch.cards.TrendPictures
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.food.budget.EnergyBudget
import app.selfwatch.me.GoalsFragment
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.TimeRange
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import app.selfwatch.ui.R as UiR

/**
 * Weight goal (Food; card design standard): the weight still to go as the number, tagged with the
 * recommended calories a day, over a trend line that runs from the first weigh-in (weekly trend) into
 * the projection to the goal, with the goal dashed and both ends dated. See [WeightGoalMath] for how the
 * calories are worked out; they are re-worked each Monday from what really happened. The data keeps the
 * weekly targets and BMI for TalkBack and the detail. Small form: the number and the line.
 */
object WeightGoalCard : Card<WeightGoalCard.Data>("weight_goal", CardTab.FOOD, R.string.card_weight_goal, R.string.card_weight_goal_summary) {

    /** Weeks of past targets shown. */
    const val HISTORY_WEEKS: Int = 6

    private const val TREND_DAYS = 90
    private const val BURN_DAYS = 21
    private const val MIN_ADAPTIVE_DAYS = 14
    private const val MIN_LOGGED_DAYS = 10
    private const val MIN_MEASURED_DAYS = 7
    private const val MAX_PROJECTION_WEEKS = 104
    private const val DAY_SEC = 86_400L
    private const val WEEK_SEC = 7 * DAY_SEC

    /**
     * The plan ([plan] null: no goal weight or no weigh-in; then [needsGoal] says which), the weigh-ins and
     * trend for the chart, the targets of past weeks ([history], oldest first), BMI now and at the goal.
     */
    data class Data(
        val plan: WeightPlan?,
        val currentX100: Int?,
        val goalX100: Int?,
        val goalDate: Day?,
        val weighIns: List<BodyMeasure>,
        val trend: List<Int>,
        val history: List<Pair<Day, Int>>,
        val bmiNow: Double?,
        val bmiGoal: Double?,
        val today: Day,
        val units: Units,
    ) {
        /** True when the goal weight is missing (else the weigh-ins are). */
        val needsGoal: Boolean get() = goalX100 == null
    }

    override val tables: Set<Table> = setOf(Table.BODY, Table.GOAL, Table.SETTING, Table.DIARY, Table.DAY_SUMMARY)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override suspend fun load(env: CardEnv): Data {
        val db = env.db
        val today = env.clock.today()
        val zone = env.clock.zone()
        val goalX100 = db.goals.goalOn(GoalKind.WEIGHT_KG_X100, today)?.value
        val goalDate = db.settings.getInt(WeightGoalMath.DATE_KEY)?.let { Day(it) }
        val weights = db.body.series(BodyKind.WEIGHT, DayRange(today.plusDays(-TREND_DAYS), today), zone).sortedBy { it.tSec }
        val trend = WeightMath.trend(LongArray(weights.size) { weights[it].tSec }, IntArray(weights.size) { weights[it].valueX100 }).map { it.roundToInt() }
        val height = db.settings.profile().heightCm
        val current = trend.lastOrNull()
        if (goalX100 == null || current == null) {
            return Data(null, current, goalX100, goalDate, weights, trend, emptyList(), current?.let { WeightGoalMath.bmi(it, height) }, goalX100?.let { WeightGoalMath.bmi(it, height) }, today, env.units)
        }
        val floor = EnergyBudget.floorFor(db.settings.profile().sex)
        val monday = ActivityWeek.start(today)
        suspend fun planAt(m: Day, from: Day, weightX100: Int): WeightPlan {
            val (burn, source) = burn(db, env, m, weights, trend)
            return WeightGoalMath.plan(weightX100, goalX100, from, goalDate, burn, source, floor)
        }
        val plan = planAt(monday, today, current)
        val history = (HISTORY_WEEKS - 1 downTo 1).mapNotNull { k ->
            val m = monday.plusDays(-k * ActivityWeek.DAYS)
            val at = trendAt(weights, trend, m.startSec(zone)) ?: return@mapNotNull null
            m to planAt(m, m, at).targetKcal
        } + (monday to plan.targetKcal)
        return Data(plan, current, goalX100, goalDate, weights, trend, history, WeightGoalMath.bmi(current, height), WeightGoalMath.bmi(goalX100, height), today, env.units)
    }

    /** The trend at the last weigh-in before [tSec], or null. */
    private fun trendAt(weights: List<BodyMeasure>, trend: List<Int>, tSec: Long): Int? = weights.indexOfLast { it.tSec < tSec }.takeIf { it >= 0 }?.let { trend[it] }

    /** The daily burn for the week starting [monday]: adaptive, else measured, else estimated. */
    private suspend fun burn(db: SelfWatchDb, env: CardEnv, monday: Day, weights: List<BodyMeasure>, trend: List<Int>): Pair<Int, BurnSource> {
        val zone = env.clock.zone()
        val window = DayRange(monday.plusDays(-BURN_DAYS), monday.plusDays(-1))
        val logged = db.diary.dailyKcalX10(window).values.filter { it > 0 }
        val endT = monday.startSec(zone)
        val startT = window.first.startSec(zone)
        val endTrend = trendAt(weights, trend, endT)
        val startIdx = weights.indexOfLast { it.tSec < startT + DAY_SEC }
        val endIdx = weights.indexOfLast { it.tSec < endT }
        if (logged.size >= MIN_LOGGED_DAYS && endTrend != null && startIdx >= 0 && endIdx > startIdx &&
            (weights[endIdx].tSec - weights[startIdx].tSec) >= MIN_ADAPTIVE_DAYS * DAY_SEC
        ) {
            val days = ((weights[endIdx].tSec - weights[startIdx].tSec) / DAY_SEC).toInt()
            val avg = logged.average() / 10.0
            return WeightGoalMath.adaptiveBurn(avg, trend[endIdx] - trend[startIdx], days) to BurnSource.ADAPTIVE
        }
        val measured = db.summaries.days(window).mapNotNull { s -> s.restingKcal?.takeIf { it > 0 }?.let { it + (s.activeKcal ?: 0) } }
        if (measured.size >= MIN_MEASURED_DAYS) return measured.average().roundToInt() to BurnSource.MEASURED
        val resting = EnergyBudget(db, env.clock).forDay(monday).restingKcal
        return (resting * WeightGoalMath.LIGHT_ACTIVITY).roundToInt() to BurnSource.ESTIMATED
    }

    override fun small(ctx: Context, data: Data): SmallFace? {
        val p = data.plan ?: return null
        val toGo = toGo(data) ?: return null
        return SmallFace(
            value = toGo,
            unit = BodyUnits.unit(BodyKind.WEIGHT, data.units),
            label = ctx.getString(R.string.d2_tag_kcal_a_day, Fmt.number(p.targetKcal.toLong())),
            status = if (p.onTime(data.goalDate)) CardStatus.GOOD else CardStatus.CLOSE,
            note = rateText(ctx, p, data),
        )
    }

    /** The weight still to go, signed, in display units ("−4.5"), or null without a goal and a trend. */
    fun toGo(data: Data): String? {
        val goal = data.goalX100 ?: return null
        val now = data.currentX100 ?: return null
        val d = goal - now
        val n = Fmt.number(BodyUnits.toDisplay(BodyKind.WEIGHT, abs(d), data.units), 1)
        return when {
            d < 0 -> "−$n"
            d > 0 -> "+$n"
            else -> n
        }
    }

    /**
     * The trend line: the weekly trend from the first weigh-in up to today, then the projection week by
     * week to the goal; the goal dashed; dated ends (first weigh-in, the day the goal is reached). Null
     * without a plan.
     */
    fun picture(ctx: Context, data: Data): TrendPicture? {
        val p = data.plan ?: return null
        val goal = data.goalX100 ?: return null
        val current = data.currentX100 ?: return null
        val w = data.weighIns
        if (w.isEmpty() || data.trend.size != w.size) return null
        val zone = ZoneId.systemDefault()
        val past = ArrayList<Int>()
        var sec = w.first().tSec
        val now = data.today.startSec(zone)
        while (sec < now) {
            val i = w.indexOfLast { it.tSec <= sec }
            if (i >= 0) past += data.trend[i]
            sec += WEEK_SEC
        }
        past += current
        val proj = WeightGoalMath.projection(current, p.ratePerWeekX100, goal, MAX_PROJECTION_WEEKS).drop(1)
        val values = (past + proj).map { BodyUnits.toDisplay(BodyKind.WEIGHT, it, data.units).toFloat() }
        if (values.size < 2) return null
        val end = p.reachDay ?: data.goalDate ?: data.today
        return TrendPicture.Line(
            values.toFloatArray(),
            TrendPictures.dayLabel(ctx, Day.of(w.first().tSec, zone)),
            Fmt.day(ctx, end.yyyymmdd, data.today.toLocalDate()),
            ChartPalette.of(ctx).primary,
            goal = BodyUnits.toDisplay(BodyKind.WEIGHT, goal, data.units).toFloat(),
        )
    }

    override fun createSmallGraphic(ctx: Context): View = PictureFrame(ctx, small = true)

    override fun bindSmallGraphic(graphic: View, data: Data) {
        (graphic as PictureFrame).show(picture(graphic.context, data))
    }

    /** "Lose 0.5 kg a week · goal by Mar 3" and the like. */
    fun rateText(ctx: Context, p: WeightPlan, data: Data): String {
        val goal = data.goalX100 ?: return ""
        if (p.ratePerWeekX100 == 0) return ctx.getString(R.string.weight_goal_keep, Fmt.weight(goal, data.units))
        val rate = Fmt.weight(abs(p.ratePerWeekX100), data.units)
        val head = ctx.getString(if (p.ratePerWeekX100 < 0) R.string.weight_goal_lose else R.string.weight_goal_gain, rate)
        val reach = p.reachDay?.let { ctx.getString(R.string.weight_goal_reach, Fmt.weight(goal, data.units), Fmt.day(ctx, it.yyyymmdd, data.today.toLocalDate())) }
        return listOfNotNull(head, reach).joinToString(" · ")
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<Data>(SwCard(parent.context)) {
        private val ctx = parent.context
        private val sw = root as SwCard
        private val tile = StatTile(ctx).apply { setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number) }
        private val line = PictureFrame(ctx, small = false).apply { id = R.id.weight_goal_chart }
        private val empty = EmptyState(ctx).apply {
            buttonText = ctx.getString(R.string.weight_goal_set)
            onButton = { host.open(GoalsFragment()) }
        }

        init {
            sw.id = R.id.weight_goal_card
            sw.title = ctx.getString(R.string.card_weight_goal)
            sw.isClickable = true
            sw.isFocusable = true
            sw.setOnClickListener { host.open(GoalsFragment()) }
            sw.addView(tile, Views.matchWrap(ctx))
            sw.addView(line, Views.matchWrap(ctx, 8))
            sw.addView(empty, Views.matchWrap(ctx))
        }

        override fun bind(data: Data) {
            val p = data.plan
            tile.visibility = if (p == null) View.GONE else View.VISIBLE
            empty.visibility = if (p == null) View.VISIBLE else View.GONE
            if (p == null) {
                line.show(null)
                empty.message = ctx.getString(if (data.needsGoal) R.string.weight_goal_need_goal else R.string.weight_goal_need_weight)
                sw.contentDescription = listOf(sw.title, empty.message).joinToString(". ")
                return
            }
            tile.value = toGo(data) ?: ctx.getString(R.string.dash)
            tile.unit = BodyUnits.unit(BodyKind.WEIGHT, data.units)
            tile.label = ctx.getString(R.string.d2_tag_kcal_a_day, Fmt.number(p.targetKcal.toLong()))
            line.show(picture(ctx, data))
            val why = listOfNotNull(
                ctx.getString(
                    when (p.source) {
                        BurnSource.ADAPTIVE -> R.string.weight_goal_source_adaptive
                        BurnSource.MEASURED -> R.string.weight_goal_source_measured
                        BurnSource.ESTIMATED -> R.string.weight_goal_source_estimated
                    },
                    Fmt.number(p.burnKcal.toLong()),
                ),
                if (p.capped) ctx.getString(R.string.weight_goal_capped) else null,
                if (p.floorApplied) ctx.getString(R.string.weight_goal_floor) else null,
            ).joinToString(" ")
            val bmi = data.bmiNow?.let { now ->
                data.bmiGoal?.let { g -> ctx.getString(R.string.weight_goal_bmi, Fmt.number(now, 1), Fmt.number(g, 1)) }
                    ?: ctx.getString(R.string.weight_goal_bmi_now, Fmt.number(now, 1))
            }
            sw.contentDescription = listOfNotNull(
                sw.title, "${tile.value} ${tile.unit} ${ctx.getString(R.string.d2_tag_to_go)}", tile.label, WeightGoalCard.rateText(ctx, p, data), why, bmi,
            ).joinToString(". ")
        }
    }
}
