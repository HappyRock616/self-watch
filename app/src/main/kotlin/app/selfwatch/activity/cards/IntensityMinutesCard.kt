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
import app.selfwatch.activity.ActivityGoals
import app.selfwatch.activity.ActivityStatsLoader
import app.selfwatch.activity.ActivityWeek
import app.selfwatch.activity.ExerciseSleepDetailFragment
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardStatusColors
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.ui.format.Fmt

/**
 * Intensity minutes this week (C10): moderate minutes plus vigorous minutes counted twice, Monday to
 * Sunday, against the weekly active-minutes goal (default 300), with WHO 2020's minimum of 150 as a note.
 * Bars per day; the wording stays "some is better than none". Opens the exercise-and-sleep overlay (O5).
 */
object IntensityMinutesCard : Card<IntensityMinutesCard.Data>(
    "intensity_minutes", CardTab.ACTIVITY, R.string.card_intensity_minutes, R.string.card_intensity_minutes_summary,
) {
    /**
     * @property days the week, Monday first.
     * @property perDay intensity minutes per day of [days] (null: nothing recorded or still to come).
     * @property target weekly target: the weekly active-minutes goal.
     * @property hasData false when there is no activity data in the last 4 weeks at all (empty state).
     */
    data class Data(val days: List<Day>, val perDay: List<Int?>, val target: Int, val hasData: Boolean) {
        /** Intensity minutes this week. */
        val weekMin: Int get() = perDay.sumOf { it ?: 0 }
    }

    override val tables: Set<Table> = setOf(Table.ACTIVITY, Table.DAY_SUMMARY, Table.GOAL)

    override suspend fun load(env: CardEnv): Data {
        val loader = ActivityStatsLoader(env.db, env.clock)
        val days = ActivityWeek.days(env.day)
        val sofar = loader.intensityPerDay(days.filter { it <= env.day })
        val perDay = days.mapIndexed { i, _ -> sofar.getOrNull(i) }
        val hasData = perDay.any { it != null } || loader.hasActivity(DayRange(env.day.plusDays(-HISTORY_DAYS), env.day))
        return Data(days, perDay, ActivityGoals.activeMinWeek(env.db, env.day), hasData)
    }

    override val needs: Set<DataKind> = setOf(DataKind.STEPS, DataKind.ACTIVE_TIME)

    override fun small(ctx: Context, data: Data): SmallFace? {
        if (!data.hasData) return null
        val week = data.weekMin
        return SmallFace(
            value = Fmt.number(week.toLong()),
            label = ctx.getString(R.string.small_of_week_target, Fmt.number(data.target.toLong())),
            unit = ctx.getString(R.string.unit_min),
            status = CardStatusColors.ofProgress(week.toDouble(), data.target.toDouble()),
            note = if (week >= data.target) {
                ctx.getString(R.string.small_target_reached)
            } else {
                ctx.getString(R.string.small_min_to_go, Fmt.number((data.target - week).toLong()))
            },
            progress = if (data.target > 0) week.toFloat() / data.target else null,
        )
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(ExerciseSleepDetailFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    private const val HISTORY_DAYS = 27

    private class Holder(host: CardHost, parent: ViewGroup) : StatCardView<Data>(
        parent, R.layout.card_activity_intensity_minutes,
        StatIds(R.id.im_tile, R.id.im_bar, R.id.im_bars, R.id.im_caption, R.id.im_note, R.id.im_empty),
    ) {
        init {
            root.isClickable = true
            root.setOnClickListener { host.open(ExerciseSleepDetailFragment()) }
        }

        override fun bind(data: Data) {
            val ctx = root.context
            showEmpty(!data.hasData)
            if (!data.hasData) return
            val week = data.weekMin
            val target = Fmt.number(data.target.toLong())
            tile.value = Fmt.number(week.toLong())
            tile.unit = ctx.getString(R.string.unit_min)
            tile.label = if (data.target > ActivityGoals.WHO_MIN_WEEK) {
                ctx.getString(R.string.im_label_who, target, Fmt.number(ActivityGoals.WHO_MIN_WEEK.toLong()))
            } else {
                ctx.getString(R.string.im_label, target)
            }
            progress(week, data.target)
            caption.text = when {
                week == 0 -> ctx.getString(R.string.im_caption_none)
                week < data.target -> ctx.getString(R.string.im_caption_left, Fmt.number((data.target - week).toLong()))
                else -> ctx.getString(R.string.im_caption_done)
            }
            bars(
                ctx.getString(R.string.card_intensity_minutes),
                data.perDay.map { it ?: 0 }, weekdayLabels(data.days.map { it.yyyymmdd }), null,
            )
            note.setText(R.string.im_note)
            root.contentDescription = ctx.getString(R.string.im_description, tile.value, target)
        }
    }
}
