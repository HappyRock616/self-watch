/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.cards

import android.content.Context
import app.selfwatch.R
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units

/** A loader result with the display units it is shown in. */
data class InUnits<T>(val value: T, val units: Units)

/** Text shared by the Food number cards. */
object FoodCardText {
    /**
     * A per-kg value (g/kg, kcal/kg) in [units]: "1.4" per kg, or per pound for US units ("0.64"; two
     * decimals under 10 so small values never round to 0). [decimals] is used per kg.
     */
    fun perKg(v: Double, units: Units, decimals: Int = 1): String = when (units) {
        Units.METRIC -> Fmt.number(v, decimals)
        Units.IMPERIAL -> {
            val perLb = v * Fmt.KG_PER_POUND
            Fmt.number(perLb, if (decimals == 0) 0 else if (perLb < 10) 2 else 1)
        }
    }

    /** "g/kg" or "g/lb". */
    fun gramsUnit(ctx: Context, units: Units): String =
        ctx.getString(if (units == Units.METRIC) R.string.food_card_unit_g_per_kg else R.string.unit_g_per_lb)

    /** "kcal/kg" or "kcal/lb". */
    fun kcalUnit(ctx: Context, units: Units): String =
        ctx.getString(if (units == Units.METRIC) R.string.food_card_unit_kcal_per_kg else R.string.unit_kcal_per_lb)

    /** "1.2 g/kg" or "0.54 g/lb". */
    fun grams(ctx: Context, v: Double, units: Units): String = ctx.getString(R.string.per_weight_value, perKg(v, units), gramsUnit(ctx, units))

    /** "1.2–2.0 g/kg" or "0.54–0.91 g/lb". */
    fun gramsRange(ctx: Context, low: Double, high: Double, units: Units): String =
        ctx.getString(R.string.per_weight_range, perKg(low, units), perKg(high, units), gramsUnit(ctx, units))

    /** "Target 1.2 g/kg or more" or "Target band 1.2–2.0 g/kg" (per lb for US units). */
    fun target(ctx: Context, t: PerKgTarget, units: Units): String {
        val high = t.high
        return if (high == null) {
            ctx.getString(R.string.food_card_target_min, grams(ctx, t.low, units))
        } else {
            ctx.getString(R.string.food_card_target_band, perKg(t.low, units), ctx.getString(R.string.per_weight_value, perKg(high, units), gramsUnit(ctx, units)))
        }
    }

    /** "4 of 6 foods have sodium data" from [c] with the plural [plural], or null when every food has it. */
    fun coverage(ctx: Context, plural: Int, c: Coverage): String? =
        if (c.known >= c.entries) null else ctx.resources.getQuantityString(plural, c.entries, c.known, c.entries)

    /** The bar's end for a value against a target: a little past the band's top, or the value. */
    fun barMax(value: Double, t: PerKgTarget): Float = maxOf(value, (t.high ?: t.low) * BAR_HEADROOM).toFloat()

    private const val BAR_HEADROOM = 1.25
}
