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
import app.selfwatch.cards.SmallFace
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
import app.selfwatch.ui.charts.Axis
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.charts.DotTrendLayer
import app.selfwatch.ui.charts.LineLayer
import app.selfwatch.ui.charts.OverlayChartView
import app.selfwatch.ui.components.BarSeries
import app.selfwatch.ui.components.BarsView
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
 * Weight goal (Food): the recommended calories a day to reach the goal weight by the goal date and how
 * that number changed over the last weeks; the weight trend with the projected line to the goal; BMI now
 * and at the goal. See [WeightGoalMath] for how the number is worked out; it is re-worked each Monday
 * from what really happened.
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
        return SmallFace(
            value = Fmt.number(p.targetKcal.toLong()),
            unit = ctx.getString(R.string.unit_kcal),
            label = ctx.getString(R.string.weight_goal_small_label),
            status = if (p.onTime(data.goalDate)) CardStatus.GOOD else CardStatus.CLOSE,
            note = rateText(ctx, p, data),
        )
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
        private val rate = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body)
        private val why = Views.caption(ctx)
        private val chart = OverlayChartView(ctx).apply { id = R.id.weight_goal_chart }
        private val historyLabel = Views.caption(ctx, ctx.getString(R.string.weight_goal_history))
        private val history = BarsView(ctx).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val bmi = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body)
        private val empty = EmptyState(ctx).apply {
            buttonText = ctx.getString(R.string.weight_goal_set)
            onButton = { host.open(GoalsFragment()) }
        }

        init {
            sw.id = R.id.weight_goal_card
            sw.title = ctx.getString(R.string.card_weight_goal)
            sw.actionText = ctx.getString(R.string.goal_lines_edit)
            sw.onAction = { host.open(GoalsFragment()) }
            sw.addView(tile, Views.matchWrap(ctx))
            sw.addView(rate, Views.matchWrap(ctx, 4))
            sw.addView(why, Views.matchWrap(ctx, 4))
            sw.addView(chart, Views.matchWrap(ctx, 8).apply { height = ctx.resources.getDimensionPixelSize(R.dimen.weight_goal_chart) })
            sw.addView(historyLabel, Views.matchWrap(ctx, 8))
            sw.addView(history, Views.matchWrap(ctx, 4).apply { height = ctx.resources.getDimensionPixelSize(R.dimen.trend_card_bars) })
            sw.addView(bmi, Views.matchWrap(ctx, 8))
            sw.addView(empty, Views.matchWrap(ctx))
        }

        override fun bind(data: Data) {
            val p = data.plan
            for (v in listOf(tile, rate, why, chart, historyLabel, history)) v.visibility = if (p == null) View.GONE else View.VISIBLE
            empty.visibility = if (p == null) View.VISIBLE else View.GONE
            bmi.visibility = if (data.bmiNow == null) View.GONE else View.VISIBLE
            bmi.text = data.bmiNow?.let { now ->
                data.bmiGoal?.let { g -> ctx.getString(R.string.weight_goal_bmi, Fmt.number(now, 1), Fmt.number(g, 1)) }
                    ?: ctx.getString(R.string.weight_goal_bmi_now, Fmt.number(now, 1))
            }
            if (p == null) {
                empty.message = ctx.getString(if (data.needsGoal) R.string.weight_goal_need_goal else R.string.weight_goal_need_weight)
                sw.contentDescription = listOf(sw.title, empty.message).joinToString(". ")
                return
            }
            tile.value = Fmt.number(p.targetKcal.toLong())
            tile.unit = ctx.getString(R.string.unit_kcal)
            tile.label = ctx.getString(R.string.weight_goal_a_day)
            rate.text = WeightGoalCard.rateText(ctx, p, data)
            why.text = listOfNotNull(
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
            showChart(data, p)
            history.labels = data.history.map { (d, _) -> Fmt.day(ctx, d.yyyymmdd, data.today.toLocalDate()) }
            history.series = listOf(BarSeries(ctx.getString(R.string.weight_goal_history), ChartPalette.of(ctx).primary, data.history.map { it.second }.toIntArray()))
            sw.contentDescription = listOfNotNull(sw.title, "${tile.value} ${tile.unit} ${tile.label}", rate.text, why.text, bmi.text.takeIf { bmi.visibility == View.VISIBLE }).joinToString(". ")
        }

        private fun showChart(data: Data, p: WeightPlan) {
            val zone = ZoneId.systemDefault()
            fun display(x100: Int): Int = (BodyUnits.toDisplay(BodyKind.WEIGHT, x100, data.units) * SCALE).roundToInt()
            val palette = ChartPalette.of(ctx)
            val w = data.weighIns
            val now = data.today.startSec(zone)
            val goal = data.goalX100 ?: return
            val proj = WeightGoalMath.projection(data.currentX100 ?: return, p.ratePerWeekX100, goal, MAX_PROJECTION_WEEKS)
            val unit = BodyUnits.unit(BodyKind.WEIGHT, data.units)
            val layers = mutableListOf<app.selfwatch.ui.charts.ChartLayer>(
                DotTrendLayer(
                    "weight", ctx.getString(R.string.weight_goal_trend), palette.primary, Axis.LEFT,
                    LongArray(w.size) { w[it].tSec }, IntArray(w.size) { display(w[it].valueX100) },
                    trend = IntArray(data.trend.size) { display(data.trend[it]) },
                    goal = display(goal), scale = SCALE, unit = unit, lockedOn = true,
                ),
            )
            if (proj.size >= 2) {
                layers += LineLayer(
                    "projection", ctx.getString(R.string.weight_goal_projection), palette.secondary, Axis.LEFT,
                    LongArray(proj.size) { now + it * WEEK_SEC }, IntArray(proj.size) { display(proj[it]) },
                    scale = SCALE, gapSec = 2 * WEEK_SEC, unit = unit,
                )
            }
            val start = w.firstOrNull()?.tSec ?: now
            chart.range = TimeRange(start, maxOf(now + DAY_SEC, now + (proj.size - 1) * WEEK_SEC + DAY_SEC))
            chart.layers = layers
        }
    }

    private const val SCALE = 100
}
