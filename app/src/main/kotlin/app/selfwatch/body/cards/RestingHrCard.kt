/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body.cards

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.selfwatch.R
import app.selfwatch.body.BodyTrendLoader
import app.selfwatch.body.HeartDetailFragment
import app.selfwatch.body.RangeStatus
import app.selfwatch.body.RestingHrTrend
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.db.Table
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.Sparkline
import app.selfwatch.ui.components.StatTile
import kotlin.math.abs
import app.selfwatch.ui.R as UiR

/**
 * C18 Resting heart rate: the 4-week rolling average against the 4 weeks before (lower or steady is the
 * aim; no single-day numbers), with the rolling trend over 90 days. Opens Heart (with the intensity
 * minutes overlay, O6).
 */
object RestingHrCard : Card<RestingHrCard.Data>("resting_hr", CardTab.BODY, R.string.card_resting_hr, R.string.card_resting_hr_summary) {

    /** The trend, or null with fewer than a week of readings in 4 weeks. */
    data class Data(val trend: RestingHrTrend?)

    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY, Table.METRIC)

    override suspend fun load(env: CardEnv): Data = Data(BodyTrendLoader(env.db, env.clock).restingHr())

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_body_resting_hr)) {
        init {
            root.setOnClickListener { host.open(HeartDetailFragment()) }
        }

        override fun bind(data: Data) {
            val ctx = root.context
            val t = data.trend
            val tile = find<StatTile>(R.id.resting_hr_tile)
            val status = find<TextView>(R.id.resting_hr_status)
            val usual = find<TextView>(R.id.resting_hr_usual)
            val spark = find<Sparkline>(R.id.resting_hr_spark)
            for (v in listOf(tile, status, usual, spark)) v.visibility = if (t == null) View.GONE else View.VISIBLE
            find<EmptyState>(R.id.resting_hr_empty).visibility = if (t == null) View.VISIBLE else View.GONE
            if (t == null) {
                root.contentDescription = ctx.getString(R.string.rhr_card_empty)
                return
            }
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
            tile.value = t.currentBpm.toString()
            tile.unit = ctx.getString(R.string.unit_bpm)
            val d = t.deltaBpm
            status.text = when (t.status) {
                null -> ctx.getString(R.string.rhr_card_no_baseline)
                RangeStatus.WITHIN -> ctx.getString(R.string.rhr_card_steady)
                RangeStatus.BELOW -> ctx.getString(R.string.rhr_card_lower, "−" + abs(d ?: 0))
                RangeStatus.ABOVE -> ctx.getString(R.string.rhr_card_higher, abs(d ?: 0).toString())
            }
            usual.text = t.baselineBpm?.let { ctx.getString(R.string.rhr_card_usual, it.toString()) }
            usual.visibility = if (t.baselineBpm == null) View.GONE else View.VISIBLE
            spark.values = t.rolling.toIntArray()
            spark.visibility = if (t.rolling.size >= 2) View.VISIBLE else View.GONE
            root.contentDescription = ctx.getString(R.string.rhr_card_description, tile.value, status.text)
        }
    }
}
