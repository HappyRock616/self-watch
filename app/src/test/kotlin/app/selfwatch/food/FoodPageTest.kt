/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Serving
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Food tab audit: meal summary math, "Recent for <meal>" and its one-tap add. */
@OptIn(ExperimentalCoroutinesApi::class)
class FoodPageTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val fake = FakeFoodBackend()
    private val today = fake.clock.today()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun TestScope.diary(): DiaryViewModel {
        val vm = DiaryViewModel(flowOf(true), dispatcher) { fake }
        backgroundScope.launch { vm.state.collect {} }
        return vm
    }

    private fun ref(id: Long) = FoodRef(FoodOrigin.MY_FOOD, id)

    private var nextId = 100L
    private fun line(day: Int, slot: Long, ref: FoodRef?, name: String, t: Long = 0, kcal: Int = 100) =
        DiaryEntry(nextId++, today.plusDays(-day), slot, t, ref, name, null, 100, "1 cup", null, FakeFoodBackend.n(kcal), null)

    @Test
    fun recentsAreTheMealsMostFrequentFoodsNotAlreadyInIt() {
        val oats = ref(1)
        val eggs = ref(2)
        val toast = ref(3)
        val soup = ref(4)
        val history = listOf(
            line(1, 1, oats, "Oats", t = 10),
            line(2, 1, oats, "Oats", t = 5),
            line(3, 1, oats, "Oats", t = 1),
            line(1, 1, eggs, "Eggs", t = 11),
            line(2, 1, toast, "Toast", t = 6),
            line(5, 1, toast, "Toast old", t = 2),
            line(1, 2, soup, "Soup"), // lunch, not breakfast
            line(1, 1, null, "Cake"), // quick add: never offered
        )
        val picked = MealMath.recents(history, 1, inMeal = emptyList())
        assertEquals(listOf("Oats", "Toast", "Eggs"), picked.map { it.name })
        // Each food comes as its latest line (its last serving).
        assertEquals(today.plusDays(-2), picked[1].day)

        // Already eaten today: not offered again.
        val today = listOf(line(0, 1, oats, "Oats"))
        assertEquals(listOf("Toast", "Eggs"), MealMath.recents(history, 1, today).map { it.name })
        // Short list, empty without history.
        assertEquals(2, MealMath.recents(history, 1, emptyList(), limit = 2).size)
        assertTrue(MealMath.recents(history, 3, emptyList()).isEmpty())
    }

    @Test
    fun sharesSplitTheMealsCaloriesMostFirst() {
        val a = line(0, 1, ref(1), "Rice", kcal = 300)
        val b = line(0, 1, ref(2), "Beans", kcal = 100)
        val shares = MealMath.shares(listOf(b, a))
        assertEquals(listOf("Rice", "Beans"), shares.map { it.entry.name })
        assertEquals(listOf(75, 25), shares.map { it.percent })
        val none = MealMath.shares(listOf(line(0, 1, null, "Water", kcal = 0)))
        assertNull(none.single().percent)
    }

    @Test
    fun completeCountsFoodsThatKnowEveryNutrient() {
        val full = Nutrients.fromList(List(Nutrients.Field.entries.size) { 10 })
        val e1 = line(0, 1, ref(1), "Full").copy(nutrients = full)
        val e2 = line(0, 1, ref(2), "Partial")
        assertEquals(1, MealMath.complete(listOf(e1, e2)))
    }

    @Test
    fun diaryOffersRecentsPerMealAndOneTapLogsTheLastServing() = test {
        val oats = FakeFoodBackend.food(1, "Oats", kcal = 150)
        fake.foods[oats.ref] = oats
        fake.last[oats.ref] = Serving(0, "1 cup", 2400) to 200
        fake.entry(today.plusDays(-1), 1, "Oats", 150, oats.ref)
        fake.entry(today.plusDays(-2), 1, "Oats", 150, oats.ref)
        fake.entry(today.plusDays(-40), 3, "Old stew", 400, FoodRef(FoodOrigin.MY_FOOD, 9)) // outside the window
        val vm = diary()
        val breakfast = vm.state.value.meals.first { it.slot.id == 1L }
        assertEquals(listOf("Oats"), breakfast.recents.map { it.name })
        assertTrue("no history for dinner", vm.state.value.meals.first { it.slot.id == 3L }.recents.isEmpty())

        val events = ArrayList<DiaryEvent>()
        backgroundScope.launch { vm.events.collect { events.add(it) } }
        vm.logRecent(1, breakfast.recents.single())
        val now = vm.state.value.meals.first { it.slot.id == 1L }
        assertEquals(1, now.entries.size)
        assertEquals(200, now.entries.single().amountX100) // the last serving: 2 cups
        assertTrue("logged foods leave the strip", now.recents.isEmpty())
        val logged = events.single() as DiaryEvent.Logged
        assertEquals("Oats", logged.name)

        vm.undoLog(logged.id)
        assertTrue(vm.state.value.meals.first { it.slot.id == 1L }.entries.isEmpty())
    }

    @Test
    fun mealSummaryOffersTheMealsRecentsAndLogsIntoThatMeal() = test {
        val stew = FakeFoodBackend.food(5, "Stew", kcal = 300)
        fake.foods[stew.ref] = stew
        fake.entry(today.plusDays(-1), 3, "Stew", 300, stew.ref)
        fake.entry(today.plusDays(-2), 1, "Toast", 200, FoodRef(FoodOrigin.MY_FOOD, 6)) // breakfast: not offered at dinner
        val vm = MealSummaryViewModel(today, 3) { fake }
        backgroundScope.launch { vm.state.collect {} }
        assertEquals(listOf("Stew"), vm.state.value.recents.map { it.name })

        val events = ArrayList<DiaryEvent>()
        backgroundScope.launch { vm.events.collect { events.add(it) } }
        vm.logRecent(vm.state.value.recents.single())
        assertEquals(listOf("Stew"), vm.state.value.entries.map { it.name })
        assertEquals(3L, vm.state.value.entries.single().mealSlotId)
        assertTrue(vm.state.value.recents.isEmpty())
        val logged = events.single() as DiaryEvent.Logged
        vm.undoLog(logged.id)
        assertTrue(vm.state.value.entries.isEmpty())
    }

    @Test
    fun relogCopiesTheLineWhenTheFoodIsGone() = test {
        val gone = FoodRef(FoodOrigin.FOODS_DB, 77)
        val last = line(3, 2, gone, "Mystery bar").copy(id = 0)
        val id = QuickLog.relog(fake, last, today, 4)
        val e = fake.entries.single { it.id == id }
        assertEquals(today, e.day)
        assertEquals(4L, e.mealSlotId)
        assertEquals("Mystery bar", e.name)
    }
}
