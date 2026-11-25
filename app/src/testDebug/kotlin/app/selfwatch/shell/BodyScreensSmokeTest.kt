/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.shell

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.fragment.app.Fragment
import app.selfwatch.R
import app.selfwatch.activity.CreateDocumentFor
import app.selfwatch.activity.ExportTarget
import app.selfwatch.activity.WorkoutDetailFragment
import app.selfwatch.activity.WorkoutExport
import app.selfwatch.activity.WorkoutListFragment
import app.selfwatch.activity.ActivityFragment
import app.selfwatch.body.BodyFragment
import app.selfwatch.body.BodySignalsDetailFragment
import app.selfwatch.cards.CardRegistry
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardSlot
import app.selfwatch.cards.CardStack
import app.selfwatch.cards.CardStore
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.EditCardsFragment
import app.selfwatch.body.EnergySheet
import app.selfwatch.body.HeartDetailFragment
import app.selfwatch.food.FoodFragment
import app.selfwatch.body.MeasureDetailFragment
import app.selfwatch.body.MeasurementsFragment
import app.selfwatch.body.SleepDetailFragment
import app.selfwatch.body.TestDb
import app.selfwatch.body.WeightDetailFragment
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.demo.DemoData
import app.selfwatch.me.GoalsFragment
import app.selfwatch.me.ProfileFragment
import app.selfwatch.model.health.BodyKind
import app.selfwatch.ui.charts.OverlayChartView
import app.selfwatch.ui.components.EmptyState
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File

/**
 * Every Body and Activity screen opens with no data and with the demo data, without crashing; every
 * tab shows every registered card with the demo data; Edit cards works on every tab.
 */
