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
import app.selfwatch.cards.PictureFrame
import app.selfwatch.cards.SmallFace
import app.selfwatch.cards.TrendPicture
import app.selfwatch.cards.TrendPictures
import app.selfwatch.db.Table
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import kotlin.math.abs

/**
 * Waist, hips and body fat (card design standard: small multiples): each a number over a mini trend line
 * of 90 days with dated ends. Small form: the waist (or the first measurement with a value) and its line.
 * Opens Measurements.
 */
object MeasurementsCard : Card<MeasurementsCard.Data>(
    "measurements",
    CardTab.BODY,
    R.string.card_measurements,
    R.string.card_measurements_summary,
) {
    /** One measurement's 90 days: values (kind unit ×100, oldest first) and the first and last days. */
    data class Line(val valuesX100: List<Int>, val first: Day?, val last: Day?)

    /** The tiles in display [units], with each kind's [lines]. */
    data class Data(val tiles: List<MeasureTile>, val units: Units, val lines: Map<BodyKind, Line> = emptyMap())

    override val tables: Set<Table> = setOf(Table.BODY)

    override suspend fun load(env: CardEnv): Data {
        val tiles = BodyLoader(env.db, env.clock).measures()
        val today = env.clock.today()
        val zone = env.clock.zone()
        val lines = tiles.associate { t ->
            val s = env.db.body.series(t.kind, DayRange(today.plusDays(-LINE_DAYS), today), zone).sortedBy { it.tSec }
            t.kind to Line(s.map { it.valueX100 }, s.firstOrNull()?.let { Day.of(it.tSec, zone) }, s.lastOrNull()?.let { Day.of(it.tSec, zone) })
        }
        return Data(tiles, env.units, lines)
    }

    override val needs: Set<DataKind> = setOf(DataKind.MEASUREMENTS)

    override val defaultSize: CardSize get() = CardSize.LARGE

    /** The tile the small form shows: the waist, else the first measurement with a value. */
    private fun lead(data: Data): MeasureTile? = data.tiles.firstOrNull { it.kind == BodyKind.WAIST && it.valueX100 != null }
        ?: data.tiles.firstOrNull { it.valueX100 != null }

    /** [v] (kind unit ×100) as display text: whole numbers for lengths. */
    private fun number(kind: BodyKind, v: Int, units: Units): String = if (BodyUnits.dim(kind) == BodyUnits.Dim.LENGTH) {
        Fmt.number(BodyUnits.toDisplay(kind, v, units), 0)
    } else {
        BodyUnits.number(kind, v, units)
    }

    /** [kind]'s mini trend line, or null under two readings. */
    fun line(ctx: Context, data: Data, kind: BodyKind): TrendPicture? {
        val l = data.lines[kind] ?: return null
        if (l.valuesX100.size < 2) return null
        return TrendPicture.Line(
            l.valuesX100.map { BodyUnits.toDisplay(kind, it, data.units).toFloat() }.toFloatArray(),
            TrendPictures.dayLabel(ctx, l.first),
            TrendPictures.dayLabel(ctx, l.last),
        )
    }

    override fun small(ctx: Context, data: Data): SmallFace? {
        val t = lead(data) ?: return null
        val v = t.valueX100 ?: return null
        val unit = BodyUnits.unit(t.kind, data.units)
        val note = t.changeX100?.takeIf { it != 0 }?.let { c ->
            val sign = if (c < 0) "−" else "+"
            ctx.getString(R.string.small_change_30, sign + Fmt.number(BodyUnits.toDisplay(t.kind, abs(c), data.units), 1) + " " + unit)
        }
        return SmallFace(value = number(t.kind, v, data.units), label = MeasureEditor.kindLabel(ctx, t.kind), unit = unit, note = note)
    }

    override fun createSmallGraphic(ctx: Context): View = PictureFrame(ctx, small = true)

    override fun bindSmallGraphic(graphic: View, data: Data) {
        (graphic as PictureFrame).show(lead(data)?.let { line(graphic.context, data, it.kind) })
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(MeasurementsFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    private const val LINE_DAYS = 90

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_body_measurements)) {
        init {
            (root as SwCard).setOnClickListener { host.open(MeasurementsFragment()) }
        }

        override fun bind(data: Data) {
            val ctx = root.context
            val ids = mapOf(
                BodyKind.WAIST to (R.id.measure_waist to R.id.measure_waist_line),
                BodyKind.HIPS to (R.id.measure_hips to R.id.measure_hips_line),
                BodyKind.BODY_FAT to (R.id.measure_fat to R.id.measure_fat_line),
            )
            val spoken = ArrayList<String>()
            for (t in data.tiles) {
                val (tileId, lineId) = ids[t.kind] ?: continue
                val tile = find<StatTile>(tileId)
                val v = t.valueX100
                tile.value = if (v == null) ctx.getString(R.string.dash) else number(t.kind, v, data.units)
                tile.unit = if (v == null) null else BodyUnits.unit(t.kind, data.units)
                find<PictureFrame>(lineId).show(line(ctx, data, t.kind))
                if (v != null) spoken += "${MeasureEditor.kindLabel(ctx, t.kind)} ${tile.value} ${tile.unit}"
            }
            root.contentDescription = (listOf(ctx.getString(R.string.body_measurements)) + spoken).joinToString(". ")
        }
    }
}
