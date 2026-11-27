/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body.cards

import android.content.Context
import android.view.View
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
import app.selfwatch.cards.CardVisuals
import app.selfwatch.cards.GoalLine
import app.selfwatch.cards.GoalMath
import app.selfwatch.cards.GoalMetric
import app.selfwatch.cards.GoalRule
import app.selfwatch.cards.GoalText
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.food.WaterGlass
import app.selfwatch.food.budget.EnergyBudget
import app.selfwatch.food.diary.WaterService
import app.selfwatch.me.GoalsFragment
import app.selfwatch.model.food.GoalKind
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.RingItem
import app.selfwatch.ui.components.RingRowView
import app.selfwatch.ui.components.RingView
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Units
import java.util.Locale
import kotlin.math.abs

/**
 * Body today (the first card on Body, card design standard): a row of mini goal rings, each with its value
 * inside and one word under it, in the status color: calories eaten against the day's target, calories
 * burned against resting plus the active-calories goal, water glasses, the energy level now (0–100), last
 * night's sleep and the weight trend against the goal weight. Rings without data are left out (energy,
 * sleep, weight). Small form: one ring of the goals on track. Opens Goals.
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

    override fun createSmallGraphic(ctx: Context): View = CardVisuals.smallRing(ctx)

    override val smallGraphicShowsValue: Boolean = true

    override fun bindSmallGraphic(graphic: View, data: Data) {
        val (good, judged) = GoalMath.onTrack(data.lines)
        if (judged == 0) return
        val ctx = graphic.context
        (graphic as RingView).apply {
            progress = good.toFloat() / judged
            color = CardStatusColors.of(ctx, CardStatusColors.ofProgress(good.toDouble(), judged.toDouble(), closeShare = 0.5))
                ?: ChartPalette.of(ctx).primary
            centerText = ctx.getString(R.string.body1_of, good, judged)
            subText = ctx.getString(R.string.body1_on_track)
        }
    }

    /** One ring per goal line: progress, status color, a short value and one word. */
    fun rings(ctx: Context, lines: List<GoalLine>, units: Units): List<RingItem> {
        val palette = ChartPalette.of(ctx)
        return lines.map { line ->
            val color = CardStatusColors.of(ctx, line.status) ?: palette.series(2)
            RingItem(progress(line), color, shortValue(ctx, line, units), ctx.getString(word(line.metric)))
        }
    }

    /** How full a ring is: the goal share, or for weight how near the trend is to the goal. */
    fun progress(line: GoalLine): Float {
        line.progress?.let { return it }
        val done = line.done ?: return 0f
        val goal = line.goal ?: return if (line.metric.rule == GoalRule.TARGET) 1f else 0f
        if (goal <= 0) return 0f
        return (1f - abs(done - goal).toFloat() / goal / WEIGHT_FULL_SHARE).coerceIn(0f, 1f)
    }

    /** The value inside a ring: "982", "3/8", "7:04", "182.1". */
    fun shortValue(ctx: Context, line: GoalLine, units: Units): String {
        val done = line.done ?: return ctx.getString(R.string.dash)
        return when (line.metric) {
            GoalMetric.WATER -> ctx.getString(
                R.string.body1_of,
                WaterGlass.glasses(done, units),
                WaterGlass.goalGlasses(line.goal ?: WaterGlass.goalMl(null, units), units),
            )
            GoalMetric.SLEEP -> String.format(Locale.getDefault(), "%d:%02d", done / MINUTES, done % MINUTES)
            else -> GoalText.value(ctx, line.metric, done, units)
        }
    }

    private fun word(metric: GoalMetric): Int = when (metric) {
        GoalMetric.KCAL_EATEN -> R.string.body1_ring_eaten
        GoalMetric.KCAL_BURNED -> R.string.body1_ring_burned
        GoalMetric.WATER -> R.string.body1_ring_water
        GoalMetric.ENERGY -> R.string.body1_ring_energy
        GoalMetric.SLEEP -> R.string.body1_ring_sleep
        GoalMetric.WEIGHT -> R.string.body1_ring_weight
        else -> metric.label
    }

    private const val MINUTES = 60

    /** Off the goal weight by this share or more draws an empty ring. */
    private const val WEIGHT_FULL_SHARE = 0.1f

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<Data>(SwCard(parent.context)) {
        private val ctx = parent.context
        private val row = RingRowView(ctx).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val empty = EmptyState(ctx)

        init {
            val sw = root as SwCard
            sw.id = R.id.body_today_card
            sw.title = ctx.getString(R.string.card_body_today)
            sw.actionText = ctx.getString(R.string.goal_lines_edit)
            sw.onAction = { host.open(GoalsFragment()) }
            sw.isClickable = true
            sw.isFocusable = true
            sw.setOnClickListener { host.open(GoalsFragment()) }
            sw.addView(row, Views.matchWrap(ctx, 4))
            sw.addView(empty, Views.matchWrap(ctx))
        }

        override fun bind(data: Data) {
            val lines = data.lines
            CardVisuals.show(row, lines.isNotEmpty())
            CardVisuals.show(empty, lines.isEmpty())
            if (lines.isEmpty()) {
                empty.message = ctx.getString(R.string.body_today_empty)
                root.contentDescription = listOf(ctx.getString(R.string.card_body_today), empty.message).joinToString(". ")
                return
            }
            row.items = rings(ctx, lines, data.units)
            val (good, judged) = GoalMath.onTrack(lines)
            val spoken = lines.map { line ->
                listOfNotNull(ctx.getString(line.metric.label), GoalText.doneOfGoal(ctx, line, data.units), CardStatusColors.spoken(ctx, line.status))
                    .joinToString(", ")
            }
            root.contentDescription = (
                listOf(ctx.getString(R.string.card_body_today), ctx.resources.getQuantityString(R.plurals.goal_lines_on_track, judged, good, judged)) + spoken
                ).joinToString(". ")
        }
    }
}
