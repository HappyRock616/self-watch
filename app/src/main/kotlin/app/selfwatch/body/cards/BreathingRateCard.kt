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
import app.selfwatch.body.BreathingInfo
import app.selfwatch.body.BreathingResult
import app.selfwatch.body.HeartCardLoader
import app.selfwatch.body.RangeStatus
import app.selfwatch.body.SleepDetailFragment
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
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.R as UiR

/**
 * Breathing rate (card design standard): last night's average breathing while asleep, with a range bar of
 * the user's usual range (learned from the 60 nights before it, like HRV: no population numbers) and a
 * marker at last night. Until about two weeks of nights are in, the tag says it is learning. Small form:
 * the number and the range bar. Opens Sleep.
 */
object BreathingRateCard : Card<BreathingInfo>("breathing_rate", CardTab.BODY, R.string.card_breathing_rate, R.string.card_breathing_rate_summary) {

    override val tables: Set<Table> = setOf(Table.METRIC, Table.SLEEP_SESSION, Table.DAY_SUMMARY)

    override suspend fun load(env: CardEnv): BreathingInfo = HeartCardLoader(env.db, env.clock).breathing()

    override val needs: Set<DataKind> = setOf(DataKind.RESPIRATION)

    /** The short tag: "Usual", "Below usual", "Above usual" or "Learning 5/14". */
    fun tag(ctx: Context, data: BreathingInfo): String =
        data.result?.let { ctx.getString(BodyVisuals.usualTag(it.status)) } ?: ctx.getString(R.string.body1_learning, data.nights, data.needed)

    override fun small(ctx: Context, data: BreathingInfo): SmallFace? {
        val r = data.result
        val last = r?.lastX10 ?: data.lastX10 ?: return null
        val status = when (r?.status) {
            null -> CardStatus.NONE
            RangeStatus.WITHIN -> CardStatus.GOOD
            RangeStatus.BELOW, RangeStatus.ABOVE -> CardStatus.CLOSE
        }
        return SmallFace(
            value = Fmt.number(last / TENTHS, 1),
            label = tag(ctx, data),
            unit = ctx.getString(R.string.unit_br_min),
            status = status,
            note = ctx.getString(R.string.br_label),
        )
    }

    override fun createSmallGraphic(ctx: Context): View = BodyVisuals.smallRange(ctx)

    override fun bindSmallGraphic(graphic: View, data: BreathingInfo) {
        val r = data.result
        CardVisuals.show(graphic, r != null)
        if (r != null) bindRange(graphic as RangeBarView, r, ticks = false)
    }

    /** Last night against the usual range on [bar]. */
    fun bindRange(bar: RangeBarView, r: BreathingResult, ticks: Boolean) {
        BodyVisuals.bindUsual(
            bar, r.lowX10 / TENTHS.toFloat(), r.highX10 / TENTHS.toFloat(), r.lastX10 / TENTHS.toFloat(),
            if (ticks) Fmt.number(r.lowX10 / TENTHS, 1) else null,
            if (ticks) Fmt.number(r.highX10 / TENTHS, 1) else null,
        )
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(SleepDetailFragment())
        return true
    }

    private const val TENTHS = 10.0

    override fun createView(host: CardHost, parent: ViewGroup): CardView<BreathingInfo> = Holder(host, parent)

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<BreathingInfo>(CardView.inflate(parent, R.layout.card_body_breathing_rate)) {
        private val tile = find<StatTile>(R.id.br_tile)
        private val range = find<RangeBarView>(R.id.br_range)
        private val empty = find<EmptyState>(R.id.br_empty)

        init {
            root.setOnClickListener { host.open(SleepDetailFragment()) }
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
        }

        override fun bind(data: BreathingInfo) {
            val ctx = root.context
            val last = data.result?.lastX10 ?: data.lastX10
            CardVisuals.show(tile, last != null)
            CardVisuals.show(empty, last == null)
            if (last == null) {
                CardVisuals.show(range, false)
                root.contentDescription = ctx.getString(R.string.br_empty)
                return
            }
            tile.value = Fmt.number(last / TENTHS, 1)
            tile.unit = ctx.getString(R.string.unit_br_min)
            tile.label = tag(ctx, data)
            val r = data.result
            CardVisuals.show(range, r != null)
            if (r != null) {
                bindRange(range, r, ticks = true)
                root.contentDescription = ctx.getString(
                    R.string.body1_range_description, ctx.getString(R.string.card_breathing_rate), tile.value, ctx.getString(R.string.unit_brpm),
                    tile.label, Fmt.number(r.lowX10 / TENTHS, 1), Fmt.number(r.highX10 / TENTHS, 1),
                )
            } else {
                root.contentDescription = ctx.getString(R.string.br_description, tile.value, tile.label)
            }
        }
    }
}
