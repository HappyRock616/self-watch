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
import app.selfwatch.activity.ActivityStatsLoader
import app.selfwatch.activity.ActivityWeek
import app.selfwatch.activity.LongSession
import app.selfwatch.activity.LongSessionCheck
import app.selfwatch.activity.WorkoutListFragment
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.ui.charts.LevelColors
import app.selfwatch.ui.components.BarsView
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import app.selfwatch.ui.format.Units
import java.util.Locale
import app.selfwatch.ui.R as UiR

/**
 * Workout time this week (C20, card design standard): workout time this week and the 5 weeks before as
 * bars (this week in the goal color). The long-session check ([LongSessionCheck]: a run, walk or hike
 * in the last 7 days more than 10% longer than the longest of its kind in the 30 days before) is still
 * loaded for the detail screens; the card itself carries no notes. Small form: the bars. Opens Workouts.
 */
object WeeklyVolumeCard : Card<WeeklyVolumeCard.Data>("weekly_volume", CardTab.ACTIVITY, R.string.card_weekly_volume, R.string.card_weekly_volume_summary) {

    /** Weeks shown, this week last. */
    const val WEEKS: Int = 6

    /**
     * @property weekStarts the Mondays of the weeks shown, oldest first.
     * @property durationsSec workout time per week; [distancesM] workout distance per week.
     * @property checked true when a run, walk or hike in the last 7 days was checked.
     * @property longSession the flagged session, or null.
     */
    data class Data(
        val weekStarts: List<Day>,
        val durationsSec: List<Int>,
        val distancesM: List<Int>,
        val checked: Boolean,
        val longSession: LongSession?,
        val units: Units,
    ) {
        /** True without any workout in the weeks shown. */
        val isEmpty: Boolean get() = durationsSec.all { it == 0 }
    }

    override val tables: Set<Table> = setOf(Table.WORKOUT)

    override suspend fun load(env: CardEnv): Data {
        val loader = ActivityStatsLoader(env.db, env.clock)
        val thisWeek = ActivityWeek.start(env.day)
        val starts = (WEEKS - 1 downTo 0).map { thisWeek.plusDays(-ActivityWeek.DAYS * it) }
        val lookback = env.day.plusDays(-(LongSessionCheck.WINDOW_DAYS + LongSessionCheck.RECENT_DAYS))
        val workouts = loader.workouts(DayRange(minOf(starts.first(), lookback), env.day))
        val zone = env.clock.zone()
        val time = IntArray(WEEKS)
        val dist = IntArray(WEEKS)
        for (w in workouts) {
            val i = starts.indexOfLast { it <= Day.of(w.startSec, zone) }
            if (i < 0 || Day.of(w.startSec, zone) < starts.first()) continue
            time[i] += w.durationSec
            dist[i] += w.distanceM ?: 0
        }
        val now = env.clock.nowSec()
        val checked = workouts.any { LongSessionCheck.family(it.sport) != null && it.startSec >= now - LongSessionCheck.RECENT_DAYS * DAY_SEC }
        return Data(starts, time.toList(), dist.toList(), checked, LongSessionCheck.check(workouts, now), env.units)
    }

    override val needs: Set<DataKind> = setOf(DataKind.WORKOUTS)

    override fun small(ctx: Context, data: Data): SmallFace? {
        if (data.isEmpty) return null
        return SmallFace(
            value = Fmt.duration((data.durationsSec.lastOrNull() ?: 0).toLong(), Locale.getDefault(), FmtLabels.from(ctx)),
            label = ctx.getString(R.string.wv_label),
        )
    }

    override fun createSmallGraphic(ctx: Context): View = ActivityVisuals.smallBars(ctx)

    override fun bindSmallGraphic(graphic: View, data: Data) {
        bars(graphic as BarsView, data)
        // Six date labels don't fit half a card: the small bars go without them.
        graphic.labels = emptyList()
    }

    /** Minutes per week, oldest first, this week in the goal color. */
    private fun bars(chart: BarsView, data: Data) {
        val ctx = chart.context
        val c = LevelColors.of(ctx)
        val last = data.durationsSec.size - 1
        ActivityVisuals.weekBars(
            chart, ctx.getString(R.string.card_weekly_volume), data.durationsSec.map { it / MINUTE_SEC },
            StatCardView.weekLabels(data.weekStarts.map { it.yyyymmdd }), null,
        ) { g, _ -> if (g == last) c.good else c.neutral }
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(WorkoutListFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    private const val DAY_SEC = 86_400L
    private const val MINUTE_SEC = 60

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_activity_weekly_volume)) {
        private val tile = find<StatTile>(R.id.wv_tile)
        private val chart = find<BarsView>(R.id.wv_bars)
        private val empty = find<EmptyState>(R.id.wv_empty)

        init {
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
            root.setOnClickListener { host.open(WorkoutListFragment()) }
        }

        override fun bind(data: Data) {
            val ctx = root.context
            ActivityVisuals.show(!data.isEmpty, tile, chart)
            ActivityVisuals.show(data.isEmpty, empty)
            if (data.isEmpty) {
                root.contentDescription = empty.message
                return
            }
            val time = Fmt.duration((data.durationsSec.lastOrNull() ?: 0).toLong(), Locale.getDefault(), FmtLabels.from(ctx))
            ActivityVisuals.number(tile, time, null, ctx.getString(R.string.tag_this_week))
            bars(chart, data)
            root.contentDescription = ctx.getString(R.string.design_weekly_volume_description, time)
        }
    }
}
