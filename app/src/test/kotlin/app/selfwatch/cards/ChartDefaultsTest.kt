/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.cards

import android.app.Application
import app.selfwatch.activity.OverlayLayers
import app.selfwatch.activity.TrendMetric
import app.selfwatch.body.BodyCompLayers
import app.selfwatch.body.SleepLayers
import app.selfwatch.body.TestDb
import app.selfwatch.body.WeightLayers
import app.selfwatch.db.SettingKeys
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ChartDefaultsTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()

    @Test
    fun everyGoalAndChartGivesAValidSet() {
        for (goal in MainGoal.entries) {
            for (chart in ChartId.entries) {
                val hidden = ChartDefaults.hidden(goal, chart)
                val visible = ChartDefaults.visible(goal, chart)
                val what = "$goal × $chart"
                assertTrue("$what hides only known layers", chart.toggleable.containsAll(hidden))
                assertTrue("$what shows only known layers", (chart.toggleable + chart.locked).containsAll(visible))
                assertTrue("$what shows a layer", (chart.toggleable - hidden).isNotEmpty())
                assertTrue("$what hidden and visible are disjoint", hidden.none { it in visible })
                assertTrue("$what keeps locked layers on", chart.locked.none { it in hidden })
            }
            assertFalse(WeightLayers.WEIGHT in ChartDefaults.hidden(goal, ChartId.WEIGHT))
            assertTrue(WeightLayers.WEIGHT in ChartDefaults.visible(goal, ChartId.WEIGHT))
            // Two layers per chart (the weight itself plus one), three or more only where the chart needs them.
            assertEquals(2, ChartDefaults.visible(goal, ChartId.WEIGHT).size)
            assertEquals(2, ChartDefaults.visible(goal, ChartId.SLEEP_NIGHT).size)
            assertEquals(2, ChartDefaults.visible(goal, ChartId.ACTIVITY_TRENDS).size)
            assertEquals(2, ChartDefaults.visible(goal, ChartId.TRAINING).size)
            // The new two-layer charts: both lines on for every goal.
            assertEquals(setOf("resting", "average"), ChartDefaults.visible(goal, ChartId.HEART_RATE_TRENDS))
            assertEquals(setOf("steps", "hr"), ChartDefaults.visible(goal, ChartId.TODAY_TIMELINE))
        }
        assertEquals(ChartId.entries.size, ChartDefaults.KEYS.toSet().size)
    }

    @Test
    fun tableMatchesTheOwnersChoices() {
        fun on(goal: MainGoal, chart: ChartId) = ChartDefaults.visible(goal, chart)
        assertEquals(setOf(WeightLayers.WEIGHT, WeightLayers.NET), on(MainGoal.WEIGHT_LOSS, ChartId.WEIGHT))
        assertEquals(setOf(WeightLayers.WEIGHT, WeightLayers.KCAL_IN), on(MainGoal.BUILD_MUSCLE, ChartId.WEIGHT))
        assertEquals(setOf(WeightLayers.WEIGHT, WeightLayers.ACTIVE_MIN), on(MainGoal.STRESS_WELLBEING, ChartId.WEIGHT))
        assertEquals(setOf(SleepLayers.STAGES, SleepLayers.HR), on(MainGoal.HEART_HEALTH, ChartId.SLEEP_NIGHT))
        assertEquals(setOf(SleepLayers.STAGES, SleepLayers.HRV), on(MainGoal.ENDURANCE, ChartId.SLEEP_NIGHT))
        assertEquals(setOf(TrendMetric.DISTANCE.id, TrendMetric.ACTIVE_MIN.id), on(MainGoal.ENDURANCE, ChartId.ACTIVITY_TRENDS))
        assertEquals(setOf(TrendMetric.STEPS.id, TrendMetric.ACTIVE_MIN.id), on(MainGoal.WEIGHT_LOSS, ChartId.ACTIVITY_TRENDS))
        assertEquals(setOf(OverlayLayers.LOAD, OverlayLayers.HRV), on(MainGoal.GENERAL_HEALTH, ChartId.TRAINING))
        assertEquals(setOf(BodyCompLayers.MEASURE, BodyCompLayers.PROTEIN, BodyCompLayers.STRENGTH), on(MainGoal.BUILD_MUSCLE, ChartId.BODY_COMP))
        assertEquals(setOf(BodyCompLayers.MEASURE, BodyCompLayers.PROTEIN), on(MainGoal.WEIGHT_LOSS, ChartId.BODY_COMP))
        for (chart in listOf(ChartId.HEART_TREND, ChartId.EXERCISE_SLEEP, ChartId.SLEEP_KCAL, ChartId.BODY_SIGNALS, ChartId.HEART_RATE_TRENDS, ChartId.TODAY_TIMELINE)) {
            assertEquals(chart.toggleable.toSet(), on(MainGoal.ATHLETIC, chart))
        }
    }

    @Test
    fun noStoredChoiceUsesTheGoalsDefaults() = runBlocking {
        val db = TestDb.fresh(ctx)
        for (goal in MainGoal.entries) {
            CardStore(db.settings).setMainGoal(goal)
            for (chart in ChartId.entries) {
                assertNull(db.settings.get(chart.key))
                assertEquals("$goal × $chart", ChartDefaults.hidden(goal, chart), ChartChoice(chart).load(db))
            }
        }
    }

    @Test
    fun aToggleIsStoredAndOutlivesAGoalChange() = runBlocking {
        val db = TestDb.fresh(ctx)
        val store = CardStore(db.settings)
        store.setMainGoal(MainGoal.WEIGHT_LOSS)
        val weight = ChartChoice(ChartId.WEIGHT)
        weight.save(db, setOf(WeightLayers.NET))
        assertEquals(setOf(WeightLayers.NET), weight.load(db))
        assertEquals(WeightLayers.NET, db.settings.get(WeightLayers.KEY))
        // Keeping the cards keeps every chart's choice.
        store.setMainGoal(MainGoal.ENDURANCE)
        assertEquals(setOf(WeightLayers.NET), weight.load(db))
        // A chart the user never touched follows the new goal.
        assertEquals(ChartDefaults.hidden(MainGoal.ENDURANCE, ChartId.SLEEP_NIGHT), ChartChoice(ChartId.SLEEP_NIGHT).load(db))
    }

    @Test
    fun aGoalChangeKeepsEveryChartsChoice() = runBlocking {
        val db = TestDb.fresh(ctx)
        val store = CardStore(db.settings)
        store.setMainGoal(MainGoal.WEIGHT_LOSS)
        for (chart in ChartId.entries) ChartChoice(chart).save(db, emptySet())
        store.setMainGoal(MainGoal.ENDURANCE)
        for (chart in ChartId.entries) {
            assertEquals("", db.settings.get(chart.key))
            assertEquals(emptySet<String>(), ChartChoice(chart).load(db))
        }
    }
}
