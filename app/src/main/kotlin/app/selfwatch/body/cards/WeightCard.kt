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
import app.selfwatch.body.BodyLoader
import app.selfwatch.body.BodyTrendLoader
import app.selfwatch.body.BodyUnits
import app.selfwatch.body.RangeStatus
import app.selfwatch.body.WeightDetailFragment
import app.selfwatch.body.WeightEditor
import app.selfwatch.body.WeightBand
import app.selfwatch.body.WeightInfo
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.time.Day
import app.selfwatch.ui.components.Sparkline
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import java.time.ZoneId
import kotlin.math.abs
import app.selfwatch.ui.R as UiR

/**
 * C2 Weight: the smoothed trend first (the latest weigh-in beneath; daily weight swings with water), the
 * 7- and 90-day trend change, the goal, a sparkline of the trend, and a band for the main goal: lose
 * weight 0.5–1 kg a week, weight management within 3% either side of the goal, build muscle
 * +0.25–0.5% of body weight a week, stay active a note on a 5% fall in 90 days. Opens Weight.
 */
object WeightCard : Card<WeightCard.Data>("weight", CardTab.BODY, R.string.card_weight, R.string.card_weight_summary) {

    /** The weight (null with no weigh-ins) in display [units], and the main goal's [band] (null for none). */
    data class Data(val weight: WeightInfo?, val units: Units, val band: WeightBand? = null)

    override val tables: Set<Table> = setOf(Table.BODY, Table.GOAL, Table.SETTING)

    override suspend fun load(env: CardEnv): Data =
        Data(BodyLoader(env.db, env.clock).weight(), env.units, BodyTrendLoader(env.db, env.clock).weightBand())

    override val needs: Set<DataKind> = setOf(DataKind.WEIGHT)

    override fun small(ctx: Context, data: Data): SmallFace? {
        val w = data.weight ?: return null
        val units = data.units
        // Trend first, as on the large card.
        val trendX100 = w.spark.lastOrNull() ?: w.latestKgX100
        val unit = BodyUnits.unit(BodyKind.WEIGHT, units)
        val note = w.weekChangeX100?.let {
            val sign = if (it < 0) "−" else if (it > 0) "+" else ""
            ctx.getString(R.string.body_this_week, sign + Fmt.number(BodyUnits.toDisplay(BodyKind.WEIGHT, abs(it), units), 1) + " " + unit)
        }
        val b = data.band
        val status = when {
            b == null -> CardStatus.NONE
            b.kind == WeightBand.Kind.UNPLANNED_LOSS -> CardStatus.CLOSE
            b.status == null -> CardStatus.NONE
            b.status == RangeStatus.WITHIN -> CardStatus.GOOD
            else -> CardStatus.CLOSE
        }
        return SmallFace(
            value = BodyUnits.number(BodyKind.WEIGHT, trendX100, units),
            label = ctx.getString(R.string.small_trend),
            unit = unit,
            status = status,
            note = note,
        )
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(WeightDetailFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_body_weight)) {
        private var units = Units.METRIC

        init {
            val card = root as SwCard
            card.setOnClickListener { host.open(WeightDetailFragment()) }
            card.onAction = { WeightEditor.log(host.fragment, units) }
        }

        override fun bind(data: Data) {
            val ctx = root.context
            units = data.units
            val tile = find<StatTile>(R.id.weight_tile)
            val goal = find<TextView>(R.id.weight_goal)
            val spark = find<Sparkline>(R.id.weight_spark)
            val bandText = find<TextView>(R.id.weight_band)
            val note = find<TextView>(R.id.weight_note)
            val w = data.weight
            if (w == null) {
                tile.value = ctx.getString(R.string.dash)
                tile.unit = null
                tile.label = ctx.getString(R.string.body_weight_empty)
                tile.delta = null
                tile.deltaText = null
                goal.visibility = View.GONE
                spark.visibility = View.GONE
                bandText.visibility = View.GONE
                note.visibility = View.GONE
                return
            }
            // Trend first: the smoothed weight leads, the latest weigh-in sits beneath it.
            val trendX100 = w.spark.lastOrNull() ?: w.latestKgX100
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
            tile.value = BodyUnits.number(BodyKind.WEIGHT, trendX100, units)
            tile.unit = BodyUnits.unit(BodyKind.WEIGHT, units)
            tile.label = ctx.getString(
                R.string.weight_trend_label,
                Fmt.weight(w.latestKgX100, units),
                Fmt.day(ctx, Day.of(w.latestSec, ZoneId.systemDefault()).yyyymmdd),
            )
            val band = data.band?.let { bandLine(it) }
            bandText.text = band
            bandText.visibility = if (band == null) View.GONE else View.VISIBLE
            note.visibility = View.VISIBLE
            val wk = w.weekChangeX100
            tile.delta = wk?.toFloat()
            tile.deltaText = wk?.let { ctx.getString(R.string.body_this_week, weightNumber(abs(it))) }
            val goalParts = ArrayList<String>()
            w.goalKgX100?.let { goalParts += ctx.getString(R.string.body_goal, Fmt.weight(it, units)) }
            w.longChangeX100?.let {
                goalParts += ctx.getString(if (it <= 0) R.string.body_down_90 else R.string.body_up_90, weightNumber(abs(it)))
            }
            goal.text = goalParts.joinToString(" · ")
            goal.visibility = if (goalParts.isEmpty()) View.GONE else View.VISIBLE
            spark.values = w.spark.toIntArray()
            spark.visibility = if (w.spark.size >= 2) View.VISIBLE else View.GONE
        }

        private fun weightNumber(kgX100: Int): String = Fmt.number(BodyUnits.toDisplay(BodyKind.WEIGHT, kgX100, units), 1)

        /** "−0.6 kg" (signed, with unit). */
        private fun signedWeight(kgX100: Int): String = (if (kgX100 < 0) "−" else if (kgX100 > 0) "+" else "") + Fmt.weight(abs(kgX100), units)

        /** The goal band as one line. */
        private fun bandLine(b: WeightBand): String {
            val ctx = root.context
            return when (b.kind) {
                WeightBand.Kind.LOSS_RATE, WeightBand.Kind.GAIN_RATE -> {
                    val lo = minOf(abs(b.lowX100), abs(b.highX100))
                    val hi = maxOf(abs(b.lowX100), abs(b.highX100))
                    val low = weightNumber(lo)
                    val high = Fmt.weight(hi, units)
                    val rate = b.ratePerWeekX100
                    when {
                        rate == null || b.status == null -> ctx.getString(R.string.weight_band_rate_unknown, low, high)
                        b.status == RangeStatus.WITHIN -> ctx.getString(R.string.weight_band_rate_within, signedWeight(rate), low, high)
                        b.status == RangeStatus.BELOW -> ctx.getString(R.string.weight_band_rate_below, signedWeight(rate), low, high)
                        else -> ctx.getString(R.string.weight_band_rate_above, signedWeight(rate), low, high)
                    }
                }
                WeightBand.Kind.MAINTAIN -> ctx.getString(
                    if (b.status == RangeStatus.WITHIN) R.string.weight_band_maintain_within else R.string.weight_band_maintain_out,
                    Fmt.weight((b.lowX100 + b.highX100) / 2, units),
                    weightNumber(b.lowX100),
                    Fmt.weight(b.highX100, units),
                )
                WeightBand.Kind.UNPLANNED_LOSS -> ctx.getString(
                    R.string.weight_band_unplanned,
                    Fmt.number(abs(b.changePctX10 ?: 0) / 10.0, 1) + " " + ctx.getString(R.string.unit_percent),
                )
            }
        }
    }
}