@RunWith(RobolectricTestRunner::class)
class BodyScreensSmokeTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()

    private fun launch(): ActivityController<MainActivity> = Robolectric.buildActivity(MainActivity::class.java).setup()

    private fun idle() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            idle()
        }
    }

    private fun MainActivity.open(f: Fragment): Fragment {
        val host = supportFragmentManager.findFragmentByTag(Tab.BODY.tag) ?: error("no body tab")
        Nav.open(host, f)
        supportFragmentManager.executePendingTransactions()
        idle()
        val shown = supportFragmentManager.findFragmentById(R.id.detail_container) ?: error("nothing shown")
        assertTrue("${f.javaClass.simpleName} visible", shown.isVisible)
        return shown
    }

    private fun screens(workoutId: Long): List<Fragment> = listOf(
        SleepDetailFragment(),
        HeartDetailFragment(),
        WeightDetailFragment(),
        MeasurementsFragment(),
        MeasureDetailFragment.newInstance(BodyKind.WAIST, null),
        GoalsFragment(),
        EditCardsFragment.newInstance(CardTab.BODY),
        EditCardsFragment.newInstance(CardTab.ACTIVITY),
        EditCardsFragment.newInstance(CardTab.FOOD),
        ProfileFragment(),
        BodySignalsDetailFragment(),
        MeasureDetailFragment.newInstance(BodyKind.BODY_FAT, null),
        WorkoutListFragment(),
        WorkoutDetailFragment.newInstance(workoutId),
    )

    private fun visibleEmptyStates(v: View): Int {
        if (v.visibility != View.VISIBLE) return 0
        if (v is EmptyState) return 1
        if (v is android.view.ViewGroup) return (0 until v.childCount).sumOf { visibleEmptyStates(v.getChildAt(it)) }
        return 0
    }

    @Test
    fun everyScreenOpensEmpty() {
        TestDb.fresh(ctx)
        val activity = launch().get()
        idle()
        // No device yet: Body shows the goal card (and what can be typed in).
        waitFor("body goal card") { activity.findViewById<View>(R.id.body_today_card)?.isShown == true }
        activity.select(Tab.ACTIVITY)
        idle()
        for (f in screens(workoutId = 1).dropLast(1)) {
            val shown = activity.open(f)
            assertNotNull(shown.view)
            activity.onBackPressedDispatcher.onBackPressed()
            activity.supportFragmentManager.executePendingTransactions()
        }
        assertEquals(0, activity.supportFragmentManager.backStackEntryCount)
        // A workout that does not exist closes itself.
        Nav.open(activity.supportFragmentManager.findFragmentByTag(Tab.BODY.tag) ?: error("no body"), WorkoutDetailFragment.newInstance(999))
        activity.supportFragmentManager.executePendingTransactions()
        assertEquals(1, activity.supportFragmentManager.backStackEntryCount)
        waitFor("missing workout closes") { activity.supportFragmentManager.backStackEntryCount == 0 }
        idle()
        assertEquals(0, activity.supportFragmentManager.backStackEntryCount)
    }

    @Test
    fun everyScreenOpensWithDemoData() {
        val db = TestDb.fresh(ctx)
        val demo = DemoData(ctx, db)
        runBlocking { demo.load() }
        val workout = runBlocking { db.workouts.list(1).first() }
        assertTrue("demo workout has a FIT file", File(ctx.filesDir, workout.fitPath ?: "").isFile)
        val activity = launch().get()
        idle()
        activity.select(Tab.ACTIVITY)
        idle()
        activity.select(Tab.BODY)
        idle()
        bodyCardsShowAndOpen(activity)
        for (f in screens(workout.id)) {
            val shown = activity.open(f)
            val v = shown.requireView()
            if (shown is SleepDetailFragment || shown is HeartDetailFragment || shown is WorkoutDetailFragment) {
                waitFor("${shown.javaClass.simpleName} chart") { findAll(v, OverlayChartView::class.java).any { it.layers.isNotEmpty() } }
            }
            // The Body overlays: body signals (O7), intensity minutes with resting HR (O6), protein and strength (O3).
            if (shown is BodySignalsDetailFragment) {
                waitFor("body signals chart") { v.findViewById<OverlayChartView>(R.id.body_signals_chart).layers.size >= 2 }
            }
            if (shown is HeartDetailFragment) {
                waitFor("heart trend chart") { v.findViewById<OverlayChartView>(R.id.heart_trend_chart).layers.map { it.id } == listOf("intensity", "resting_hr") }
            }
            if (shown is MeasureDetailFragment && shown.requireArguments().getInt("kind") == BodyKind.BODY_FAT.code) {
                waitFor("body fat with protein and strength") {
                    v.findViewById<OverlayChartView>(R.id.measure_chart).layers.map { it.id }.containsAll(listOf("measure", "protein", "strength"))
                }
            }
            if (shown is WeightDetailFragment) {
                val chart = findAll(v, OverlayChartView::class.java).single()
                waitFor("weight with calories and activity") { chart.layers.map { it.id }.containsAll(listOf("weight", "net", "active_min", "kcal_in", "kcal_out")) }
            }
            activity.onBackPressedDispatcher.onBackPressed()
            activity.supportFragmentManager.executePendingTransactions()
            idle()
        }
        runBlocking { demo.clear() }
        assertTrue(runBlocking { db.workouts.list(10).isEmpty() })
        assertTrue(runBlocking { db.body.latest(BodyKind.WEIGHT) == null })
        assertTrue(!File(ctx.filesDir, DemoData.DEMO_DIR).exists())
    }

    /** The root view id of each registered card, as it appears in its tab. */
    private fun legacy(tab: CardTab) = CardRegistry.of(tab).filter { it.toString() in rootIds }

    private val rootIds: Map<String, Int> = mapOf(
        "food/energy" to R.id.energy_card,
        "activity/intensity" to R.id.intensity_card,
        "body/weight" to R.id.weight_card,
        "body/measurements" to R.id.measurements_card,
        "body/recovery" to R.id.recovery_row,
        "body/spo2" to R.id.spo2_card,
        "activity/today_rings" to R.id.today_rings_card,
        "activity/trends" to R.id.trend_card,
        "activity/workouts" to R.id.recent_card,
        "activity/intensity_minutes" to R.id.intensity_minutes_card,
        "activity/steps_today" to R.id.steps_today_card,
        "activity/active_kcal" to R.id.active_kcal_card,
        "activity/strength_sessions" to R.id.strength_card,
        "activity/active_days" to R.id.active_days_card,
        "activity/training_load" to R.id.training_load_card,
        "activity/weekly_volume" to R.id.weekly_volume_card,
        "activity/easy_hard" to R.id.easy_hard_card,
        "food/macros_today" to R.id.food_macros_card,
        "food/water" to R.id.food_water_card,
        "food/protein_per_kg" to R.id.food_protein_per_kg_card,
        "food/protein_per_meal" to R.id.food_protein_per_meal_card,
        "food/carbs_per_kg" to R.id.food_carbs_per_kg_card,
        "food/workout_fuel" to R.id.food_workout_fuel_card,
        "food/fuel_balance" to R.id.food_fuel_balance_card,
        "food/logging_days" to R.id.food_logging_days_card,
        "food/sodium" to R.id.food_sodium_card,
        "food/fibre" to R.id.food_fibre_card,
        "food/total_sugar" to R.id.food_total_sugar_card,
        "body/sleep" to R.id.sleep_night_card,
        "body/waist_height" to R.id.waist_height_card,
        "body/body_fat" to R.id.body_fat_card,
        "body/hrv_baseline" to R.id.hrv_baseline_card,
        "body/resting_hr" to R.id.resting_hr_card,
        "body/body_stress" to R.id.body_stress_card,
        "body/body_signals" to R.id.body_signals_card,
        "body/heart_rate_now" to R.id.heart_rate_now_card,
        "body/heart_rate_trends" to R.id.heart_rate_trends_card,
        "body/breathing_rate" to R.id.breathing_rate_card,
        "body/energy_level" to R.id.energy_level_card,
        "activity/today_timeline" to R.id.today_timeline_card,
        "activity/recovery_hr" to R.id.recovery_hr_card,
        "activity/distance_today" to R.id.distance_today_card,
        "activity/calories_today" to R.id.calories_today_card,
    )

    private fun MainActivity.stack(tab: CardTab): CardStack? = when (tab) {
        CardTab.BODY -> (supportFragmentManager.findFragmentByTag(Tab.BODY.tag) as? BodyFragment)?.cards
        CardTab.ACTIVITY -> (supportFragmentManager.findFragmentByTag(Tab.ACTIVITY.tag) as? ActivityFragment)?.cards
        CardTab.FOOD -> (supportFragmentManager.findFragmentByTag(Tab.FOOD.tag) as? FoodFragment)?.cards
    }

    private fun CardTab.shell(): Tab = when (this) {
        CardTab.BODY -> Tab.BODY
        CardTab.ACTIVITY -> Tab.ACTIVITY
        CardTab.FOOD -> Tab.FOOD
    }

    /**
     * Every tab shows every registered card, in the saved (reversed) order, each with its own view and
     * the demo data; then every tab goes back to the general-health set.
     */
    private fun everyTabShowsEveryCard(activity: MainActivity) {
        val db = SelfWatchDb.get(ctx)
        val store = CardStore(db.settings)
        for (tab in CardTab.entries) {
            val order = legacy(tab).reversed()
            for (c in order) assertNotNull("$c has a root id in this test", rootIds[c.toString()])
            runBlocking { store.save(tab, order) }
            activity.select(tab.shell())
            idle()
            val tabView = activity.supportFragmentManager.findFragmentByTag(tab.shell().tag)?.requireView() ?: error("no $tab view")
            waitFor("$tab cards in order") {
                val stack = activity.stack(tab)
                stack != null && stack.shownIds == order.map { it.id } &&
                    order.all { c -> tabView.findViewById<View>(rootIds.getValue(c.toString()))?.isShown == true }
            }
            // Only the visible cards are built: hide all but one and the others leave the screen.
            runBlocking { store.save(tab, order.take(1)) }
            waitFor("$tab shows one card") {
                activity.stack(tab)?.shownIds == listOf(order.first().id) &&
                    order.drop(1).all { c -> tabView.findViewById<View>(rootIds.getValue(c.toString())) == null }
            }
            runBlocking { store.save(tab, order) }
            waitFor("$tab cards back") { activity.stack(tab)?.shownIds == order.map { it.id } }
        }
        // The demo data reached the cards that always have it (earlier days' readings and workouts).
        waitFor("demo data in the cards") {
            activity.findViewById<View>(R.id.intensity_empty)?.visibility == View.GONE &&
                activity.window.decorView.let { d ->
                    listOf(R.id.hrn_empty, R.id.hrt_empty, R.id.br_empty, R.id.el_empty).all { d.findViewById<View>(it)?.visibility == View.GONE }
                } &&
                activity.findViewById<View>(R.id.spo2_empty)?.visibility == View.GONE &&
                activity.findViewById<View>(R.id.trend_empty)?.visibility == View.GONE &&
                activity.findViewById<View>(R.id.recent_empty)?.visibility == View.GONE &&
                activity.findViewById<android.view.ViewGroup>(R.id.recent_list)?.childCount ?: 0 > 0 &&
                activity.window.decorView.findViewWithTag<View>("water_0") != null
        }
        // Tapping a water square logs a glass for the day shown.
        val drinks = runBlocking { db.water.entries(app.selfwatch.model.time.SystemClock.today()) }
        val before = drinks.size
        val units = DisplayUnits.of(runBlocking { db.settings.profile().units })
        val glasses = app.selfwatch.food.WaterGlass.glasses(drinks.sumOf { it.ml }, units)
        activity.select(Tab.FOOD)
        idle()
        assertTrue(activity.window.decorView.findViewWithTag<View>("water_$glasses")?.performClick() == true)
        waitFor("glass logged") { runBlocking { db.water.entries(app.selfwatch.model.time.SystemClock.today()).size } == before + 1 }
        for (tab in CardTab.entries) runBlocking { store.reset(tab) }
        activity.select(Tab.BODY)
        idle()
    }

    /** Every Body card is shown in the saved order, has its demo data, and opens its screen or tab. */
    private fun bodyCardsShowAndOpen(activity: MainActivity) {
        everyTabShowsEveryCard(activity)
        val db = SelfWatchDb.get(ctx)
        val order = legacy(CardTab.BODY).reversed() + listOf(app.selfwatch.body.cards.EnergyCard, app.selfwatch.body.cards.IntensityCard)
        runBlocking { CardStore(db.settings).save(CardTab.BODY, order) }
        val column = activity.findViewById<android.view.ViewGroup>(R.id.body_cards)
        waitFor("cards in order") {
            column.childCount == order.size && (0 until order.size).map { column.getChildAt(it).id } == order.map { rootIds.getValue(it.toString()) }
        }
        // The phase-2 Body cards show their demo values, not their empty states.
        waitFor("demo data in the phase-2 Body cards") {
            listOf(
                R.id.sleep_night_empty, R.id.waist_height_empty, R.id.body_fat_empty, R.id.hrv_baseline_empty,
                R.id.resting_hr_empty, R.id.body_stress_empty, R.id.body_signals_empty,
            ).all { activity.findViewById<View>(it)?.visibility == View.GONE }
        }
        val opens = mapOf(
            R.id.weight_card to WeightDetailFragment::class.java,
            R.id.measurements_card to MeasurementsFragment::class.java,
            R.id.sleep_card to SleepDetailFragment::class.java,
            R.id.rest_hr_card to HeartDetailFragment::class.java,
            R.id.hrv_card to HeartDetailFragment::class.java,
            R.id.spo2_card to SleepDetailFragment::class.java,
            R.id.sleep_night_card to SleepDetailFragment::class.java,
            R.id.waist_height_card to MeasureDetailFragment::class.java,
            R.id.body_fat_card to MeasureDetailFragment::class.java,
            R.id.hrv_baseline_card to HeartDetailFragment::class.java,
            R.id.resting_hr_card to HeartDetailFragment::class.java,
            R.id.body_stress_card to HeartDetailFragment::class.java,
            R.id.body_signals_card to BodySignalsDetailFragment::class.java,
        )
        for ((id, type) in opens) {
            assertTrue(activity.findViewById<View>(id).performClick())
            activity.supportFragmentManager.executePendingTransactions()
            idle()
            val shown = activity.supportFragmentManager.findFragmentById(R.id.detail_container)
            assertTrue("card opens ${type.simpleName}", type.isInstance(shown))
            activity.onBackPressedDispatcher.onBackPressed()
            activity.supportFragmentManager.executePendingTransactions()
            idle()
        }
        val tabs = mapOf(R.id.intensity_card to Tab.ACTIVITY)
        for ((id, tab) in tabs) {
            assertTrue(activity.findViewById<View>(id).performClick())
            activity.supportFragmentManager.executePendingTransactions()
            idle()
            assertTrue("card opens ${tab.name}", activity.supportFragmentManager.findFragmentByTag(tab.tag)?.isVisible == true)
            activity.select(Tab.BODY)
            idle()
        }
        assertTrue(activity.findViewById<View>(R.id.energy_card).performClick())
        activity.supportFragmentManager.executePendingTransactions()
        idle()
        val body = activity.supportFragmentManager.findFragmentByTag(Tab.BODY.tag) ?: error("no body")
        body.childFragmentManager.executePendingTransactions()
        val sheet = body.childFragmentManager.fragments.filterIsInstance<EnergySheet>().single()
        sheet.dismissNow()
        // Back to the general-health set for the screens below.
        runBlocking { CardStore(db.settings).reset(CardTab.BODY) }
    }

    @Test
    fun editCardsReordersResizesAndResets() {
        val db = TestDb.fresh(ctx)
        val store = CardStore(db.settings)
        val sleep = app.selfwatch.body.cards.SleepCard
        val weight = app.selfwatch.body.cards.WeightCard
        val water = app.selfwatch.food.cards.WaterCard
        runBlocking {
            store.saveSlots(CardTab.BODY, listOf(CardSlot(sleep, CardSize.LARGE), CardSlot(weight, CardSize.SMALL), CardSlot(water, CardSize.SMALL)))
        }
        val activity = launch().get()
        idle()
        val shown = activity.open(EditCardsFragment.newInstance(CardTab.BODY))
        val list = shown.requireView().findViewById<RecyclerView>(R.id.edit_cards_list)
        fun layout() {
            list.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            list.layout(0, 0, 1080, list.measuredHeight)
            idle()
        }
        waitFor("rows") { (list.adapter?.itemCount ?: 0) > 4 }
        layout()
        // Row 0 is "On this tab"; take the first card (Sleep) off with its switch.
        val first = list.findViewHolderForAdapterPosition(1) ?: error("no first row")
        findAll(first.itemView, MaterialSwitch::class.java).single().performClick()
        waitFor("hidden card saved") { runBlocking { store.visible(CardTab.BODY) } == listOf(weight, water) }
        layout()
        // Move Water (row 2) up with the accessibility action.
        val second = list.findViewHolderForAdapterPosition(2) ?: error("no second card")
        val toggle = findAll(second.itemView, MaterialSwitch::class.java).single()
        val up = toggle.createAccessibilityNodeInfo().actionList.firstOrNull { it.label == ctx.getString(R.string.edit_cards_move_up) } ?: error("no move up")
        assertTrue(toggle.performAccessibilityAction(up.id, null))
        waitFor("move saved") { runBlocking { store.visible(CardTab.BODY) } == listOf(water, weight) }
        layout()
        // Make Water large with its size choice.
        val row = list.findViewHolderForAdapterPosition(1) ?: error("no water row")
        val large = findAll(row.itemView, com.google.android.material.button.MaterialButton::class.java).single { it.text == ctx.getString(R.string.edit_cards_large) }
        large.performClick()
        waitFor("size saved") { runBlocking { store.slots(CardTab.BODY) }.first() == CardSlot(water, CardSize.LARGE) }
        // Reset brings back the default layout (it follows the devices again).
        shown.requireView().findViewById<View>(R.id.edit_cards_reset).performClick()
        waitFor("reset saved") { runBlocking { !store.isSaved(CardTab.BODY) } }
        activity.onBackPressedDispatcher.onBackPressed()
        activity.supportFragmentManager.executePendingTransactions()
        idle()
    }

    @Test
    fun everyTabMenuOpensItsEditCards() {
        val db = TestDb.fresh(ctx)
        val store = CardStore(db.settings)
        val activity = launch().get()
        idle()
        for (tab in CardTab.entries) {
            activity.select(tab.shell())
            idle()
            val host = activity.supportFragmentManager.findFragmentByTag(tab.shell().tag) ?: error("no $tab")
            val toolbar = host.requireView().findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            assertTrue("$tab menu has Edit cards", toolbar.menu.performIdentifierAction(R.id.menu_edit_cards, 0))
            activity.supportFragmentManager.executePendingTransactions()
            idle()
            val edit = activity.supportFragmentManager.findFragmentById(R.id.detail_container) as? EditCardsFragment ?: error("$tab: no Edit cards")
            val list = edit.requireView().findViewById<RecyclerView>(R.id.edit_cards_list)
            waitFor("$tab rows") { (list.adapter?.itemCount ?: 0) > 0 }
            // Reset on this tab writes this tab only.
            edit.requireView().findViewById<View>(R.id.edit_cards_reset).performClick()
            waitFor("$tab reset") { runBlocking { !store.isSaved(tab) } }
            activity.onBackPressedDispatcher.onBackPressed()
            activity.supportFragmentManager.executePendingTransactions()
            idle()
        }
    }

    @Test
    fun exportIntentAsksToCreateADocument() {
        val target = ExportTarget("application/gpx+xml", "selfwatch-workout-7.gpx")
        val intent = CreateDocumentFor().createIntent(ctx, target)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("application/gpx+xml", intent.type)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("selfwatch-workout-7.gpx", intent.getStringExtra(Intent.EXTRA_TITLE))
        // The real exporters name a single workout's file and type.
        val exporters = WorkoutExport.exporters(ctx)
        val gpx = WorkoutExport.target(exporters, "gpx", 7) ?: error("no gpx exporter")
        assertTrue(gpx.name.endsWith(".gpx"))
        assertTrue(WorkoutExport.target(exporters, "fit", 7)?.name?.endsWith(".fit") == true)
        assertTrue(WorkoutExport.target(exporters, "tcx", 7)?.name?.endsWith(".tcx") == true)
        assertEquals(null, WorkoutExport.target(exporters, "nope", 7))
        assertEquals("gpx", WorkoutExport.FORMATS.first())
        val result = Intent().setData(android.net.Uri.parse("content://x/y"))
        assertEquals("content://x/y", CreateDocumentFor().parseResult(android.app.Activity.RESULT_OK, result).toString())
        assertEquals(null, CreateDocumentFor().parseResult(android.app.Activity.RESULT_CANCELED, result))
    }

    @Test
    @Config(qualifiers = "land", fontScale = 2.0f)
    fun screensOpenInLandscapeAtDoubleFontWithDemoData() {
        everyScreenOpensWithDemoData()
    }

    private fun <T : View> findAll(v: View, type: Class<T>): List<T> {
        val out = ArrayList<T>()
        if (type.isInstance(v)) out += type.cast(v)!!
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) out += findAll(v.getChildAt(i), type)
        return out
    }
}
