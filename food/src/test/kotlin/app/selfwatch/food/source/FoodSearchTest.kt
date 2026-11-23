/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.source

import app.selfwatch.db.CustomFoodOrigin
import app.selfwatch.db.dao.CustomFood
import app.selfwatch.food.DbTestBase
import app.selfwatch.food.FoodsDbFixture
import app.selfwatch.food.diary.DiaryService
import app.selfwatch.model.fake.FakeHttp
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Recipe
import app.selfwatch.model.food.RecipeKind
import app.selfwatch.model.food.Serving
import app.selfwatch.model.time.Day
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodSearchTest : DbTestBase() {

    private fun custom(name: String, brand: String?, kcalPer100: Int, servings: List<Serving>, barcode: Long? = null) = CustomFood(
        food = Food(FoodRef(FoodOrigin.MY_FOOD, 0), name, brand, 0, Basis.PER_100G, Nutrients(kcalPer100, null, null, null, null, null, null, null, null), servings, null, "", barcode),
        origin = CustomFoodOrigin.TYPED,
        originRef = null,
        hidden = false,
        createdSec = 1,
        updatedSec = 1,
        importId = null,
    )

    private fun recipe(name: String, kcal: Int?) = Recipe(
        0, RecipeKind.RECIPE, name, 40, "1 bowl", null, null, null, null, null, null,
        kcal?.let { Nutrients(it, null, null, null, null, null, null, null, null) }, emptySet(), emptyList(), null,
    )

    @Test
    fun myFoodsFindsCustomFoodsRecipesAndHistory() = test {
        val granola = db.foods.insert(custom("Granola, honey", "Home", 4500, listOf(Serving(0, "1/2 cup", 600)), barcode = 96385074L))
        db.recipes.insert(recipe("Granola bars", 1800), 1)
        val src = MyFoodsSource(db)
        val hits = src.search("gran", 10)
        assertEquals(listOf("Granola bars", "Granola, honey"), hits.map { it.name })
        assertEquals(listOf(FoodHit.TAG_RECIPE, FoodHit.TAG_MY_FOOD), hits.map { it.sourceTag })
        assertEquals(1800, hits[0].kcalX10PerDefault)
        assertEquals("1 bowl", hits[0].defaultServing)
        assertEquals(2700, hits[1].kcalX10PerDefault) // 450 kcal/100 g × 60 g
        assertEquals(listOf(0, 1), hits.map { it.rank })

        // A logged foods.db item shows up as history; a blank query lists recents.
        val bigMac = Food(FoodRef(FoodOrigin.FOODS_DB, FoodsDbFixture.BIG_MAC), "Big Mac", "McDonald's", 2, Basis.PER_100G,
            Nutrients(2570, null, null, null, null, null, null, null, null), listOf(Serving(0, "1 sandwich", 2190)), 2018, "NYC MenuStat", null)
        DiaryService(db, clock).add(Day(20261120), 2, bigMac, bigMac.servings[0], 100)
        val hist = src.search("mcdon", 10)
        assertEquals(listOf("Big Mac"), hist.map { it.name })
        assertEquals(FoodHit.TAG_HISTORY, hist[0].sourceTag)
        assertEquals(5628, hist[0].kcalX10PerDefault) // per one sandwich
        assertEquals(listOf("Big Mac"), src.search("", 10).map { it.name })

        // get() resolves both origins; barcode finds the custom food.
        assertEquals("Granola, honey", src.get(FoodRef(FoodOrigin.MY_FOOD, granola))?.name)
        assertEquals(MyFoodsSource.SOURCE_MY_FOOD, src.byBarcode(96385074L)?.sourceLabel)
        val recipeId = db.recipes.list().single().id
        val asFood = src.get(FoodRef(FoodOrigin.RECIPE, recipeId))!!
        assertEquals(Basis.PER_ITEM, asFood.basis)
        assertEquals(1800, asFood.per.kcalX10)
        assertEquals(listOf("1 bowl"), asFood.servings.map { it.label })
        assertNull(src.get(FoodRef(FoodOrigin.FOODS_DB, 1)))
    }

    @Test
    fun searchMergesInSourceOrderAndDedupes() = test {
        val file = File(app.filesDir, "fooddata/foods.db")
        FoodsDbFixture.buildFoods(file)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val foodsDb = FoodsDbSource(FoodsDbHandle(file, scope))
            db.foods.insert(custom("Big Mac copycat", null, 2400, listOf(Serving(0, "1 burger", 2000))))
            val bigMac = foodsDb.get(FoodsDbFixture.BIG_MAC)!!
            DiaryService(db, clock).add(Day(20261120), 2, bigMac, bigMac.servings[0], 100)

            val search = FoodSearch(listOf(MyFoodsSource(db), foodsDb, OffSource(FakeHttp(), db, clock)))
            val emissions = search.search("big mac").toList()
            assertEquals(3, emissions.size) // one per source
            val last = emissions.last()
            // My food first, then the logged Big Mac (history), and foods.db does not repeat it.
            assertEquals(listOf("Big Mac copycat", "Big Mac"), last.map { it.name })
            assertEquals(listOf(FoodHit.TAG_MY_FOOD, FoodHit.TAG_HISTORY), last.map { it.sourceTag })
            assertEquals(1, last.count { it.ref == FoodRef(FoodOrigin.FOODS_DB, FoodsDbFixture.BIG_MAC) })

            // foods.db rows follow.
            val egg = search.search("egg").toList().last()
            assertEquals(listOf(FoodHit.TAG_FOODS_DB), egg.map { it.sourceTag }.distinct())

            // Barcode: foods.db answers before Open Food Facts is asked.
            val y = search.byBarcode(FoodsDbFixture.YOGURT_GTIN) as BarcodeLookup.Found
            assertEquals(FoodHit.TAG_FOODS_DB, y.sourceTag)
            assertEquals("Fage", y.food.brand)
            assertEquals("Fage", search.get(y.food.ref)?.brand)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun slowEarlierSourceKeepsItsPlaceAndFailuresCountAsEmpty() = test {
        val gate = CompletableDeferred<Unit>()
        fun hit(name: String, id: Long, tag: String) = FoodHit(FoodRef(FoodOrigin.FOODS_DB, id), name, null, null, null, tag, 0)
        val slow = object : FakeSource() {
            override suspend fun search(query: String, limit: Int): List<FoodHit> {
                gate.await()
                return listOf(hit("slow", 1, "a"))
            }
        }
        val fast = object : FakeSource() {
            override suspend fun search(query: String, limit: Int): List<FoodHit> = listOf(hit("fast", 2, "b"), hit("slow", 1, "b"))
        }
        val broken = object : FakeSource() {
            override suspend fun search(query: String, limit: Int): List<FoodHit> = error("boom")
        }
        val search = FoodSearch(listOf(slow, fast, broken))
        val scope = CoroutineScope(Dispatchers.Default)
        val all = scope.async { search.search("x").toList() }
        repeat(50) { yield() }
        gate.complete(Unit)
        val emissions = all.await()
        assertEquals(3, emissions.size)
        assertEquals(listOf("slow", "fast"), emissions.last().map { it.name })
        assertEquals("a", emissions.last()[0].sourceTag)
        // Before the slow first source answered, its hits were not there yet.
        assertTrue(emissions.dropLast(1).all { list -> list.none { it.sourceTag == "a" } })
        assertEquals(listOf(emptyList<FoodHit>()), FoodSearch(emptyList()).search("x").toList())
    }

    private abstract class FakeSource : FoodSource {
        override val origin: FoodOrigin = FoodOrigin.FOODS_DB

        override suspend fun byBarcode(gtin: Long): Food? = null

        override suspend fun get(id: Long): Food? = null
    }
}
