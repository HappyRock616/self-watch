/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.recipes

import app.selfwatch.db.CustomFoodOrigin
import app.selfwatch.db.dao.CustomFood
import app.selfwatch.food.DbTestBase
import app.selfwatch.food.FoodsDbFixture
import app.selfwatch.food.source.FoodSearch
import app.selfwatch.food.source.FoodsDbHandle
import app.selfwatch.food.source.FoodsDbSource
import app.selfwatch.food.source.MyFoodsSource
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
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecipeServiceTest : DbTestBase() {
    private lateinit var scope: CoroutineScope
    private lateinit var search: FoodSearch
    private lateinit var svc: RecipeService

    @Before
    fun setUp() {
        val file = File(app.filesDir, "fooddata/foods.db")
        FoodsDbFixture.buildFoods(file)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        search = FoodSearch(listOf(MyFoodsSource(db), FoodsDbSource(FoodsDbHandle(file, scope))))
        svc = RecipeService(db, search, clock)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun item(seq: Int, line: String) = RecipeItem(seq, line, null, null, null, null, MatchState.UNMATCHED)

    private fun recipe(vararg lines: String, servingsX10: Int = 20, stated: Nutrients? = null) = Recipe(
        id = 0, kind = RecipeKind.RECIPE, name = "Bean Bowl", servingsX10 = servingsX10, servingLabel = "1 bowl",
        prepMin = 10, cookMin = 5, steps = "Warm.\nServe.", notes = null, sourceName = null, sourceUrl = null,
        statedPerServing = stated, tags = setOf("Dinner ", "vegetarian"), items = lines.mapIndexed { i, l -> item(i, l) }, importId = null,
    )

    @Test
    fun suggestsGenericFoodsWithWeights() = test {
        val r = svc.suggestMatches(
            recipe("1 can (15 oz) black beans, drained", "1 medium onion, chopped", "2 large eggs", "1 cup flour", "1 cup milk", "Salt to taste", "2 cups dragonfruit puree"),
        )
        val byLine = r.items.associateBy { it.line }
        fun ref(id: Long) = FoodRef(FoodOrigin.FOODS_DB, id)
        with(byLine.getValue("1 can (15 oz) black beans, drained")) {
            assertEquals(ref(FoodsDbFixture.BLACK_BEANS), food)
            assertEquals(MatchState.SUGGESTED, match)
            assertEquals(100, amountX100)
            assertEquals("can", unit)
            assertEquals(4253, gramsX10) // 15 oz = 425.25 g
        }
        with(byLine.getValue("1 medium onion, chopped")) {
            assertEquals(ref(FoodsDbFixture.ONION), food)
            assertEquals(1100, gramsX10) // the "1 medium" serving
        }
        with(byLine.getValue("2 large eggs")) {
            assertEquals(ref(FoodsDbFixture.EGG), food)
            assertEquals(1000, gramsX10) // 2 × "1 large" (50 g)
        }
        assertEquals(1250, byLine.getValue("1 cup flour").gramsX10) // the food's own "1 cup"
        assertEquals(2440, byLine.getValue("1 cup milk").gramsX10)
        assertEquals(MatchState.SKIPPED, byLine.getValue("Salt to taste").match)
        with(byLine.getValue("2 cups dragonfruit puree")) {
            assertEquals(MatchState.UNMATCHED, match)
            assertNull(food)
            assertEquals(200, amountX100)
        }
    }

    @Test
    fun statedComputedAndPartial() = test {
        val stated = svc.nutritionPerServing(recipe("1 cup flour", stated = Nutrients(3200, 100, null, null, null, null, null, null, null)))
        assertEquals(NutritionSource.STATED, stated.source)
        assertEquals(3200, stated.perServing.kcalX10)

        val computed = svc.nutritionPerServing(svc.suggestMatches(recipe("1 can (15 oz) black beans, drained", "1 medium onion, chopped", "salt, to taste")))
        assertEquals(NutritionSource.COMPUTED, computed.source)
        assertEquals(0, computed.unmatched)
        // beans 91 kcal/100 g × 425.3 g = 387.0, onion 40 × 1.1 = 44.0 → 431.0 kcal for 2 servings.
        assertEquals(2155, computed.perServing.kcalX10)
        assertEquals(NutrientsCheck.fibreBeansOnion, computed.perServing.fibreX10)

        val partial = svc.nutritionPerServing(svc.suggestMatches(recipe("1 can (15 oz) black beans, drained", "2 cups dragonfruit puree")))
        assertEquals(NutritionSource.PARTIAL, partial.source)
        assertEquals(1, partial.unmatched)
        assertEquals(1935, partial.perServing.kcalX10)

        val nothing = svc.nutritionPerServing(recipe("2 cups dragonfruit puree"))
        assertEquals(NutritionSource.PARTIAL, nothing.source)
        assertEquals(Nutrients.UNKNOWN, nothing.perServing)
    }

    @Test
    fun perItemFoodsCountByAmount() = test {
        val r = recipe("2 hamburgers", servingsX10 = 10)
        val confirmed = svc.confirm(r, 0, search.get(FoodRef(FoodOrigin.FOODS_DB, FoodsDbFixture.HAMBURGER))!!)
        assertEquals(MatchState.CONFIRMED, confirmed.items[0].match)
        val n = svc.nutritionPerServing(confirmed)
        assertEquals(NutritionSource.COMPUTED, n.source)
        assertEquals(5000, n.perServing.kcalX10)
    }

    @Test
    fun prefersTheUsersOwnFood() = test {
        val id = db.foods.insert(
            CustomFood(
                Food(FoodRef(FoodOrigin.MY_FOOD, 0), "Black beans, home cooked", null, 0, Basis.PER_100G,
                    Nutrients(1300, null, null, null, null, null, null, null, null), listOf(Serving(0, "1 cup", 1700)), null, "", null),
                CustomFoodOrigin.TYPED, null, false, 1, 1, null,
            ),
        )
        val r = svc.suggestMatches(recipe("2 cups black beans"))
        assertEquals(FoodRef(FoodOrigin.MY_FOOD, id), r.items[0].food)
        assertEquals(3400, r.items[0].gramsX10)
    }

    @Test
    fun saveListUpdateDelete() = test {
        val id = svc.save(svc.suggestMatches(recipe("1 cup flour", "2 large eggs")))
        val saved = svc.get(id)!!
        assertEquals(setOf("dinner", "vegetarian"), saved.tags)
        assertEquals(listOf(MatchState.SUGGESTED, MatchState.SUGGESTED), saved.items.map { it.match })
        assertEquals(listOf("Bean Bowl"), svc.list(null, null).first().map { it.name })
        assertEquals(listOf("Bean Bowl"), svc.list("vegetarian", "bean").first().map { it.name })
        assertTrue(svc.list("breakfast", null).first().isEmpty())
        assertEquals(id, svc.save(saved.copy(name = "Bean Bowl Deluxe")))
        assertEquals("Bean Bowl Deluxe", svc.get(id)?.name)
        assertEquals(listOf("dinner", "vegetarian"), svc.tags())
        val skipped = svc.skip(saved, 1)
        assertEquals(MatchState.SKIPPED, skipped.items[1].match)
        assertTrue(svc.delete(id))
        assertTrue(svc.list(null, null).first().isEmpty())
    }

    @Test
    fun queriesAndStems() {
        assertEquals(listOf("large egg", "egg"), IngredientMatcher.queries("large eggs"))
        assertEquals(listOf("boneless skinless chicken breast", "chicken breast", "breast"), IngredientMatcher.queries("boneless skinless chicken breasts"))
        assertEquals(listOf("red bell pepper", "bell pepper", "pepper"), IngredientMatcher.queries("red bell pepper"))
        assertEquals("tomato", IngredientMatcher.stem("tomatoes"))
        assertEquals("berr", IngredientMatcher.stem("berries"))
        assertEquals("glass", IngredientMatcher.stem("glass"))
        assertTrue(IngredientMatcher.queries("").isEmpty())
    }

    private object NutrientsCheck {
        // fibre: beans 6.9 g/100 g × 425.3 g = 29.3 g (293), onion 1.7 × 1.1 = 1.9 g (19) → 312 / 2 servings = 156.
        const val fibreBeansOnion: Int = 156
    }
}
