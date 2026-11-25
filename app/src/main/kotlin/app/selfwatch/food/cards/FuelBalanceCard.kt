/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.cards

import android.content.Context
import android.view.ViewGroup
import app.selfwatch.R
import app.selfwatch.body.MeasurementsFragment
import app.selfwatch.body.WeightDetailFragment
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import kotlin.math.roundToInt

/**
 * Fuel for training (C24), a soft flag: the 7-day average of (kcal eaten − active kcal) per kg of
 * fat-free mass (weight × (1 − body fat %)). Under about 30 kcal/kg it says "you may be under-fuelling
 * for your training"; it never names a condition or diagnoses. Without body fat it explains what it needs.
 */
object FuelBalanceCard : Card<InUnits<FuelBalance>>(
    "fuel_balance",
    CardTab.FOOD,
    R.string.card_fuel_balance,
    R.string.card_fuel_balance_summary,
) {
    override val tables: Set<Table> = setOf(Table.DIARY, Table.BODY, Table.DAY_SUMMARY)

    override suspend fun load(env: CardEnv): InUnits<FuelBalance> = InUnits(FoodCardLoader(env.db, env.clock).fuelBalance(env.day), env.units)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override fun small(ctx: Context, data: InUnits<FuelBalance>): SmallFace? = small(ctx, data.value, data.units)

    private fun small(ctx: Context, data: FuelBalance, units: Units): SmallFace? {
        val label = ctx.getString(R.string.small_fuel_label)
        val missing = when (data.missing) {
            FuelMissing.WEIGHT -> R.string.small_needs_weight
            FuelMissing.BODY_FAT -> R.string.small_needs_body_fat
            FuelMissing.DAYS, FuelMissing.NONE -> null
        }
        if (missing != null) return SmallFace(value = ctx.getString(R.string.dash), label = label, note = ctx.getString(missing))
        val value = data.kcalPerKgFfm ?: return null
        // A soft flag, never an alarm: low is "close", not "off".
        return SmallFace(
            value = FoodCardText.perKg(value.toDouble(), units, decimals = 0),
            label = label,
            unit = FoodCardText.kcalUnit(ctx, units),
            status = if (data.low) CardStatus.CLOSE else CardStatus.GOOD,
            note = ctx.getString(if (data.low) R.string.small_fuel_low else R.string.small_fuel_ok),
        )
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<InUnits<FuelBalance>> = Holder(host, parent)

    private class Holder(host: CardHost, parent: ViewGroup) :
        FoodMetricView<InUnits<FuelBalance>>(parent, R.id.food_fuel_balance_card, R.string.card_fuel_balance) {

        private var missing = FuelMissing.NONE

        init {
            empty.onButton = {
                when (missing) {
                    FuelMissing.WEIGHT -> host.open(WeightDetailFragment())
                    FuelMissing.BODY_FAT -> host.open(MeasurementsFragment())
                    FuelMissing.DAYS, FuelMissing.NONE -> Unit
                }
            }
        }

        override fun bind(data: InUnits<FuelBalance>) = bind(data.value, data.units)

        private fun bind(data: FuelBalance, units: Units) {
            missing = data.missing
            val value = data.kcalPerKgFfm
            val fewDays = ctx.resources.getQuantityString(R.plurals.fuel_balance_few_days, data.loggedDays, data.loggedDays)
            when (data.missing) {
                FuelMissing.WEIGHT -> {
                    empty.buttonText = ctx.getString(R.string.food_card_log_weight)
                    showEmpty(ctx.getString(R.string.food_card_no_weight))
                    return
                }
                FuelMissing.BODY_FAT -> {
                    empty.buttonText = ctx.getString(R.string.fuel_balance_add_body_fat)
                    showEmpty(ctx.getString(R.string.fuel_balance_no_body_fat))
                    return
                }
                FuelMissing.DAYS, FuelMissing.NONE -> empty.buttonText = null
            }
            if (value == null) {
                showEmpty(fewDays)
                return
            }
            showContent()
            tile.value = FoodCardText.perKg(value.toDouble(), units, decimals = 0)
            tile.unit = FoodCardText.kcalUnit(ctx, units)
            tile.label = ctx.getString(R.string.fuel_balance_label)
            val limit = FoodCardMath.LOW_FUEL_KCAL_PER_KG_FFM.toFloat()
            bar.set(
                value.coerceAtLeast(0).toFloat(),
                maxOf(value.toFloat(), limit * 2),
                if (data.low) palette.alarm else palette.primary,
                limit = limit,
            )
            show(bar, true)
            val line = if (data.low) {
                ctx.getString(R.string.fuel_balance_low)
            } else {
                val ok = ctx.getString(R.string.per_weight_value, FoodCardText.perKg(FoodCardMath.LOW_FUEL_KCAL_PER_KG_FFM.toDouble(), units, decimals = 0), FoodCardText.kcalUnit(ctx, units))
                ctx.getString(R.string.fuel_balance_ok, ok)
            }
            text(note, line)
            text(
                coverage,
                ctx.resources.getQuantityString(
                    R.plurals.fuel_balance_inputs,
                    data.loggedDays,
                    Fmt.number((data.avgEatenKcal ?: 0).toLong()),
                    Fmt.number((data.avgActiveKcal ?: 0).toLong()),
                    Fmt.weight(((data.ffmKg ?: 0.0) * 100).roundToInt(), units),
                    data.loggedDays,
                ),
            )
            card.contentDescription = ctx.getString(
                R.string.food_card_description,
                ctx.getString(R.string.card_fuel_balance),
                "${tile.value} ${tile.unit}",
                line,
            )
        }
    }
}
