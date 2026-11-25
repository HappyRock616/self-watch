/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.shell

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import androidx.fragment.app.Fragment
import app.selfwatch.R
import app.selfwatch.activity.WorkoutDetailFragment
import app.selfwatch.activity.WorkoutListFragment
import app.selfwatch.body.BodySignalsDetailFragment
import app.selfwatch.body.HeartDetailFragment
import app.selfwatch.body.MeasureDetailFragment
import app.selfwatch.body.MeasurementsFragment
import app.selfwatch.body.SleepDetailFragment
import app.selfwatch.body.TestDb
import app.selfwatch.body.WeightDetailFragment
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardRegistry
import app.selfwatch.cards.CardStore
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.EditCardsFragment
import app.selfwatch.cards.UnitPatterns
import app.selfwatch.db.SettingKeys
import app.selfwatch.demo.DemoData
import app.selfwatch.food.DayNutritionFragment
import app.selfwatch.food.SleepCaloriesFragment
import app.selfwatch.me.GoalsFragment
import app.selfwatch.me.ProfileFragment
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.time.SystemClock
import app.selfwatch.ui.format.Units
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The Units setting (Me › Settings) is the one source of units: with the demo data and every card on
 * every tab, each tab and every screen reachable from the cards and the Me tab shows only the chosen
 * system, for Metric and for Imperial.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenUnitsTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()
    private val failures = LinkedHashSet<String>()

    private fun idle(times: Int = 3) {
        repeat(times) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(60)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The Units setting's own choices name both systems on purpose. */
    private val choices: Set<String> by lazy {
        listOf(R.string.profile_units_metric, R.string.profile_units_imperial, R.string.settings_units_metric, R.string.settings_units_imperial)
            .map { ctx.getString(it) }.toSet()
    }

    private fun check(where: String, v: View, units: Units): List<String> {
        val texts = UnitPatterns.texts(v)
        for (t in texts) {
            if (choices.any { t.contains(it) }) continue
            val hit = UnitPatterns.hit(t, units) ?: continue
            failures += "$where under $units shows '$t' (matches ${hit.pattern})"
        }
        return texts
    }

    /** The screens each card opens from its small form. */
    private fun cardScreens(): List<Fragment> {
        val out = LinkedHashMap<Class<*>, Fragment>()
        val host = object : CardHost {
            override val fragment: Fragment get() = throw UnsupportedOperationException()
            override val context: Context get() = ctx
            override fun open(screen: Fragment) {
                out.putIfAbsent(screen.javaClass, screen)
            }
            override fun openTab(tab: Tab) = Unit
            override fun launch(block: suspend () -> Unit) = Unit
        }
        for (c in CardRegistry.all) runCatching { c.openDetail(host) }
        return out.values.toList()
    }

    private fun screens(workoutId: Long): List<Fragment> = listOf(
        SleepDetailFragment(),
        HeartDetailFragment(),
        WeightDetailFragment(),
        MeasurementsFragment(),
        MeasureDetailFragment.newInstance(BodyKind.WAIST, null),
        MeasureDetailFragment.newInstance(BodyKind.BODY_FAT, null),
        GoalsFragment(),
        ProfileFragment(),
        BodySignalsDetailFragment(),
        WorkoutListFragment(),
        WorkoutDetailFragment.newInstance(workoutId),
        DayNutritionFragment.newInstance(SystemClock.today().plusDays(-1)),
        SleepCaloriesFragment(),
        EditCardsFragment.newInstance(CardTab.BODY),
        EditCardsFragment.newInstance(CardTab.ACTIVITY),
        EditCardsFragment.newInstance(CardTab.FOOD),
    ) + cardScreens()

    private fun MainActivity.open(f: Fragment): View? {
        val host = supportFragmentManager.findFragmentByTag(Tab.BODY.tag) ?: error("no body tab")
        Nav.open(host, f)
        supportFragmentManager.executePendingTransactions()
        idle(8)
        return supportFragmentManager.findFragmentById(R.id.detail_container)?.view
    }

    private fun everyScreen(units: Units) {
        val db = TestDb.fresh(ctx)
        val workoutId = runBlocking {
            DemoData(ctx, db).load()
            db.settings.set(SettingKeys.UNITS, if (units == Units.METRIC) "metric" else "imperial")
            val store = CardStore(db.settings)
            for (tab in CardTab.entries) store.save(tab, CardRegistry.of(tab))
            db.workouts.list(1).first().id
        }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        val tabText = HashMap<Tab, String>()
        for (tab in listOf(Tab.BODY, Tab.ACTIVITY, Tab.FOOD)) {
            activity.select(tab)
            idle(15)
            tabText[tab] = check("tab $tab", activity.window.decorView, units).joinToString("\n")
        }
        // The water card counts glasses in the chosen units (no ml under US units, no fl oz under metric).
        val water = activity.window.decorView.findViewById<View>(R.id.food_water_card)
        val waterText = water?.let { UnitPatterns.texts(it).joinToString(" ") }.orEmpty()
        val expected = if (units == Units.IMPERIAL) "fl oz" else " L)"
        assertTrue("water card under $units says '$waterText'", waterText.contains("glasses") && waterText.contains(expected))
        val all = screens(workoutId)
        var opened = 0
        for (f in all) {
            val v = runCatching { activity.open(f) }.getOrNull() ?: continue
            opened++
            check(f.javaClass.simpleName, v, units)
            activity.onBackPressedDispatcher.onBackPressed()
            activity.supportFragmentManager.executePendingTransactions()
            idle()
        }
        assertTrue("screens opened under $units: $opened of ${all.size}", opened >= all.size - 2)
    }

    @Test
    fun everyScreenFollowsTheUnitsSetting() {
        for (units in Units.entries) everyScreen(units)
        assertTrue(failures.joinToString("\n", "\n"), failures.isEmpty())
    }
}
