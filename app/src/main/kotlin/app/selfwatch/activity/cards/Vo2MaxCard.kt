/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity.cards

import android.content.Context
import app.selfwatch.R
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.TrendCard
import app.selfwatch.cards.TrendData
import app.selfwatch.db.Table
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.TimeRange
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import java.time.ZoneId

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
 * VO2 max: the watch's latest estimate in ml/kg/min with its change over 30 days and a sparkline of the
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
        return TrendData(
            latest = last.second / TEN,
            series = main.map { it.second / TEN },
            change = Vo2MaxMath.change(main)?.div(TEN),
            units = env.units,
            extras = listOf(other, if (cycling) 1.0 else 0.0),
        )
    }

    override fun format(ctx: Context, value: Double, units: Units): String = Fmt.number(value, 1)

    override fun unit(ctx: Context, units: Units): String = ctx.getString(R.string.unit_vo2_max)

    override fun label(ctx: Context, data: TrendData): String =
        ctx.getString(if (data.extras.getOrNull(1) == 1.0) R.string.vo2_max_cycling else R.string.vo2_max_running)

    override fun note(ctx: Context, data: TrendData): String? {
        val cyclingToo = data.extras.firstOrNull() ?: return super.note(ctx, data)
        return ctx.getString(R.string.vo2_max_cycling_note, format(ctx, cyclingToo, data.units))
    }

    override fun emptyMessage(ctx: Context): String = ctx.getString(R.string.vo2_max_empty)

    private const val TEN = 10.0
    private const val DAY_SEC = 86_400L
}
