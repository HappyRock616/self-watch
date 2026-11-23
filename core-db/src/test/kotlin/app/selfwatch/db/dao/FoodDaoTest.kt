/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db.dao

import app.selfwatch.db.CustomFoodOrigin
import app.selfwatch.db.DbTest
import app.selfwatch.db.RecipeOrigin
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.MatchState
import app.selfwatch.model.food.MealSlot
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Recipe
import app.selfwatch.model.food.RecipeItem
import app.selfwatch.model.food.RecipeKind
import app.selfwatch.model.food.Serving
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodDaoTest : DbTest() {
    private val today = Day(20261123)

    private fun food(name: String, brand: String? = null, barcode: Long? = null, hidden: Boolean = false, importId: Long? = null) =
        CustomFood(
            food = Food(
                ref = FoodRef(FoodOrigin.MY_FOOD, 0), name = name, brand = brand, kind = 0, basis = Basis.PER_100G,
                per = Nutrients(1650, 310, 36, 10, 0, 0, 0, 74, 85),
                servings = listOf(Serving(1, "1 breast", 1740), Serving(2, "100 g", 1000)),
                dataYear = null, sourceLabel = "", barcode = barcode,
            ),
            origin = CustomFoodOrigin.TYPED, originRef = null, hidden = hidden, createdSec = 10, updatedSec = 10, importId = importId,
        )

    private fun recipe(name: String, tags: Set<String> = emptySet(), importId: Long? = null) = Recipe(
        id = 0, kind = RecipeKind.RECIPE, name = name, servingsX10 = 40, servingLabel = "bowl", prepMin = 10, cookMin = 30,
        steps = "Cook.", notes = null, sourceName = "Me", sourceUrl = null, statedPerServing = null, tags = tags,
        items = listOf(
            RecipeItem(1, "2 cups flour", null, 200, "cup", 2500, MatchState.UNMATCHED),
            RecipeItem(2, "1 egg", FoodRef(FoodOrigin.FOODS_DB, 77), 100, null, 500, MatchState.CONFIRMED),
        ),
        importId = importId,
    )

    // ---- custom food ----

    @Test
    fun customFoodCrud() = test {
        val id = db.foods.insert(food("Chicken breast", "Tesco", barcode = 5000112637922))
        val got = db.foods.get(id)!!
        assertEquals(food("Chicken breast", "Tesco", barcode = 5000112637922).copy(food = got.food.copy(ref = FoodRef(FoodOrigin.MY_FOOD, id))), got)
        assertEquals(2, db.foods.servings(id).size)
        assertEquals(id, db.foods.byBarcode(5000112637922)!!.food.ref.id)
        assertNull(db.foods.byBarcode(1))

        val changed = got.copy(food = got.food.copy(name = "Turkey breast", servings = listOf(Serving(1, "slice", 300))), updatedSec = 20)
        assertTrue(db.foods.update(changed))
        assertEquals("Turkey breast", db.foods.get(id)!!.food.name)
        assertEquals(listOf(Serving(1, "slice", 300)), db.foods.servings(id))
        assertEquals(emptyList<MyFoodHit>(), db.foods.searchMine("chicken"))
        assertEquals(1, db.foods.searchMine("turk").size)
        assertFalse(db.foods.update(changed.copy(food = changed.food.copy(ref = FoodRef(FoodOrigin.MY_FOOD, 999)))))

        assertTrue(db.foods.setHidden(id, true, 30))
        assertTrue(db.foods.get(id)!!.hidden)
        assertEquals(emptyList<CustomFood>(), db.foods.list())
        assertEquals(1, db.foods.list(includeHidden = true).size)
        assertEquals(emptyList<MyFoodHit>(), db.foods.searchMine("turk"))
        assertNull(db.foods.byBarcode(5000112637922))
        assertTrue(db.foods.setHidden(id, false, 40))
        assertEquals(1, db.foods.searchMine("turk").size)
        assertFalse(db.foods.setHidden(999, true, 1))

        assertTrue(db.foods.delete(id))
        assertFalse(db.foods.delete(id))
        assertEquals(0L, count("custom_serving"))
        assertEquals(0L, count("my_food_fts"))
    }

    @Test
    fun searchMinePrefixesAccentsAndRecipes() = test {
        val a = db.foods.insert(food("Crème fraîche", "Président"))
        val b = db.foods.insert(food("Greek yoghurt", "Fage"))
        val c = db.foods.insert(food("Greek salad"))
        db.foods.insert(food("Hidden greek", hidden = true))
        val r = db.recipes.insert(recipe("Greek lemon soup"), nowSec = 1)

        assertEquals(listOf(a), db.foods.searchMine("creme").map { it.ref.id })
        assertEquals(listOf(a), db.foods.searchMine("CRÈME FR").map { it.ref.id })
        assertEquals(listOf(a), db.foods.searchMine("presid").map { it.ref.id }) // brand, accent-free query
        val greek = db.foods.searchMine("gr")
        assertEquals(listOf(FoodRef(FoodOrigin.MY_FOOD, c), FoodRef(FoodOrigin.MY_FOOD, b), FoodRef(FoodOrigin.RECIPE, r)), greek.map { it.ref })
        assertEquals(listOf(b), db.foods.searchMine("greek yog").map { it.ref.id })
        assertEquals(listOf(FoodRef(FoodOrigin.RECIPE, r)), db.foods.searchMine("lem").map { it.ref })
        assertEquals(1, db.foods.searchMine("gr", limit = 1).size)
        // Operators and punctuation are harmless.
        assertEquals(emptyList<MyFoodHit>(), db.foods.searchMine("\"*) OR NEAR("))
        assertEquals(emptyList<MyFoodHit>(), db.foods.searchMine("   "))
        assertEquals(listOf(b), db.foods.searchMine("greek-yog!").map { it.ref.id })
        assertEquals("chick* bre*", MyFoodFts.matchQuery("Chick, bre"))
        assertNull(MyFoodFts.matchQuery("--"))
    }

    // ---- diary ----

    @Test
    fun diaryCrudAndTotals() = test {
        val e1 = db.diary.add(entry(today, slot = 1, tSec = 100, name = "Oats", kcalX10 = 1500))
        val e2 = db.diary.add(entry(today, slot = 3, tSec = 200, name = "Pasta", kcalX10 = 6000))
        db.diary.add(entry(today, slot = 1, tSec = 300, name = "Milk", kcalX10 = 640).copy(nutrients = Nutrients.UNKNOWN))
        db.diary.add(entry(today.plusDays(1), name = "Tomorrow"))
        assertEquals(listOf("Oats", "Milk", "Pasta"), db.diary.day(today).map { it.name })
        assertEquals(0, db.diary.day(today)[1].nutrients.kcalX10) // unknown energy is stored as 0
        assertEquals(entry(today, slot = 1, tSec = 100, name = "Oats", kcalX10 = 1500).copy(id = e1), db.diary.get(e1))

        val totals = db.diary.dayTotals(today)
        assertEquals(7500, totals.kcalX10)
        assertEquals(6, totals.proteinX10)
        assertNull(totals.fatX10)
        assertEquals(Nutrients.UNKNOWN.copy(kcalX10 = null), db.diary.dayTotals(Day(20200101)))
        assertEquals(setOf(1L, 3L), db.diary.slotTotals(today).keys)
        assertEquals(1500, db.diary.slotTotals(today)[1]!!.kcalX10)

        assertTrue(db.diary.update(db.diary.get(e2)!!.copy(amountX100 = 150, mealSlotId = 2)))
        assertEquals(150, db.diary.get(e2)!!.amountX100)
        assertTrue(db.diary.delete(e2))
        assertFalse(db.diary.delete(e2))
        assertNull(db.diary.get(e2))
        assertEquals(3, db.diary.addAll(listOf(entry(today), entry(today), entry(today))))
        assertEquals(0, db.diary.addAll(emptyList()))
        assertEquals(5, db.diary.day(today).size)
    }

    @Test
    fun dailyKcalGroupsByDayInRange() = test {
        db.diary.add(entry(today, name = "Oats", kcalX10 = 1500))
        db.diary.add(entry(today, name = "Pasta", kcalX10 = 6000))
        db.diary.add(entry(today.plusDays(-2), name = "Soup", kcalX10 = 2505))
        db.diary.add(entry(today.plusDays(-9), name = "Out of range", kcalX10 = 9990))
        db.diary.add(entry(today.plusDays(1), name = "Tomorrow", kcalX10 = 100))
        val got = db.diary.dailyKcalX10(DayRange(today.plusDays(-3), today))
        assertEquals(mapOf(today.plusDays(-2) to 2505L, today to 7500L), got)
        // The grouped sums agree with the per-day totals.
        assertEquals(db.diary.dayTotals(today).kcalX10?.toLong(), got[today])
        assertTrue(db.diary.dailyKcalX10(DayRange(Day(20200101), Day(20200131))).isEmpty())
    }

    @Test
    fun copyMealRecentsFrequent() = test {
        val apple = FoodRef(FoodOrigin.FOODS_DB, 11)
        val oats = FoodRef(FoodOrigin.MY_FOOD, 5)
        db.diary.add(entry(today, slot = 1, tSec = 100, food = oats, name = "Oats", importId = 3))
        db.diary.add(entry(today, slot = 1, tSec = 110, food = apple, name = "Apple"))
        db.diary.add(entry(today, slot = 2, tSec = 120, food = apple, name = "Apple"))
        db.diary.add(entry(today, slot = 2, tSec = 130, name = "Quick add"))
        db.diary.add(entry(today, slot = 2, tSec = 140, name = "Quick add"))
        db.diary.add(entry(today, slot = 2, tSec = 150, name = "Other quick"))

        val next = today.plusDays(1)
        assertEquals(2, db.diary.copyMeal(today, 1, next, tSec = 999))
        val copied = db.diary.day(next)
        assertEquals(listOf("Oats", "Apple"), copied.map { it.name })
        assertTrue(copied.all { it.tSec == 999L && it.importId == null && it.mealSlotId == 1L })
        assertEquals(oats, copied[0].food)
        assertEquals(2, db.diary.copyMeal(today, 1, next, tSec = 1000, toSlotId = 4))

        val recents = db.diary.recents(10)
        assertEquals(listOf("Apple", "Oats", "Other quick", "Quick add"), recents.map { it.name })
        assertEquals(1000L, recents[0].tSec)
        assertEquals(2, db.diary.recents(2).size)

        val freq = db.diary.frequent(10)
        assertEquals("Apple", freq[0].entry.name)
        assertEquals(4, freq[0].count) // 2 originals + 2 copies
        assertEquals("Oats", freq[1].entry.name)
        assertEquals(3, freq[1].count)
        assertEquals(2, freq.first { it.entry.name == "Quick add" }.count)
    }

    @Test
    fun mealSlots() = test {
        assertEquals(listOf("Breakfast", "Lunch", "Dinner", "Snacks"), db.diary.mealSlots().map { it.name })
        val id = db.diary.addMealSlot("Supper")
        assertEquals(MealSlot(id, "Supper", 5, false), db.diary.mealSlots().last())
        assertTrue(db.diary.updateMealSlot(MealSlot(id, "Second breakfast", 0, false)))
        assertTrue(db.diary.updateMealSlot(MealSlot(4, "Snacks", 4, true)))
        assertEquals(listOf("Second breakfast", "Breakfast", "Lunch", "Dinner"), db.diary.mealSlots(includeHidden = false).map { it.name })
        assertEquals(5, db.diary.mealSlots().size)
    }

    // ---- favourite, water, fast ----

    @Test
    fun favourites() = test {
        val a = FoodRef(FoodOrigin.FOODS_DB, 1)
        val b = FoodRef(FoodOrigin.RECIPE, 2)
        db.favourites.add(a, 10)
        db.favourites.add(b, 20)
        db.favourites.add(a, 30)
        assertEquals(listOf(a, b), db.favourites.list())
        assertTrue(db.favourites.isFavourite(b))
        assertTrue(db.favourites.remove(b))
        assertFalse(db.favourites.remove(b))
        assertFalse(db.favourites.isFavourite(b))
    }

    @Test
    fun water() = test {
        val a = db.water.add(today, 100, 250)
        db.water.add(today, 200, 500, importId = 4)
        db.water.add(today.plusDays(1), 300, 1000)
        assertEquals(750, db.water.dayTotal(today))
        assertEquals(0, db.water.dayTotal(Day(20200101)))
        assertEquals(listOf(250, 500), db.water.entries(today).map { it.ml })
        assertEquals(4L, db.water.entries(today)[1].importId)
        assertTrue(db.water.delete(a))
        assertFalse(db.water.delete(a))
        assertEquals(500, db.water.dayTotal(today))
    }

    @Test
    fun fasts() = test {
        val a = db.fasts.start(100, 960)
        assertEquals(Fast(a, 100, null, 960), db.fasts.current())
        assertTrue(db.fasts.end(a, 5000))
        assertNull(db.fasts.current())
        val b = db.fasts.start(6000, 720)
        assertTrue(db.fasts.update(Fast(b, 5900, null, 800)))
        assertEquals(Fast(b, 5900, null, 800), db.fasts.current())
        assertEquals(listOf(b, a), db.fasts.list(10).map { it.id })
        assertEquals(Fast(a, 100, 5000, 960), db.fasts.list(10)[1])
        assertTrue(db.fasts.delete(b))
        assertFalse(db.fasts.end(b, 1))
        assertEquals(1, db.fasts.list(10).size)
    }

    // ---- recipe ----

    @Test
    fun recipeCrud() = test {
        val stated = Nutrients(4000, 120, null, null, null, null, null, 300, null)
        val id = db.recipes.insert(recipe("Pancakes", setOf("breakfast", "sweet")).copy(statedPerServing = stated), nowSec = 5, origin = RecipeOrigin.WEB, originRef = "https://x")
        val other = db.recipes.insert(recipe("Pasta bake", setOf("dinner")), nowSec = 6)
        val got = db.recipes.get(id)!!
        assertEquals(recipe("Pancakes", setOf("breakfast", "sweet")).copy(id = id, statedPerServing = stated), got)
        assertEquals(RecipeOrigin.WEB to "https://x", db.recipes.origin(id))
        assertNull(db.recipes.get(999))

        assertEquals(listOf("Pancakes", "Pasta bake"), db.recipes.list().map { it.name })
        assertEquals(listOf(other), db.recipes.list(tag = "dinner").map { it.id })
        assertEquals(listOf(id), db.recipes.list(query = "panc").map { it.id })
        assertEquals(emptyList<RecipeListItem>(), db.recipes.list(tag = "dinner", query = "panc"))
        assertEquals(emptyList<RecipeListItem>(), db.recipes.list(query = "?!"))
        assertEquals(setOf("breakfast", "sweet"), db.recipes.list(query = "pan")[0].tags)
        assertEquals(stated, db.recipes.list(query = "pan")[0].statedPerServing)
        assertEquals(listOf("breakfast", "dinner", "sweet"), db.recipes.tags())

        val edited = got.copy(name = "Crêpes", tags = setOf("french"), items = got.items.take(1))
        assertTrue(db.recipes.update(edited, nowSec = 9))
        assertEquals(edited, db.recipes.get(id))
        assertEquals(RecipeOrigin.WEB to "https://x", db.recipes.origin(id))
        assertEquals(listOf(id), db.recipes.list(query = "crepe").map { it.id })
        assertEquals(emptyList<RecipeListItem>(), db.recipes.list(query = "panc"))
        assertFalse(db.recipes.update(edited.copy(id = 999), nowSec = 9))

        assertTrue(db.recipes.delete(id))
        assertFalse(db.recipes.delete(id))
        assertEquals(0L, count("recipe_item", "recipe_id = $id"))
        assertEquals(0L, count("recipe_tag", "recipe_id = $id"))
        assertEquals(1L, count("my_food_fts"))
    }
}
