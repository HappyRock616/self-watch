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
import app.selfwatch.body.GarminBands
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardView
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.TrendCard
import app.selfwatch.cards.TrendData
import app.selfwatch.db.Table
import app.selfwatch.model.food.Sex
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.TimeRange
import app.selfwatch.ui.charts.LevelColors
import app.selfwatch.ui.components.Band
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.RangeBarView
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.components.TrendLineView
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import java.time.ZoneId
import app.selfwatch.ui.R as UiR

/** Pure VO2 max arithmetic for the card (values ×10, as stored). */
object Vo2MaxMath {
    /** Days of history the card reads. */
    const val HISTORY_DAYS: Int = 180

    /** The change is measured over this many days ("Up 1.2 over 30 days"). */
    const val CHANGE_DAYS: Int = 30

    /**
     * One value per local day in [zone]: the day's last estimate. The watch can update more than once a
     * day (a run and a ride, or the metrics file after an activity file); the last is what it shows.
     */
    fun daily(samples: List<MetricSample>, zone: ZoneId): List<Pair<Day, Int>> {
        val byDay = sortedMapOf<Day, MetricSample>()
        for (s in samples) {
            val d = Day.of(s.tSec, zone)
            val prev = byDay[d]
            if (prev == null || s.tSec >= prev.tSec) byDay[d] = s
        }
        return byDay.map { (d, s) -> d to s.value }
    }

    /**
     * The latest value minus the one the watch showed [CHANGE_DAYS] before it (the last value on or
     * before that day); null without a value that old, so a fresh estimate shows no change.
     */
    fun change(daily: List<Pair<Day, Int>>): Int? {
        val (lastDay, last) = daily.lastOrNull() ?: return null
        val cutoff = lastDay.plusDays(-CHANGE_DAYS)
        val before = daily.lastOrNull { it.first <= cutoff } ?: return null
        return last - before.second
    }
}

/**
 * VO2 max (card design standard): the watch's latest estimate in ml/kg/min on a range bar of Garmin's ratings
 * for the user's age and sex (Superior … Poor; small form: the bar) and a trend line of the
 * last six months. Running first (also the single estimate of watches without a cycling one); a rider's
 * card shows cycling when there is no running value. With both, the note names the cycling value.
 * [TrendData.extras]: the cycling value when the card shows running (or null), then 1.0 when the card
 * shows cycling.
 */
object Vo2MaxCard : TrendCard("vo2_max", CardTab.ACTIVITY, R.string.card_vo2_max, R.string.card_vo2_max_summary) {
    override val tables: Set<Table> = setOf(Table.METRIC)

    override val needs: Set<DataKind> = setOf(DataKind.VO2_MAX)

    override suspend fun load(env: CardEnv): TrendData {
        val zone = env.clock.zone()
        val now = env.clock.nowSec()
        val range = TimeRange(now - Vo2MaxMath.HISTORY_DAYS * DAY_SEC, now + 1)
        val run = Vo2MaxMath.daily(env.db.metrics.series(MetricKind.VO2_MAX_RUNNING, range), zone)
        val ride = Vo2MaxMath.daily(env.db.metrics.series(MetricKind.VO2_MAX_CYCLING, range), zone)
        val cycling = run.isEmpty()
        val main = if (cycling) ride else run
        val last = main.lastOrNull() ?: return TrendData(null, units = env.units)
        val other = if (cycling) null else ride.lastOrNull()?.second?.div(TEN)
        val profile = env.db.settings.profile()
        val age = profile.birthYear?.let { env.clock.today().year - it }?.takeIf { it in 10..100 }
        return TrendData(
            latest = last.second / TEN,
            series = main.map { it.second / TEN },
            change = Vo2MaxMath.change(main)?.div(TEN),
            units = env.units,
            extras = listOf(other, if (cycling) 1.0 else 0.0, age?.toDouble(), profile.sex.ordinal.toDouble()),
        )
    }

    /** Garmin's rating (Superior … Poor) for the user's age and sex, or null without a birth year. */
    private fun band(data: TrendData) = data.latest?.let { v ->
        val age = data.extras.getOrNull(2)?.toInt() ?: return@let null
        val sex = Sex.entries[(data.extras.getOrNull(3) ?: Sex.UNSPECIFIED.ordinal.toDouble()).toInt()]
        GarminBands.vo2Max(v, age, sex)
    }

    override fun status(data: TrendData): CardStatus = band(data)?.status ?: CardStatus.NONE

