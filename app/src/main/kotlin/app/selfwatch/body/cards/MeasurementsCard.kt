/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body.cards

import android.content.Context
import android.view.ViewGroup
import app.selfwatch.R
import app.selfwatch.body.BodyLoader
import app.selfwatch.body.BodyUnits
import app.selfwatch.body.MeasureEditor
import app.selfwatch.body.MeasureTile
import app.selfwatch.body.MeasurementsFragment
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.model.health.BodyKind
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import kotlin.math.abs

/** Waist, hips and body fat; opens Measurements. */
object MeasurementsCard : Card<MeasurementsCard.Data>(
    "measurements",
    CardTab.BODY,
    R.string.card_measurements,
    R.string.card_measurements_summary,
) {
    /** The tiles in display [units]. */
    data class Data(val tiles: List<MeasureTile>, val units: Units)

    override val tables: Set<Table> = setOf(Table.BODY)

    override suspend fun load(env: CardEnv): Data = Data(BodyLoader(env.db, env.clock).measures(), env.units)

    override val needs: Set<DataKind> = setOf(DataKind.MEASUREMENTS)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override fun small(ctx: Context, data: Data): SmallFace? {
        // The waist leads; without one, the first measurement that has a value.
        val t = data.tiles.firstOrNull { it.kind == BodyKind.WAIST && it.valueX100 != null }
            ?: data.tiles.firstOrNull { it.valueX100 != null }
            ?: return null
        val v = t.valueX100 ?: return null
        val value = if (BodyUnits.dim(t.kind) == BodyUnits.Dim.LENGTH) {
            Fmt.number(BodyUnits.toDisplay(t.kind, v, data.units), 0)
        } else {
            BodyUnits.number(t.kind, v, data.units)
        }
        val unit = BodyUnits.unit(t.kind, data.units)
        val note = t.changeX100?.takeIf { it != 0 }?.let { c ->
            val sign = if (c < 0) "−" else "+"
            ctx.getString(R.string.small_change_30, sign + Fmt.number(BodyUnits.toDisplay(t.kind, abs(c), data.units), 1) + " " + unit)
        }
        return SmallFace(value = value, label = MeasureEditor.kindLabel(ctx, t.kind), unit = unit, note = note)
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(MeasurementsFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_body_measurements)) {
        init {
            val card = root as SwCard
            card.setOnClickListener { host.open(MeasurementsFragment()) }
            card.onAction = { host.open(MeasurementsFragment()) }
        }

        override fun bind(data: Data) {
            val ctx = root.context
            val ids = mapOf(
                BodyKind.WAIST to R.id.measure_waist,
                BodyKind.HIPS to R.id.measure_hips,
                BodyKind.BODY_FAT to R.id.measure_fat,
            )
            for (t in data.tiles) {
                val tile = find<StatTile>(ids[t.kind] ?: continue)
                val v = t.valueX100
                tile.value = if (v == null) {
                    ctx.getString(R.string.dash)
                } else if (BodyUnits.dim(t.kind) == BodyUnits.Dim.LENGTH) {
                    Fmt.number(BodyUnits.toDisplay(t.kind, v, data.units), 0)
                } else {
                    BodyUnits.number(t.kind, v, data.units)
                }
                tile.unit = if (v == null) null else BodyUnits.unit(t.kind, data.units)
                val c = t.changeX100
                tile.delta = c?.toFloat()
                tile.deltaText = c?.takeIf { it != 0 }?.let { Fmt.number(BodyUnits.toDisplay(t.kind, abs(it), data.units), 1) }
            }
        }
    }
}
