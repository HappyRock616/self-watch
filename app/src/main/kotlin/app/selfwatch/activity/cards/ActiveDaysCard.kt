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
import app.selfwatch.activity.IntensityMath
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.db.Table
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.ui.format.Fmt

/**
 * Active days this week (C15): days, Monday to today, with at least [IntensityMath.ACTIVE_DAY_MIN]
 * intensity minutes (moderate once, vigorous twice). There is no official number; the aim shown is
 * "most days" (5) and the wording "some is better than none" (CDC).
 */
object ActiveDaysCard : Card<ActiveDaysCard.Data>("active_days", CardTab.ACTIVITY, R.string.card_active_days, R.string.card_active_days_summary) {

    /**
     * @property days the week, Monday first; [perDay] their intensity minutes (null: none or still to come).
     * @property hasData false without any activity data in the last 4 weeks (empty state).
     */
    data class Data(val days: List<Day>, val perDay: List<Int?>, val hasData: Boolean) {
        /** Days reaching the threshold. */
        val activeDays: Int get() = IntensityMath.activeDays(perDay)
    }

    override val tables: Set<Table> = setOf(Table.ACTIVITY, Table.DAY_SUMMARY)

    override suspend fun load(env: CardEnv): Data {
        val loader = ActivityStatsLoader(env.db, env.clock)
        val days = ActivityWeek.days(env.day)
        val sofar = loader.intensityPerDay(days.filter { it <= env.day })
        val perDay = days.mapIndexed { i, _ -> sofar.getOrNull(i) }
        val hasData = perDay.any { it != null } || loader.hasActivity(DayRange(env.day.plusDays(-HISTORY_DAYS), env.day))
        return Data(days, perDay, hasData)
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(parent)

    private const val HISTORY_DAYS = 27

    private class Holder(parent: ViewGroup) : StatCardView<Data>(
        parent, R.layout.card_activity_active_days,
        StatIds(R.id.ad_tile, R.id.ad_bar, R.id.ad_bars, R.id.ad_caption, R.id.ad_note, R.id.ad_empty),
    ) {
        override fun bind(data: Data) {
            val ctx = root.context
            showEmpty(!data.hasData)
            if (!data.hasData) return
            tile.value = Fmt.number(data.activeDays.toLong())
            tile.unit = null
            tile.label = ctx.getString(R.string.ad_label)
            progress(data.activeDays, IntensityMath.ACTIVE_DAYS_TARGET)
            caption.text = ctx.getString(R.string.ad_caption, Fmt.number(IntensityMath.ACTIVE_DAY_MIN.toLong()))
            bars(
                ctx.getString(R.string.card_active_days),
                data.perDay.map { it ?: 0 }, weekdayLabels(data.days.map { it.yyyymmdd }), IntensityMath.ACTIVE_DAY_MIN,
            )
            note.setText(R.string.ad_note)
            root.contentDescription = ctx.getString(R.string.ad_description, tile.value)
        }
    }
}
