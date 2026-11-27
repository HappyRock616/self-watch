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
import app.selfwatch.body.BodyTrendLoader
import app.selfwatch.body.BodyUnits
import app.selfwatch.body.RangeStatus
import app.selfwatch.body.WeightBand
import app.selfwatch.body.WeightDetailFragment
import app.selfwatch.body.WeightEditor
import app.selfwatch.body.WeightInfo
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardStatusColors
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
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import kotlin.math.abs
import app.selfwatch.ui.R as UiR

/**
 * C2 Weight (card design standard): the smoothed trend as the number, tagged with the week's change, and a
 * trend line of the last 30 days with the goal as a dashed line and both ends dated. The line is colored by
 * the main goal's band (lose 0.5–1 kg a week, keep within 3% of the goal, build +0.25–0.5% a week, or a
 * 5% fall in 90 days). Small form: the number and the line. Opens Weight.
 */
object WeightCard : Card<WeightCard.Data>("weight", CardTab.BODY, R.string.card_weight, R.string.card_weight_summary) {

    /**
     * The weight (null with no weigh-ins) in display [units], the main goal's [band] (null for none) and
     * [startDay], the day of the first weigh-in on the line.
     */
    data class Data(val weight: WeightInfo?, val units: Units, val band: WeightBand? = null, val startDay: Day? = null)

    override val tables: Set<Table> = setOf(Table.BODY, Table.GOAL, Table.SETTING)

    override suspend fun load(env: CardEnv): Data {
        val w = BodyLoader(env.db, env.clock).weight()
        val zone = env.clock.zone()
        // The line covers the 30 days up to the latest weigh-in (as BodyLoader.weight's trend does).
        val start = w?.let { info ->
            val from = info.latestSec - LINE_DAYS * DAY_SEC
            env.db.body.series(BodyKind.WEIGHT, DayRange(Day.of(from, zone), env.clock.today()), zone)
                .filter { it.tSec >= from }.minByOrNull { it.tSec }?.let { Day.of(it.tSec, zone) }
        }
        return Data(w, env.units, BodyTrendLoader(env.db, env.clock).weightBand(), start)
    }

    override val needs: Set<DataKind> = setOf(DataKind.WEIGHT)

    /** How the trend stands against the main goal's band. */
    fun status(data: Data): CardStatus {
        val b = data.band
        return when {
            b == null -> CardStatus.NONE
            b.kind == WeightBand.Kind.UNPLANNED_LOSS -> CardStatus.CLOSE
            b.status == null -> CardStatus.NONE
            b.status == RangeStatus.WITHIN -> CardStatus.GOOD
            else -> CardStatus.CLOSE
        }
    }

    /** "−0.6 kg this week", or null without a week of trend. */
    fun weekTag(ctx: Context, data: Data): String? = data.weight?.weekChangeX100?.let {
        val sign = if (it < 0) "−" else if (it > 0) "+" else ""
        val unit = BodyUnits.unit(BodyKind.WEIGHT, data.units)
        ctx.getString(R.string.body_this_week, sign + Fmt.number(BodyUnits.toDisplay(BodyKind.WEIGHT, abs(it), data.units), 1) + " " + unit)
    }

    /** The trend line (display units) with the goal dashed and both ends dated, or null under two points. */
    fun picture(ctx: Context, data: Data): TrendPicture? {
        val w = data.weight ?: return null
        if (w.spark.size < 2) return null
        val units = data.units
        val zone = java.time.ZoneId.systemDefault()
        return TrendPicture.Line(
            w.spark.map { BodyUnits.toDisplay(BodyKind.WEIGHT, it, units).toFloat() }.toFloatArray(),
            TrendPictures.dayLabel(ctx, data.startDay) ?: ctx.getString(R.string.d2_trend_first),
            TrendPictures.dayLabel(ctx, Day.of(w.latestSec, zone)),
            CardStatusColors.of(ctx, status(data)) ?: ChartPalette.of(ctx).primary,
            goal = w.goalKgX100?.let { BodyUnits.toDisplay(BodyKind.WEIGHT, it, units).toFloat() },
        )
    }

    override fun small(ctx: Context, data: Data): SmallFace? {
        val w = data.weight ?: return null
        // Trend first, as on the large card.
        val trendX100 = w.spark.lastOrNull() ?: w.latestKgX100
        return SmallFace(
            value = BodyUnits.number(BodyKind.WEIGHT, trendX100, data.units),
            label = ctx.getString(R.string.small_trend),
            unit = BodyUnits.unit(BodyKind.WEIGHT, data.units),
            status = status(data),
            note = weekTag(ctx, data),
        )
    }

    override fun createSmallGraphic(ctx: Context): View = PictureFrame(ctx, small = true)

    override fun bindSmallGraphic(graphic: View, data: Data) {
        (graphic as PictureFrame).show(picture(graphic.context, data))
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(WeightDetailFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    private const val LINE_DAYS = 30
    private const val DAY_SEC = 86_400L

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_body_weight)) {
        private var units = Units.METRIC
        private val tile = find<StatTile>(R.id.weight_tile)
        private val line = find<PictureFrame>(R.id.weight_line)
        private val empty = find<EmptyState>(R.id.weight_empty)

        init {
            val card = root as SwCard
            card.setOnClickListener { host.open(WeightDetailFragment()) }
            card.onAction = { WeightEditor.log(host.fragment, units) }
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
        }

        override fun bind(data: Data) {
            val ctx = root.context
            units = data.units
            val w = data.weight
            tile.visibility = if (w == null) View.GONE else View.VISIBLE
            empty.visibility = if (w == null) View.VISIBLE else View.GONE
            if (w == null) {
                line.show(null)
                root.contentDescription = listOf(ctx.getString(R.string.body_weight), ctx.getString(R.string.weight_empty)).joinToString(". ")
                return
            }
            // Trend first: the smoothed weight leads.
            val trendX100 = w.spark.lastOrNull() ?: w.latestKgX100
            tile.value = BodyUnits.number(BodyKind.WEIGHT, trendX100, units)
            tile.unit = BodyUnits.unit(BodyKind.WEIGHT, units)
            tile.label = weekTag(ctx, data) ?: ctx.getString(R.string.small_trend)
            line.show(picture(ctx, data))
            val goal = w.goalKgX100?.let { ctx.getString(R.string.body_goal, Fmt.weight(it, units)) }
            root.contentDescription = listOfNotNull(
                ctx.getString(R.string.body_weight),
                "${tile.value} ${tile.unit}",
                tile.label,
                goal,
                CardStatusColors.spoken(ctx, status(data)),
            ).joinToString(". ")
        }
    }
}
