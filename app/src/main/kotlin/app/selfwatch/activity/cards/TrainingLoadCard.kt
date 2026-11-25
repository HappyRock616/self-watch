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
import app.selfwatch.activity.ActivityStatsLoader
import app.selfwatch.activity.ActivityWeek
import app.selfwatch.activity.MaxHr
import app.selfwatch.activity.MaxHrSource
import app.selfwatch.activity.TrainingDetailFragment
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
import app.selfwatch.ui.format.Fmt
import kotlin.math.roundToInt

/**
 * Weekly effort (C19, formerly "Training load") from heart-rate-zone minutes ([app.selfwatch.activity.HrZones.load]): this
 * week so far and the 5 weeks before as bars, read as steady progression. No acute:chronic ratio
 * (WP-15, C19). Opens the training-and-recovery overlay (O4).
 */
object TrainingLoadCard : Card<TrainingLoadCard.Data>("training_load", CardTab.ACTIVITY, R.string.card_training_load, R.string.card_training_load_summary) {

    /** Weeks shown, this week last. */
    const val WEEKS: Int = 6

    /** Weeks before this one that make the "recent weeks" average. */
    const val RECENT_WEEKS: Int = 4

    /**
     * @property weekStarts the Mondays of the weeks shown, oldest first; [loads] each week's load.
     * @property hasData false without any workout with heart rate in those weeks (empty state).
     */
    data class Data(val weekStarts: List<Day>, val loads: List<Int>, val maxHr: MaxHr, val hasData: Boolean) {
        /** This week's load so far. */
        val thisWeek: Int get() = loads.lastOrNull() ?: 0

        /** The average of the [RECENT_WEEKS] weeks before this one, or null when they have no load. */
        val recentAverage: Int?
            get() {
                val before = loads.dropLast(1).takeLast(RECENT_WEEKS)
                return if (before.none { it > 0 }) null else before.average().roundToInt()
            }
    }

    override val tables: Set<Table> = setOf(Table.WORKOUT, Table.METRIC, Table.SETTING)

    override suspend fun load(env: CardEnv): Data {
        val loader = ActivityStatsLoader(env.db, env.clock)
        val maxHr = loader.maxHr()
        val thisWeek = ActivityWeek.start(env.day)
        val starts = (WEEKS - 1 downTo 0).map { thisWeek.plusDays(-ActivityWeek.DAYS * it) }
        val sessions = loader.zones(loader.workouts(DayRange(starts.first(), env.day)), maxHr.bpm)
        val zone = env.clock.zone()
        val loads = IntArray(WEEKS)
        var any = false
        for (s in sessions) {
            if (s.zoneSec == null) continue
            any = true
            val i = starts.indexOfLast { it <= Day.of(s.workout.startSec, zone) }
            if (i >= 0) loads[i] += s.load
        }
        return Data(starts, loads.toList(), maxHr, any)
    }

    override val needs: Set<DataKind> = setOf(DataKind.WORKOUTS)

    override fun small(ctx: Context, data: Data): SmallFace? {
        if (!data.hasData) return null
        val avg = data.recentAverage
        // The recent average is a reference, not a target: a bar, no status color.
        return SmallFace(
            value = Fmt.number(data.thisWeek.toLong()),
            label = ctx.getString(R.string.tl_label),
            note = avg?.let { ctx.getString(R.string.small_recent_avg, Fmt.number(it.toLong())) },
            progress = avg?.takeIf { it > 0 }?.let { data.thisWeek.toFloat() / it },
        )
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(TrainingDetailFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    /** The words for where [source]'s maximum heart rate came from. */
    fun sourceText(source: MaxHrSource): Int = when (source) {
        MaxHrSource.STORED -> R.string.tl_source_stored
        MaxHrSource.AGE -> R.string.tl_source_age
        MaxHrSource.DEFAULT -> R.string.tl_source_default
    }

    private class Holder(host: CardHost, parent: ViewGroup) : StatCardView<Data>(
        parent, R.layout.card_activity_training_load,
        StatIds(R.id.tl_tile, R.id.tl_bar, R.id.tl_bars, R.id.tl_caption, R.id.tl_note, R.id.tl_empty),
    ) {
        init {
            root.isClickable = true
            root.setOnClickListener { host.open(TrainingDetailFragment()) }
        }

        override fun bind(data: Data) {
            val ctx = root.context
            showEmpty(!data.hasData)
            if (!data.hasData) return
            tile.value = Fmt.number(data.thisWeek.toLong())
            tile.unit = null
            tile.label = ctx.getString(R.string.tl_label)
            val avg = data.recentAverage
            // The bar shows this week against the recent average; it is a reference, not a target.
            progress(data.thisWeek, avg)
            caption.text = if (avg == null) ctx.getString(R.string.tl_caption_first) else ctx.getString(R.string.tl_caption, Fmt.number(avg.toLong()))
            bars(
                ctx.getString(R.string.card_training_load),
                data.loads, weekLabels(data.weekStarts.map { it.yyyymmdd }), avg,
            )
            note.text = ctx.getString(R.string.tl_note, Fmt.number(data.maxHr.bpm.toLong()), ctx.getString(sourceText(data.maxHr.source)))
            root.contentDescription = ctx.getString(
                R.string.tl_description, tile.value, avg?.let { Fmt.number(it.toLong()) } ?: ctx.getString(R.string.dash),
            )
        }
    }
}