    override fun format(ctx: Context, value: Double, units: Units): String = Fmt.number(value, 1)

    override fun unit(ctx: Context, units: Units): String = ctx.getString(R.string.unit_vo2_max)

    override fun label(ctx: Context, data: TrendData): String {
        val sport = ctx.getString(if (data.extras.getOrNull(1) == 1.0) R.string.vo2_max_cycling else R.string.vo2_max_running)
        return band(data)?.let { ctx.getString(R.string.vo2_max_label_band, sport, ctx.getString(it.name)) } ?: sport
    }

    override fun note(ctx: Context, data: TrendData): String? {
        val cyclingToo = data.extras.firstOrNull() ?: return super.note(ctx, data)
        return ctx.getString(R.string.vo2_max_cycling_note, format(ctx, cyclingToo, data.units))
    }

    override fun emptyMessage(ctx: Context): String = ctx.getString(R.string.vo2_max_empty)

    /** The user's age and sex from [TrendData.extras], or null without a birth year. */
    private fun ageSex(data: TrendData): Pair<Int, Sex>? {
        val age = data.extras.getOrNull(2)?.toInt() ?: return null
        val sex = Sex.entries[(data.extras.getOrNull(3) ?: Sex.UNSPECIFIED.ordinal.toDouble()).toInt()]
        return age to sex
    }

    /**
     * Shows [data] on a range bar of Garmin's ratings (Poor, Fair, Good, Excellent, Superior for the
     * user's age and sex, Poor in the "off" color up to Superior in the goal color); false without a
     * birth year (no ratings to draw).
     */
    private fun range(bar: RangeBarView, data: TrendData, ticks: Boolean): Boolean {
        val v = data.latest ?: return false
        val (age, sex) = ageSex(data) ?: return false
        val e = ActivityVisuals.vo2Edges(age, sex)
        val c = LevelColors.of(bar.context)
        val lo = e[0] - (e[1] - e[0]) * 2
        val hi = e[3] + (e[3] - e[2])
        bar.min = minOf(lo, v.toFloat() - 1f)
        bar.max = maxOf(hi, v.toFloat() + 1f)
        bar.bands = listOf(Band(e[0], c.scaleOff), Band(e[1], c.scaleClose), Band(e[2], c.scaleNeutral), Band(e[3], c.scaleGood), Band(bar.max, c.scaleGood))
        bar.value = v.toFloat()
        bar.ticks = if (ticks) e.map { it to Fmt.number(it.toDouble(), 1) } else emptyList()
        return true
    }

    override fun createSmallGraphic(ctx: Context): View = RangeBarView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun bindSmallGraphic(graphic: View, data: TrendData) {
        graphic.visibility = if (range(graphic as RangeBarView, data, ticks = false)) View.VISIBLE else View.GONE
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<TrendData> = Holder(parent)

    private class Holder(parent: ViewGroup) : CardView<TrendData>(CardView.inflate(parent, R.layout.card_activity_vo2_max)) {
        private val tile = find<StatTile>(R.id.vo2_tile)
        private val bar = find<RangeBarView>(R.id.vo2_range)
        private val trend = find<TrendLineView>(R.id.vo2_trend)
        private val empty = find<EmptyState>(R.id.vo2_empty)

        init {
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
        }

        override fun bind(data: TrendData) {
            val ctx = root.context
            val v = data.latest
            ActivityVisuals.show(v != null, tile, bar, trend)
            ActivityVisuals.show(v == null, empty)
            if (v == null) {
                root.contentDescription = empty.message
                return
            }
            val rating = band(data)?.let { ctx.getString(it.name) }
            ActivityVisuals.number(tile, format(ctx, v, data.units), unit(ctx, data.units), rating ?: label(ctx, data))
            ActivityVisuals.show(range(bar, data, ticks = true), bar)
            val s = data.series
            trend.values = s.map { it.toFloat() }.toFloatArray()
            trend.color = LevelColors.of(ctx).good
            trend.startLabel = s.firstOrNull()?.let { format(ctx, it, data.units) }
            trend.endLabel = s.lastOrNull()?.let { format(ctx, it, data.units) }
            ActivityVisuals.show(s.size >= 2, trend)
            root.contentDescription = ctx.getString(R.string.design_vo2_description, tile.value, label(ctx, data))
        }
    }

    private const val TEN = 10.0
    private const val DAY_SEC = 86_400L
}
