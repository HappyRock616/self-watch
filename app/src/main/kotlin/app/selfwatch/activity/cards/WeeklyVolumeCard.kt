/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity.cards

import android.view.ViewGroup
import app.selfwatch.R
import app.selfwatch.activity.ActivityStatsLoader
import app.selfwatch.activity.ActivityWeek
import app.selfwatch.activity.LongSession
import app.selfwatch.activity.LongSessionCheck
import app.selfwatch.activity.Sports
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.db.Table
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import app.selfwatch.ui.format.Units
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Weekly volume (C20): workout time (and distance) this week and the 5 weeks before as bars, plus the
 * long-session check ([LongSessionCheck]): a run, walk or hike in the last 7 days more than 10% longer
 * than the longest of its kind in the 30 days before it is pointed out, gently.
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

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(parent)

    private const val DAY_SEC = 86_400L
    private const val MINUTE_SEC = 60

    private class Holder(parent: ViewGroup) : StatCardView<Data>(
        parent, R.layout.card_activity_weekly_volume,
        StatIds(R.id.wv_tile, R.id.wv_bar, R.id.wv_bars, R.id.wv_caption, R.id.wv_note, R.id.wv_empty),
    ) {
        override fun bind(data: Data) {
            val ctx = root.context
            showEmpty(data.isEmpty)
            if (data.isEmpty) return
            val loc = Locale.getDefault()
            val labels = FmtLabels.from(ctx)
            val time = Fmt.duration((data.durationsSec.lastOrNull() ?: 0).toLong(), loc, labels)
            val metres = data.distancesM.lastOrNull() ?: 0
            tile.value = time
            tile.unit = null
            tile.label = if (metres > 0) {
                ctx.getString(R.string.wv_label_distance, Fmt.distance(metres.toDouble(), data.units, loc, labels))
            } else {
                ctx.getString(R.string.wv_label)
            }
            progress(0, null)
            val flag = data.longSession
            text(
                caption,
                when {
                    flag != null -> {
                        val day = DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(loc, "EEEMMMd"), loc)
                            .format(Instant.ofEpochSecond(flag.workout.startSec).atZone(java.time.ZoneId.systemDefault()))
                        ctx.getString(
                            R.string.wv_caption_flag,
                            ctx.getString(Sports.label(flag.workout.sport)).lowercase(loc), day, Fmt.number(flag.pctLonger.toLong()),
                        )
                    }
                    data.checked -> ctx.getString(R.string.wv_caption_ok)
                    else -> null
                },
            )
            bars(
                ctx.getString(R.string.card_weekly_volume),
                data.durationsSec.map { it / MINUTE_SEC }, weekLabels(data.weekStarts.map { it.yyyymmdd }), null,
            )
            note.setText(R.string.wv_note)
            root.contentDescription = ctx.getString(R.string.wv_description, time)
        }
    }
}
