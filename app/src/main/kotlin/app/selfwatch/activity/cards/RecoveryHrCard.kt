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
import android.widget.TextView
import app.selfwatch.R
import app.selfwatch.activity.RecoveryHrLoader
import app.selfwatch.activity.RecoveryInfo
import app.selfwatch.activity.Sports
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
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.Sparkline
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import java.time.Instant
import java.time.ZoneId
import app.selfwatch.ui.R as UiR

/**
 * Heart-rate recovery: how far the heart rate fell in the first minute (and the first two) after the latest
 * workout with readings after it, and the last 10 workouts' 1-minute drops as a trend (method and limits:
 * [app.selfwatch.activity.RecoveryHrMath]). "A bigger drop in the first minute usually means better
 * fitness", always against the user's own earlier workouts. Opens the workout.
 */
object RecoveryHrCard : Card<RecoveryInfo>("recovery_hr", CardTab.ACTIVITY, R.string.card_recovery_hr, R.string.card_recovery_hr_summary) {

    override val tables: Set<Table> = setOf(Table.WORKOUT, Table.METRIC)

    override suspend fun load(env: CardEnv): RecoveryInfo = RecoveryHrLoader(env.db).load()

    override val needs: Set<DataKind> = setOf(DataKind.WORKOUTS)

    override fun small(ctx: Context, data: RecoveryInfo): SmallFace? {
        val r = data.latest ?: return null
        if (data.workout == null) return null
        val drop = r.oneMinDrop ?: return null
        val mean = data.trendMean
        // Against the user's own recent workouts: as big a drop as usual or bigger is on track.
        val status = when {
            mean == null || data.trend.size < 2 -> CardStatus.NONE
            drop >= mean -> CardStatus.GOOD
            else -> CardStatus.CLOSE
        }
        return SmallFace(
            value = dropText(drop),
            label = ctx.getString(R.string.small_drop_label),
            unit = ctx.getString(R.string.unit_bpm),
            status = status,
            note = mean?.takeIf { data.trend.size >= 2 }?.let { ctx.getString(R.string.small_recent_avg, dropText(it)) },
        )
    }

    /** A drop as shown on the card: a fall is "−24", a rise "+3". */
    private fun dropText(bpm: Int): String = if (bpm >= 0) "−$bpm" else "+${-bpm}"

    override fun createView(host: CardHost, parent: ViewGroup): CardView<RecoveryInfo> = Holder(host, parent)

    private class Holder(private val host: CardHost, parent: ViewGroup) : CardView<RecoveryInfo>(CardView.inflate(parent, R.layout.card_activity_recovery_hr)) {
        private val tile = find<StatTile>(R.id.rhr_tile)
        private val two = find<TextView>(R.id.rhr_two)
        private val workout = find<TextView>(R.id.rhr_workout)
        private val spark = find<Sparkline>(R.id.rhr_spark)
        private val trend = find<TextView>(R.id.rhr_trend)
        private val explain = find<TextView>(R.id.rhr_explain)
        private val tip = find<TextView>(R.id.rhr_tip)
        private val empty = find<EmptyState>(R.id.rhr_empty)

        init {
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
            spark.color = ChartPalette.of(root.context).primary
            explain.setText(R.string.rhr_explain)
            tip.setText(R.string.rhr_tip)
        }

        override fun bind(data: RecoveryInfo) {
            val ctx = root.context
            val r = data.latest
            val w = data.workout
            for (v in listOf(tile, two, workout, spark, trend, explain)) v.visibility = if (r == null) View.GONE else View.VISIBLE
            empty.visibility = if (r == null) View.VISIBLE else View.GONE
            if (r == null || w == null) {
                empty.message = ctx.getString(if (w == null) R.string.rhr_empty_none else R.string.rhr_empty_no_readings)
                root.contentDescription = empty.message
                root.setOnClickListener(null)
                root.isClickable = false
                return
            }
            root.setOnClickListener { host.open(WorkoutDetailFragment.newInstance(w.id)) }
            tile.value = drop(r.oneMinDrop ?: 0)
            tile.unit = null
            tile.label = ctx.getString(R.string.rhr_label)
            two.text = r.twoMinDrop?.let { ctx.getString(R.string.rhr_two_min, drop(it)) }
            two.visibility = if (r.twoMinDrop == null) View.GONE else View.VISIBLE
            val day = Fmt.day(ctx, Fmt.dateToDay(Instant.ofEpochSecond(w.startSec).atZone(ZoneId.systemDefault()).toLocalDate()))
            workout.text = ctx.getString(R.string.rhr_workout, ctx.getString(Sports.label(w.sport)), day, r.endBpm)
            spark.values = data.trend.toIntArray()
            spark.visibility = if (data.trend.size >= 2) View.VISIBLE else View.GONE
            trend.text = data.trendMean?.let { ctx.getString(R.string.rhr_trend, data.trend.size, drop(it)) }
            trend.visibility = if (data.trend.size >= 2) View.VISIBLE else View.GONE
            root.contentDescription = ctx.getString(R.string.rhr_description, tile.value, workout.text)
        }

        /** A drop as shown: a fall is "−24", a rise (heart rate went up) "+3". */
        private fun drop(bpm: Int): String = if (bpm >= 0) "−$bpm" else "+${-bpm}"
    }
}
