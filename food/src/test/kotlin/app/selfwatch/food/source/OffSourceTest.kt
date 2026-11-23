/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.source

import app.selfwatch.db.CustomFoodOrigin
import app.selfwatch.food.DbTestBase
import app.selfwatch.model.fake.FakeHttp
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.net.NetFeature
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OffSourceTest : DbTestBase() {
    private val http = FakeHttp()
    private val gtin = 3017620422003L

    private val reply = """
        {"code":"3017620422003","product":{"brands":"Nutoka, Nutoka Group","product_name":"Hazelnut  Cocoa Spread",
        "serving_quantity":"15","serving_size":"15 g",
        "nutriments":{"energy-kcal_100g":539,"energy_100g":2252,"proteins_100g":6.3,"fat_100g":30.9,
        "saturated-fat_100g":10.6,"carbohydrates_100g":57.5,"sugars_100g":56.3,"fiber_100g":"3.45",
        "sodium_100g":0.0428,"salt_100g":0.107}},"status":1,"status_verbose":"product found"}
    """.trimIndent()

    @Test
    fun lookupMapsAndSavesACustomFood() = test {
        http.enqueue(200, reply)
        val src = OffSource(http, db, clock)
        val found = src.lookup(gtin) as BarcodeLookup.Found
        val req = http.requests().single()
        assertEquals(NetFeature.OPEN_FOOD_FACTS, req.feature)
        assertEquals("GET", req.method)
        assertEquals(
            "https://world.openfoodfacts.org/api/v2/product/3017620422003?fields=product_name,brands,nutriments,serving_size,serving_quantity",
            req.url,
        )
        val f = found.food
        assertEquals(FoodOrigin.MY_FOOD, f.ref.origin)
        assertEquals("Hazelnut Cocoa Spread", f.name)
        assertEquals("Nutoka", f.brand)
        assertEquals(Basis.PER_100G, f.basis)
        assertEquals(5390, f.per.kcalX10)
        assertEquals(63, f.per.proteinX10)
        assertEquals(309, f.per.fatX10)
        assertEquals(106, f.per.satfatX10)
        assertEquals(575, f.per.carbX10)
        assertEquals(563, f.per.sugarX10)
        assertEquals(35, f.per.fibreX10) // "3.45" → 34.5 → 35 (half away from zero)
        assertEquals(43, f.per.sodiumMg) // 0.0428 g → 42.8 mg
        assertNull(f.per.cholesterolMg)
        assertEquals(listOf("15 g" to 150, "100 g" to 1000), f.servings.map { it.label to it.gramsX10 })

        // Saved with origin "Open Food Facts lookup" and the barcode, so the next scan is offline.
        val saved = db.foods.get(f.ref.id)!!
        assertEquals(CustomFoodOrigin.OPEN_FOOD_FACTS, saved.origin)
        assertEquals("3017620422003", saved.originRef)
        assertEquals(gtin, saved.food.barcode)
        val search = FoodSearch(listOf(MyFoodsSource(db), src))
        http.blocked.add(NetFeature.OPEN_FOOD_FACTS)
        val again = search.byBarcode(gtin) as BarcodeLookup.Found
        assertEquals(f.ref, again.food.ref)
        assertEquals(FoodHit.TAG_MY_FOOD, again.sourceTag)
        assertEquals(1, http.requests().size)
    }

    @Test
    fun kilojoulesOnlyAreConverted() {
        val json = """{"status":1,"product":{"product_name":"Oat Drink","nutriments":{"energy_100g":192,"cholesterol_100g":0.002}}}"""
        val f = (OffSource.parseProduct(json, 7310865004703L) as Outcome.Ok).value!!
        assertEquals(459, f.per.kcalX10) // 192 kJ / 4.184 = 45.89 kcal
        assertEquals(2, f.per.cholesterolMg)
        assertNull(f.brand)
        assertEquals(listOf("100 g"), f.servings.map { it.label })
        val kj = """{"status":1,"product":{"product_name":"X","nutriments":{"energy-kj_100g":"418.4"}}}"""
        assertEquals(1000, (OffSource.parseProduct(kj, 7310865004703L) as Outcome.Ok).value!!.per.kcalX10)
    }

    @Test
    fun missingNameFallsBackToBrandThenCode() {
        val noName = """{"status":1,"product":{"brands":"Acme","nutriments":{}}}"""
        assertEquals("Acme", (OffSource.parseProduct(noName, 96385074L) as Outcome.Ok).value!!.name)
        val nothing = """{"status":1,"product":{}}"""
        val f = (OffSource.parseProduct(nothing, 96385074L) as Outcome.Ok).value!!
        assertEquals("96385074", f.name)
        assertNull(f.per.kcalX10)
    }

    @Test
    fun notFoundOfflineBlockedAndBroken() = test {
        val src = OffSource(http, db, clock)
        http.enqueue(404, """{"status":0,"status_verbose":"product not found"}""")
        assertEquals(BarcodeLookup.NotFound, src.lookup(gtin))
        http.enqueue(200, """{"status":0,"status_verbose":"product not found"}""")
        assertEquals(BarcodeLookup.NotFound, src.lookup(gtin))
        http.enqueue(Outcome.Fail(SwError.Offline))
        assertEquals(BarcodeLookup.Unavailable(SwError.Offline), src.lookup(gtin))
        http.enqueue(503, "busy")
        assertTrue(src.lookup(gtin) is BarcodeLookup.Unavailable)
        http.enqueue(200, "<html>not json</html>")
        assertTrue((src.lookup(gtin) as BarcodeLookup.Unavailable).error is SwError.BadInput)
        http.blocked.add(NetFeature.OPEN_FOOD_FACTS)
        assertEquals(BarcodeLookup.Unavailable(SwError.Blocked(NetFeature.OPEN_FOOD_FACTS)), src.lookup(gtin))
        assertNull(src.byBarcode(gtin))
        assertTrue(db.foods.list().isEmpty()) // nothing saved on failure

        // Through FoodSearch the reason reaches the screen.
        val search = FoodSearch(listOf(MyFoodsSource(db), src))
        assertEquals(BarcodeLookup.Unavailable(SwError.Blocked(NetFeature.OPEN_FOOD_FACTS)), search.byBarcode(gtin))
        assertTrue(src.search("anything", 10).isEmpty()) // no online text search
    }

    @Test
    fun eanEightAndUpcCodesUseTheirShortForm() = test {
        val src = OffSource(http, db, clock)
        src.lookup(96385074L)
        src.lookup(12345678905L)
        assertEquals(
            listOf("96385074", "0012345678905"),
            http.requests().map { it.url.substringAfter("/product/").substringBefore('?') },
        )
    }
}
