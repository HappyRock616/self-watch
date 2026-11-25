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
import app.selfwatch.ui.format.FmtLabels
import app.selfwatch.ui.format.Units

/** Distance today with the last 7 days as bars. No target: how far is context, not a test. */
object DistanceTodayCard : Card<DistanceTodayCard.Data>("distance_today", CardTab.ACTIVITY, R.string.card_distance_today, R.string.card_distance_today_summary) {

    /** Distance in metres per day (today last) and the display units. */
    data class Data(val week: WeekValues, val units: Units)

    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY, Table.ACTIVITY)

    override suspend fun load(env: CardEnv): Data = Data(TodayLoader(env.db, env.clock).distanceM(env.day), env.units)

    override val needs: Set<DataKind> = setOf(DataKind.DISTANCE)

    override fun small(ctx: Context, data: Data): SmallFace? {
        val w = data.week
        if (w.isEmpty) return null
        val labels = FmtLabels.from(ctx)
        val text = Fmt.distance((w.today ?: 0).toDouble(), data.units, labels = labels)
        // The number and its unit apart, as on the large card.
        val split = text.lastIndexOf(NBSP)
        return SmallFace(
            value = if (split > 0) text.substring(0, split) else text,
            label = ctx.getString(R.string.dt_label),
            unit = if (split > 0) text.substring(split + 1) else null,
            note = w.average?.let { ctx.getString(R.string.small_avg_7_days, Fmt.distance(it, data.units, labels = labels)) },
        )
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(parent)

    private const val NBSP = "\u00A0"

    private class Holder(parent: ViewGroup) : StatCardView<Data>(
        parent, R.layout.card_activity_distance_today,
        StatIds(R.id.dt_tile, R.id.dt_bar, R.id.dt_bars, R.id.dt_caption, R.id.dt_note, R.id.dt_empty),
    ) {
        override fun bind(data: Data) {
            val ctx = root.context
            val w = data.week
            showEmpty(w.isEmpty)
            if (w.isEmpty) return
            val labels = FmtLabels.from(ctx)
            val text = Fmt.distance((w.today ?: 0).toDouble(), data.units, labels = labels)
            // The big number without its unit; the unit goes beside it.
            val split = text.lastIndexOf(NBSP)
            tile.value = if (split > 0) text.substring(0, split) else text
            tile.unit = if (split > 0) text.substring(split + 1) else null
            tile.label = ctx.getString(R.string.dt_label)
            progress(0, null)
            text(caption, w.average?.let { ctx.getString(R.string.dt_caption, Fmt.distance(it, data.units, labels = labels)) })
            bars(ctx.getString(R.string.card_distance_today), w.values.map { it ?: 0 }, weekdayLabels(w.days.map { it.yyyymmdd }), null)
            note.setText(R.string.dt_note)
            root.contentDescription = ctx.getString(R.string.dt_description, text)
        }
    }
}
