/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.cards

import app.selfwatch.model.food.Nutrients
import kotlin.math.roundToInt

/** A macronutrient of the Daily balance card. */
enum class Macro(
    /** Calories per gram. */
    val kcalPerG: Int,
    /** The balanced share of calories, in percent (US Dietary Guidelines' acceptable ranges for adults). */
    val range: IntRange,
    /** The share used for a target when the user set none (inside [range]). */
    val defaultPct: Int,
) {
    PROTEIN(4, 10..35, 20),
    CARBS(4, 45..65, 50),
    FAT(9, 20..35, 30),
}

/** One nutrient line: [value] eaten (g, or mg for sodium; null: nothing with it logged), against [target] (a goal, or a limit when [isLimit]). */
data class NutrientLine(val value: Double?, val target: Double?, val isLimit: Boolean)

/**
 * Nutrient fields that arrive with food database v2 (branch food-v2: `Nutrients.transfatX10`,
 * `monofatX10`, `polyfatX10`, `addsugarX10`). Until that lands they are null and the Daily balance card
 * leaves those lines out.
 *
 * FOOD-V2 HOOK: once `Nutrients` has the v2 fields, return them here, e.g. `n.transfatX10`. Nothing else
 * needs to change; the card shows each line when its value is not null.
 */
object NutrientsV2 {
    /** Trans fat, g ×10. */
    fun transFatX10(n: Nutrients): Int? = n.transfatX10

    /** Monounsaturated fat, g ×10. */
    fun monoFatX10(n: Nutrients): Int? = n.monofatX10

    /** Polyunsaturated fat, g ×10. */
    fun polyFatX10(n: Nutrients): Int? = n.polyfatX10

    /** Added sugar, g ×10. */
    fun addedSugarX10(n: Nutrients): Int? = n.addsugarX10
}

/** Daily balance arithmetic (pure). */
object DailyBalanceMath {
    /** Sodium limit a day, mg (US Dietary Guidelines). */
    const val SODIUM_LIMIT_MG: Int = 2_300

    /** Saturated fat and added sugar each under 10% of calories. */
    const val LIMIT_SHARE: Double = 0.10

    /** Fiber: 14 g per 1,000 kcal. */
    const val FIBER_G_PER_1000: Double = 14.0

    private const val KCAL_PER_G_FAT = 9.0
    private const val KCAL_PER_G_CARB = 4.0
    private const val PCT = 100.0

    /** The gram target of [macro] for [goalKcal]: the user's own goal [ownG] if set, else its default share. */
    fun target(macro: Macro, goalKcal: Int, ownG: Int?): Double = ownG?.toDouble() ?: (goalKcal * macro.defaultPct / PCT / macro.kcalPerG)

    /** Each macro's share of the calories from [proteinG], [carbsG] and [fatG], in percent (empty when none). */
    fun shares(proteinG: Double, carbsG: Double, fatG: Double): Map<Macro, Int> {
        val kcal = mapOf(Macro.PROTEIN to proteinG * Macro.PROTEIN.kcalPerG, Macro.CARBS to carbsG * Macro.CARBS.kcalPerG, Macro.FAT to fatG * Macro.FAT.kcalPerG)
        val total = kcal.values.sum()
        if (total <= 0) return emptyMap()
        return kcal.mapValues { (it.value / total * PCT).roundToInt() }
    }

    /** True when [pct] of [macro] lies in its balanced range. */
    fun balanced(macro: Macro, pct: Int): Boolean = pct in macro.range

    /** Saturated fat limit in grams for [goalKcal]. */
    fun satFatLimitG(goalKcal: Int): Double = goalKcal * LIMIT_SHARE / KCAL_PER_G_FAT

    /** Added sugar limit in grams for [goalKcal]. */
    fun addedSugarLimitG(goalKcal: Int): Double = goalKcal * LIMIT_SHARE / KCAL_PER_G_CARB

    /** Fiber target in grams for [goalKcal]. */
    fun fiberTargetG(goalKcal: Int): Double = goalKcal * FIBER_G_PER_1000 / 1000.0

    /** Grams from a ×10 value. */
    fun g(x10: Int?): Double? = x10?.let { it / 10.0 }
}
