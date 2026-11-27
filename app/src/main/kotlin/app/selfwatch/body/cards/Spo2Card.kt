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
import app.selfwatch.body.SleepDetailFragment
import app.selfwatch.body.Spo2Info
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.CardVisuals
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.model.time.Day
import app.selfwatch.ui.charts.LevelColors
import app.selfwatch.ui.components.Band
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.RangeBarView
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.R as UiR

/**
 * Blood oxygen (card design standard): last night's average asleep on a range bar from 80 to 100 %
 * (under 90 orange, 90–94 yellow, 95 and up good), with "asleep · low 89%" as its tag. Small form: the
 * number and the range bar. Opens Sleep.
 */
object Spo2Card : Card<Spo2Card.Data>("spo2", CardTab.BODY, R.string.card_spo2, R.string.card_spo2_summary) {

    /** Blood oxygen (null with no readings in 30 days) and [today], to name last night. */
    data class Data(val spo2: Spo2Info?, val today: Day)

    override val tables: Set<Table> = setOf(Table.METRIC)

    override suspend fun load(env: CardEnv): Data = Data(BodyLoader(env.db, env.clock).spo2(), env.clock.today())

    override val needs: Set<DataKind> = setOf(DataKind.SPO2)

    /** "asleep · low 89%", or "asleep" without a lowest reading. */
    fun tag(ctx: Context, o: Spo2Info): String =
        o.lowestPct?.let { ctx.getString(R.string.body1_spo2_low, it) } ?: ctx.getString(R.string.body1_spo2_asleep)

    override fun small(ctx: Context, data: Data): SmallFace? {
        val o = data.spo2 ?: return null
        return SmallFace(
            value = o.lastPct.toString(),
            label = tag(ctx, o),
            unit = ctx.getString(R.string.unit_percent),
            note = if (o.lastDay == data.today) ctx.getString(R.string.sleep_card_last_night) else Fmt.day(ctx, o.lastDay.yyyymmdd),
        )
    }

    override fun createSmallGraphic(ctx: Context): View = BodyVisuals.smallRange(ctx)

    override fun bindSmallGraphic(graphic: View, data: Data) {
        val o = data.spo2 ?: return
        bindRange(graphic as RangeBarView, o.lastPct, ticks = false)
    }

    /** [pct] on the 80–100 % bar. */
    fun bindRange(bar: RangeBarView, pct: Int, ticks: Boolean) {
        val c = LevelColors.of(bar.context)
        bar.min = MIN
        bar.max = MAX
        bar.bands = listOf(Band(LOW, c.stressMedium), Band(OK, c.scaleClose), Band(MAX, c.scaleGood))
        bar.value = pct.toFloat().coerceIn(MIN, MAX)
        bar.ticks = if (ticks) listOf(LOW to "90", OK to "95") else emptyList()
    }

    private const val MIN = 80f
    private const val LOW = 90f
    private const val OK = 95f
    private const val MAX = 100f

    override fun openDetail(host: CardHost): Boolean {
        host.open(SleepDetailFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_body_spo2)) {
        private val tile = find<StatTile>(R.id.spo2_tile)
        private val range = find<RangeBarView>(R.id.spo2_range)
        private val empty = find<EmptyState>(R.id.spo2_empty)

        init {
            root.setOnClickListener { host.open(SleepDetailFragment()) }
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
        }

        override fun bind(data: Data) {
            val ctx = root.context
            val o = data.spo2
            for (v in listOf(tile, range)) CardVisuals.show(v, o != null)
            CardVisuals.show(empty, o == null)
            if (o == null) {
                root.contentDescription = ctx.getString(R.string.spo2_empty)
                return
            }
            tile.value = o.lastPct.toString()
            tile.unit = ctx.getString(R.string.unit_percent)
            tile.label = tag(ctx, o)
            bindRange(range, o.lastPct, ticks = true)
            val night = if (o.lastDay == data.today) ctx.getString(R.string.spo2_last_night) else Fmt.day(ctx, o.lastDay.yyyymmdd)
            root.contentDescription = ctx.getString(R.string.spo2_description, Fmt.percent(o.lastPct / 100.0), listOf(night, tile.label).joinToString(", "))
        }
    }
}
