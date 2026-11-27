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
import app.selfwatch.body.BodyTrendLoader
import app.selfwatch.body.GarminBands
import app.selfwatch.body.HeartDetailFragment
import app.selfwatch.body.HrvInfo
import app.selfwatch.body.RangeStatus
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.CardVisuals
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.RangeBarView
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.R as UiR

/**
 * C17 HRV, as Garmin shows HRV status (card design standard): the 7-day average with a range bar of the
 * baseline band and a marker at the average, and the status as a short tag. From the watch's own summary
 * when it sends one (Balanced / Unbalanced / Low / Poor); without it, the 7-night average against Self
 * Watch's own usual band (learned from the last 60 days). Small form: the number and the range bar.
 * Opens Heart.
 */
object HrvBaselineCard : Card<HrvInfo>("hrv_baseline", CardTab.BODY, R.string.card_hrv_baseline, R.string.card_hrv_baseline_summary) {

    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY, Table.METRIC)

    override suspend fun load(env: CardEnv): HrvInfo = BodyTrendLoader(env.db, env.clock).hrv()

    override val needs: Set<DataKind> = setOf(DataKind.HRV)

    /** What the card shows: the 7-day average, the band (null: none known), the tag and its status. */
    data class HrvReading(val ms: Int, val lowMs: Int?, val highMs: Int?, val tag: String, val status: CardStatus)

    /** The watch's summary when there is one, else Self Watch's own band; null with neither. */
    fun view(ctx: Context, data: HrvInfo): HrvReading? {
        val w = data.watch
        val wBand = GarminBands.hrvStatus(w?.status)
        if (w?.weekMs != null && wBand != null) return HrvReading(w.weekMs, w.lowMs, w.highMs, ctx.getString(wBand.name), wBand.status)
        val r = data.result ?: return null
        // Higher HRV than usual is not a worry; lower is the one to watch.
        val status = if (r.status == RangeStatus.BELOW) CardStatus.CLOSE else CardStatus.GOOD
        return HrvReading(r.sevenDayMs, r.lowMs, r.highMs, ctx.getString(BodyVisuals.usualTag(r.status)), status)
    }

    override fun small(ctx: Context, data: HrvInfo): SmallFace? {
        val label = ctx.getString(R.string.body1_seven_day)
        val v = view(ctx, data)
        if (v == null) {
            if (data.nights == 0 || data.nights >= data.needed) return null
            return SmallFace(
                value = ctx.getString(R.string.dash),
                label = label,
                note = ctx.getString(R.string.small_learning_nights, data.nights, data.needed),
            )
        }
        return SmallFace(value = v.ms.toString(), label = v.tag, unit = ctx.getString(R.string.unit_ms), status = v.status, note = label)
    }

    override fun createSmallGraphic(ctx: Context): View = BodyVisuals.smallRange(ctx)

    override fun bindSmallGraphic(graphic: View, data: HrvInfo) {
        val v = view(graphic.context, data)
        val bar = graphic as RangeBarView
        if (v?.lowMs == null || v.highMs == null) {
            CardVisuals.show(bar, false)
            return
        }
        BodyVisuals.bindUsual(bar, v.lowMs.toFloat(), v.highMs.toFloat(), v.ms.toFloat(), null, null)
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(HeartDetailFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<HrvInfo> = Holder(host, parent)

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<HrvInfo>(CardView.inflate(parent, R.layout.card_body_hrv)) {
        private val tile = find<StatTile>(R.id.hrv_baseline_tile)
        private val range = find<RangeBarView>(R.id.hrv_baseline_range)
        private val empty = find<EmptyState>(R.id.hrv_baseline_empty)

        init {
            root.setOnClickListener { host.open(HeartDetailFragment()) }
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
        }

        override fun bind(data: HrvInfo) {
            val ctx = root.context
            val v = view(ctx, data)
            CardVisuals.show(tile, v != null)
            CardVisuals.show(empty, v == null)
            if (v == null) {
                CardVisuals.show(range, false)
                empty.message = if (data.nights >= data.needed) {
                    ctx.getString(R.string.hrv_card_recent)
                } else {
                    ctx.getString(R.string.hrv_card_learning, data.nights, data.needed)
                }
                root.contentDescription = empty.message
                return
            }
            val ms = ctx.getString(R.string.unit_ms)
            tile.value = v.ms.toString()
            tile.unit = ms
            tile.label = listOf(ctx.getString(R.string.body1_seven_day), v.tag).joinToString(" · ")
            val hasBand = v.lowMs != null && v.highMs != null
            CardVisuals.show(range, hasBand)
            if (v.lowMs != null && v.highMs != null) {
                BodyVisuals.bindUsual(range, v.lowMs.toFloat(), v.highMs.toFloat(), v.ms.toFloat(), v.lowMs.toString(), v.highMs.toString())
                root.contentDescription = ctx.getString(
                    R.string.body1_range_description, ctx.getString(R.string.card_hrv_baseline), tile.value, ms, v.tag, v.lowMs.toString(), v.highMs.toString(),
                )
            } else {
                root.contentDescription = ctx.getString(R.string.hrv_card_description, tile.value, v.tag)
            }
        }
    }
}
