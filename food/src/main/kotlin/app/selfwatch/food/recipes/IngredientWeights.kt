/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.recipes

import app.selfwatch.food.nutrition.NutrientMath
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.Serving

/**
 * Turns a parsed amount and unit into grams for a matched food, in this order:
 * 1. A weight unit converts directly.
 * 2. One of the food's named servings in the same unit ("1 clove", "1 cup" = 128 g).
 * 3. A volume unit: millilitres × the food's density, taken from any of its servings with a volume
 *    unit, else water (1 g/ml).
 * 4. A container with a package size ("1 can (15 oz)").
 * 5. No unit: the food's first serving counted in items ("1 large", "1 medium", "1 sandwich").
 * 6. A pinch, dash or drop: small fixed weights.
 * Otherwise unknown (null).
 */
object IngredientWeights {

    /** Grams ×10 of [p] for [food] (null [food]: only rules 1, 3 with water, 4 and 6 apply), or null if unknown. */
    fun gramsX10(p: ParsedIngredient, food: Food?): Int? {
        val amount = p.amountX100?.toLong() ?: return null
        val unit = p.unit?.let { Units.byName(it) }
        val servings = food?.servings.orEmpty().filter { (it.gramsX10 ?: 0) > 0 }

        if (unit != null && unit.kind == UnitKind.MASS) return massX10(amount, unit)

        if (unit != null) {
            perServingUnit(servings, unit.name)?.let { (labelAmountX100, gX10) ->
                return NutrientMath.clampInt(NutrientMath.mulDivRound(amount * gX10, 1, labelAmountX100))
            }
        }
        if (unit != null && unit.kind == UnitKind.VOLUME) {
            return volumeX10(amount, unit, densityX1000(servings))
        }
        if (unit != null && unit.isContainer && p.packageAmountX100 != null && p.packageUnit != null) {
            val pu = Units.byName(p.packageUnit) ?: return null
            val each = when (pu.kind) {
                UnitKind.MASS -> massX10(p.packageAmountX100.toLong(), pu)
                UnitKind.VOLUME -> volumeX10(p.packageAmountX100.toLong(), pu, densityX1000(servings))
                UnitKind.COUNT -> null
            } ?: return null
            return NutrientMath.clampInt(NutrientMath.mulDivRound(each.toLong(), amount, 100))
        }
        if (unit == null) {
            countServing(servings)?.let { (labelAmountX100, gX10) ->
                return NutrientMath.clampInt(NutrientMath.mulDivRound(amount * gX10, 1, labelAmountX100))
            }
            return null
        }
        Units.FALLBACK_GRAMS_X10[unit.name]?.let { return NutrientMath.clampInt(NutrientMath.mulDivRound(it.toLong(), amount, 100)) }
        return null
    }

    private fun massX10(amountX100: Long, unit: Units.Unit): Int =
        // amount/100 × mg-per-unit/1000 g × 10  =  amountX100 × perUnitX1000 / 10 000
        NutrientMath.clampInt(NutrientMath.mulDivRound(amountX100, unit.perUnitX1000, 10_000))

    private fun volumeX10(amountX100: Long, unit: Units.Unit, densityX1000: Long): Int {
        // amount/100 × µl/1000 × density/1000 × 10  =  amountX100 × µl × density / 10 000 000
        val ulTotal = amountX100 * unit.perUnitX1000
        return NutrientMath.clampInt(NutrientMath.mulDivRound(ulTotal, densityX1000, 10_000_000))
    }

    /** The first serving whose label is in [unitName], as (label amount ×100, grams ×10). */
    private fun perServingUnit(servings: List<Serving>, unitName: String): Pair<Long, Long>? {
        for (s in servings) {
            val lp = IngredientParser.parse(s.label)
            val a = lp.amountX100 ?: continue
            if (a > 0 && lp.unit == unitName) return a.toLong() to (s.gramsX10 ?: continue).toLong()
        }
        return null
    }

    /** The first serving counted in items (no unit, or "piece"/"item"-like), as (label amount ×100, grams ×10). */
    private fun countServing(servings: List<Serving>): Pair<Long, Long>? {
        for (s in servings.sortedBy { it.seq }) {
            val lp = IngredientParser.parse(s.label)
            val a = lp.amountX100 ?: continue
            if (a <= 0) continue
            if (lp.unit == null || lp.unit == "piece") return a.toLong() to (s.gramsX10 ?: continue).toLong()
        }
        return null
    }

    /** Density g/ml ×1000 from the first serving with a volume unit; water (1000) when none. */
    private fun densityX1000(servings: List<Serving>): Long {
        for (s in servings) {
            val lp = IngredientParser.parse(s.label)
            val a = lp.amountX100 ?: continue
            val u = lp.unit?.let { Units.byName(it) } ?: continue
            if (u.kind != UnitKind.VOLUME || a <= 0) continue
            val g = s.gramsX10 ?: continue
            // grams/ml ×1000 = (g/10) / (a/100 × µl/1000) × 1000 = g × 10^7 / (a × µl)
            val d = NutrientMath.mulDivRound(g.toLong(), 10_000_000L, a.toLong() * u.perUnitX1000)
            if (d > 0) return d
        }
        return WATER_DENSITY_X1000
    }

    private const val WATER_DENSITY_X1000 = 1000L
}
