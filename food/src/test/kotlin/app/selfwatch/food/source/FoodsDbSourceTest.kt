/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.source

import app.selfwatch.food.FoodsDbFixture
import app.selfwatch.model.food.Allergen
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.FoodCategory
import app.selfwatch.model.food.FoodOrigin
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FoodsDbSourceTest {
    private lateinit var file: File

    @Before
    fun build() {
        file = File(RuntimeEnvironment.getApplication().filesDir, "fooddata/foods.db")
        FoodsDbFixture.buildFoods(file)
    }

    @Test
    fun fixtureFollowsTheSpec() {
        FoodDbChecks.structural(file)
        FoodDbChecks.spotChecks(file, FoodsDbFixture.YOGURT_GTIN)
    }

    /** The same checks on Tony's sample (testdata/fooddb/foods-sample.db); skipped until it is delivered. */
    @Test
    fun tonysSampleFollowsTheSpec() {
        val sample = sampleFile()
        assumeTrue("testdata/fooddb/foods-sample.db not delivered yet", sample != null)
        val copy = File(RuntimeEnvironment.getApplication().filesDir, "sample/foods.db")
        copy.parentFile?.mkdirs()
        sample!!.copyTo(copy, overwrite = true)
        FoodDbChecks.structural(copy)
        FoodDbChecks.spotChecks(copy, null)
    }

    @Test
    fun searchOrdersByPopularityAndIgnoresAccentsAndPunctuation() = runTest {
        val src = FoodsDbSource(FoodsDbHandle(file, backgroundScope, StandardTestDispatcher(testScheduler)))
        val mc = src.search("McDonald's", 50)
        assertEquals(listOf("Big Mac", "Hamburger", "Big Breakfast"), mc.map { it.name })
        assertEquals(listOf(0, 1, 2), mc.map { it.rank })
        assertTrue(mc.all { it.sourceTag == FoodHit.TAG_FOODS_DB && it.ref.origin == FoodOrigin.FOODS_DB && it.kind == 2 })
        assertEquals(listOf("Big Mac"), src.search("mcdonalds big ma", 50).map { it.name })
        assertEquals(listOf("Crème brûlée"), src.search("CRÈME brû", 50).map { it.name })
        assertEquals(listOf("Buttermilk Pancake Mix"), src.search("flapj", 50).map { it.name }) // keywords
        assertEquals(listOf("Total 0% Greek Yogurt"), src.search("yoghurt", 50).map { it.name })
        assertTrue(src.search("\"\" OR * NEAR -", 50).isEmpty())
        assertTrue(src.search("zzzz", 50).isEmpty())
        assertEquals(2, src.search("mcdonalds", 2).size)
    }

    @Test
    fun hitsCarryTheDefaultServingEnergy() = runTest {
        val src = FoodsDbSource(FoodsDbHandle(file, backgroundScope, StandardTestDispatcher(testScheduler)))
        val bigMac = src.search("big mac", 5).single()
        assertEquals("1 sandwich", bigMac.defaultServing)
        assertEquals(5628, bigMac.kcalX10PerDefault) // 257 kcal/100 g × 219 g = 562.83 kcal
        assertEquals(6, bigMac.sourceCode) // NYC MenuStat
        assertEquals(2018, bigMac.dataYear)
        val brulee = src.search("creme brulee", 5).single()
        assertEquals(5, brulee.sourceCode)
        assertEquals(2020, brulee.dataYear)
        val burger = src.search("hamburger", 5).single()
        assertEquals(2500, burger.kcalX10PerDefault) // per item
    }

    @Test
    fun getAndBarcodeReturnFullFoods() = runTest {
        val src = FoodsDbSource(FoodsDbHandle(file, backgroundScope, StandardTestDispatcher(testScheduler)))
        val y = src.byBarcode(FoodsDbFixture.YOGURT_GTIN)!!
        assertEquals("Total 0% Greek Yogurt", y.name)
        assertEquals("Fage", y.brand)
        assertEquals(Basis.PER_100G, y.basis)
        assertEquals(103, y.per.proteinX10)
        assertEquals(FoodsDbFixture.YOGURT_GTIN, y.barcode)
        assertEquals(listOf("1 container", "1 cup"), y.servings.map { it.label })
        assertEquals("USDA Branded Foods", y.sourceLabel)
        val p = src.get(FoodsDbFixture.PANCAKE_MIX)!!
        assertNull(p.per.cholesterolMg) // unknown stays unknown
        assertEquals(FoodsDbFixture.PANCAKE_GTIN, p.barcode)
        assertEquals(Basis.PER_100ML, src.get(FoodsDbFixture.MILK)!!.basis)
        assertNull(src.byBarcode(4006381333931L))
        assertNull(src.get(999_999))
        assertEquals("20261122", src.meta()["data_version"])
    }

    @Test
    fun handleClosesAfterThreeIdleMinutesAndReopens() = runTest {
        val handle = FoodsDbHandle(file, backgroundScope, StandardTestDispatcher(testScheduler))
        val src = FoodsDbSource(handle)
        assertFalse(handle.isOpen)
        src.search("egg", 5)
        assertTrue(handle.isOpen)
        advanceTimeBy(FoodsDbHandle.IDLE_MS - 1000)
        runCurrent()
        assertTrue(handle.isOpen)
        src.get(FoodsDbFixture.EGG) // a use restarts the timer
        advanceTimeBy(FoodsDbHandle.IDLE_MS - 1000)
        runCurrent()
        assertTrue(handle.isOpen)
        advanceTimeBy(2000)
        runCurrent()
        assertFalse(handle.isOpen)
        assertEquals("Egg, whole, raw", src.get(FoodsDbFixture.EGG)?.name)
        assertTrue(handle.isOpen)
        handle.close()
        assertFalse(handle.isOpen)
    }

    @Test
    fun missingOrForeignFilesGiveNothing() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val missing = FoodsDbSource(FoodsDbHandle(File(file.parentFile, "none.db"), backgroundScope, io))
        assertFalse(missing.isInstalled)
        assertTrue(missing.search("big mac", 5).isEmpty())
        assertNull(missing.byBarcode(FoodsDbFixture.YOGURT_GTIN))

        val foreign = File(file.parentFile, "foreign.db")
        FoodsDbFixture.buildFoods(foreign, applicationId = 42)
        assertTrue(FoodsDbSource(FoodsDbHandle(foreign, backgroundScope, io)).search("big mac", 5).isEmpty())

        val newer = File(file.parentFile, "v3.db")
        FoodsDbFixture.buildFoods(newer, userVersion = 3)
        assertTrue(FoodsDbSource(FoodsDbHandle(newer, backgroundScope, io)).search("big mac", 5).isEmpty())
        val older = File(file.parentFile, "v1.db")
        FoodsDbFixture.buildFoods(older, userVersion = 1)
        assertTrue(FoodsDbSource(FoodsDbHandle(older, backgroundScope, io)).search("big mac", 5).isEmpty())

        val junk = File(file.parentFile, "junk.db")
        junk.writeText("this is not a database at all, just some text that is long enough")
        assertTrue(FoodsDbSource(FoodsDbHandle(junk, backgroundScope, io)).search("big mac", 5).isEmpty())
    }

    @Test
    fun ftsMatchIsWholeWordsWithALastPrefix() {
        assertEquals("mcdonalds big*", FoodsDbSearch.ftsMatch(FoodsDbSearch.words("McDonald's  \"Big\"")))
        assertEquals("and or not*", FoodsDbSearch.ftsMatch(FoodsDbSearch.words("AND OR NOT")))
        assertEquals(listOf("crème", "brûlée"), FoodsDbSearch.words("Crème brûlée"))
        assertEquals(listOf("reeses", "cups"), FoodsDbSearch.words("Reese’s Cups"))
        assertTrue(FoodsDbSearch.words("  -*\"()  ").isEmpty())
    }

    @Test
    fun foodsCarryV2NutrientsCategoryAndDiet() = runTest {
        val src = FoodsDbSource(FoodsDbHandle(file, backgroundScope, StandardTestDispatcher(testScheduler)))
        val egg = src.get(FoodsDbFixture.EGG)!!
        assertEquals(138, egg.per.potassiumMg)
        assertEquals(37, egg.per.monofatX10)
        assertEquals(19, egg.per.polyfatX10)
        assertEquals(18, egg.per.ironX10)
        assertEquals(160, egg.per.vitaUg)
        assertEquals(20, egg.per.vitdX10)
        assertEquals(0, egg.per.transfatX10)
        assertNull(egg.per.addsugarX10) // unknown stays unknown
        assertEquals(FoodCategory(304, "Eggs", "Dairy & eggs"), egg.category)
        assertEquals(false, egg.dietInfo.vegan)
        assertEquals(true, egg.dietInfo.vegetarian)
        assertTrue(egg.dietInfo.glutenFreeNatural)
        assertEquals(listOf(Allergen.EGG), egg.dietInfo.contains)
        assertEquals(false, egg.dietInfo.has(Allergen.MILK))
        val beans = src.get(FoodsDbFixture.BLACK_BEANS)!!
        assertTrue(beans.dietInfo.isUnknown)
        assertNull(beans.dietInfo.has(Allergen.SOY))
        assertNull(beans.per.potassiumMg)
        assertEquals(18, src.categories().size)
        assertEquals("Eggs", src.categories().first { it.id == 304 }.name)
        // Hits carry diet info for the conflict badges.
        val hit = src.search("flour", 5).first()
        assertEquals(Allergen.of(1), Allergen.of(hit.dietInfo!!.allergens))
    }

    @Test
    fun searchRanksEverydayNamesBrandsAndWholeWords() = runTest {
        val src = FoodsDbSource(FoodsDbHandle(file, backgroundScope, StandardTestDispatcher(testScheduler)))
        suspend fun names(q: String) = src.search(q, 50).map { it.name }

        assertEquals("Big Mac", names("big mac").first())
        // Brand mode: a bare chain name lists the menu by popularity; the chain is named on each hit.
        val menu = src.search("mcdonalds", 50)
        assertEquals(listOf("Big Mac", "Hamburger", "Big Breakfast"), menu.map { it.name })
        assertTrue(menu.all { it.chain == "McDonald's" })
        assertEquals(listOf("Big Mac", "Big Breakfast"), names("McDonald's big"))
        assertEquals(listOf("Spicy Deluxe Sandwich"), names("chick fil a sandwich"))
        assertEquals(listOf("Nuggets, 8 count", "Spicy Deluxe Sandwich"), names("chickfila"))
        assertNull(src.search("big mac", 5).first().chain)
        // Everyday name first, then names that start with the word, prefix-only matches last.
        assertEquals(listOf("Carrots, raw", "Carrot cake", "Baby carrots"), names("carrot"))
        assertEquals(listOf("Cola (Coke or Pepsi)", "Coke Zero", "Coke Float, Large"), names("coke"))
        val salmon = names("salmon")
        assertEquals(listOf("Salmon (cooked)", "Salmon salad", "Salmonberries, raw"), salmon)
        // "ice cream": whole word "ice", so no iced tea.
        val ice = names("ice cream")
        assertEquals(listOf("Ice cream, vanilla", "Ice cream sandwich"), ice)
        assertTrue("Iced tea, lemon" in names("iced"))
        // As you type: the last word is a prefix.
        assertEquals("Ice cream, vanilla", names("ice cr").first())
    }

    @Test
    fun tiersFollowTheSearchRules() {
        fun c(name: String, kw: String? = null, pop: Int = 500, brand: String? = null) = FoodsDbSearch.Candidate(name, brand, kw, pop)
        val q = FoodsDbSearch.words("ice cream")
        assertEquals(0, FoodsDbSearch.tier(q, c("Ice cream, vanilla", ", ice cream, vanilla ice cream", 1000)))
        assertEquals(1, FoodsDbSearch.tier(q, c("Frozen dessert", ", vanilla ice cream", 1000)))
        // Not pinned (popularity below 1000): keywords are not everyday terms.
        assertEquals(2, FoodsDbSearch.tier(q, c("Ice cream, vanilla", ", ice cream", 999)))
        assertEquals(3, FoodsDbSearch.tier(q, c("Vanilla ice cream bar")))
        assertEquals(4, FoodsDbSearch.tier(q, c("Gelato", "ice cream")))
        assertEquals(5, FoodsDbSearch.tier(FoodsDbSearch.words("salmon"), c("Salmonberries, raw")))
        // A short last word may be a prefix of a name word.
        assertEquals(3, FoodsDbSearch.tier(FoodsDbSearch.words("apple ju"), c("Green apple juice")))
        assertEquals(2, FoodsDbSearch.tier(FoodsDbSearch.words("creme brulee"), c("Crème brûlée")))
        val chains = listOf(1L to "McDonald's", 3L to "Chick-fil-A")
        assertEquals(FoodsDbSearch.BrandMode(3, "Chick-fil-A", listOf("sandwich")), FoodsDbSearch.brandMode(FoodsDbSearch.words("chick fil a sandwich"), chains))
        assertEquals(FoodsDbSearch.BrandMode(1, "McDonald's", emptyList()), FoodsDbSearch.brandMode(FoodsDbSearch.words("mcdonalds"), chains))
        assertNull(FoodsDbSearch.brandMode(FoodsDbSearch.words("big mac"), chains))
    }

    companion object {
        /** testdata/fooddb/foods-sample.db, looked up from the module or the repo root, or null. */
        fun sampleFile(): File? = listOf("../testdata/fooddb/foods-sample.db", "testdata/fooddb/foods-sample.db")
            .map { File(it) }
            .firstOrNull { it.isFile }
    }
}
