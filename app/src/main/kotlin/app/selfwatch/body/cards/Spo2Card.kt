/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body.cards

import android.content.Context
import android.view.View
import android.view.ViewGroup
import app.selfwatch.R
import app.selfwatch.body.BodyLoader
import app.selfwatch.body.SleepDetailFragment
import app.selfwatch.body.Spo2Info
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.model.time.Day
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.Sparkline
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.R as UiR

/** Last night's blood oxygen and 30 nights; opens Sleep. */
object Spo2Card : Card<Spo2Card.Data>("spo2", CardTab.BODY, R.string.card_spo2, R.string.card_spo2_summary) {

    /** Blood oxygen (null with no readings in 30 days) and [today], to name last night. */
    data class Data(val spo2: Spo2Info?, val today: Day)

    override val tables: Set<Table> = setOf(Table.METRIC)

    override suspend fun load(env: CardEnv): Data = Data(BodyLoader(env.db, env.clock).spo2(), env.clock.today())

    override val needs: Set<DataKind> = setOf(DataKind.SPO2)

    override fun small(ctx: Context, data: Data): SmallFace? {
        val o = data.spo2 ?: return null
        return SmallFace(
            value = o.lastPct.toString(),
            label = if (o.lastDay == data.today) ctx.getString(R.string.sleep_card_last_night) else Fmt.day(ctx, o.lastDay.yyyymmdd),
            unit = ctx.getString(R.string.unit_percent),
        )
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(SleepDetailFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_body_spo2)) {
        init {
            root.setOnClickListener { host.open(SleepDetailFragment()) }
        }

        override fun bind(data: Data) {
            val ctx = root.context
            val o = data.spo2
            val tile = find<StatTile>(R.id.spo2_tile)
            val spark = find<Sparkline>(R.id.spo2_spark)
            find<EmptyState>(R.id.spo2_empty).visibility = if (o == null) View.VISIBLE else View.GONE
            tile.visibility = if (o == null) View.GONE else View.VISIBLE
            if (o == null) {
                spark.visibility = View.GONE
                return
            }
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
            tile.value = o.lastPct.toString()
            tile.unit = ctx.getString(R.string.unit_percent)
            tile.label = if (o.lastDay == data.today) ctx.getString(R.string.spo2_last_night) else Fmt.day(ctx, o.lastDay.yyyymmdd)
            spark.values = o.nights.toIntArray()
            spark.visibility = if (o.nights.size >= 2) View.VISIBLE else View.GONE
            root.contentDescription = ctx.getString(R.string.spo2_description, Fmt.percent(o.lastPct / 100.0), tile.label)
        }
    }
}
