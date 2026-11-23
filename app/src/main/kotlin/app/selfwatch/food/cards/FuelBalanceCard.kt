/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.cards

import android.view.ViewGroup
import app.selfwatch.R
import app.selfwatch.body.MeasurementsFragment
import app.selfwatch.body.WeightDetailFragment
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.db.Table
import app.selfwatch.ui.format.Fmt

/**
 * Fuel for training (C24), a soft flag: the 7-day average of (kcal eaten − active kcal) per kg of
 * fat-free mass (weight × (1 − body fat %)). Under about 30 kcal/kg it says "you may be under-fuelling
 * for your training"; it never names a condition or diagnoses. Without body fat it explains what it needs.
 */
object FuelBalanceCard : Card<FuelBalance>(
    "fuel_balance",
    CardTab.FOOD,
    R.string.card_fuel_balance,
    R.string.card_fuel_balance_summary,
) {
    override val tables: Set<Table> = setOf(Table.DIARY, Table.BODY, Table.DAY_SUMMARY)

    override suspend fun load(env: CardEnv): FuelBalance = FoodCardLoader(env.db, env.clock).fuelBalance(env.day)

    override fun createView(host: CardHost, parent: ViewGroup): CardView<FuelBalance> = Holder(host, parent)

    private class Holder(host: CardHost, parent: ViewGroup) :
        FoodMetricView<FuelBalance>(parent, R.id.food_fuel_balance_card, R.string.card_fuel_balance) {

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

        override fun bind(data: FuelBalance) {
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
            tile.value = Fmt.number(value.toLong())
            tile.unit = ctx.getString(R.string.food_card_unit_kcal_per_kg)
            tile.label = ctx.getString(R.string.fuel_balance_label)
            val limit = FoodCardMath.LOW_FUEL_KCAL_PER_KG_FFM.toFloat()
            bar.set(
                value.coerceAtLeast(0).toFloat(),
                maxOf(value.toFloat(), limit * 2),
                if (data.low) palette.alarm else palette.primary,
                limit = limit,
            )
            show(bar, true)
            val line = ctx.getString(if (data.low) R.string.fuel_balance_low else R.string.fuel_balance_ok)
            text(note, line)
            text(
                coverage,
                ctx.resources.getQuantityString(
                    R.plurals.fuel_balance_inputs,
                    data.loggedDays,
                    Fmt.number((data.avgEatenKcal ?: 0).toLong()),
                    Fmt.number((data.avgActiveKcal ?: 0).toLong()),
                    Fmt.number(data.ffmKg ?: 0.0, 1),
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
