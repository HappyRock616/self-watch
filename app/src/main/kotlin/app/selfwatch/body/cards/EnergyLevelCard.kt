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
import app.selfwatch.body.GarminBands
import app.selfwatch.body.HeartCardLoader
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.CardVisuals
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.ui.components.ArcGaugeView
import app.selfwatch.ui.components.DayStripView
import app.selfwatch.ui.components.EmptyState

/**
 * Energy level (card design standard): the watch's own 0 to 100 estimate of the body's energy (FIT
 * `stress_level` field 3, synced with the all-day data) as an arc gauge with Garmin's level, today in
 * 15-minute slots colored by level, and today's charged ↑ / drained ↓ as a short tag. Context only, no
 * target. Small form: the gauge.
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
            label = ctx.getString(GarminBands.energy(e.latest).name),
            note = ctx.getString(R.string.small_energy_level_range, e.high, e.low),
        )
    }

    override fun createSmallGraphic(ctx: Context): View = CardVisuals.smallGauge(ctx, BodyVisuals.energyBands(ctx))

    override val smallGraphicShowsValue: Boolean = true

    override fun bindSmallGraphic(graphic: View, data: Data) {
        val e = data.info ?: return
        (graphic as ArcGaugeView).apply {
            value = e.latest.toFloat()
            centerText = e.latest.toString()
            subText = context.getString(GarminBands.energy(e.latest).name)
        }
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(parent)

    private class Holder(parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_body_energy_level)) {
        private val gauge = find<ArcGaugeView>(R.id.el_gauge)
        private val strip = find<DayStripView>(R.id.el_strip)
        private val tag = find<TextView>(R.id.el_tag)
        private val empty = find<EmptyState>(R.id.el_empty)

        init {
            val ctx = root.context
            gauge.bands = BodyVisuals.energyBands(ctx)
            strip.colorFor = { BodyVisuals.energyColor(ctx, it) }
        }

        override fun bind(data: Data) {
            val ctx = root.context
            val e = data.info
            for (v in listOf(gauge, strip, tag)) CardVisuals.show(v, e != null)
            CardVisuals.show(empty, e == null)
            if (e == null) {
                root.contentDescription = ctx.getString(R.string.el_empty)
                return
            }
            // Garmin's levels: high 76+, medium 51–75, low 26–50, very low 25 and under.
            val band = ctx.getString(GarminBands.energy(e.latest).name)
            gauge.value = e.latest.toFloat()
            gauge.centerText = e.latest.toString()
            gauge.subText = band
            strip.values = e.slots.toIntArray()
            CardVisuals.show(strip, e.slots.any { it >= 0 })
            tag.text = ctx.getString(R.string.body1_charged_drained, e.charged, e.drained)
            root.contentDescription = ctx.getString(R.string.body1_energy_description, e.latest, band, e.charged, e.drained)
        }
    }
}
