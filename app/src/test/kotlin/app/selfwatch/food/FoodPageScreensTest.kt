/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import android.os.Looper
import android.os.SystemClock as AndroidClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.selfwatch.FoodGraph
import app.selfwatch.R
import app.selfwatch.SelfWatchApp
import app.selfwatch.db.CustomFoodOrigin
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.TestDb
import app.selfwatch.db.dao.CustomFood
import app.selfwatch.me.MeTestSupport
import app.selfwatch.model.food.Allergen
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.DietInfo
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Serving
import app.selfwatch.model.time.SystemClock
import app.selfwatch.shell.AddSheet
import app.selfwatch.shell.MainActivity
import app.selfwatch.shell.Tab
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Food tab audit on the real app: meal header → Meal summary (its own add, its recent foods),
 * "Recent for <meal>" one-tap add, removing a food with (−) and by swipe; dark, light at 1.5× and a
 * small phone at 2×.
 */
@RunWith(RobolectricTestRunner::class)
class FoodPageScreensTest {

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
    private fun MainActivity.dump(): String = texts(window.decorView).toString()

    private val yogurt = Food(
        FoodRef(FoodOrigin.MY_FOOD, 0), "Greek yogurt", "Dairy Farm", 0, Basis.PER_100G,
        Nutrients(970, 90, 50, 30, 40, 40, 0, 35, 10, potassiumMg = 141, calciumMg = 110),
        listOf(Serving(0, "1 cup", 2270)), null, "", null,
        dietInfo = DietInfo(DietInfo.VEGETARIAN, 3, Allergen.MILK.bit, true),
    )

    private fun lunchIds(): List<Long> = runBlocking { food.diary.load(SystemClock.today()).meals.first { it.slot.id == 2L }.entries.map { it.id } }

    private fun count(slot: Long): Int = runBlocking { food.diary.load(SystemClock.today()).meals.first { it.slot.id == slot }.entries.size }

    private fun seed() = runBlocking {
        val id = food.db.foods.insert(CustomFood(yogurt, CustomFoodOrigin.TYPED, null, false, 0, 0, null))
        val saved = requireNotNull(food.myFoods.get(id))
        val today = SystemClock.today()
        food.diary.add(today.plusDays(-1), 1, saved, saved.servings[0], 100) // yesterday's breakfast: the recent offer
        food.diary.add(today.plusDays(-1), 3, saved, saved.servings[0], 100) // and dinner (offered in the Dinner summary)
        food.diary.add(today, 2, saved, saved.servings[0], 100)
        food.diary.quickAdd(today, 2, "Office cake", Nutrients(3500, null, null, null, null, null, null, null, null))
    }

