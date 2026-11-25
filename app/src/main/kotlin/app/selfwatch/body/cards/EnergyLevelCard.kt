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
import android.widget.TextView
import app.selfwatch.R
import app.selfwatch.body.EnergyLevelInfo
import app.selfwatch.body.HeartCardLoader
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.model.time.SystemClock
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.Sparkline
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.R as UiR

/**
 * Energy level: the watch's own 0 to 100 estimate of the body's energy today (FIT `stress_level` field 3,
 * synced with the all-day data), with today's high and low and a sparkline. Context only, no target.
 */
object EnergyLevelCard : Card<EnergyLevelCard.Data>("energy_level", CardTab.BODY, R.string.card_energy_level, R.string.card_energy_level_summary) {

    /** Today's energy level, or null with no reading today. */
    data class Data(val info: EnergyLevelInfo?)

    override val tables: Set<Table> = setOf(Table.METRIC)

    override suspend fun load(env: CardEnv): Data = Data(HeartCardLoader(env.db, env.clock).energy())

    override val needs: Set<DataKind> = setOf(DataKind.ENERGY_LEVEL)

    override fun small(ctx: Context, data: Data): SmallFace? {
        val e = data.info ?: return null
        return SmallFace(
            value = e.latest.toString(),
            label = ctx.getString(R.string.small_of_100),
            note = ctx.getString(R.string.small_energy_level_range, e.high, e.low),
        )
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(parent)

    private class Holder(parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_body_energy_level)) {
        private val tile = find<StatTile>(R.id.el_tile)
        private val today = find<TextView>(R.id.el_today)
        private val spark = find<Sparkline>(R.id.el_spark)
        private val note = find<TextView>(R.id.el_note)
        private val empty = find<EmptyState>(R.id.el_empty)

        init {
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
            spark.color = ChartPalette.of(root.context).primary
            note.setText(R.string.el_note)
        }

        override fun bind(data: Data) {
            val ctx = root.context
            val e = data.info
            for (v in listOf(tile, today, spark, note)) v.visibility = if (e == null) View.GONE else View.VISIBLE
            empty.visibility = if (e == null) View.VISIBLE else View.GONE
            if (e == null) {
                root.contentDescription = ctx.getString(R.string.el_empty)
                return
            }
            tile.value = e.latest.toString()
            tile.unit = null
            tile.label = ctx.getString(R.string.el_label, HeartRateNowCard.ageText(ctx, e.latestSec, SystemClock.nowSec(), live = false))
            today.text = ctx.getString(R.string.el_today, e.high, e.low)
            spark.values = e.spark.toIntArray()
            spark.visibility = if (e.spark.size >= 2) View.VISIBLE else View.GONE
            root.contentDescription = ctx.getString(R.string.el_description, e.latest, today.text)
        }
    }
}
