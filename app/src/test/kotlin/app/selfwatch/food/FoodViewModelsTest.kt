/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import app.selfwatch.food.nutrition.NutrientMath
import app.selfwatch.food.source.BarcodeLookup
import app.selfwatch.food.source.FoodHit
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.MatchState
import app.selfwatch.model.food.MealSlot
import app.selfwatch.model.food.Recipe
import app.selfwatch.model.food.RecipeItem
import app.selfwatch.model.food.RecipeKind
import app.selfwatch.model.food.Serving
import app.selfwatch.model.net.NetFeature
import app.selfwatch.model.result.SwError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FoodViewModelsTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val fake = FakeFoodBackend()
    private val today = fake.clock.today()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    // --- Diary ---

    private fun TestScope.diary(enabled: Boolean = true): DiaryViewModel {
        val vm = DiaryViewModel(flowOf(enabled)) { fake }
        backgroundScope.launch { vm.state.collect {} }
        return vm
    }

    @Test
    fun diaryShowsTodaysMealsWithKcalAndBudget() = test {
        fake.entry(today, 1, "Oatmeal", 150)
        fake.entry(today, 1, "Banana", 105)
        fake.entry(today, 2, "Burrito", 750)
        val s = diary().state.value
        assertEquals(today, s.day)
        assertTrue(s.isToday)
        assertEquals(listOf("Breakfast", "Lunch", "Dinner", "Snacks"), s.meals.map { it.slot.name })
        assertEquals(255, s.meals[0].kcal)
        assertEquals(1005, s.eatenKcal)
        assertEquals(2000 - 1005 + 300, s.budget?.remainingKcal)
    }

    @Test
    fun copyYesterdayOnlyWhenYesterdayHadTheMealAndTodayHasNot() = test {
        val y = today.plusDays(-1)
        fake.entry(y, 3, "Pasta", 600)
        fake.entry(y, 3, "Salad", 40)
        fake.entry(y, 1, "Toast", 200)
        fake.entry(today, 1, "Eggs", 150)
        val vm = diary()
        val meals = vm.state.value.meals.associateBy { it.slot.id }
        assertEquals(YesterdayOffer(2, 640), meals[3L]?.yesterday)
        assertNull("today already has breakfast", meals[1L]?.yesterday)
        assertNull("yesterday had no lunch", meals[2L]?.yesterday)

        vm.copyYesterday(3)
        val after = vm.state.value.meals.first { it.slot.id == 3L }
        assertEquals(2, after.entries.size)
        assertNull(after.yesterday)
    }

    @Test
    fun daySwitcherNeverGoesPastToday() = test {
        val vm = diary()
        vm.nextDay()
        assertEquals(today, vm.state.value.day)
        vm.previousDay()
        assertEquals(today.plusDays(-1), vm.state.value.day)
        assertFalse(vm.state.value.isToday)
        vm.goToToday()
        assertEquals(today, vm.state.value.day)
    }

    @Test
    fun deleteOffersUndoThatRestores() = test {
        val e = fake.entry(today, 2, "Soup", 200)
        val vm = diary()
        val events = ArrayList<DiaryEvent>()
        backgroundScope.launch { vm.events.collect { events.add(it) } }
        vm.delete(e)
        assertTrue(vm.state.value.meals[1].entries.isEmpty())
        assertEquals(DiaryEvent.Deleted(e), events.single())
        vm.undoDelete(e)
        assertEquals(1, vm.state.value.meals[1].entries.size)
    }

    @Test
    fun waterSquaresAndFastToggle() = test {
        val vm = diary()
        vm.tapWater(0)
        vm.tapWater(1)
        assertEquals(500, vm.state.value.water?.totalMl)
        vm.tapWater(0) // tapping a full square removes the last glass
        assertEquals(250, vm.state.value.water?.totalMl)
        vm.toggleFast()
        assertEquals(DiaryViewModel.DEFAULT_FAST_MIN, vm.state.value.fast?.fast?.goalMin)
        vm.toggleFast()
        assertNull(vm.state.value.fast)
    }

    @Test
    fun foodOffNeverBuildsTheBackend() = test {
        var built = false
        val vm = DiaryViewModel(flowOf(false)) { built = true; fake }
        backgroundScope.launch { vm.state.collect {} }
        assertEquals(false, vm.state.value.enabled)
        assertFalse(built)
    }

    // --- Add food ---

    @Test
    fun oneTapLogsTheLastServing() = test {
        val f = FakeFoodBackend.food(1, "Greek yogurt", Basis.PER_100G, 60, listOf(Serving(0, "1 cup", 2450), Serving(1, "170 g pot", 1700)))
        fake.foods[f.ref] = f
        fake.last[f.ref] = Serving(-1, "170 g pot", 1700) to 200
        val vm = AddFoodViewModel(today, 2) { fake }
        vm.quickLog(FoodRows.hitOf(f, FoodHit.TAG_MY_FOOD))
        val (_, serving, amount) = fake.logged.single()
        assertEquals("170 g pot", serving.label)
        assertEquals(1, serving.seq) // matched back to the named serving
        assertEquals(200, amount)
        assertEquals(2L, fake.entries.single().mealSlotId)
    }

    @Test
    fun oneTapUsesTheDefaultServingWhenNeverLogged() = test {
        val perItem = FakeFoodBackend.food(1, "Apple", Basis.PER_ITEM, 95, listOf(Serving(0, "1 medium", 1820)))
        val per100 = FakeFoodBackend.food(2, "Rice", Basis.PER_100G, 130, emptyList())
        fake.foods[perItem.ref] = perItem
        fake.foods[per100.ref] = per100
        val vm = AddFoodViewModel(today, 1) { fake }
        val events = ArrayList<AddFoodEvent>()
        backgroundScope.launch { vm.events.collect { events.add(it) } }
        vm.quickLog(FoodRows.hitOf(perItem, FoodHit.TAG_MY_FOOD))
        vm.quickLog(FoodRows.hitOf(per100, FoodHit.TAG_MY_FOOD))
        assertEquals("1 medium" to 100, fake.logged[0].second.label to fake.logged[0].third)
        assertEquals(NutrientMath.GRAM to FoodRows.HUNDRED_GRAMS_X100, fake.logged[1].second to fake.logged[1].third)
        assertEquals(listOf(95, 130), events.filterIsInstance<AddFoodEvent.Logged>().map { it.kcal })
    }

    @Test
    fun searchIsDebouncedAndShowsEachMergedAnswer() = test {
        val a = FoodHit(FoodRef(FoodOrigin.MY_FOOD, 1), "Banana bread", null, 2000, "1 slice", FoodHit.TAG_MY_FOOD, 0)
        val b = FoodHit(FoodRef(FoodOrigin.FOODS_DB, 9), "Banana", null, 1050, "1 medium", FoodHit.TAG_FOODS_DB, 0, kind = 0)
        fake.searchAnswers["banana"] = listOf(listOf(a), listOf(a, b))
        val vm = AddFoodViewModel(today, 1) { fake }
        vm.setQuery("b")
        vm.setQuery("ba")
        vm.setQuery("banana")
        advanceTimeBy(AddFoodViewModel.DEBOUNCE_MS - 10)
        runCurrent()
        assertTrue("nothing searched while typing", fake.searched.isEmpty())
        advanceTimeBy(20)
        runCurrent()
        assertEquals(listOf("banana"), fake.searched)
        assertEquals(listOf(a, b), vm.state.value.results)
        assertEquals(listOf(a, b), vm.state.value.rows)
        vm.setQuery("")
        advanceUntilIdle()
        assertNull(vm.state.value.results)
    }

    @Test
    fun tabsLoadTheirRows() = test {
        val f = FakeFoodBackend.food(1, "Granola")
        fake.foods[f.ref] = f
        fake.favourites.add(f.ref)
        val vm = AddFoodViewModel(today, 1) { fake }
        assertEquals(AddFoodTab.RECENT, vm.state.value.tab)
        assertTrue(vm.state.value.rows.isEmpty())
        vm.selectTab(AddFoodTab.FAVORITES)
        assertEquals(listOf("Granola"), vm.state.value.rows.map { it.name })
        vm.selectTab(AddFoodTab.MINE)
        assertEquals(listOf("Granola"), vm.state.value.rows.map { it.name })
    }

    // --- Scan ---

    @Test
    fun scanFoundLocallyOpensTheFood() = test {
        val f = FakeFoodBackend.food(5, "Granola bar")
        fake.localBarcodes[4006381333931L] = f
        val vm = ScanViewModel { fake }
        vm.onBarcode(4006381333931L)
        assertEquals(ScanState.Found(4006381333931L, f.ref, "Granola bar"), vm.state.value)
        assertEquals(0, fake.onlineCalls)
    }

    @Test
    fun scanNotFoundOffersOnlineThenCustomFood() = test {
        val vm = ScanViewModel { fake }
        vm.onBarcode(123L)
        assertEquals(ScanState.NotFound(123L, canLookOnline = true, triedOnline = false), vm.state.value)
        fake.online = BarcodeLookup.NotFound
        vm.lookOnline()
        assertEquals(ScanState.NotFound(123L, canLookOnline = false, triedOnline = true), vm.state.value)
        assertEquals(1, fake.onlineCalls)
    }

    @Test
    fun scanOnlineFoundOrUnavailable() = test {
        val saved = FakeFoodBackend.food(77, "Oat drink")
        fake.online = BarcodeLookup.Found(saved, FoodHit.TAG_OPEN_FOOD_FACTS)
        val vm = ScanViewModel { fake }
        vm.onBarcode(55L)
        vm.lookOnline()
        assertEquals(ScanState.Found(55L, saved.ref, "Oat drink"), vm.state.value)

        vm.scanAgain()
        fake.online = BarcodeLookup.Unavailable(SwError.Offline)
        vm.onBarcode(56L)
        vm.lookOnline()
        assertEquals(ScanState.Unavailable(56L, SwError.Offline), vm.state.value)
    }

    @Test
    fun scanWithFirewallBlockingNeverOffersOnline() = test {
        fake.onlineAllowed = false
        val vm = ScanViewModel { fake }
        vm.onBarcode(99L)
        assertEquals(ScanState.NotFound(99L, canLookOnline = false, triedOnline = false), vm.state.value)
        vm.lookOnline()
        assertEquals(0, fake.onlineCalls)
        assertNotNull(SwError.Blocked(NetFeature.OPEN_FOOD_FACTS))
    }

    @Test
    fun typedCodesAreValidated() = test {
        val vm = ScanViewModel { fake }
        vm.onTyped("12345")
        assertEquals(ScanState.BadCode, vm.state.value)
        vm.onTyped("4006381333931")
        assertTrue(vm.state.value is ScanState.NotFound)
    }

    // --- Food detail and quick add ---

    @Test
    fun detailPicksLastServingAndPreviews() = test {
        val f = FakeFoodBackend.food(1, "Milk", Basis.PER_100G, 64, listOf(Serving(0, "1 cup", 2440)))
        fake.foods[f.ref] = f
        val vm = FoodDetailViewModel(FoodDetailTarget.Add(f.ref, today, 1)) { fake }
        val s = vm.state.value
        assertEquals(listOf("1 cup", "g"), s.servings.map { it.label })
        assertEquals(156, FoodRows.kcal(s.preview))
        vm.selectServing(1)
        assertEquals(FoodRows.HUNDRED_GRAMS_X100, vm.state.value.amountX100)
        assertEquals(64, FoodRows.kcal(vm.state.value.preview))
        vm.save()
        assertEquals(1, fake.entries.size)
    }

    @Test
    fun quickAddNeedsCalories() = test {
        val vm = QuickAddViewModel(today, 4) { fake }
        assertFalse(vm.save("Snack", null, null, null, null))
        assertTrue(vm.save("Snack", 25_000, 1_000, null, null))
        val e = fake.entries.single()
        assertEquals(2500, e.nutrients.kcalX10)
        assertEquals(100, e.nutrients.proteinX10)
        assertEquals(4L, e.mealSlotId)
    }

    // --- Recipes ---

    private fun recipe(): Recipe = Recipe(
        1, RecipeKind.RECIPE, "Pancakes", 40, null, null, null, "Mix.", null, null, null, null, setOf("breakfast"),
        listOf(
            RecipeItem(0, "2 cups flour", null, 200, "cup", null, MatchState.UNMATCHED),
            RecipeItem(1, "1 1/2 cups milk, warm", null, 150, "cup", null, MatchState.UNMATCHED),
            RecipeItem(2, "salt to taste", null, null, null, null, MatchState.SKIPPED),
        ),
        null,
    )

    @Test
    fun recipeScalingRule() {
        assertEquals(300, RecipeDetailViewModel.scaleX100(200, 40, 60))
        assertEquals(100, RecipeDetailViewModel.scaleX100(200, 40, 20))
        assertEquals(17, RecipeDetailViewModel.scaleX100(25, 30, 20))
        val r = recipe()
        assertEquals("2 cups flour", RecipeDetailViewModel.lineText(r.items[0], 40, 40))
        assertEquals("salt to taste", RecipeDetailViewModel.lineText(r.items[2], 40, 80))
    }

    @Test
    fun recipeDetailScalesAndFixesMatches() = test {
        fake.recipes[1] = recipe()
        val flour = FakeFoodBackend.food(8, "Flour", origin = FoodOrigin.FOODS_DB)
        fake.foods[flour.ref] = flour
        val vm = RecipeDetailViewModel(1) { fake }
        assertTrue(vm.state.value.needsMatching)
        vm.setServings(80)
        val scaled = vm.state.value.lines[0].text
        assertTrue(scaled, scaled.startsWith("4 cup"))
        vm.confirm(0, flour.ref)
        vm.skip(1)
        val s = vm.state.value
        assertEquals(MatchState.CONFIRMED, s.lines[0].item.match)
        assertEquals("Flour", s.lines[0].foodName)
        assertEquals(MatchState.SKIPPED, fake.recipes[1]?.items?.get(1)?.match)
        assertFalse(s.needsMatching)
        assertEquals(80, s.servingsX10) // scale kept across the save
    }

    @Test
    fun recipeEditorKeepsMatchesOfUnchangedLines() {
        val old = recipe().copy(items = recipe().items.mapIndexed { i, it -> if (i == 0) it.copy(food = FoodRef(FoodOrigin.FOODS_DB, 8), match = MatchState.CONFIRMED) else it })
        val r = RecipeEditorViewModel.recipeOf(RecipeForm("Pancakes", "6", "2 cups flour\n\n3 eggs\n", "", "Breakfast, quick"), old)
        assertEquals(60, r.servingsX10)
        assertEquals(listOf("2 cups flour", "3 eggs"), r.items.map { it.line })
        assertEquals(MatchState.CONFIRMED, r.items[0].match)
        assertEquals(MatchState.UNMATCHED, r.items[1].match)
        assertEquals(300, r.items[1].amountX100)
        assertEquals(setOf("breakfast", "quick"), r.tags)
        assertEquals(1L, r.id)
    }

    @Test
    fun slotGuessFollowsTheClock() {
        val slots = listOf(MealSlot(1, "B", 1, false), MealSlot(2, "L", 2, false), MealSlot(3, "D", 3, false), MealSlot(4, "S", 4, false))
        assertEquals(1L, FoodRows.slotForHour(7, slots)?.id)
        assertEquals(2L, FoodRows.slotForHour(12, slots)?.id)
        assertEquals(3L, FoodRows.slotForHour(19, slots)?.id)
        assertEquals(4L, FoodRows.slotForHour(23, slots)?.id)
        assertEquals(2L, FoodRows.slotForHour(23, slots.take(2))?.id)
    }

    @Test
    fun customFoodFormValidation() {
        val (none, errors) = FoodEditorViewModel.toFood(FoodForm(), 0)
        assertNull(none)
        assertEquals(setOf(FoodFormError.NAME, FoodFormError.KCAL), errors)
        val (food, ok) = FoodEditorViewModel.toFood(FoodForm(name = "Bar", kcal = "190", perServing = true, servingLabel = "1 bar", servingGrams = "40", protein = "4.5", barcode = "4006381333931"), 0)
        assertTrue(ok.isEmpty())
        assertEquals(1900, food?.per?.kcalX10)
        assertEquals(45, food?.per?.proteinX10)
        assertEquals(Serving(0, "1 bar", 400), food?.servings?.single())
        assertEquals(4006381333931L, food?.barcode)
    }
}
