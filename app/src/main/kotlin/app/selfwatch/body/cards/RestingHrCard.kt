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
import app.selfwatch.body.HeartDetailFragment
import app.selfwatch.body.RangeStatus
import app.selfwatch.body.RestingHrMath
import app.selfwatch.body.RestingHrTrend
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
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.components.TrendLineView
import kotlin.math.abs
import app.selfwatch.ui.R as UiR

/**
 * C18 Resting heart rate, as Garmin shows it (card design standard): today's value (the watch's, else the
 * 4-week average) with "7-day avg 62" as its tag, and the rolling 4-week average over 90 days as a trend
 * line with your usual band shaded. Small form: the number and the trend line. Opens Heart.
 */
object RestingHrCard : Card<RestingHrCard.Data>("resting_hr", CardTab.BODY, R.string.card_resting_hr, R.string.card_resting_hr_summary) {

    /** The trend (null with fewer than a week of readings in 4 weeks), today's value and the 7-day average. */
    data class Data(val trend: RestingHrTrend?, val todayBpm: Int? = null, val sevenDayBpm: Int? = null)

    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY, Table.METRIC)

    override suspend fun load(env: CardEnv): Data {
        val loader = BodyTrendLoader(env.db, env.clock)
        val today = env.clock.today()
        val week = loader.restingByDay(app.selfwatch.model.time.DayRange(today.plusDays(-6), today))
        val avg = week.values.takeIf { it.isNotEmpty() }?.average()?.let { Math.round(it).toInt() }
        return Data(loader.restingHr(), week[today], avg)
    }

    override val needs: Set<DataKind> = setOf(DataKind.RESTING_HR, DataKind.HEART_RATE)

    /** The short tag: "7-day avg 62", else "today" or "4-week avg". */
    fun tag(ctx: Context, data: Data): String = when {
        data.sevenDayBpm != null -> ctx.getString(R.string.body1_seven_day_value, data.sevenDayBpm.toString())
        data.todayBpm != null -> ctx.getString(R.string.rhr_card_today)
        else -> ctx.getString(R.string.body1_four_week)
    }

    override fun small(ctx: Context, data: Data): SmallFace? {
        val t = data.trend
        if (t == null) {
            val v = data.todayBpm ?: data.sevenDayBpm ?: return null
            return SmallFace(value = v.toString(), label = ctx.getString(R.string.rhr_card_today), unit = ctx.getString(R.string.unit_bpm))
        }
        val d = abs(t.deltaBpm ?: 0)
        val (status, note) = when (t.status) {
            null -> CardStatus.NONE to ctx.getString(R.string.small_learning_usual)
            RangeStatus.WITHIN -> CardStatus.GOOD to ctx.getString(R.string.small_steady)
            RangeStatus.BELOW -> CardStatus.GOOD to ctx.getString(R.string.small_rhr_lower, d)
            RangeStatus.ABOVE -> CardStatus.CLOSE to ctx.getString(R.string.small_rhr_higher, d)
        }
        return SmallFace(
            value = (data.todayBpm ?: t.currentBpm).toString(),
            label = tag(ctx, data),
            unit = ctx.getString(R.string.unit_bpm),
            status = status,
            note = note,
        )
    }

    override fun createSmallGraphic(ctx: Context): View = BodyVisuals.smallTrend(ctx)

    override fun bindSmallGraphic(graphic: View, data: Data) {
        val t = data.trend
        val line = graphic as TrendLineView
        if (t == null || t.rolling.size < 2) {
            CardVisuals.show(line, false)
            return
        }
        bindTrend(line, t)
    }

    /** Shows the 90-day rolling average on [line] with the usual band and labeled ends. */
    fun bindTrend(line: TrendLineView, t: RestingHrTrend) {
        val ctx = line.context
        line.color = ChartPalette.of(ctx).series(2)
        line.values = FloatArray(t.rolling.size) { t.rolling[it].toFloat() }
        line.band = t.baselineBpm?.let { (it - RestingHrMath.STEADY_BPM).toFloat() to (it + RestingHrMath.STEADY_BPM).toFloat() }
        line.startLabel = BodyVisuals.daysAgo(ctx, t.rolling.size - 1)
        line.endLabel = ctx.getString(R.string.body1_today)
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(HeartDetailFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_body_resting_hr)) {
        private val tile = find<StatTile>(R.id.resting_hr_tile)
        private val trend = find<TrendLineView>(R.id.resting_hr_trend)
        private val empty = find<EmptyState>(R.id.resting_hr_empty)

        init {
            root.setOnClickListener { host.open(HeartDetailFragment()) }
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
        }

        override fun bind(data: Data) {
            val ctx = root.context
            val t = data.trend
            for (v in listOf(tile, trend)) CardVisuals.show(v, t != null)
            CardVisuals.show(empty, t == null)
            if (t == null) {
                root.contentDescription = ctx.getString(R.string.rhr_card_empty)
                return
            }
            tile.value = (data.todayBpm ?: t.currentBpm).toString()
            tile.unit = ctx.getString(R.string.unit_bpm)
            tile.label = tag(ctx, data)
            CardVisuals.show(trend, t.rolling.size >= 2)
            if (t.rolling.size >= 2) bindTrend(trend, t)
            val d = t.deltaBpm
            val status = when (t.status) {
                null -> ctx.getString(R.string.rhr_card_no_baseline)
                RangeStatus.WITHIN -> ctx.getString(R.string.rhr_card_steady)
                RangeStatus.BELOW -> ctx.getString(R.string.rhr_card_lower, "−" + abs(d ?: 0))
                RangeStatus.ABOVE -> ctx.getString(R.string.rhr_card_higher, abs(d ?: 0).toString())
            }
            root.contentDescription = ctx.getString(R.string.rhr_card_description, tile.value, listOf(tile.label, status).joinToString(". "))
        }
    }
}
