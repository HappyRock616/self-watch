/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.cards

import android.content.Context
import android.view.View
import android.view.ViewGroup
import app.selfwatch.cards.PictureFrame
import app.selfwatch.cards.TrendPicture
import app.selfwatch.R
import app.selfwatch.body.WeightDetailFragment
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardStatusColors
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import java.util.Locale

/**
 * Carbs per kg (C9): carbs for the day shown ÷ the latest weight against a band set by the last 7 days'
 * average training time from workouts (under 1 h a day 3–5 g/kg, about 1 h 5–7, 1–3 h 6–10, 4 h or
 * more 8–12).
 */
object CarbsPerKgCard : Card<InUnits<CarbsPerKg>>(
    "carbs_per_kg",
    CardTab.FOOD,
    R.string.card_carbs_per_kg,
    R.string.card_carbs_per_kg_summary,
) {
    override val tables: Set<Table> = setOf(Table.DIARY, Table.BODY, Table.WORKOUT)

    override suspend fun load(env: CardEnv): InUnits<CarbsPerKg> = InUnits(FoodCardLoader(env.db, env.clock).carbsPerKg(env.day), env.units)

    override fun small(ctx: Context, data: InUnits<CarbsPerKg>): SmallFace? {
        val c = data.value
        if (c.kgX100 == null) {
            return SmallFace(
                value = ctx.getString(R.string.dash),
                label = ctx.getString(R.string.small_per_kg),
                unit = FoodCardText.gramsUnit(ctx, data.units),
                note = ctx.getString(R.string.small_needs_weight),
            )
        }
        val perKg = c.perKg ?: return null
        val carbs = c.carbsG ?: return null
        val t = c.band.target
        val high = t.high
        val status = when {
            perKg < t.low -> CardStatusColors.ofProgress(perKg, t.low)
            high != null && perKg > high -> CardStatus.CLOSE
            else -> CardStatus.GOOD
        }
        return SmallFace(
            value = FoodCardText.perKg(perKg, data.units),
            label = if (high == null) {
                ctx.getString(R.string.small_aim_min, FoodCardText.perKg(t.low, data.units))
            } else {
                ctx.getString(R.string.small_aim_band, FoodCardText.perKg(t.low, data.units), FoodCardText.perKg(high, data.units))
            },
            unit = FoodCardText.gramsUnit(ctx, data.units),
            status = status,
            note = ctx.getString(R.string.small_carbs_grams, Fmt.number(carbs.toLong())),
            progress = if (t.low > 0) (perKg / t.low).toFloat() else null,
        )
    }

    /** Carbs per kg on a range bar: the training band marked with its edges; null without a value. */
    fun picture(ctx: Context, data: InUnits<CarbsPerKg>): TrendPicture? {
        val c = data.value
        val perKg = c.perKg ?: return null
        if (c.kgX100 == null || c.carbsG == null) return null
        val t = c.band.target
        val ticks = listOfNotNull(t.low, t.high).map { it.toFloat() to FoodCardText.perKg(it, data.units) }
        return FoodPictures.target(ctx, perKg, t.low, t.high, ticks)
    }

    override fun createSmallGraphic(ctx: Context): View = PictureFrame(ctx, small = true)

    override fun bindSmallGraphic(graphic: View, data: InUnits<CarbsPerKg>) {
        (graphic as PictureFrame).show(picture(graphic.context, data))
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<InUnits<CarbsPerKg>> = Holder(host, parent)

    private class Holder(host: CardHost, parent: ViewGroup) :
        FoodMetricView<InUnits<CarbsPerKg>>(parent, R.id.food_carbs_per_kg_card, R.string.card_carbs_per_kg) {

        init {
            empty.onButton = { host.open(WeightDetailFragment()) }
        }

        override fun bind(data: InUnits<CarbsPerKg>) {
            val c = data.value
            val kg = c.kgX100
            if (kg == null) {
                empty.buttonText = ctx.getString(R.string.food_card_log_weight)
                showEmpty(ctx.getString(R.string.food_card_no_weight))
                return
            }
            val perKg = c.perKg
            if (perKg == null || c.carbsG == null) {
                empty.buttonText = null
                showEmpty(ctx.getString(R.string.carbs_kg_empty))
                return
            }
            val t = c.band.target
            tile.value = FoodCardText.perKg(perKg, data.units)
            tile.unit = FoodCardText.gramsUnit(ctx, data.units)
            val target = FoodCardText.target(ctx, t, data.units)
            tile.label = ProteinPerKgCard.aim(ctx, t, data.units)
            showContent(picture(ctx, data))
            val training = if (c.avgTrainingMin <= 0) {
                ctx.getString(R.string.carbs_kg_training_none)
            } else {
                ctx.getString(R.string.carbs_kg_training, Fmt.duration(c.avgTrainingMin * 60L, Locale.getDefault(), FmtLabels.from(ctx)))
            }
            val inputs = ctx.getString(R.string.carbs_kg_inputs, Fmt.number(c.carbsG.toLong()), Fmt.weight(kg, data.units))
            card.contentDescription = ctx.getString(
                R.string.food_card_description, ctx.getString(R.string.card_carbs_per_kg), "${tile.value} ${tile.unit}", "$target. $training. $inputs",
            )
        }
    }
}
