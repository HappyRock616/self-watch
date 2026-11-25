/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import app.selfwatch.data.ImportViewModel
import app.selfwatch.interop.Interop
import app.selfwatch.interop.api.MemorySink
import app.selfwatch.model.food.MatchState
import app.selfwatch.model.food.Recipe
import app.selfwatch.model.food.RecipeItem
import app.selfwatch.model.food.RecipeKind
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipInputStream

/** Copy day, export day, meal names, the + sheet's water and fasting, and recipe import and export. */
@OptIn(ExperimentalCoroutinesApi::class)
class FoodToolsTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val fake = FakeFoodBackend()
    private val today = fake.clock.today()
    private val yesterday = today.plusDays(-1)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun TestScope.diary(): Pair<DiaryViewModel, List<DiaryEvent>> {
        val vm = DiaryViewModel(flowOf(true), dispatcher) { fake }
        val events = ArrayList<DiaryEvent>()
        backgroundScope.launch { vm.state.collect {} }
        backgroundScope.launch { vm.events.collect { events.add(it) } }
        return vm to events
    }

    // --- Copy day ---

    @Test
    fun copyDayCopiesEveryMealAndUndoRemovesOnlyTheCopies() = test {
        fake.entry(today, 1, "Oatmeal", 150)
        fake.entry(today, 3, "Pasta", 600)
        fake.entry(yesterday, 2, "Soup", 200)
        val (vm, events) = diary()
        vm.copyTo(yesterday)
        val copied = events.filterIsInstance<DiaryEvent.CopiedTo>().single()
        assertEquals(yesterday, copied.to)
        assertEquals(2, copied.ids.size)
        val y = fake.entries.filter { it.day == yesterday }
        assertEquals(setOf("Soup", "Oatmeal", "Pasta"), y.map { it.name }.toSet())
        assertEquals(3L, y.first { it.name == "Pasta" }.mealSlotId)
        vm.undoCopy(copied.ids)
        assertEquals(listOf("Soup"), fake.entries.filter { it.day == yesterday }.map { it.name })
        assertEquals(2, fake.entries.count { it.day == today })
        assertTrue(DiaryEvent.CopyUndone in events)
    }

    @Test
    fun copyOneMealCopiesOnlyThatMeal() = test {
        fake.entry(today, 1, "Oatmeal", 150)
        fake.entry(today, 3, "Pasta", 600)
        val (vm, _) = diary()
        vm.copyTo(yesterday, slot = 3)
        assertEquals(listOf("Pasta"), fake.entries.filter { it.day == yesterday }.map { it.name })
    }

    @Test
    fun copyOntoTheSameDayOrFromAnEmptyDayCopiesNothing() = test {
        fake.entry(today, 1, "Oatmeal", 150)
        val (vm, events) = diary()
        vm.copyTo(today)
        assertEquals(1, fake.entries.size)
        vm.showDay(yesterday)
        vm.copyTo(today)
        assertEquals(1, fake.entries.size)
        assertEquals(listOf(DiaryEvent.NothingLogged), events)
    }

    // --- Export day ---

    @Test
    fun exportDayWritesOneCsvRowPerEntryWithMealAndNutrients() = test {
        fake.entry(today, 1, "Oatmeal", 150)
        fake.entry(today, 3, "Pasta, fresh", 600)
        fake.entry(yesterday, 2, "Soup", 200)
        val (vm, events) = diary()
        val sink = MemorySink("day.csv")
        vm.exportDay(today, sink, ZoneOffset.UTC)
        val lines = sink.bytes().toString(Charsets.UTF_8).removePrefix(BOM).trim().lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0], lines[0].startsWith("Day,Time,Group,Food Name,Amount"))
        assertTrue(lines[1], lines[1].contains("Breakfast") && lines[1].contains("Oatmeal") && lines[1].contains("150"))
        assertTrue(lines[2], lines[2].contains("Dinner") && lines[2].contains("\"Pasta, fresh\""))
        assertEquals(DiaryEvent.Exported(2), events.single())
    }

    @Test
    fun exportDayIncludesTheV2NutrientsWithUnits() = test {
        fake.entries += app.selfwatch.model.food.DiaryEntry(
            500, today, 1, 0, null, "Lentil soup", null, 100, "bowl", null,
            app.selfwatch.model.food.Nutrients(
                2300, 120, 40, 5, 350, 40, 80, 600, 0,
                transfatX10 = 0, monofatX10 = 15, polyfatX10 = 12, addsugarX10 = 10, potassiumMg = 730, calciumMg = 45,
                ironX10 = 33, vitaUg = 20, vitcX10 = 45, vitdX10 = null,
            ),
            null,
        )
        val (vm, _) = diary()
        val sink = MemorySink("day.csv")
        vm.exportDay(today, sink, ZoneOffset.UTC)
        val lines = sink.bytes().toString(Charsets.UTF_8).removePrefix(BOM).trim().lines()
        val header = lines[0].split(",")
        val cells = lines[1].split(",")
        fun cell(name: String): String {
            val i = header.indexOf(name)
            assertTrue("missing column $name in $header", i >= 0)
            return cells[i]
        }
        assertEquals("0", cell("Trans-Fats (g)"))
        assertEquals("1.5", cell("Monounsaturated (g)"))
        assertEquals("1.2", cell("Polyunsaturated (g)"))
        assertEquals("1", cell("Added Sugars (g)"))
        assertEquals("730", cell("Potassium (mg)"))
        assertEquals("45", cell("Calcium (mg)"))
        assertEquals("3.3", cell("Iron (mg)"))
        assertEquals("20", cell("Vitamin A (µg)"))
        assertEquals("4.5", cell("Vitamin C (mg)"))
        assertEquals("", cell("Vitamin D (µg)")) // unknown stays blank, never 0
        assertEquals("230", cell("Energy (kcal)"))
    }

    @Test
    fun exportOfAnEmptyDayWritesNothing() = test {
        val (vm, events) = diary()
        val sink = MemorySink()
        vm.exportDay(today, sink)
        assertEquals(0, sink.bytes().size)
        assertEquals(DiaryEvent.NothingLogged, events.single())
        assertEquals("selfwatch-food-${today.toLocalDate()}.csv", DayExport.fileName(today))
    }

    // --- Meal names ---

    @Test
    fun mealsCanBeRenamedReorderedAddedRemovedAndBroughtBack() = test {
        val e = fake.entry(today, 4, "Apple", 95)
        val vm = MealNamesViewModel { fake }
        assertEquals(listOf("Breakfast", "Lunch", "Dinner", "Snacks"), vm.state.value.visible.map { it.name })

        vm.rename(1, "  Early bite ")
        vm.rename(2, "   ")
        assertEquals(listOf("Early bite", "Lunch", "Dinner", "Snacks"), vm.state.value.visible.map { it.name })

        vm.move(3, -1)
        vm.move(1, -1) // already first: nothing
        assertEquals(listOf(1L, 3L, 2L, 4L), vm.state.value.visible.map { it.id })

        vm.add("Second dinner")
        assertEquals("Second dinner", vm.state.value.visible.last().name)

        vm.remove(4)
        assertEquals(listOf("Snacks"), vm.state.value.hidden.map { it.name })
        // The entry keeps its meal, and its day still shows the removed meal.
        assertEquals(4L, fake.entries.single { it.id == e.id }.mealSlotId)
        val (d, _) = diary()
        assertTrue(d.state.value.meals.any { it.slot.id == 4L && it.entries.size == 1 })

        vm.restore(4)
        assertEquals(4L, vm.state.value.visible.last().id)
        assertTrue(vm.state.value.hidden.isEmpty())
    }

    @Test
    fun theLastMealCannotBeRemoved() = test {
        val vm = MealNamesViewModel { fake }
        listOf(1L, 2L, 3L, 4L).forEach { vm.remove(it) }
        assertEquals(1, vm.state.value.visible.size)
        assertFalse(vm.state.value.canRemove)
        assertEquals(MealNamesViewModel.MAX_NAME, MealNamesViewModel.clean("x".repeat(99))?.length)
    }

    // --- + sheet: water and fasting ---

    @Test
    fun quickLogAddsAGlassAndTogglesTheFast() = test {
        val w = QuickLog.water(fake, WaterGlass.US_ML)
        assertEquals(355, w.totalMl)
        assertEquals(2000, w.goalMl)
        QuickLog.undoWater(fake)
        assertEquals(0, fake.water)

        assertEquals(QuickLogResult.FastStarted(DiaryViewModel.DEFAULT_FAST_MIN), QuickLog.toggleFast(fake))
        assertTrue(fake.fast != null)
        fake.clock.advance(3_600)
        val ended = QuickLog.toggleFast(fake) as QuickLogResult.FastEnded
        assertEquals(3_600L, ended.elapsedSec)
        assertTrue(fake.fast == null)
    }

    // --- Recipe book ---

    private fun recipe(name: String, tags: Set<String>) = Recipe(
        0, RecipeKind.RECIPE, name, 20, null, null, null, "Cook.", null, null, null, null, tags,
        listOf(RecipeItem(0, "1 cup rice", null, null, null, null, MatchState.UNMATCHED)), null,
    )

    @Test
    fun recipeBookExportsEveryRecipeAsAZip() = test {
        fake.recipes[1] = recipe("Chili", setOf("dinner")).copy(id = 1)
        fake.recipes[2] = recipe("Porridge", setOf("breakfast")).copy(id = 2)
        val vm = RecipeBookViewModel { fake }
        val events = CopyOnWriteArrayList<RecipeBookEvent>()
        backgroundScope.launch { vm.events.collect { events.add(it) } }
        vm.selectTag("dinner") // a filter on screen doesn't limit the export
        val sink = MemorySink()
        vm.export(sink)
        val end = System.currentTimeMillis() + 5_000
        while (events.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(RecipeBookEvent.Exported(2), events.single())
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(sink.bytes())).use { z -> generateSequence { z.nextEntry }.forEach { names.add(it.name) } }
        assertEquals(listOf("Recipes/Chili/recipe.json", "Recipes/Porridge/recipe.json"), names.sorted())
        assertTrue(vm.exportFileName().endsWith(".zip"))
        assertEquals("application/zip", vm.exportMimeType)
    }

    @Test
    fun recipeImportOffersOnlyTheRecipeImporters() {
        val all = Interop.importers()
        val recipes = ImportViewModel.importersFor(all, recipesOnly = true)
        assertEquals(listOf("recipe-jsonld", "paprika", "mealie", "tandoor", "mealmaster", "cooklang"), recipes.map { it.id })
        assertEquals(all, ImportViewModel.importersFor(all, recipesOnly = false))
    }

    private companion object {
        const val BOM = "\uFEFF"
    }
}
