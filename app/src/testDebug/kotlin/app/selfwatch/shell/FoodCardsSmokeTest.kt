/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import app.selfwatch.R
import app.selfwatch.body.TestDb
import app.selfwatch.cards.CardStore
import app.selfwatch.cards.CardTab
import app.selfwatch.demo.DemoData
import app.selfwatch.food.AddFoodActivity
import app.selfwatch.food.FoodFragment
import app.selfwatch.food.SleepCaloriesFragment
import app.selfwatch.food.SleepCaloriesLayers
import app.selfwatch.food.cards.FoodCards
import app.selfwatch.ui.charts.OverlayChartView
import app.selfwatch.ui.components.ToggleChipGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Every Food card shows the demo data (for yesterday, a complete diary day) with the meals still
 * usable below the strip, and the sleep and calories chart opens from the logging days card with its
 * chips remembered in settings.
 */
@RunWith(RobolectricTestRunner::class)
class FoodCardsSmokeTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()

    private val numberCards = listOf(
        R.id.food_protein_per_kg_card,
        R.id.food_protein_per_meal_card,
        R.id.food_carbs_per_kg_card,
        R.id.food_workout_fuel_card,
        R.id.food_fuel_balance_card,
        R.id.food_sodium_card,
        R.id.food_fibre_card,
        R.id.food_total_sugar_card,
    )

    private fun idle() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            idle()
        }
    }

    @Test
    fun foodCardsShowDemoDataAndMealsStayUsable() {
        val db = TestDb.fresh(ctx)
        runBlocking {
            DemoData(ctx, db).load()
            CardStore(db.settings).save(CardTab.FOOD, FoodCards.ALL)
        }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        activity.select(Tab.FOOD)
        idle()
        val food = activity.supportFragmentManager.findFragmentByTag(Tab.FOOD.tag) as FoodFragment
        val view = food.requireView()
        // Yesterday is a complete demo day (today may not have meals yet, depending on the hour).
        assertTrue(view.findViewById<View>(R.id.day_prev).performClick())
        idle()
        waitFor("every Food card with data") {
            food.cards?.shownIds == FoodCards.ALL.map { it.id } &&
                numberCards.all { id ->
                    val card = view.findViewById<View>(id)
                    card?.isShown == true && card.findViewById<View>(R.id.metric_empty).visibility == View.GONE
                } &&
                view.findViewById<View>(R.id.food_logging_days_card)?.findViewById<ChipGroup>(R.id.metric_dots)?.childCount == 7
        }
        // The logging days card shows the week as seven dots, the protein card one dot per meal.
        val week = view.findViewById<View>(R.id.food_logging_days_card).findViewById<ChipGroup>(R.id.metric_dots)
        assertEquals(7, week.childCount)
        val meals = view.findViewById<View>(R.id.food_protein_per_meal_card).findViewById<ChipGroup>(R.id.metric_dots)
        assertEquals(4, meals.childCount)
        // Partial sodium data is said, not hidden.
        val sodium = view.findViewById<View>(R.id.food_sodium_card)
        assertTrue(sodium.contentDescription.isNotBlank())

        // The meals still sit below the cards and open Add food.
        val diary = view.findViewById<ViewGroup>(R.id.food_diary)
        waitFor("meals") { diary.childCount >= 4 }
        val add = diary.findViewsWithText(ctx.getString(R.string.food_add_to_meal, "Breakfast"))
        assertTrue(add.performClick())
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(AddFoodActivity::class.java.name, started.component?.className)

        // The sleep and calories chart opens from the logging days card, both layers on.
        assertTrue(view.findViewById<View>(R.id.sleep_calories_link).performClick())
        activity.supportFragmentManager.executePendingTransactions()
        idle()
        val shown = activity.supportFragmentManager.findFragmentById(R.id.detail_container)
        assertTrue(shown is SleepCaloriesFragment)
        val screen = shown!!.requireView()
        val chart = screen.findViewById<OverlayChartView>(R.id.sleep_calories_chart)
        waitFor("sleep and calories layers") { chart.layers.map { it.id } == SleepCaloriesLayers.ALL }
        assertTrue(SleepCaloriesLayers.ALL.all { chart.isLayerVisible(it) })
        // Switching calories off is remembered.
        val chips = findChips(screen)
        val kcalChip = chips.single { it.text == ctx.getString(R.string.sleep_calories_layer_kcal) }
        kcalChip.performClick()
        waitFor("choice saved") { runBlocking { db.settings.get(SleepCaloriesLayers.KEY) } == SleepCaloriesLayers.KCAL }
        assertTrue(!chart.isLayerVisible(SleepCaloriesLayers.KCAL))
        activity.onBackPressedDispatcher.onBackPressed()
        activity.supportFragmentManager.executePendingTransactions()
        idle()
        runBlocking { CardStore(db.settings).reset(CardTab.FOOD) }
    }

    @Test
    @Config(qualifiers = "land", fontScale = 2.0f)
    fun foodCardsInLandscapeAtDoubleFont() {
        foodCardsShowDemoDataAndMealsStayUsable()
    }

    private fun View.findViewsWithText(text: String): View {
        val out = ArrayList<View>()
        findViewsWithText(out, text, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        return out.first()
    }

    private fun findChips(v: View): List<Chip> {
        if (v is ToggleChipGroup) return (0 until v.childCount).mapNotNull { v.getChildAt(it) as? Chip }
        if (v is ViewGroup) return (0 until v.childCount).flatMap { findChips(v.getChildAt(it)) }
        return emptyList()
    }
}
