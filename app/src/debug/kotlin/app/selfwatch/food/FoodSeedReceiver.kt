/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.selfwatch.db.CustomFoodOrigin
import app.selfwatch.db.dao.CustomFood
import app.selfwatch.graph
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.MatchState
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Recipe
import app.selfwatch.model.food.RecipeItem
import app.selfwatch.model.food.RecipeKind
import app.selfwatch.model.food.Serving
import app.selfwatch.model.log.SwLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Debug builds only: fills the food diary with sample foods for screenshots and manual testing.
 * Guarded by the DUMP permission, so only `adb shell` can send it:
 * `adb shell am broadcast -a app.selfwatch.debug.SEED_FOOD -p app.selfwatch.debug`.
 * Does nothing when custom foods already exist.
 */
class FoodSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val food = context.graph.food
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (food.db.foods.list().isNotEmpty()) return@launch
                val today = food.clock.today()
                val ids = HashMap<String, Food>()
                for (f in SAMPLES) {
                    val id = food.db.foods.insert(CustomFood(f, CustomFoodOrigin.TYPED, null, false, food.clock.nowSec(), food.clock.nowSec(), null))
                    ids[f.name] = requireNotNull(food.myFoods.get(id))
                }
                suspend fun log(day: app.selfwatch.model.time.Day, slot: Long, name: String, amountX100: Int = 100) {
                    val f = requireNotNull(ids[name])
                    food.diary.add(day, slot, f, f.servings.first(), amountX100)
                }
                log(today, 1, "Oatmeal")
                log(today, 1, "Banana")
                log(today, 1, "Coffee with milk")
                log(today, 2, "Burrito bowl")
                log(today.plusDays(-1), 3, "Salmon fillet")
                log(today.plusDays(-1), 3, "Brown rice", 150)
                log(today.plusDays(-1), 3, "Side salad")
                ids["Greek yogurt"]?.let { food.db.favourites.add(it.ref, food.clock.nowSec()) }
                repeat(4) { food.water.add(250, today) }
                food.recipes.save(
                    Recipe(
                        0, RecipeKind.RECIPE, "Weeknight chili", 40, null, 15, 30, "Brown the onion. Add everything else. Simmer 30 minutes.",
                        null, null, null, null, setOf("dinner", "batch"),
                        listOf(
                            RecipeItem(0, "1 can black beans, drained", null, null, null, null, MatchState.UNMATCHED),
                            RecipeItem(1, "2 cups brown rice", null, null, null, null, MatchState.UNMATCHED),
                            RecipeItem(2, "salt to taste", null, null, null, null, MatchState.UNMATCHED),
                        ),
                        null,
                    ).let { food.recipes.suggestMatches(it) },
                )
                SwLog.i(TAG, "sample food data added")
            } catch (e: Exception) {
                SwLog.e(TAG, "seeding failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "FoodSeed"

        fun f(name: String, brand: String?, basis: Basis, kcal: Int, p: Int, fat: Int, c: Int, serving: Serving): Food = Food(
            FoodRef(FoodOrigin.MY_FOOD, 0), name, brand, 0, basis,
            Nutrients(kcal * 10, p * 10, fat * 10, null, c * 10, null, null, null, null), listOf(serving), null, "", null,
        )

        val SAMPLES: List<Food> = listOf(
            f("Oatmeal", null, Basis.PER_ITEM, 150, 5, 3, 27, Serving(0, "1 cup", 2340)),
            f("Banana", null, Basis.PER_ITEM, 105, 1, 0, 27, Serving(0, "1 medium", 1180)),
            f("Coffee with milk", null, Basis.PER_ITEM, 175, 9, 9, 13, Serving(0, "12 oz", 3550)),
            f("Burrito bowl", "Corner Cantina", Basis.PER_ITEM, 750, 35, 25, 90, Serving(0, "1 bowl", 5000)),
            f("Greek yogurt", "Fage", Basis.PER_100G, 88, 9, 5, 4, Serving(0, "170 g pot", 1700)),
            f("Salmon fillet", null, Basis.PER_100G, 208, 20, 13, 0, Serving(0, "1 fillet", 1500)),
            f("Brown rice", null, Basis.PER_100G, 123, 3, 1, 26, Serving(0, "1/2 cup", 980)),
            f("Side salad", null, Basis.PER_ITEM, 60, 2, 4, 5, Serving(0, "1 bowl", 1000)),
            f("Granola bar", "Trail Co", Basis.PER_ITEM, 190, 4, 7, 29, Serving(0, "1 bar", 400)),
        )
    }
}
