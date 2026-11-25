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
import app.selfwatch.activity.TodayLoader
import app.selfwatch.activity.WeekValues
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.ui.format.Fmt
import kotlin.math.roundToLong

/**
 * Calories today: the total burned so far (active from the watch plus resting), split into its two
 * parts, with the last 7 days as bars. Labeled as an estimate; active calories alone are `active_kcal`.
 */
object CaloriesTodayCard : Card<CaloriesTodayCard.Data>("calories_today", CardTab.ACTIVITY, R.string.card_calories_today, R.string.card_calories_today_summary) {

    /** Total kcal per day (today last) and today's active and resting parts (null: none). */
    data class Data(val week: WeekValues, val activeKcal: Int?, val restingKcal: Int?)

    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY, Table.ACTIVITY)

    override suspend fun load(env: CardEnv): Data {
        val (week, parts) = TodayLoader(env.db, env.clock).totalKcal(env.day)
        return Data(week, parts.first, parts.second)
    }

    override val needs: Set<DataKind> = setOf(DataKind.ACTIVE_CALORIES, DataKind.RESTING_CALORIES)

    override fun small(ctx: Context, data: Data): SmallFace? {
        val w = data.week
        if (w.isEmpty) return null
        return SmallFace(
            value = Fmt.number((w.today ?: 0).toLong()),
            label = ctx.getString(R.string.small_burned_so_far),
            unit = ctx.getString(R.string.unit_kcal),
            note = ctx.getString(
                R.string.small_kcal_parts,
                Fmt.number((data.activeKcal ?: 0).toLong()),
                Fmt.number((data.restingKcal ?: 0).toLong()),
            ),
        )
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(parent)

    private class Holder(parent: ViewGroup) : StatCardView<Data>(
        parent, R.layout.card_activity_calories_today,
        StatIds(R.id.ct_tile, R.id.ct_bar, R.id.ct_bars, R.id.ct_caption, R.id.ct_note, R.id.ct_empty),
    ) {
        override fun bind(data: Data) {
            val ctx = root.context
            val w = data.week
            showEmpty(w.isEmpty)
            if (w.isEmpty) return
            tile.value = Fmt.number((w.today ?: 0).toLong())
            tile.unit = null
            tile.label = ctx.getString(R.string.ct_label, Fmt.number((data.activeKcal ?: 0).toLong()), Fmt.number((data.restingKcal ?: 0).toLong()))
            progress(0, null)
            // Today is not over: the average leaves it out.
            val earlier = w.values.dropLast(1).filterNotNull()
            text(caption, earlier.takeIf { it.isNotEmpty() }?.let { ctx.getString(R.string.ct_caption, Fmt.number(it.average().roundToLong())) })
            bars(ctx.getString(R.string.card_calories_today), w.values.map { it ?: 0 }, weekdayLabels(w.days.map { it.yyyymmdd }), null)
            note.setText(R.string.ct_note)
            root.contentDescription = ctx.getString(R.string.ct_description, tile.value)
        }
    }
}