    private fun allScreens() {
        seed()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.select(Tab.FOOD)
        waitFor("recent strip", { a.dump() }) { a.shows("Recent for Breakfast") }
        val root = a.window.decorView

        // The FAB doesn't cover the last card: the column ends with more room than the button takes.
        val column = a.findViewById<View>(R.id.food_main).parent as View
        val density = a.resources.displayMetrics.density
        assertTrue("bottom room", column.paddingBottom >= ((FAB_DP + FAB_MARGIN_DP) * density).toInt())

        // The + button on the Food tab still opens the + sheet.
        a.findViewById<View>(R.id.add_fab).performClick()
        idle()
        val sheet = a.supportFragmentManager.findFragmentByTag(AddSheet.TAG) as AddSheet
        assertTrue(sheet.isAdded)
        sheet.dismissNow()
        idle()

        // (−) uses the theme's secondary color.
        val (yogurtId, cakeId) = lunchIds()
        val minus = root.findViewWithTag<MaterialButton>("remove_$cakeId")
        assertEquals(MaterialColors.getColor(minus, com.google.android.material.R.attr.colorSecondary), minus.iconTint.defaultColor)

        // Dinner (empty today) → its summary: its own add for this meal, and its recent foods with one-tap add.
        root.findViewWithTag<View>("meal_header_3").performClick()
        idle()
        fun summary(): View? = a.supportFragmentManager.findFragmentById(R.id.detail_container)?.view
        waitFor("dinner summary", { a.dump() }) { summary()?.findViewWithTag<View>("meal_add") != null && a.shows("Recent for Dinner") }
        assertTrue(a.shows("Add food to Dinner"))
        summary()!!.findViewWithTag<View>("meal_add").performClick()
        idle()
        val started = shadowOf(a).nextStartedActivity
        assertEquals(AddFoodActivity::class.java.name, started?.component?.className)
        assertEquals(3L, started?.getLongExtra("slot", 0L))
        assertEquals(SystemClock.today().yyyymmdd, started?.getIntExtra("day", 0))
        fun summaryPlus() = findPlus(summary()?.findViewWithTag<ViewGroup>("recent_3"))
        waitFor("summary recent row", { a.dump() }) { summaryPlus() != null }
        summaryPlus()!!.performClick()
        waitFor("dinner logged from the summary", { a.dump() }) { count(3) == 1 }
        waitFor("summary shows the food", { a.dump() }) { a.shows("What each food brings") && summary()?.findViewWithTag<View>("recent_3") == null }
        a.supportFragmentManager.popBackStackImmediate()
        idle()

        // Lunch header → Meal summary.
        root.findViewWithTag<View>("meal_header_2").performClick()
        idle()
        waitFor("meal summary", { a.dump() }) { a.shows("Calories and macros") && a.shows("What each food brings") }
        assertTrue(a.shows("Potassium known for 1 of 2 foods"))
        assertTrue(a.shows("Full nutrient data for 0 of 2 foods"))
        assertTrue(a.shows("—")) // vitamins nobody lists
        assertTrue(a.shows("Added sugar"))
        assertTrue(texts(a.window.decorView).any { Regex("· [0-9]+%").containsMatchIn(it) }) // macro share of calories (meal_summary_macro_value)
        assertTrue(a.window.decorView.findViewWithTag<View>("meal_food_$cakeId")?.isShown == true)
        a.supportFragmentManager.popBackStackImmediate()
        idle()

        // Recent for Breakfast: one tap logs yesterday's serving.
        fun plus() = findPlus(root.findViewWithTag<ViewGroup>("recent_1"))
        waitFor("recent row laid out", { a.dump() }) { plus() != null }
        plus()!!.performClick()
        waitFor("recent logged", { a.dump() }) { count(1) == 1 }
        waitFor("logged snackbar", { a.dump() }) { a.shows("Greek yogurt added to Breakfast") }

        // (−) removes (the existing delete, with its Undo snackbar).
        a.window.decorView.findViewWithTag<View>("remove_$cakeId").performClick()
        waitFor("cake removed") { cakeId !in lunchIds() }

        // Swipe removes too.
        waitFor("row shown") { a.window.decorView.findViewWithTag<View>("entry_$yogurtId")?.width ?: 0 > 0 }
        swipe(a.window.decorView.findViewWithTag("entry_$yogurtId"))
        waitFor("yogurt swiped away") { yogurtId !in lunchIds() }
    }

    private fun findPlus(v: View?): View? = when {
        v == null -> null
        v is MaterialButton && v.contentDescription?.contains("Greek yogurt") == true && v.isShown -> v
        v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findPlus(v.getChildAt(it)) }
        else -> null
    }

    private fun swipe(row: View) {
        val t = AndroidClock.uptimeMillis()
        val y = row.height / 2f
        val end = row.width * 0.8f
        fun ev(action: Int, x: Float, dt: Long) = MotionEvent.obtain(t, t + dt, action, x, y, 0)
        row.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 4f, 0))
        row.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, end / 2, 20))
        row.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, end, 40))
        row.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, end, 60))
        idle()
    }

    @Test
    fun foodPageInDarkTheme() = allScreens()

    @Test
    @Config(fontScale = 1.5f)
    fun foodPageInLightThemeWithAnotherAccentAt150Percent() {
        runBlocking {
            app.graph.db.settings.set(SettingKeys.THEME, "light")
            app.graph.db.settings.set(SettingKeys.ACCENT, "#2E9E5B")
        }
        allScreens()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp", fontScale = 2.0f)
    fun foodPageAtDoubleFontOnASmallPhone() = allScreens()

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L
        const val FAB_DP = 56
        const val FAB_MARGIN_DP = 16
    }
}
