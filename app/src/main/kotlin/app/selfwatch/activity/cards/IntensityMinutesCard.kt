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
import app.selfwatch.activity.ExerciseSleepDetailFragment
import app.selfwatch.activity.IntensityMath
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.MainGoal
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.Table
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.ui.format.Fmt

/**
 * Intensity minutes this week (C10): moderate minutes plus vigorous minutes counted twice, Monday to
 * Sunday, against 150 (250 for the weight goals, with 300 noted for extra benefit; WHO 2020, ACSM 2009).
 * Bars per day; the wording stays "some is better than none". Opens the exercise-and-sleep overlay (O5).
 */
object IntensityMinutesCard : Card<IntensityMinutesCard.Data>(
    "intensity_minutes", CardTab.ACTIVITY, R.string.card_intensity_minutes, R.string.card_intensity_minutes_summary,
) {
    /**
     * @property days the week, Monday first.
     * @property perDay intensity minutes per day of [days] (null: nothing recorded or still to come).
     * @property target weekly target.
     * @property extraBenefit true when the card also mentions [IntensityMath.EXTRA_BENEFIT_MIN].
     * @property hasData false when there is no activity data in the last 4 weeks at all (empty state).
     */
    data class Data(val days: List<Day>, val perDay: List<Int?>, val target: Int, val extraBenefit: Boolean, val hasData: Boolean) {
        /** Intensity minutes this week. */
        val weekMin: Int get() = perDay.sumOf { it ?: 0 }
    }

    override val tables: Set<Table> = setOf(Table.ACTIVITY, Table.DAY_SUMMARY, Table.SETTING)

    override suspend fun load(env: CardEnv): Data {
        val loader = ActivityStatsLoader(env.db, env.clock)
        val days = ActivityWeek.days(env.day)
        val sofar = loader.intensityPerDay(days.filter { it <= env.day })
        val perDay = days.mapIndexed { i, _ -> sofar.getOrNull(i) }
        val goal = MainGoal.byId(env.db.settings.get(SettingKeys.MAIN_GOAL))
        val hasData = perDay.any { it != null } || loader.hasActivity(DayRange(env.day.plusDays(-HISTORY_DAYS), env.day))
        return Data(days, perDay, IntensityMath.weekTarget(goal), IntensityMath.showsExtraBenefit(goal), hasData)
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
            tile.label = if (data.extraBenefit) {
                ctx.getString(R.string.im_label_extra, target, Fmt.number(IntensityMath.EXTRA_BENEFIT_MIN.toLong()))
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
