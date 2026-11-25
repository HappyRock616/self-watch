/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.cards

import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import app.selfwatch.body.TestDb
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.demo.DemoData
import app.selfwatch.food.WaterGlass
import app.selfwatch.food.cards.WaterCard
import app.selfwatch.food.diary.WaterDay
import app.selfwatch.gallery.CardSamples
import app.selfwatch.me.GoalField
import app.selfwatch.model.time.SystemClock
import app.selfwatch.shell.Tab
import app.selfwatch.ui.format.Units
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import app.selfwatch.ui.R as UiR

/**
 * Every card, small and large, with demo data and with the gallery's samples, in both unit systems:
 * no metric unit shows under US units, and no US unit under metric (energy stays kcal and nutrients
 * g/mg/µg in both).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CardUnitsTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    private val screen: Fragment by lazy {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val f = ScreenFragment()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, f).commitNow()
        f
    }

    private inner class Host(override val context: Context) : CardHost {
        override val fragment: Fragment get() = screen
        override fun open(screen: Fragment) = Unit
        override fun openTab(tab: Tab) = Unit
        override fun launch(block: suspend () -> Unit) = Unit
    }

    private val ctx: Context by lazy { ContextThemeWrapper(app, UiR.style.Theme_SelfWatch) }

    /** Every text shown in [card]'s small form, its small and large views for [data]. */
    @Suppress("UNCHECKED_CAST")
    private fun shownText(card: Card<*>, data: Any?): List<String> {
        val c = card as Card<Any?>
        val out = ArrayList<String>()
        if (card !is WaitingCard) {
            c.small(ctx, data)?.let { f -> out += listOfNotNull(f.value, f.label, f.unit, f.note).map { it.toString() } }
        }
        for (size in CardSize.entries) {
            val parent = FrameLayout(ctx)
            val view: CardView<Any?> = if (size == CardSize.SMALL) SmallCardView(c, Host(ctx), parent) else c.createView(Host(ctx), parent)
            view.bind(data)
            collect(view.root, out)
        }
        return out
    }

    private fun collect(v: View, out: MutableList<String>) {
        UnitPatterns.texts(v, out)
    }

    private fun check(where: String, card: Card<*>, texts: List<String>, units: Units) {
        for (t in texts) {
            val hit = UnitPatterns.hit(t, units) ?: continue
            failures += "$where $card under $units shows '$t' (matches ${hit.pattern})"
        }
    }

    private val failures = LinkedHashSet<String>()

    private fun assertNoFailures() = assertTrue(failures.joinToString("\n", "\n"), failures.isEmpty())

    private fun demoDb(units: Units): SelfWatchDb = TestDb.fresh(app).also {
        runBlocking {
            DemoData(app, it).load()
            it.settings.set(SettingKeys.UNITS, if (units == Units.METRIC) "metric" else "imperial")
        }
    }

    @Test
    fun everyCardWithDemoDataUsesOneUnitSystem() {
        for (units in Units.entries) {
            val db = demoDb(units)
            val today = SystemClock.today()
            for (card in CardRegistry.all) {
                // Food cards show a chosen diary day: yesterday is a complete demo day.
                for (day in listOf(today, today.plusDays(-1))) {
                    val data = runBlocking { card.load(CardEnv(db, SystemClock, day, units)) }
                    check("demo $day", card, shownText(card, data), units)
                }
            }
        }
        assertNoFailures()
    }

    @Test
    fun everyCardWithAnEmptyDatabaseUsesOneUnitSystem() {
        for (units in Units.entries) {
            val db = TestDb.fresh(app)
            runBlocking { db.settings.set(SettingKeys.UNITS, if (units == Units.METRIC) "metric" else "imperial") }
            for (card in CardRegistry.all) {
                val data = runBlocking { card.load(CardEnv(db, SystemClock, SystemClock.today(), units)) }
                check("empty", card, shownText(card, data), units)
            }
        }
        assertNoFailures()
    }

    @Test
    fun gallerySamplesUseOneUnitSystem() {
        for (units in Units.entries) {
            for ((card, data) in CardSamples.all(units)) check("gallery", card, shownText(card, data), units)
        }
        assertNoFailures()
    }

    @Test
    fun waterCountsGlassesInTheUserUnits() {
        val day = SystemClock.today()
        // Three US glasses (12 fl oz each) against the default goal of 8 glasses (96 fl oz).
        val us = WaterCard.Data(WaterDay(day, emptyList(), 3 * WaterGlass.US_ML, null), Units.IMPERIAL)
        assertEquals("3 of 8 glasses (36 of 96 fl oz)", WaterGlass.ofGoal(ctx, us.water.totalMl, us.goalMl, us.units))
        assertEquals("3", WaterCard.small(ctx, us).value)
        assertEquals(8, WaterCard.squares(us))
        // Three metric glasses (350 ml) against 8 glasses (2.8 L).
        val metric = WaterCard.Data(WaterDay(day, emptyList(), 3 * WaterGlass.METRIC_ML, null), Units.METRIC)
        assertEquals("3 of 8 glasses (1.1 of 2.8 L)", WaterGlass.ofGoal(ctx, metric.water.totalMl, metric.goalMl, metric.units))
        // A goal typed in fl oz is stored in ml and shown back as typed.
        val goal = GoalField.WATER.parse("64", Units.IMPERIAL)!!
        assertEquals(1_893, goal)
        assertEquals("64", GoalField.WATER.toText(goal, Units.IMPERIAL))
        assertEquals("5 of 5 glasses (64 of 64 fl oz)", WaterGlass.ofGoal(ctx, goal, goal, Units.IMPERIAL))
        // Water logged as metric glasses still counts after switching to US units (to the nearest glass).
        assertEquals(3, WaterGlass.glasses(3 * WaterGlass.METRIC_ML, Units.IMPERIAL))
    }
}
