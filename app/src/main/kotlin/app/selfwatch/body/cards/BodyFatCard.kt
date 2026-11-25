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
import app.selfwatch.body.BodyFatInfo
import app.selfwatch.body.BodyTrendLoader
import app.selfwatch.body.MeasureDetailFragment
import app.selfwatch.body.MeasureEditor
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.time.Day
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.Sparkline
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import java.time.ZoneId
import kotlin.math.abs
import app.selfwatch.ui.R as UiR

/**
 * C6 Body fat: the smoothed trend (no target, trend only) with the 90-day change and a sparkline, and a
 * "not medical" note; opens the body fat history with the protein and strength overlay (O3).
 */
object BodyFatCard : Card<BodyFatCard.Data>("body_fat", CardTab.BODY, R.string.card_body_fat, R.string.card_body_fat_summary) {

    /** The trend (null with no readings) and the display [units] (for logging). */
    data class Data(val fat: BodyFatInfo?, val units: Units)

    override val tables: Set<Table> = setOf(Table.BODY)

    override suspend fun load(env: CardEnv): Data = Data(BodyTrendLoader(env.db, env.clock).bodyFat(), env.units)

    override val needs: Set<DataKind> = setOf(DataKind.BODY_FAT)

    override fun small(ctx: Context, data: Data): SmallFace? {
        val f = data.fat ?: return null
        val percent = ctx.getString(R.string.unit_percent)
        val note = f.change90X100?.let {
            val sign = if (it < 0) "−" else if (it > 0) "+" else ""
            ctx.getString(R.string.small_change_90, sign + Fmt.number(abs(it) / 100.0, 1) + " " + percent)
        }
        return SmallFace(
            value = Fmt.number(f.trendX100 / 100.0, 1),
            label = ctx.getString(R.string.small_trend),
            unit = percent,
            note = note,
        )
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(MeasureDetailFragment.newInstance(BodyKind.BODY_FAT, null))
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_body_fat)) {
        private var units = Units.METRIC

        init {
            root.setOnClickListener { host.open(MeasureDetailFragment.newInstance(BodyKind.BODY_FAT, null)) }
            find<EmptyState>(R.id.body_fat_empty).apply {
                buttonText = context.getString(R.string.body_log)
                onButton = { MeasureEditor.log(host.fragment, BodyKind.BODY_FAT, units) }
            }
        }

        override fun bind(data: Data) {
            val ctx = root.context
            units = data.units
            val f = data.fat
            val tile = find<StatTile>(R.id.body_fat_tile)
            val change = find<TextView>(R.id.body_fat_change)
            val spark = find<Sparkline>(R.id.body_fat_spark)
            val note = find<TextView>(R.id.body_fat_note)
            find<EmptyState>(R.id.body_fat_empty).visibility = if (f == null) View.VISIBLE else View.GONE
            for (v in listOf(tile, change, spark, note)) v.visibility = if (f == null) View.GONE else View.VISIBLE
            if (f == null) {
                root.contentDescription = ctx.getString(R.string.body_fat_empty)
                return
            }
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
            tile.value = Fmt.number(f.trendX100 / 100.0, 1)
            tile.unit = ctx.getString(R.string.unit_percent)
            tile.label = ctx.getString(
                R.string.weight_trend_label,
                Fmt.number(f.latestX100 / 100.0, 1) + " " + ctx.getString(R.string.unit_percent),
                Fmt.day(ctx, Day.of(f.latestSec, ZoneId.systemDefault()).yyyymmdd),
            )
            val c = f.change90X100
            change.text = c?.let {
                val sign = if (it < 0) "−" else if (it > 0) "+" else ""
                ctx.getString(R.string.body_fat_change, sign + Fmt.number(abs(it) / 100.0, 1) + " " + ctx.getString(R.string.unit_percent))
            }
            change.visibility = if (c == null) View.GONE else View.VISIBLE
            spark.values = f.spark.toIntArray()
            spark.visibility = if (f.spark.size >= 2) View.VISIBLE else View.GONE
            root.contentDescription = ctx.getString(R.string.body_fat_description, tile.value.toString() + tile.unit, change.text ?: "")
        }
    }
}
