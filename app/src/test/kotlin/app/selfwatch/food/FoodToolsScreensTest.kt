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
import android.app.DatePickerDialog
import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import app.selfwatch.FoodGraph
import app.selfwatch.R
import app.selfwatch.SelfWatchApp
import app.selfwatch.data.ImportActivity
import app.selfwatch.db.CustomFoodOrigin
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.TestDb
import app.selfwatch.db.dao.CustomFood
import app.selfwatch.me.SettingsActivity
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
import app.selfwatch.shell.AddSheet
import app.selfwatch.shell.DisplayUnits
import app.selfwatch.shell.MainActivity
import app.selfwatch.shell.Tab
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.ListRow
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * Smoke test for every item that used to say "coming soon" on the Food tab, in the recipe book and in
 * the + sheet: each one now opens its screen or does its job, and no "coming soon" toast appears.
 */
@RunWith(RobolectricTestRunner::class)
class FoodToolsScreensTest {

    private lateinit var app: SelfWatchApp
    private val food: FoodGraph get() = app.graph.food
    private val today = SystemClock.today()

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as SelfWatchApp
        TestDb.wipeShared(app)
    }

    @After
    fun tearDown() = MeTestSupport.resetDb()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(POLL_MS))
            if (cond()) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun texts(v: View): List<String> = when {
        v.visibility != View.VISIBLE -> emptyList()
        v is TextView -> listOf(v.text?.toString().orEmpty())
        v is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun Activity.shows(text: String): Boolean = texts(window.decorView).any { it.contains(text) }

    private fun assertNoComingSoon() {
        val toast = ShadowToast.getTextOfLatestToast()
        assertFalse("coming soon toast: $toast", toast?.contains("coming soon", ignoreCase = true) == true)
        val soon = app.getString(R.string.coming_soon)
        assertFalse(toast == soon)
    }

    private val apple = Food(FoodRef(FoodOrigin.MY_FOOD, 0), "Crisp apple", null, 0, Basis.PER_ITEM, Nutrients(950, 5, 3, null, 250, null, null, null, null), listOf(Serving(0, "1 medium", 1820)), null, "", null)

    private fun seedDiary() = runBlocking {
        val id = food.db.foods.insert(CustomFood(apple, CustomFoodOrigin.TYPED, null, false, 0, 0, null))
        val saved = requireNotNull(food.myFoods.get(id))
        food.diary.add(today, 1, saved, saved.servings[0], 100)
        food.diary.quickAdd(today, 3, "Office cake", Nutrients(3500, null, null, null, null, null, null, null, null))
    }

    private fun openFoodTab(): Pair<MainActivity, FoodFragment> {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.select(Tab.FOOD)
        idle()
        val f = a.supportFragmentManager.findFragmentByTag(Tab.FOOD.tag) as FoodFragment
        return a to f
    }

    private fun menu(f: Fragment, id: Int) {
        val bar = f.requireView().findViewById<MaterialToolbar>(R.id.toolbar)
        assertTrue(bar.menu.performIdentifierAction(id, 0))
        idle()
    }

    private fun latestDialog(): Dialog = requireNotNull(ShadowDialog.getLatestDialog()) { "no dialog" }

    @Test
    fun copyDayCopiesTheWholeDayToThePickedDayWithUndo() {
        seedDiary()
        val (a, f) = openFoodTab()
        waitFor("entries") { a.shows("Crisp apple") }
        menu(f, R.id.menu_copy_day)
        assertNoComingSoon()
        val choose = latestDialog() as AlertDialog
        assertTrue(choose.isShowing)
        choose.listView.performItemClick(choose.listView, 0, 0) // whole day
        idle()
        val picker = latestDialog() as DatePickerDialog
        val y = today.plusDays(-1).toLocalDate()
        picker.updateDate(y.year, y.monthValue - 1, y.dayOfMonth)
        picker.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        waitFor("copied") { runBlocking { food.db.diary.day(today.plusDays(-1)).size } == 2 }
        waitFor("undo snackbar") { a.shows("Copied 2 items") }
    }

    @Test
    fun mealNamesOpensTheEditorAndRenames() {
        val (a, f) = openFoodTab()
        waitFor("meals") { a.shows("Breakfast") }
        menu(f, R.id.menu_meal_names)
        assertNoComingSoon()
        val sheet = f.childFragmentManager.findFragmentByTag(MealNamesSheet.TAG) as MealNamesSheet
        waitFor("meal rows") { sheet.view?.findViewWithTag<View>("meal_1") != null }
        runBlocking { food.db.diary.updateMealSlot(food.db.diary.mealSlots().first { it.id == 1L }.copy(name = "First bite")) }
        waitFor("renamed in diary") { a.shows("First bite") }
    }

    @Test
    fun exportDayAsksWhereToSaveAndWritesTheCsv() {
        seedDiary()
        val (a, f) = openFoodTab()
        waitFor("entries") { a.shows("Crisp apple") }
        menu(f, R.id.menu_export_day)
        assertNoComingSoon()
        val started = requireNotNull(shadowOf(a).nextStartedActivityForResult) { "no file picker" }
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, started.intent.action)
        assertEquals("selfwatch-food-${today.toLocalDate()}.csv", started.intent.getStringExtra(Intent.EXTRA_TITLE))
        val out = File(app.cacheDir, "day.csv").apply { delete() }
        shadowOf(a).receiveResult(started.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(out)))
        waitFor("csv written") { out.isFile && out.readText().contains("Office cake") }
        assertTrue(out.readText().contains("Crisp apple"))
    }

    @Test
    fun foodOffButtonTurnsFoodTrackingOnOrOpensSettings() {
        runBlocking { app.graph.settings.setBool(SettingKeys.FOOD_ENABLED, false) }
        val (a, f) = openFoodTab()
        val off = f.requireView().findViewById<EmptyState>(R.id.food_off)
        waitFor("off message") { off.isShown }
        off.onButton?.invoke()
        idle()
        assertNoComingSoon()
        val ask = latestDialog() as AlertDialog
        ask.getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
        idle()
        assertEquals(SettingsActivity::class.java.name, shadowOf(a).nextStartedActivity?.component?.className)

        off.onButton?.invoke()
        idle()
        (latestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        waitFor("food tracking on") { a.shows("Breakfast") }
        assertEquals("true", runBlocking { app.graph.settings.get(SettingKeys.FOOD_ENABLED) })
    }

    private fun seedRecipe(): Long = runBlocking {
        food.recipes.save(
            Recipe(
                0, RecipeKind.RECIPE, "Weeknight chili", 40, null, null, null, "Simmer.", null, null, null, null, setOf("dinner"),
                listOf(RecipeItem(0, "1 can beans", null, null, null, null, MatchState.UNMATCHED)), null,
            ),
        )
    }

    private fun bookToolbar(a: Activity): MaterialToolbar {
        fun find(v: View): MaterialToolbar? = when (v) {
            is MaterialToolbar -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return requireNotNull(find(a.window.decorView))
    }

    @Test
    fun recipeBookImportExportAndTags() {
        seedRecipe()
        val a = Robolectric.buildActivity(RecipeActivity::class.java, RecipeActivity.bookIntent(app)).setup().get()
        waitFor("recipe row") { a.shows("Weeknight chili") }
        val bar = bookToolbar(a)

        assertTrue(bar.menu.performIdentifierAction(R.id.menu_import_recipes, 0))
        idle()
        assertNoComingSoon()
        val import = requireNotNull(shadowOf(a).nextStartedActivity)
        assertEquals(ImportActivity::class.java.name, import.component?.className)
        assertTrue(import.getBooleanExtra("recipes_only", false))

        assertTrue(bar.menu.performIdentifierAction(R.id.menu_export_recipes, 0))
        idle()
        assertNoComingSoon()
        val export = requireNotNull(shadowOf(a).nextStartedActivityForResult) { "no file picker" }
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, export.intent.action)
        val zip = File(app.cacheDir, "recipes.zip").apply { delete() }
        shadowOf(a).receiveResult(export.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(zip)))
        waitFor("zip written") { zip.isFile && zip.length() > 0 && a.shows("Saved 1 recipe") }

        assertTrue(bar.menu.performIdentifierAction(R.id.menu_tags, 0))
        idle()
        assertNoComingSoon()
        val tags = latestDialog() as AlertDialog
        tags.listView.performItemClick(tags.listView, 1, 1) // "dinner"
        idle()
        val group = a.window.decorView.findViewWithTag<com.google.android.material.chip.ChipGroup>("tags")
        waitFor("dinner chip checked") {
            (0 until group.childCount).map { group.getChildAt(it) as com.google.android.material.chip.Chip }.any { it.tag == "dinner" && it.isChecked }
        }
    }

    @Test
    fun recipeImportReadsACookFileAndSavesIt() {
        val file = File(app.cacheDir, "Rice bowl.cook").apply { writeText(">> servings: 2\nCook @rice{1%cup} in @water{2%cups}.\n") }
        val a = Robolectric.buildActivity(ImportActivity::class.java, ImportActivity.intent(app, pick = true, recipesOnly = true)).setup().get()
        assertEquals(app.getString(R.string.menu_import_recipes), bookToolbar(a).title?.toString())
        val pick = requireNotNull(shadowOf(a).nextStartedActivityForResult) { "no file picker" }
        shadowOf(a).receiveResult(pick.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
        waitFor("preview") { findButton(a.window.decorView, app.getString(R.string.import_confirm)) != null }
        val button = findButton(a.window.decorView, app.getString(R.string.import_confirm))
        assertNotNull(button)
        button?.performClick()
        waitFor("recipe saved") { runBlocking { food.db.recipes.list().any { it.name.contains("Rice bowl") } } }
    }

    private fun findButton(v: View, label: String): View? = when {
        v is TextView && v.text?.toString() == label && v.isClickable -> v
        v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findButton(v.getChildAt(it), label) }
        else -> null
    }

    @Test
    fun addSheetWaterAndFastLogInOneTap() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        fun tap(item: AddSheet.Item) {
            a.findViewById<View>(R.id.add_fab).performClick()
            idle()
            val sheet = a.supportFragmentManager.findFragmentByTag(AddSheet.TAG) as AddSheet
            sheet.requireView().findViewWithTag<ListRow>(item.name).performClick()
            idle()
        }
        tap(AddSheet.Item.WATER)
        waitFor("glass logged") { runBlocking { food.water.load(today).totalMl } == WaterGlass.ml(DisplayUnits.of(runBlocking { app.graph.db.settings.profile().units })) }
        assertNoComingSoon()
        waitFor("water snackbar") { a.shows("Glass logged") }

        tap(AddSheet.Item.FAST)
        waitFor("fast started") { runBlocking { food.fast.current() } != null }
        assertNoComingSoon()
        tap(AddSheet.Item.FAST)
        waitFor("fast ended") { runBlocking { food.fast.current() } == null }
        assertNoComingSoon()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L
    }
}
