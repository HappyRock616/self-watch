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
import androidx.fragment.app.Fragment
import app.selfwatch.R
import app.selfwatch.activity.ExerciseSleepDetailFragment
import app.selfwatch.activity.TrainingDetailFragment
import app.selfwatch.activity.cards.ActivityCards
import app.selfwatch.body.BodySignalsDetailFragment
import app.selfwatch.body.HeartDetailFragment
import app.selfwatch.body.MeasureDetailFragment
import app.selfwatch.body.SleepDetailFragment
import app.selfwatch.body.TestDb
import app.selfwatch.body.WeightDetailFragment
import app.selfwatch.cards.CardStore
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.MainGoal
import app.selfwatch.demo.DemoData
import app.selfwatch.food.SleepCaloriesFragment
import app.selfwatch.model.health.BodyKind
import app.selfwatch.ui.charts.OverlayChartView
import app.selfwatch.ui.components.ToggleChipGroup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Every chart opens with its main goal's default chips checked (weight loss and endurance, with the
 * demo data and nothing chosen yet).
 */
@RunWith(RobolectricTestRunner::class)
class ChartDefaultsSmokeTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()

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

    /** A chart screen and the chips expected on, by goal. */
    private class Screen(val make: () -> Fragment, val name: String, val chartOf: (View) -> OverlayChartView?, val on: Map<MainGoal, Set<String>>)

    private val screens: List<Screen> = listOf(
        Screen(::WeightDetailFragment, "weight", ::firstChart, mapOf(
            MainGoal.WEIGHT_LOSS to setOf("weight", "net"),
            MainGoal.ENDURANCE to setOf("weight", "net"),
        )),
        Screen(::SleepDetailFragment, "sleep", { it.findViewById(R.id.sleep_chart) }, mapOf(
            MainGoal.WEIGHT_LOSS to setOf("stages", "hr"),
            MainGoal.ENDURANCE to setOf("stages", "hrv"),
        )),
        Screen(::HeartDetailFragment, "heart trend", { it.findViewById(R.id.heart_trend_chart) }, mapOf(
            MainGoal.WEIGHT_LOSS to setOf("intensity", "resting_hr"),
            MainGoal.ENDURANCE to setOf("intensity", "resting_hr"),
        )),
        Screen({ MeasureDetailFragment.newInstance(BodyKind.BODY_FAT, null) }, "body composition", { it.findViewById(R.id.measure_chart) }, mapOf(
            MainGoal.WEIGHT_LOSS to setOf("measure", "protein"),
            MainGoal.ENDURANCE to setOf("measure", "protein"),
        )),
        Screen(::BodySignalsDetailFragment, "body signals", { it.findViewById(R.id.body_signals_chart) }, mapOf(
            MainGoal.WEIGHT_LOSS to setOf("skin_temp", "resting_hr", "respiration", "band"),
            MainGoal.ENDURANCE to setOf("skin_temp", "resting_hr", "respiration", "band"),
        )),
        Screen(::TrainingDetailFragment, "training", { it.findViewById(R.id.training_chart) }, mapOf(
            MainGoal.WEIGHT_LOSS to setOf("load", "hrv"),
            MainGoal.ENDURANCE to setOf("load", "hrv"),
        )),
        Screen(::ExerciseSleepDetailFragment, "exercise and sleep", { it.findViewById(R.id.exercise_sleep_chart) }, mapOf(
            MainGoal.WEIGHT_LOSS to setOf("exercise", "sleep"),
            MainGoal.ENDURANCE to setOf("exercise", "sleep"),
        )),
        Screen(::SleepCaloriesFragment, "sleep and calories", { it.findViewById(R.id.sleep_calories_chart) }, mapOf(
            MainGoal.WEIGHT_LOSS to setOf("sleep", "kcal_in"),
            MainGoal.ENDURANCE to setOf("sleep", "kcal_in"),
        )),
    )

    private val trendsOn: Map<MainGoal, Set<String>> = mapOf(
        MainGoal.WEIGHT_LOSS to setOf("steps", "active_min"),
        MainGoal.ENDURANCE to setOf("distance", "active_min"),
    )

    @Test
    fun weightLossDefaults() = check(MainGoal.WEIGHT_LOSS)

    @Test
    fun enduranceDefaults() = check(MainGoal.ENDURANCE)

    private fun check(goal: MainGoal) {
        val db = TestDb.fresh(ctx)
        runBlocking {
            DemoData(ctx, db).load()
            val store = CardStore(db.settings)
            store.setMainGoal(goal)
            store.save(CardTab.ACTIVITY, ActivityCards.ALL)
        }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()

        // The Activity Trends card.
        activity.select(Tab.ACTIVITY)
        idle()
        waitFor("trends chips") { activity.findViewById<ToggleChipGroup>(R.id.trend_chips)?.childCount == 4 }
        assertEquals("$goal trends", trendsOn.getValue(goal), activity.findViewById<ToggleChipGroup>(R.id.trend_chips).onIds)

        activity.select(Tab.BODY)
        idle()
        for (s in screens) {
            val host = activity.supportFragmentManager.findFragmentByTag(Tab.BODY.tag) ?: error("no body tab")
            Nav.open(host, s.make())
            activity.supportFragmentManager.executePendingTransactions()
            idle()
            val view = activity.supportFragmentManager.findFragmentById(R.id.detail_container)?.requireView() ?: error("no ${s.name}")
            waitFor("${s.name} chart") { s.chartOf(view)?.layers?.isNotEmpty() == true }
            val chart = s.chartOf(view) ?: error("no ${s.name} chart")
            val chips = chipsBelow(chart) ?: error("no ${s.name} chips")
            waitFor("${s.name} chips") { chips.childCount == chart.layers.size }
            val present = chart.layers.map { it.id }.toSet()
            val expected = s.on.getValue(goal).intersect(present)
            assertTrue("$goal ${s.name} shows a default layer", expected.isNotEmpty())
            assertEquals("$goal ${s.name} chips", expected, chips.onIds)
            assertEquals("$goal ${s.name} layers", expected, present.filter { chart.isLayerVisible(it) }.toSet())
            activity.onBackPressedDispatcher.onBackPressed()
            activity.supportFragmentManager.executePendingTransactions()
            idle()
        }
    }

    private fun firstChart(v: View): OverlayChartView? {
        if (v is OverlayChartView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) firstChart(v.getChildAt(i))?.let { return it }
        return null
    }

    /** The chip group in the same card as [chart]. */
    private fun chipsBelow(chart: View): ToggleChipGroup? {
        var p = chart.parent as? ViewGroup
        while (p != null) {
            findChips(p)?.let { return it }
            p = p.parent as? ViewGroup
        }
        return null
    }

    private fun findChips(v: View): ToggleChipGroup? {
        if (v is ToggleChipGroup) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findChips(v.getChildAt(i))?.let { return it }
        return null
    }
}
