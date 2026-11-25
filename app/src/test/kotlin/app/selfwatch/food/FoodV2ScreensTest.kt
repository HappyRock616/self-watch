/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import app.selfwatch.FoodGraph
import app.selfwatch.SelfWatchApp
import app.selfwatch.db.CustomFoodOrigin
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.TestDb
import app.selfwatch.db.dao.CustomFood
import app.selfwatch.food.nutrition.Diet
import app.selfwatch.food.nutrition.DietPrefs
import app.selfwatch.me.GoalsFragment
import app.selfwatch.me.MeTestSupport
import app.selfwatch.model.food.Allergen
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.DietInfo
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodCategory
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Serving
import app.selfwatch.model.time.SystemClock
import app.selfwatch.shell.MainActivity
import app.selfwatch.shell.Nav
import app.selfwatch.shell.Tab
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Food database v2 screens: meal macro lines, the redesigned food detail (diet badges, serving row,
 * nutrition facts), Day nutrition and the diet setting, in the default dark theme and in light with
 * another accent, at 1.0, 1.5 and 2.0 font scale.
 */
@RunWith(RobolectricTestRunner::class)
class FoodV2ScreensTest {

    private lateinit var app: SelfWatchApp
    private val food: FoodGraph get() = app.graph.food

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as SelfWatchApp
        TestDb.wipeShared(app)
    }

    @After
    fun tearDown() = MeTestSupport.resetDb()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

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

    private fun MainActivity.shows(text: String): Boolean = texts(window.decorView).any { it.contains(text) }

    private val yogurt = Food(
        FoodRef(FoodOrigin.MY_FOOD, 0), "Greek yogurt", "Dairy Farm", 0, Basis.PER_100G,
        Nutrients(970, 90, 50, 30, 40, 40, 0, 35, 10, potassiumMg = 141, calciumMg = 110),
        listOf(Serving(0, "1 cup", 2270)), null, "", null,
        dietInfo = DietInfo(DietInfo.VEGETARIAN, 3, Allergen.MILK.bit, true),
    )

    private fun seed(): Long = runBlocking {
        val id = food.db.foods.insert(CustomFood(yogurt, CustomFoodOrigin.TYPED, null, false, 0, 0, null))
        val saved = requireNotNull(food.myFoods.get(id))
        food.diary.add(SystemClock.today(), 1, saved, saved.servings[0], 100)
        food.diary.quickAdd(SystemClock.today(), 2, "Office cake", Nutrients(3500, null, null, null, null, null, null, null, null))
        id
    }

    private fun setPrefs(p: DietPrefs) = runBlocking { GraphFoodBackend(food).setDietPrefs(p) }

    private fun openFoodTab(): MainActivity {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.select(Tab.FOOD)
        return a
    }

    private fun anyFragment(a: MainActivity): Fragment = a.supportFragmentManager.fragments.first()

    private fun allScreens() {
        val id = seed()
        setPrefs(DietPrefs(setOf(Diet.VEGAN, Diet.HEART_HEALTHY), setOf(Allergen.MILK)))
        val a = openFoodTab()
        // Meal header macro line: 227 g of yogurt → carbs 9.1 g, fat 11.4 g, protein 20.4 g.
        waitFor("macro line", { texts(a.window.decorView).toString() }) { a.shows("C 9 g · F 11 g · P 20 g") }

        // Food detail: name as title, red "Contains: milk", serving row, nutrition facts.
        FoodDetailSheet.forFood(FoodRef(FoodOrigin.MY_FOOD, id), null, 1).show(a.supportFragmentManager, FoodDetailSheet.TAG)
        fun sheetTexts(): List<String> = a.supportFragmentManager.findFragmentByTag(FoodDetailSheet.TAG)?.view?.let { texts(it) } ?: emptyList()
        fun sheetShows(t: String) = sheetTexts().any { it.contains(t) }
        waitFor("detail", { sheetTexts().toString() }) { sheetShows("Contains: milk") && sheetShows("1 cup (227 g)") && sheetShows("Not vegan") }
        assertTrue(sheetShows("Greek yogurt"))
        assertTrue(sheetShows("% of your day"))
        assertTrue(sheetShows("Add to Breakfast"))
        val sheet = a.supportFragmentManager.findFragmentByTag(FoodDetailSheet.TAG)!!
        sheet.requireView().findViewWithTag<View>("facts_toggle").performClick()
        idle()
        waitFor("facts", { sheetTexts().toString() }) { sheetShows("Potassium") && sheetShows("Vitamins") && sheetShows("—") }
        (sheet as FoodDetailSheet).dismissAllowingStateLoss()
        idle()

        // Day nutrition.
        Nav.open(anyFragment(a), DayNutritionFragment.newInstance(SystemClock.today()))
        waitFor("day nutrition", { texts(a.window.decorView).toString() }) { a.shows("Macro targets") && a.shows("All nutrients") }
        assertTrue(a.shows("Your targets"))
        assertTrue(a.shows("Potassium known for 1 of 2 foods"))
        // Week view: daily averages with a bar per day, same completeness line.
        clickText(a.window.decorView, "Week")
        waitFor("week view", { texts(a.window.decorView).toString() }) { a.shows("Daily average") && a.shows("Average over 1 logged day") }
        assertTrue(a.window.decorView.findViewWithTag<View>("week_bars_kcal")?.isShown == true)
        assertTrue(a.window.decorView.findViewWithTag<View>("week_bars_sodium") != null) // heart-healthy target
        assertTrue(a.shows("Potassium known for 1 of 2 foods"))
        assertTrue(a.shows(" – "))
        a.supportFragmentManager.popBackStackImmediate()

        // Goals → Diet and allergens.
        Nav.open(anyFragment(a), GoalsFragment())
        waitFor("goals diet row", { texts(a.window.decorView).toString() }) { a.shows("Diet and allergens") && a.shows("Heart-healthy") }
        a.supportFragmentManager.popBackStackImmediate()
        Nav.open(anyFragment(a), DietFragment())
        waitFor("diet screen", { texts(a.window.decorView).toString() }) { a.shows("Your daily targets") && a.shows("amber above 1,500 mg") }
        val vegan = a.window.decorView.findViewWithTag<CompoundButton>("diet_vegan")
        assertTrue(vegan.isChecked)
        a.window.decorView.findViewWithTag<CompoundButton>("diet_high_protein").performClick()
        idle()
        assertTrue(a.shows("Protein"))

        // A target typed by hand, then reset to recommended.
        a.window.decorView.findViewWithTag<View>("target_iron").performClick()
        idle()
        val edit = ShadowDialog.getLatestDialog() as AlertDialog
        edit.window!!.decorView.findViewWithTag<EditText>("target_value").setText("15")
        edit.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idle()
        waitFor("custom iron", { texts(a.window.decorView).toString() }) { a.shows("Your own number") && a.shows("at least 15 mg") }
        a.window.decorView.findViewWithTag<View>("target_iron").performClick()
        idle()
        val again = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(again.getButton(DialogInterface.BUTTON_NEUTRAL).text.contains("Reset to recommended"))
        again.getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
        idle()
        waitFor("iron reset") { !a.shows("Your own number") }
        a.supportFragmentManager.popBackStackImmediate()

        // Custom food editor: category picker and "+ Serving size".
        val ed = Robolectric.buildActivity(FoodEditorActivity::class.java, FoodEditorActivity.intent(app, FoodRef(FoodOrigin.MY_FOOD, id))).setup().get()
        waitFor("editor filled", { texts(ed.window.decorView).toString() }) { texts(ed.window.decorView).contains("Greek yogurt") }
        ed.window.decorView.findViewWithTag<View>("category").performClick()
        idle()
        val pick = ShadowDialog.getLatestDialog() as AlertDialog
        pick.listView.performItemClick(null, YOGURT_ROW, 0)
        idle()
        assertTrue(texts(ed.window.decorView).contains("Category: Yogurt"))
        ed.window.decorView.findViewWithTag<View>("add_serving").performClick()
        idle()
        val row = ed.window.decorView.findViewWithTag<ViewGroup>("extra_serving_0")
        row.findViewWithTag<EditText>("extra_label").setText("Small pot")
        row.findViewWithTag<EditText>("extra_grams").setText("125")
        ed.window.decorView.findViewWithTag<View>("save").performClick()
        waitFor("saved with category and serving") {
            runBlocking { food.myFoods.get(id) }?.let { f -> f.category?.id == 302 && f.servings.any { it.label == "Small pot" && it.gramsX10 == 1250 } } == true
        }
    }

    private fun clickText(root: View, text: String) {
        fun find(v: View): View? = when {
            v is TextView && v.text?.toString() == text && v.isShown -> v
            v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        requireNotNull(find(root)) { "no view shows $text" }.performClick()
        idle()
    }

    @Test
    fun screensInDarkTheme() = allScreens()

    @Test
    @Config(fontScale = 1.5f)
    fun screensInLightThemeWithAnotherAccentAt150Percent() {
        runBlocking {
            app.graph.db.settings.set(SettingKeys.THEME, "light")
            app.graph.db.settings.set(SettingKeys.ACCENT, "#2E9E5B")
        }
        allScreens()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp", fontScale = 2.0f)
    fun screensAtDoubleFontOnASmallPhone() = allScreens()

    @Test
    fun searchRowsShowOnlyConflicts() {
        val id = seed()
        setPrefs(DietPrefs(emptySet(), setOf(Allergen.MILK)))
        val a = Robolectric.buildActivity(AddFoodActivity::class.java, AddFoodActivity.intent(app, null, 1)).setup().get()
        waitFor("recent row") { texts(a.window.decorView).any { it.contains("Greek yogurt") } }
        // Recents carry no diet info, so no badge there; favorites come from the food itself.
        assertTrue(texts(a.window.decorView).none { it.contains("Contains milk") })
        runBlocking { food.db.favourites.add(FoodRef(FoodOrigin.MY_FOOD, id), 1) }
        a.findViewById<View>(android.R.id.content)
        val vm = AddFoodViewModel(null, 1) { GraphFoodBackend(food) }
        vm.selectTab(AddFoodTab.FAVORITES)
        waitFor("favorite with diet") { vm.state.value.tabRows.any { it.dietInfo?.allergens == Allergen.MILK.bit } }
        assertTrue(vm.state.value.prefs.conflicts(vm.state.value.tabRows.first().dietInfo).isNotEmpty())
        // The milk filter hides the yogurt from favorites.
        waitFor("filtered") { vm.state.value.rows.isEmpty() && vm.state.value.hiddenCount == 1 }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp", fontScale = 2.0f)
    fun addFoodShowsDietChipsAndUnknownMarks() {
        seed()
        setPrefs(DietPrefs(setOf(Diet.VEGAN), setOf(Allergen.MILK)))
        val a = Robolectric.buildActivity(AddFoodActivity::class.java, AddFoodActivity.intent(app, null, 1)).setup().get()
        waitFor("chips and row", { texts(a.window.decorView).toString() }) {
            a.window.decorView.findViewWithTag<View>("filter_vegan")?.isShown == true && texts(a.window.decorView).any { it.contains("Greek yogurt") }
        }
        assertTrue(texts(a.window.decorView).contains("No milk"))
        // Recents carry no diet data: shown, marked "?".
        waitFor("unknown mark") { a.window.decorView.findViewWithTag<View>("diet_unknown")?.isShown == true }
        // No diet set: no chips.
        setPrefs(DietPrefs.NONE)
        val plain = Robolectric.buildActivity(AddFoodActivity::class.java, AddFoodActivity.intent(app, null, 1)).setup().get()
        waitFor("plain row") { texts(plain.window.decorView).any { it.contains("Greek yogurt") } }
        assertTrue(plain.window.decorView.findViewWithTag<View>("diet_filters")?.isShown != true)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp", fontScale = 2.0f)
    fun vegetarianOfferAfterTwoMeatFreeWeeks() {
        runBlocking {
            val id = food.db.foods.insert(CustomFood(yogurt.copy(category = FoodCategory(302, "", "")), CustomFoodOrigin.TYPED, null, false, 0, 0, null))
            val saved = requireNotNull(food.myFoods.get(id))
            for (d in 0 until 14) food.diary.add(SystemClock.today().plusDays(-d), 1, saved, saved.servings[0], 100)
        }
        val a = openFoodTab()
        waitFor("offer", { texts(a.window.decorView).toString() }) { a.shows("Set vegetarian?") }
        a.window.decorView.findViewWithTag<View>("veg_hint_no").performClick()
        idle()
        waitFor("offer gone") { !a.shows("Set vegetarian?") }
        waitFor("remembered") { runBlocking { app.graph.db.settings.getBool(SettingKeys.FOOD_VEG_HINT_DONE) } }
        assertTrue(runBlocking { GraphFoodBackend(food).dietPrefs() }.diets.isEmpty())
        // Never again.
        val b = openFoodTab()
        waitFor("diary") { b.shows("Breakfast") }
        idle()
        assertTrue(!b.shows("Set vegetarian?"))
    }

    private companion object {
        /** "Yogurt" (302) in the category list: None, then 101–104, 201–203, 301, 302. */
        const val YOGURT_ROW = 9
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L
    }
}
