/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.selfwatch.cards

import android.app.Application
import app.selfwatch.activity.cards.TodayRingsCard
import app.selfwatch.activity.cards.TrendsCard
import app.selfwatch.activity.cards.WorkoutsCard
import app.selfwatch.body.TestDb
import app.selfwatch.body.cards.BodyFatCard
import app.selfwatch.body.cards.BodySignalsCard
import app.selfwatch.body.cards.BodyStressCard
import app.selfwatch.body.cards.EnergyCard
import app.selfwatch.body.cards.HrvBaselineCard
import app.selfwatch.body.cards.MeasurementsCard
import app.selfwatch.body.cards.RestingHrCard
import app.selfwatch.body.cards.SleepCard
import app.selfwatch.body.cards.Spo2Card
import app.selfwatch.body.cards.WaistHeightCard
import app.selfwatch.body.cards.WeightCard
import app.selfwatch.db.SettingKeys
import app.selfwatch.food.cards.CarbsPerKgCard
import app.selfwatch.food.cards.FibreCard
import app.selfwatch.food.cards.LoggingDaysCard
import app.selfwatch.food.cards.MacrosTodayCard
import app.selfwatch.food.cards.ProteinPerKgCard
import app.selfwatch.food.cards.ProteinPerMealCard
import app.selfwatch.food.cards.SodiumCard
import app.selfwatch.food.cards.TotalSugarCard
import app.selfwatch.food.cards.WaterCard
import app.selfwatch.food.cards.WorkoutFuelCard
import app.selfwatch.me.GoalsViewModel
import app.selfwatch.model.fake.FakeClock
import app.selfwatch.ui.format.Units
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The card framework: registry, presets per goal and tab, stored order per tab, migration, Edit cards and goal changes. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CardFrameworkTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun registryIdsAreStableUniqueAndOnTheirTab() {
        assertEquals(listOf("body", "activity", "food"), CardTab.entries.map { it.key })
        assertEquals("cards.body", CardTab.BODY.cardsKey)
        assertEquals("cards.activity.known", CardTab.ACTIVITY.knownKey)
        assertEquals("cards.food.preset", CardTab.FOOD.presetKey)
        for (tab in CardTab.entries) {
            val cards = CardRegistry.of(tab)
            assertTrue("$tab has cards", cards.isNotEmpty())
            assertEquals("$tab ids are unique", cards.size, cards.map { it.id }.toSet().size)
            for (c in cards) {
                assertEquals("$c is on its tab", tab, c.tab)
                assertTrue("$c id is a plain word", c.id.matches(Regex("[a-z0-9_]+")))
                assertTrue("$c reloads on something", c.tables.isNotEmpty())
                assertEquals(c, CardRegistry.byId(tab, c.id))
            }
            assertNull(CardRegistry.byId(tab, "no_such_card"))
        }
        // The ids already stored on phones never change.
        assertEquals(
            listOf(
                "energy", "macros", "steps", "intensity", "weight", "measurements", "recovery", "spo2",
                "sleep", "waist_height", "body_fat", "hrv_baseline", "resting_hr", "body_stress", "body_signals",
            ),
            CardRegistry.of(CardTab.BODY).map { it.id },
        )
        assertEquals(
            listOf(
                "today_rings", "trends", "workouts", "intensity_minutes", "steps_today", "active_kcal", "strength_sessions", "active_days",
                "training_load", "weekly_volume", "easy_hard",
            ),
            CardRegistry.of(CardTab.ACTIVITY).map { it.id },
        )
        assertEquals(
            listOf("macros_today", "water", "protein_per_kg", "protein_per_meal", "carbs_per_kg", "workout_fuel", "fuel_balance", "logging_days", "sodium", "fibre", "total_sugar"),
            CardRegistry.of(CardTab.FOOD).map { it.id },
        )
    }

    @Test
    fun presetsForEveryGoalAndTabUseOnlyRegisteredCards() {
        for (goal in MainGoal.entries) {
            for (tab in CardTab.entries) {
                val preset = goal.cards(tab)
                assertTrue("$goal/$tab has cards", preset.isNotEmpty())
                assertEquals("$goal/$tab has no repeats", preset.distinct(), preset)
                for (c in preset) {
                    assertEquals("$goal/$tab: $c is registered on $tab", c, CardRegistry.byId(tab, c.id))
                }
            }
            assertEquals(goal, MainGoal.byId(goal.id))
        }
        // Spot checks of the table.
        assertEquals(
            listOf(WeightCard, EnergyCard, WaistHeightCard, SleepCard, MeasurementsCard),
            MainGoal.WEIGHT_LOSS.cards(CardTab.BODY),
        )
        assertEquals(
            listOf(HrvBaselineCard, SleepCard, RestingHrCard, WeightCard, BodyFatCard),
            MainGoal.ATHLETIC.cards(CardTab.BODY),
        )
        assertEquals(
            listOf(SleepCard, HrvBaselineCard, BodyStressCard, RestingHrCard, BodySignalsCard),
            MainGoal.STRESS_WELLBEING.cards(CardTab.BODY),
        )
        assertEquals(
            listOf(SleepCard, WaistHeightCard, RestingHrCard, WeightCard, EnergyCard),
            MainGoal.GENERAL_HEALTH.cards(CardTab.BODY),
        )
        assertEquals(
            listOf("intensity_minutes", "steps_today", "strength_sessions", "today_rings", "trends", "workouts"),
            MainGoal.GENERAL_HEALTH.cards(CardTab.ACTIVITY).map { it.id },
        )
        assertEquals(
            listOf("weekly_volume", "easy_hard", "training_load", "workouts", "trends"),
            MainGoal.ENDURANCE.cards(CardTab.ACTIVITY).map { it.id },
        )
        assertEquals(listOf(SodiumCard, FibreCard, WaterCard), MainGoal.GENERAL_HEALTH.cards(CardTab.FOOD))
        assertEquals(listOf(ProteinPerKgCard, ProteinPerMealCard, MacrosTodayCard, WaterCard), MainGoal.BUILD_MUSCLE.cards(CardTab.FOOD))
        assertEquals(listOf(CarbsPerKgCard, WorkoutFuelCard, ProteinPerKgCard, WaterCard), MainGoal.ENDURANCE.cards(CardTab.FOOD))
        assertEquals(listOf(ProteinPerKgCard, LoggingDaysCard, WaterCard, MacrosTodayCard), MainGoal.WEIGHT_LOSS.cards(CardTab.FOOD))
        assertTrue("total sugar is a swap-in only", MainGoal.entries.none { TotalSugarCard in it.cards(CardTab.FOOD) })
        // Every goal keeps what each tab showed before cards (no card lost by default).
        for (goal in MainGoal.entries) {
            assertTrue(goal.cards(CardTab.ACTIVITY).any { it == TodayRingsCard || it == TrendsCard || it == WorkoutsCard })
            assertTrue(WaterCard in goal.cards(CardTab.FOOD))
        }
        assertEquals(MainGoal.GENERAL_HEALTH, MainGoal.DEFAULT)
        assertEquals(MainGoal.GENERAL_HEALTH, MainGoal.byId(null))
        assertEquals(MainGoal.GENERAL_HEALTH, MainGoal.byId("nonsense"))
        assertNull(MainGoal.byIdOrNull("nonsense"))
        assertTrue(MainGoal.ATHLETIC.asksProtein)
        assertFalse(MainGoal.GENERAL_HEALTH.asksProtein)
    }

    @Test
    fun storedListIsReadBackWithTheMergeRules() {
        val tab = CardTab.BODY
        val all = CardRegistry.of(tab).joinToString(",") { it.id }
        // Never saved: the goal's set.
        assertEquals(MainGoal.ATHLETIC.cards(tab), CardStore.resolve(tab, null, null, MainGoal.ATHLETIC))
        // Unknown ids and repeats are ignored; blanks too.
        assertEquals(listOf(WeightCard, EnergyCard), CardStore.resolve(tab, "weight, gone_card,,energy,weight", all, MainGoal.GENERAL_HEALTH))
        // Ids of another tab are unknown here.
        assertEquals(listOf(WeightCard), CardStore.resolve(tab, "weight,water,trends", all, MainGoal.GENERAL_HEALTH))
        // Everything hidden stays hidden.
        assertEquals(emptyList<Card<*>>(), CardStore.resolve(tab, "", all, MainGoal.GENERAL_HEALTH))
        // A card added after the list was saved: shown at the end when the goal's set has it…
        val olderKnown = CardRegistry.of(tab).filter { it != HrvBaselineCard && it != WaistHeightCard }.joinToString(",") { it.id }
        assertEquals(listOf(WeightCard, HrvBaselineCard), CardStore.resolve(tab, "weight", olderKnown, MainGoal.ATHLETIC))
        // …and hidden otherwise. A card the user hid (known) is never brought back.
        assertEquals(listOf(WeightCard), CardStore.resolve(tab, "weight", all, MainGoal.ATHLETIC))
        assertEquals(listOf(WeightCard, WaistHeightCard), CardStore.resolve(tab, "weight", olderKnown, MainGoal.GENERAL_HEALTH))
        // The same rules on Food: water was hidden, macros is new and in the goal's set.
        assertEquals(listOf(ProteinPerKgCard, ProteinPerMealCard, MacrosTodayCard), CardStore.resolve(CardTab.FOOD, "", "water", MainGoal.BUILD_MUSCLE))
    }

    @Test
    fun eachTabRoundTripsOnItsOwn() = runBlocking {
        val db = TestDb.fresh(ctx)
        val store = CardStore(db.settings)
        assertEquals(MainGoal.GENERAL_HEALTH, store.mainGoal())
        assertFalse(store.anySaved())
        for (tab in CardTab.entries) {
            assertEquals(MainGoal.GENERAL_HEALTH.cards(tab), store.visible(tab))
            assertNull(store.preset(tab))
        }
        store.save(CardTab.BODY, listOf(Spo2Card, WeightCard))
        store.save(CardTab.ACTIVITY, listOf(WorkoutsCard))
        store.save(CardTab.FOOD, listOf(WaterCard, MacrosTodayCard))
        assertTrue(store.anySaved())
        assertEquals(listOf(Spo2Card, WeightCard), store.visible(CardTab.BODY))
        assertEquals(listOf(WorkoutsCard), store.visible(CardTab.ACTIVITY))
        assertEquals(listOf(WaterCard, MacrosTodayCard), store.visible(CardTab.FOOD))
        assertEquals("spo2,weight", db.settings.get("cards.body"))
        assertEquals("workouts", db.settings.get("cards.activity"))
        assertEquals("water,macros_today", db.settings.get("cards.food"))
        assertEquals(CardRegistry.of(CardTab.ACTIVITY).joinToString(",") { it.id }, db.settings.get("cards.activity.known"))
        // Cards of another tab are never saved on this one.
        store.save(CardTab.ACTIVITY, listOf(WaterCard, TrendsCard))
        assertEquals(listOf(TrendsCard), store.visible(CardTab.ACTIVITY))
        store.save(CardTab.FOOD, emptyList())
        assertEquals(emptyList<Card<*>>(), store.visible(CardTab.FOOD))

        // The main goal alone leaves every tab; with cards it resets all three and notes the preset.
        store.setMainGoal(MainGoal.ATHLETIC)
        assertEquals(emptyList<Card<*>>(), store.visible(CardTab.FOOD))
        store.setMainGoalWithCards(MainGoal.WEIGHT_LOSS)
        assertEquals(MainGoal.WEIGHT_LOSS, store.mainGoal())
        for (tab in CardTab.entries) {
            assertEquals(MainGoal.WEIGHT_LOSS.cards(tab), store.visible(tab))
            assertEquals(MainGoal.WEIGHT_LOSS, store.preset(tab))
        }
        // A reset touches only its tab.
        store.setMainGoal(MainGoal.ENDURANCE)
        assertEquals(MainGoal.ENDURANCE.cards(CardTab.ACTIVITY), store.reset(CardTab.ACTIVITY))
        assertEquals(MainGoal.ENDURANCE, store.preset(CardTab.ACTIVITY))
        assertEquals(MainGoal.WEIGHT_LOSS, store.preset(CardTab.BODY))
        assertEquals(MainGoal.WEIGHT_LOSS.cards(CardTab.FOOD), store.visible(CardTab.FOOD))
    }

    @Test
    fun oldBodyKeysMoveToTheNewOnes() = runBlocking {
        val db = TestDb.fresh(ctx)
        val known = "energy,macros,steps,intensity,weight,measurements,recovery"
        db.settings.setAll(
            mapOf(
                SettingKeys.MAIN_GOAL to MainGoal.ATHLETIC.id,
                SettingKeys.BODY_CARDS to "weight,energy",
                SettingKeys.BODY_CARDS_KNOWN to known,
                SettingKeys.BODY_CARDS_PRESET to MainGoal.WEIGHT_LOSS.id,
            ),
        )
        val store = CardStore(db.settings)
        // The cards added since (not known then) that are in the athletic set join at the end, in its order.
        assertEquals(
            listOf(WeightCard, EnergyCard, HrvBaselineCard, SleepCard, RestingHrCard, BodyFatCard),
            store.visible(CardTab.BODY),
        )
        assertEquals("weight,energy", db.settings.get("cards.body"))
        assertEquals(known, db.settings.get("cards.body.known"))
        assertEquals(MainGoal.WEIGHT_LOSS, store.preset(CardTab.BODY))
        for (k in listOf(SettingKeys.BODY_CARDS, SettingKeys.BODY_CARDS_KNOWN, SettingKeys.BODY_CARDS_PRESET)) {
            assertNull("$k removed", db.settings.get(k))
        }
        // Other tabs start from the goal's set.
        assertEquals(MainGoal.ATHLETIC.cards(CardTab.FOOD), store.visible(CardTab.FOOD))

        // Everything hidden under the old key stays hidden.
        db.settings.setAll(mapOf("cards.body" to null, "cards.body.known" to null, SettingKeys.BODY_CARDS to ""))
        assertEquals(emptyList<Card<*>>(), store.visible(CardTab.BODY))

        // When both exist (an old backup restored over new settings), the new keys win and the old go.
        db.settings.set(SettingKeys.BODY_CARDS, "spo2")
        assertEquals(emptyList<Card<*>>(), store.visible(CardTab.BODY))
        assertNull(db.settings.get(SettingKeys.BODY_CARDS))
    }

    @Test
    fun editCardsSavesEveryChangeOnItsTab() = runBlocking {
        val db = TestDb.fresh(ctx)
        val store = CardStore(db.settings)
        for (tab in CardTab.entries) {
            val vm = EditCardsViewModel(store, tab)
            withTimeout(5_000) { vm.state.first { !it.loading } }
            val rows = vm.state.value.rows
            val preset = MainGoal.GENERAL_HEALTH.cards(tab)
            assertEquals(CardRegistry.of(tab).toSet(), rows.map { it.card }.toSet())
            assertEquals(preset, rows.filter { it.shown }.map { it.card })
            assertEquals(preset, rows.take(preset.size).map { it.card })
        }

        val vm = EditCardsViewModel(store, CardTab.BODY)
        withTimeout(5_000) { vm.state.first { !it.loading } }
        // SLEEP, WAIST, RESTING HR… → WAIST, RESTING HR, SLEEP…
        vm.move(0, 2)
        waitSaved(store, CardTab.BODY, listOf(WaistHeightCard, RestingHrCard, SleepCard, WeightCard, EnergyCard))
        vm.setShown(WeightCard, false)
        vm.setShown(Spo2Card, true)
        waitSaved(store, CardTab.BODY, listOf(WaistHeightCard, RestingHrCard, SleepCard, EnergyCard, Spo2Card))
        vm.move(-1, 3)
        vm.move(0, 99)
        assertEquals(WaistHeightCard, vm.state.value.rows.first().card)

        val food = EditCardsViewModel(store, CardTab.FOOD)
        withTimeout(5_000) { food.state.first { !it.loading } }
        food.setShown(MacrosTodayCard, true)
        waitSaved(store, CardTab.FOOD, listOf(SodiumCard, FibreCard, WaterCard, MacrosTodayCard))
        food.move(3, 0)
        waitSaved(store, CardTab.FOOD, listOf(MacrosTodayCard, SodiumCard, FibreCard, WaterCard))
        // Body was not touched by the Food edits.
        assertEquals(listOf(WaistHeightCard, RestingHrCard, SleepCard, EnergyCard, Spo2Card), store.visible(CardTab.BODY))

        db.settings.set(SettingKeys.MAIN_GOAL, MainGoal.ATHLETIC.id)
        var resetTo: MainGoal? = null
        vm.reset { resetTo = it }
        waitSaved(store, CardTab.BODY, MainGoal.ATHLETIC.cards(CardTab.BODY))
        withTimeout(5_000) { while (resetTo == null) kotlinx.coroutines.delay(10) }
        assertEquals(MainGoal.ATHLETIC, resetTo)
        assertEquals(MainGoal.ATHLETIC.cards(CardTab.BODY), vm.state.value.rows.filter { it.shown }.map { it.card })
        assertEquals("the reset is for Body only", listOf(MacrosTodayCard, SodiumCard, FibreCard, WaterCard), store.visible(CardTab.FOOD))
    }

    @Test
    fun changingTheMainGoalInGoalsCanResetEveryTab() = runBlocking {
        val db = TestDb.fresh(ctx)
        val store = CardStore(db.settings)
        store.save(CardTab.BODY, listOf(WeightCard))
        store.save(CardTab.ACTIVITY, listOf(TrendsCard))
        store.save(CardTab.FOOD, emptyList())
        val vm = GoalsViewModel(db, FakeClock())
        vm.setMainGoalNow(MainGoal.WEIGHT_LOSS, switchCards = false)
        assertEquals(MainGoal.WEIGHT_LOSS, vm.load(Units.METRIC).mainGoal)
        assertEquals(listOf(WeightCard), store.visible(CardTab.BODY))
        assertEquals(listOf(TrendsCard), store.visible(CardTab.ACTIVITY))
        assertEquals(emptyList<Card<*>>(), store.visible(CardTab.FOOD))
        vm.setMainGoalNow(MainGoal.ATHLETIC, switchCards = true)
        assertEquals(MainGoal.ATHLETIC, vm.load(Units.METRIC).mainGoal)
        for (tab in CardTab.entries) assertEquals(MainGoal.ATHLETIC.cards(tab), store.visible(tab))
    }

    private suspend fun waitSaved(store: CardStore, tab: CardTab, expected: List<Card<*>>) {
        withTimeout(5_000) {
            while (store.visible(tab) != expected) kotlinx.coroutines.delay(10)
        }
    }
}
