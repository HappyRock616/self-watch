/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import app.selfwatch.me.MeTestSupport
import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.selfwatch.FoodGraph
import app.selfwatch.SelfWatchApp
import app.selfwatch.db.CustomFoodOrigin
import app.selfwatch.db.TestDb
import app.selfwatch.db.dao.CustomFood
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
import app.selfwatch.model.time.SystemClock
import app.selfwatch.shell.MainActivity
import app.selfwatch.shell.Tab
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Robolectric smoke tests: every food screen opens with empty data and with sample data. */
@RunWith(RobolectricTestRunner::class)
class FoodScreensTest {

    private lateinit var app: SelfWatchApp
    private val food: FoodGraph get() = app.graph.food

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as SelfWatchApp
        TestDb.wipeShared(app)
    }

    @After
    fun tearDown() = MeTestSupport.resetDb()

    private fun waitFor(what: String, dump: () -> String = { "" }, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(POLL_MS))
            if (cond()) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out waiting for $what: ${dump()}")
    }

    private fun texts(v: View): List<String> = when {
        v.visibility != View.VISIBLE -> emptyList()
        v is TextView -> listOf(v.text?.toString().orEmpty())
        v is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun rvInfo(v: View): String = when (v) {
        is androidx.recyclerview.widget.RecyclerView -> "items=${v.adapter?.itemCount} children=${v.childCount} h=${v.height} w=${v.width} shown=${v.isShown}"
        is ViewGroup -> (0 until v.childCount).joinToString("") { rvInfo(v.getChildAt(it)) }
        else -> ""
    }

    private fun Activity.shows(text: String): Boolean = texts(window.decorView).any { it.contains(text) }

    private val apple = Food(FoodRef(FoodOrigin.MY_FOOD, 0), "Crisp apple", null, 0, Basis.PER_ITEM, Nutrients(950, 5, 3, null, 250, null, null, null, null), listOf(Serving(0, "1 medium", 1820)), null, "", null)

    private fun seed(): Long = runBlocking {
        val id = food.db.foods.insert(CustomFood(apple, CustomFoodOrigin.TYPED, null, false, 0, 0, null))
        val saved = requireNotNull(food.myFoods.get(id))
        food.diary.add(SystemClock.today(), 1, saved, saved.servings[0], 100)
        food.diary.quickAdd(SystemClock.today(), 2, "Office cake", Nutrients(3500, null, null, null, null, null, null, null, null))
        food.water.add(250)
        id
    }

    private fun seedRecipe(): Long = runBlocking {
        food.recipes.save(
            Recipe(
                0, RecipeKind.RECIPE, "Weeknight chili", 40, null, null, null, "Simmer.", null, null, null, null, setOf("dinner"),
                listOf(RecipeItem(0, "1 can beans", null, null, null, null, MatchState.UNMATCHED)), null,
            ),
        )
    }

    private fun openFoodTab(): MainActivity {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.select(Tab.FOOD)
        return a
    }

    @Test
    fun diaryEmpty() {
        val a = openFoodTab()
        waitFor("meal cards") { a.shows("Breakfast") && a.shows("Snacks") && a.shows("Water") }
        assertTrue(a.shows("Daily balance"))
    }

    @Test
    fun diaryWithEntries() {
        seed()
        val a = openFoodTab()
        waitFor("entries", { texts(a.window.decorView).toString() }) { a.shows("Crisp apple") && a.shows("Office cake") && a.shows("of 8 glasses") }
    }

    @Test
    fun addFoodEmptyAndWithFoods() {
        val empty = Robolectric.buildActivity(AddFoodActivity::class.java, AddFoodActivity.intent(app, null, 1)).setup().get()
        waitFor("empty recent") { empty.shows("Foods you log show up here") }
        // foods.db is missing and food data is allowed: the automatic download is queued (Robolectric has no Wi-Fi).
        waitFor("food database queued for Wi-Fi") { empty.shows("The food database will download by itself on Wi-Fi") }
        seed()
        val a = Robolectric.buildActivity(AddFoodActivity::class.java, AddFoodActivity.intent(app, null, 1)).setup().get()
        waitFor("recent row") { a.shows("Crisp apple") }
        assertTrue(a.shows("Add to Breakfast"))
    }

    @Test
    fun foodDetailAndQuickAddSheetsOpen() {
        val id = seed()
        val a = Robolectric.buildActivity(AddFoodActivity::class.java, AddFoodActivity.intent(app, null, 1)).setup().get()
        FoodDetailSheet.forFood(FoodRef(FoodOrigin.MY_FOOD, id), null, 1).show(a.supportFragmentManager, FoodDetailSheet.TAG)
        waitFor("detail") { (a.supportFragmentManager.findFragmentByTag(FoodDetailSheet.TAG)?.view?.let { texts(it) } ?: emptyList()).any { it.contains("1 medium") } }
        QuickAddSheet.newInstance(null, 0).show(a.supportFragmentManager, QuickAddSheet.TAG)
        waitFor("quick add") { a.supportFragmentManager.findFragmentByTag(QuickAddSheet.TAG)?.view != null }
    }

    @Test
    fun scanWithoutCameraOffersTypedEntryAndRoutesNotFound() {
        val a = Robolectric.buildActivity(ScanActivity::class.java, ScanActivity.intent(app, null, 0)).setup().get()
        val edit = a.window.decorView.findViewWithTag<TextView>("manual")
        assertNotNull(edit)
        edit.text = "4006381333931"
        a.window.decorView.findViewWithTag<View>("look_up").performClick()
        waitFor("not found") { a.window.decorView.findViewWithTag<View>("create_food") != null }
    }

    @Test
    fun foodEditorOpensNewAndExisting() {
        val fresh = Robolectric.buildActivity(FoodEditorActivity::class.java, FoodEditorActivity.newIntent(app, 4006381333931L)).setup().get()
        waitFor("barcode prefilled") { fresh.window.decorView.findViewWithTag<TextView>("barcode")?.text?.toString() == "4006381333931" }
        val id = seed()
        val edit = Robolectric.buildActivity(FoodEditorActivity::class.java, FoodEditorActivity.intent(app, FoodRef(FoodOrigin.MY_FOOD, id))).setup().get()
        waitFor("name filled") { edit.window.decorView.findViewWithTag<TextView>("name")?.text?.toString() == "Crisp apple" }
    }

    @Test
    fun recipeBookEmptyAndWithRecipe() {
        val empty = Robolectric.buildActivity(RecipeActivity::class.java, RecipeActivity.bookIntent(app)).setup().get()
        waitFor("empty book") { empty.shows("No recipes yet") }
        seedRecipe()
        val a = Robolectric.buildActivity(RecipeActivity::class.java, RecipeActivity.bookIntent(app)).setup().get()
        waitFor("recipe row", { texts(a.window.decorView).toString() + " rv=" + rvInfo(a.window.decorView) }) { a.shows("Weeknight chili") }
    }

    @Test
    fun recipeDetailAndEditorOpen() {
        val id = seedRecipe()
        val a = Robolectric.buildActivity(RecipeActivity::class.java, RecipeActivity.detailIntent(app, id)).setup().get()
        waitFor("detail") { a.shows("1 can beans") && a.shows("Log a serving") }
        assertTrue(a.shows("Partial"))
        val e = Robolectric.buildActivity(RecipeEditorActivity::class.java, RecipeEditorActivity.intent(app, id)).setup().get()
        waitFor("editor filled") { e.window.decorView.findViewWithTag<TextView>("name")?.text?.toString() == "Weeknight chili" }
        val blank = Robolectric.buildActivity(RecipeEditorActivity::class.java, RecipeEditorActivity.intent(app, 0)).setup().get()
        waitFor("blank editor") { blank.window.decorView.findViewWithTag<TextView>("servings")?.text?.toString() == "1" }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L
    }
}
