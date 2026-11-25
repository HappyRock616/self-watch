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
import app.selfwatch.body.BreathingInfo
import app.selfwatch.body.HeartCardLoader
import app.selfwatch.body.RangeStatus
import app.selfwatch.body.SleepDetailFragment
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
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.R as UiR

/**
 * Breathing rate: last night's average breathing while asleep against the user's usual range, learned
 * from the 60 nights before it (like HRV: no population numbers). Until about two weeks of nights are
 * in, it says it is still learning. Opens Sleep.
 */
object BreathingRateCard : Card<BreathingInfo>("breathing_rate", CardTab.BODY, R.string.card_breathing_rate, R.string.card_breathing_rate_summary) {

    override val tables: Set<Table> = setOf(Table.METRIC, Table.SLEEP_SESSION, Table.DAY_SUMMARY)

    override suspend fun load(env: CardEnv): BreathingInfo = HeartCardLoader(env.db, env.clock).breathing()

    override val needs: Set<DataKind> = setOf(DataKind.RESPIRATION)

    override fun small(ctx: Context, data: BreathingInfo): SmallFace? {
        val r = data.result
        val last = r?.lastX10 ?: data.lastX10 ?: return null
        val (status, note) = when (r?.status) {
            null -> CardStatus.NONE to ctx.getString(R.string.small_learning_nights, data.nights, data.needed)
            RangeStatus.WITHIN -> CardStatus.GOOD to ctx.getString(R.string.small_usual_within)
            RangeStatus.BELOW -> CardStatus.CLOSE to ctx.getString(R.string.small_usual_below)
            RangeStatus.ABOVE -> CardStatus.CLOSE to ctx.getString(R.string.small_usual_above)
        }
        return SmallFace(
            value = Fmt.number(last / SMALL_TENTHS, 1),
            label = ctx.getString(R.string.br_label),
            unit = ctx.getString(R.string.unit_br_min),
            status = status,
            note = note,
        )
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(SleepDetailFragment())
        return true
    }

    private const val SMALL_TENTHS = 10.0

    override fun createView(host: CardHost, parent: ViewGroup): CardView<BreathingInfo> = Holder(host, parent)

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<BreathingInfo>(CardView.inflate(parent, R.layout.card_body_breathing_rate)) {
        private val tile = find<StatTile>(R.id.br_tile)
        private val status = find<TextView>(R.id.br_status)
        private val band = find<TextView>(R.id.br_band)
        private val spark = find<Sparkline>(R.id.br_spark)
        private val note = find<TextView>(R.id.br_note)
        private val empty = find<EmptyState>(R.id.br_empty)

        init {
            root.setOnClickListener { host.open(SleepDetailFragment()) }
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
            note.setText(R.string.br_note)
        }

        override fun bind(data: BreathingInfo) {
            val ctx = root.context
            val last = data.result?.lastX10 ?: data.lastX10
            for (v in listOf(tile, status, band, spark, note)) v.visibility = if (last == null) View.GONE else View.VISIBLE
            empty.visibility = if (last == null) View.VISIBLE else View.GONE
            if (last == null) {
                root.contentDescription = ctx.getString(R.string.br_empty)
                return
            }
            tile.value = Fmt.number(last / TENTHS, 1)
            tile.unit = ctx.getString(R.string.unit_brpm)
            tile.label = ctx.getString(R.string.br_label)
            val r = data.result
            if (r == null) {
                status.text = ctx.getString(R.string.br_learning, data.nights, data.needed)
                band.visibility = View.GONE
                spark.visibility = View.GONE
            } else {
                status.setText(
                    when (r.status) {
                        RangeStatus.BELOW -> R.string.br_below
                        RangeStatus.WITHIN -> R.string.br_within
                        RangeStatus.ABOVE -> R.string.br_above
                    },
                )
                band.text = ctx.getString(R.string.br_band, Fmt.number(r.lowX10 / TENTHS, 1), Fmt.number(r.highX10 / TENTHS, 1))
                spark.values = r.nights.toIntArray()
                spark.visibility = if (r.nights.size >= 2) View.VISIBLE else View.GONE
            }
            root.contentDescription = ctx.getString(R.string.br_description, tile.value, status.text)
        }

        private companion object {
            const val TENTHS = 10.0
        }
    }
}
