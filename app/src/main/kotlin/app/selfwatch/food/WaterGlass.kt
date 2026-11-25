/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import android.content.Context
import app.selfwatch.R
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import app.selfwatch.ui.format.Units
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Water is counted in glasses of the user's units: 12 fl oz (355 ml) for US units, 350 ml for metric.
 * One tap adds one glass. Stored amounts stay in millilitres; the goal before one is set is
 * [DEFAULT_GLASSES] glasses (96 fl oz, or 2.8 L). Every water screen (card, Body today, + sheet,
 * Goals) uses this one rule.
 */
object WaterGlass {
    /** A US glass: 12 fl oz. */
    const val US_ML: Int = 355

    /** A metric glass. */
    const val METRIC_ML: Int = 350

    /** Glasses a day before a goal is set. */
    const val DEFAULT_GLASSES: Int = 8

    /** One glass in [units], in millilitres. */
    fun ml(units: Units): Int = if (units == Units.METRIC) METRIC_ML else US_ML

    /** The goal used while none is set: [DEFAULT_GLASSES] glasses (2,840 ml ≈ 96 fl oz, or 2,800 ml). */
    fun defaultGoalMl(units: Units): Int = DEFAULT_GLASSES * ml(units)

    /** The goal set, else [defaultGoalMl]. */
    fun goalMl(set: Int?, units: Units): Int = set?.takeIf { it > 0 } ?: defaultGoalMl(units)

    /** [amountMl] as whole glasses, to the nearest (an amount logged in the other units still counts). */
    fun glasses(amountMl: Int, units: Units): Int = ((amountMl + ml(units) / 2) / ml(units)).coerceAtLeast(0)

    /** Glasses for a goal of [goalMl]: at least one. */
    fun goalGlasses(goalMl: Int, units: Units): Int = (goalMl.toDouble() / ml(units)).roundToInt().coerceAtLeast(1)

    /** "1.2 L" or "40 fl oz" for [ml]. */
    fun volume(ctx: Context, ml: Int, units: Units): String =
        Fmt.volume(ml.toDouble(), units, Locale.getDefault(), FmtLabels.from(ctx))

    /** "36 of 96 fl oz" or "1.1 of 2.8 L" for [totalMl] against [goalMl]. */
    fun amountOfGoal(ctx: Context, totalMl: Int, goalMl: Int, units: Units): String {
        val labels = FmtLabels.from(ctx)
        val loc = Locale.getDefault()
        // Both amounts in the goal's unit so "0.9 of 2.8 L" never reads "900 of 2.8 L".
        val unit = Fmt.volumeUnit(goalMl.toDouble(), units, labels)
        val done = when {
            units == Units.IMPERIAL -> Fmt.number(totalMl / Fmt.ML_PER_FL_OZ, 0, loc)
            goalMl >= 1000 -> Fmt.number(totalMl / 1000.0, 1, loc)
            else -> Fmt.number(totalMl.toDouble(), 0, loc)
        }
        return ctx.getString(R.string.water_amount_of, done, Fmt.volumeNumber(goalMl.toDouble(), units, loc), unit)
    }

    /** "3 of 8 glasses" for [totalMl] against [goalMl]. */
    fun glassesOfGoal(ctx: Context, totalMl: Int, goalMl: Int, units: Units): String {
        val goalGlasses = goalGlasses(goalMl, units)
        return ctx.resources.getQuantityString(R.plurals.water_glasses_of, goalGlasses, glasses(totalMl, units), goalGlasses)
    }

    /** "3 of 8 glasses (36 of 96 fl oz)" for [totalMl] against [goalMl]. */
    fun ofGoal(ctx: Context, totalMl: Int, goalMl: Int, units: Units): String =
        ctx.getString(R.string.water_glasses_with_amount, glassesOfGoal(ctx, totalMl, goalMl, units), amountOfGoal(ctx, totalMl, goalMl, units))
}
