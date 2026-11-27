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
import app.selfwatch.activity.RecoveryHrLoader
import app.selfwatch.activity.RecoveryInfo
import app.selfwatch.activity.WorkoutDetailFragment
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.ui.charts.LevelColors
import app.selfwatch.ui.components.BarsView
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.R as UiR

/**
 * Heart-rate recovery (card design standard), as Garmin defines it: how far the heart rate fell 2
 * minutes after the latest workout with readings after it (else the 1-minute drop), and the last 10
 * workouts' drops as bars, the latest in the goal color (method and limits:
 * [app.selfwatch.activity.RecoveryHrMath]). Always against the user's own earlier workouts. Small form:
 * the bars. Opens the workout.
 */
object RecoveryHrCard : Card<RecoveryInfo>("recovery_hr", CardTab.ACTIVITY, R.string.card_recovery_hr, R.string.card_recovery_hr_summary) {
    override val tables: Set<Table> = setOf(Table.WORKOUT, Table.METRIC)

    override suspend fun load(env: CardEnv): RecoveryInfo = RecoveryHrLoader(env.db).load()

    override val needs: Set<DataKind> = setOf(DataKind.WORKOUTS)

    override fun small(ctx: Context, data: RecoveryInfo): SmallFace? {
        val r = data.latest ?: return null
        if (data.workout == null) return null
        val drop = r.twoMinDrop ?: r.oneMinDrop ?: return null
        val mean = data.trendMean
        // Against the user's own recent workouts: as big a drop as usual or bigger is on track.
        val status = when {
            mean == null || data.trend.size < 2 -> CardStatus.NONE
            drop >= mean -> CardStatus.GOOD
            else -> CardStatus.CLOSE
        }
        return SmallFace(
            value = dropText(drop),
            label = ctx.getString(if (r.twoMinDrop != null) R.string.tag_two_min else R.string.tag_one_min),
            unit = ctx.getString(R.string.unit_bpm),
            status = status,
        )
    }

    override fun createSmallGraphic(ctx: Context): View = ActivityVisuals.smallBars(ctx)

    override fun bindSmallGraphic(graphic: View, data: RecoveryInfo) = bars(graphic as BarsView, data)

    /** The recent drops as bars, oldest first, the latest in the goal color. */
    private fun bars(chart: BarsView, data: RecoveryInfo) {
        val ctx = chart.context
        val c = LevelColors.of(ctx)
        val last = data.trend.size - 1
        val dot = ctx.getString(R.string.mark_workout_dot)
        ActivityVisuals.weekBars(
            chart, ctx.getString(R.string.card_recovery_hr), data.trend.map { it.coerceAtLeast(0) }, List(data.trend.size) { dot }, null,
        ) { g, _ -> if (g == last) c.good else c.neutral }
    }

    /** A drop as shown on the card: a fall is "−24", a rise "+3". */
    private fun dropText(bpm: Int): String = if (bpm >= 0) "−$bpm" else "+${-bpm}"

    override fun createView(host: CardHost, parent: ViewGroup): CardView<RecoveryInfo> = Holder(host, parent)

    private class Holder(private val host: CardHost, parent: ViewGroup) : CardView<RecoveryInfo>(CardView.inflate(parent, R.layout.card_activity_recovery_hr)) {
        private val tile = find<StatTile>(R.id.rhr_tile)
        private val chart = find<BarsView>(R.id.rhr_bars)
        private val empty = find<EmptyState>(R.id.rhr_empty)

        init {
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
        }

        override fun bind(data: RecoveryInfo) {
            val ctx = root.context
            val r = data.latest
            val w = data.workout
            val shown = r != null && w != null
            ActivityVisuals.show(shown, tile)
            ActivityVisuals.show(shown && data.trend.size >= 2, chart)
            ActivityVisuals.show(!shown, empty)
            if (r == null || w == null) {
                empty.message = ctx.getString(if (w == null) R.string.rhr_empty_none else R.string.rhr_empty_no_readings)
                root.contentDescription = empty.message
                root.setOnClickListener(null)
                root.isClickable = false
                return
            }
            root.setOnClickListener { host.open(WorkoutDetailFragment.newInstance(w.id)) }
            val tag = ctx.getString(if (r.twoMinDrop != null) R.string.tag_two_min else R.string.tag_one_min)
            ActivityVisuals.number(tile, dropText(r.twoMinDrop ?: r.oneMinDrop ?: 0), ctx.getString(R.string.unit_bpm), tag)
            bars(chart, data)
            root.contentDescription = ctx.getString(R.string.design_recovery_hr_description, tile.value, tag)
        }
    }
}
