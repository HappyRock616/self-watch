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
import app.selfwatch.body.BodyTrendLoader
import app.selfwatch.body.HeartDetailFragment
import app.selfwatch.body.HrvInfo
import app.selfwatch.body.RangeStatus
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.Sparkline
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.R as UiR

/**
 * C17 HRV: the 7-night average of overnight HRV against the user's own usual band (learned from the last
 * 60 days, no population scores): "within your usual range", "lower than your usual" or "higher than
 * your usual". Until about two weeks of nights are in, it explains what it is learning. Opens Heart.
 */
object HrvBaselineCard : Card<HrvInfo>("hrv_baseline", CardTab.BODY, R.string.card_hrv_baseline, R.string.card_hrv_baseline_summary) {

    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY)

    override suspend fun load(env: CardEnv): HrvInfo = BodyTrendLoader(env.db, env.clock).hrv()

    override val needs: Set<DataKind> = setOf(DataKind.HRV)

    override fun small(ctx: Context, data: HrvInfo): SmallFace? {
        val label = ctx.getString(R.string.small_seven_night_avg)
        val r = data.result
        if (r == null) {
            if (data.nights == 0 || data.nights >= data.needed) return null
            return SmallFace(
                value = ctx.getString(R.string.dash),
                label = label,
                note = ctx.getString(R.string.small_learning_nights, data.nights, data.needed),
            )
        }
        // Higher HRV than usual is not a worry; lower is the one to watch.
        val (status, note) = when (r.status) {
            RangeStatus.WITHIN -> CardStatus.GOOD to R.string.small_usual_within
            RangeStatus.ABOVE -> CardStatus.GOOD to R.string.small_usual_above
            RangeStatus.BELOW -> CardStatus.CLOSE to R.string.small_usual_below
        }
        return SmallFace(
            value = r.sevenDayMs.toString(),
            label = label,
            unit = ctx.getString(R.string.unit_ms),
            status = status,
            note = ctx.getString(note),
        )
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(HeartDetailFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<HrvInfo> = Holder(host, parent)

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<HrvInfo>(CardView.inflate(parent, R.layout.card_body_hrv)) {
        init {
            root.setOnClickListener { host.open(HeartDetailFragment()) }
        }

        override fun bind(data: HrvInfo) {
            val ctx = root.context
            val r = data.result
            val tile = find<StatTile>(R.id.hrv_baseline_tile)
            val status = find<TextView>(R.id.hrv_baseline_status)
            val band = find<TextView>(R.id.hrv_baseline_band)
            val spark = find<Sparkline>(R.id.hrv_baseline_spark)
            val note = find<TextView>(R.id.hrv_baseline_note)
            val empty = find<EmptyState>(R.id.hrv_baseline_empty)
            for (v in listOf(tile, status, band, spark, note)) v.visibility = if (r == null) View.GONE else View.VISIBLE
            empty.visibility = if (r == null) View.VISIBLE else View.GONE
            if (r == null) {
                empty.message = if (data.nights >= data.needed) {
                    ctx.getString(R.string.hrv_card_recent)
                } else {
                    ctx.getString(R.string.hrv_card_learning, data.nights, data.needed)
                }
                root.contentDescription = empty.message
                return
            }
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
            tile.value = r.sevenDayMs.toString()
            tile.unit = ctx.getString(R.string.unit_ms)
            status.setText(
                when (r.status) {
                    RangeStatus.BELOW -> R.string.hrv_card_below
                    RangeStatus.WITHIN -> R.string.hrv_card_within
                    RangeStatus.ABOVE -> R.string.hrv_card_above
                },
            )
            band.text = ctx.getString(R.string.hrv_card_band, r.lowMs.toString(), r.highMs.toString())
            note.setText(R.string.hrv_card_note)
            spark.values = r.rolling.toIntArray()
            spark.visibility = if (r.rolling.size >= 2) View.VISIBLE else View.GONE
            root.contentDescription = ctx.getString(R.string.hrv_card_description, tile.value, status.text)
        }
    }
}
