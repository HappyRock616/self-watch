/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import app.selfwatch.food.nutrition.NutrientMath
import app.selfwatch.food.source.FoodHit
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.MealSlot
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Serving
import app.selfwatch.ui.format.Units

/** Pure helpers shared by the food screens: rows, servings, calories and the meal guess. */
object FoodRows {

    /** Default amount: one serving (×100). */
    const val ONE_X100: Int = 100

    /** Default weight for foods logged by the gram: 100 g (amount ×100). */
    const val HUNDRED_GRAMS_X100: Int = 10_000

    /** Label of the serving offered for per-item foods that name none. */
    const val ONE_SERVING: String = "1 serving"

    /** The source tag for foods of [origin]. */
    fun tagFor(origin: FoodOrigin): String = when (origin) {
        FoodOrigin.MY_FOOD -> FoodHit.TAG_MY_FOOD
        FoodOrigin.RECIPE -> FoodHit.TAG_RECIPE
        FoodOrigin.FOODS_DB -> FoodHit.TAG_FOODS_DB
        FoodOrigin.OPEN_FOOD_FACTS -> FoodHit.TAG_OPEN_FOOD_FACTS
    }

    /** A list row for [food]: its default serving and that serving's energy. */
    fun hitOf(food: Food, tag: String, rank: Int = 0): FoodHit {
        val (serving, amount) = defaultServing(food)
        val kcal = NutrientMath.forServing(food, serving, amount)?.kcalX10
        val label = if (serving == NutrientMath.GRAM) "100 g" else serving.label
        return FoodHit(food.ref, food.name, food.brand, kcal, label, tag, rank, food.kind, dietInfo = food.dietInfo.takeUnless { it.isUnknown })
    }

    /**
     * The servings the user can pick for [food]: its named servings, then grams for per-weight foods.
     * A per-item food without servings gets one plain "1 serving".
     */
    fun servingOptions(food: Food): List<Serving> {
        val out = ArrayList<Serving>(food.servings.sortedBy { it.seq })
        when (food.basis) {
            Basis.PER_100G, Basis.PER_100ML -> {
                out.removeAll { it.gramsX10 == null }
                out.add(NutrientMath.GRAM)
            }
            Basis.PER_ITEM -> if (out.isEmpty()) out.add(Serving(0, ONE_SERVING, null))
        }
        return out
    }

    /** "100 g" as a serving (one of it is 100 g). */
    val HUNDRED_G: Serving = Serving(seq = -2, label = "100 g", gramsX10 = 1000)

    /** One ounce (28.35 g) as a serving. */
    val OUNCE: Serving = Serving(seq = -3, label = "oz", gramsX10 = 283)

    /**
     * The food detail's serving list (FOOD_UI_PLAN §4.1): the food's named servings, then for foods
     * measured by weight or volume "100 g" and "g", and "oz" for US [units] only. A per-item food without
     * servings gets "1 serving".
     */
    fun detailServingOptions(food: Food, units: Units): List<Serving> {
        val out = ArrayList(servingOptions(food))
        if (food.basis != Basis.PER_ITEM) {
            out.remove(NutrientMath.GRAM)
            out.removeAll { it.label == HUNDRED_G.label && it.gramsX10 == HUNDRED_G.gramsX10 }
            out.add(HUNDRED_G)
            out.add(NutrientMath.GRAM)
            if (units == Units.IMPERIAL) out.add(OUNCE)
        }
        return out
    }

    /** The serving and amount to log [food] with when nothing was logged before. */
    fun defaultServing(food: Food): Pair<Serving, Int> {
        val first = servingOptions(food).first()
        return if (first == NutrientMath.GRAM) first to HUNDRED_GRAMS_X100 else first to ONE_X100
    }

    /**
     * The serving and amount for a one-tap re-log: [last] (from the diary) when it can be logged for
     * [food], else [defaultServing].
     */
    fun quickServing(food: Food, last: Pair<Serving, Int>?): Pair<Serving, Int> {
        if (last != null && last.second > 0 && NutrientMath.forServing(food, last.first, last.second) != null) {
            val named = servingOptions(food).firstOrNull { it.label == last.first.label && it.gramsX10 == last.first.gramsX10 }
            return (named ?: last.first) to last.second
        }
        return defaultServing(food)
    }

    /** Whole kilocalories of [n] (0 when unknown). */
    fun kcal(n: Nutrients?): Int = kcalOfX10(n?.kcalX10)

    /** Whole kilocalories from kcal ×10, rounded (0 when null). */
    fun kcalOfX10(kcalX10: Int?): Int =
        kcalX10?.let { NutrientMath.clampInt(NutrientMath.divRound(it.toLong(), 10)) } ?: 0

    /**
     * The meal slot that fits the time of day: before 11:00 the first slot (breakfast), before 16:00
     * the second (lunch), before 21:00 the third (dinner), else the fourth (snacks); hidden slots are
     * skipped. Null when there are no visible slots.
     */
    fun slotForHour(hour: Int, slots: List<MealSlot>): MealSlot? {
        val visible = slots.filter { !it.hidden }.sortedBy { it.order }
        if (visible.isEmpty()) return null
        val index = when {
            hour < 11 -> 0
            hour < 16 -> 1
            hour < 21 -> 2
            else -> 3
        }
        return visible[index.coerceAtMost(visible.size - 1)]
    }
}
