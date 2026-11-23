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
    /** "1.4" for a g/kg value. */
    fun perKg(v: Double): String = Fmt.number(v, 1)

    /** "Target 1.2 g/kg or more" or "Target band 1.2–2.0 g/kg". */
    fun target(ctx: Context, t: PerKgTarget): String {
        val high = t.high
        return if (high == null) {
            ctx.getString(R.string.food_card_target_min, perKg(t.low))
        } else {
            ctx.getString(R.string.food_card_target_band, perKg(t.low), perKg(high))
        }
    }

    /** "4 of 6 foods have sodium data" from [c] with the plural [plural], or null when every food has it. */
    fun coverage(ctx: Context, plural: Int, c: Coverage): String? =
        if (c.known >= c.entries) null else ctx.resources.getQuantityString(plural, c.entries, c.known, c.entries)

    /** The bar's end for a value against a target: a little past the band's top, or the value. */
    fun barMax(value: Double, t: PerKgTarget): Float = maxOf(value, (t.high ?: t.low) * BAR_HEADROOM).toFloat()

    private const val BAR_HEADROOM = 1.25
}
