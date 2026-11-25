/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.onboarding

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.SelfWatchApp
import app.selfwatch.body.cards.SleepCard
import app.selfwatch.cards.CardStore
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.MainGoal
import app.selfwatch.db.SettingKeys
import app.selfwatch.food.cards.WeightGoalMath
import app.selfwatch.me.MeTestSupport
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.food.Profile
import app.selfwatch.model.food.Sex
import app.selfwatch.model.food.UnitSystem
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.time.Day
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/** Onboarding: seven steps (goals first), each skippable; main goal, goals and profile saved; done is remembered. */
@RunWith(RobolectricTestRunner::class)
class OnboardingViewModelTest {

    private lateinit var app: SelfWatchApp
    private val store = ViewModelStore()
    private val today = LocalDate.of(2026, 11, 23)

    @Before
    fun setUp() {
        app = MeTestSupport.freshApp()
    }

    @After
    fun tearDown() {
        store.clear()
        MeTestSupport.resetDb()
    }

    private fun vm(): OnboardingViewModel {
        val g = app.graph
        return ViewModelProvider(store, viewModelFactory { initializer { OnboardingViewModel(g.settings, g.db.goals) { today } } })[OnboardingViewModel::class.java]
    }

    @Test
    fun skippingEveryStepFinishesAndIsRemembered() {
        val settings = app.graph.settings
        assertTrue(runBlocking { OnboardingViewModel.needed(settings) })
        val vm = vm()
        val seen = ArrayList<OnboardingStep>()
        repeat(OnboardingStep.entries.size) {
            seen += vm.state.value.step
            vm.next()
        }
        assertEquals(OnboardingStep.entries, seen)
        MeTestSupport.waitFor("finished") { vm.state.value.finished }
        assertFalse(runBlocking { OnboardingViewModel.needed(settings) })
        assertNull("skipping saves no profile", runBlocking { settings.profile() }.heightCm)
        // Skipping keeps general health and the default cards.
        val cards = CardStore(settings)
        assertEquals(MainGoal.GENERAL_HEALTH, runBlocking { cards.mainGoal() })
        for (tab in CardTab.entries) assertFalse(runBlocking { cards.isSaved(tab) })
    }

    @Test
    fun goalsComeRightAfterWelcome() {
        assertEquals(OnboardingStep.GOALS, OnboardingStep.entries[1])
    }

    @Test
    fun backStopsAtTheFirstStep() {
        val vm = vm()
        assertFalse(vm.back())
        vm.next()
        vm.next()
        assertEquals(OnboardingStep.PROFILE, vm.state.value.step)
        assertTrue(vm.back())
        assertEquals(OnboardingStep.GOALS, vm.state.value.step)
        assertEquals(2, vm.state.value.number)
    }

    @Test
    fun goalsAndProfileAreSavedAndInvalidValuesDropped() {
        val vm = vm()
        MeTestSupport.waitFor("loaded") { vm.state.value.mainGoal == MainGoal.GENERAL_HEALTH }
        vm.next()
        assertEquals(OnboardingStep.GOALS, vm.state.value.step)
        vm.pickMainGoal(MainGoal.ATHLETIC)
        vm.saveGoals(StarterGoals(kcalIn = 2_100, steps = 9_000, weightKgX100 = 7_250, waterMl = 5, proteinG = 140))
        MeTestSupport.waitFor("profile step") { vm.state.value.step == OnboardingStep.PROFILE }
        val goals = runBlocking { app.graph.db.goals.allOn(Day.of(today)) }
        assertEquals(2_100, goals[GoalKind.KCAL_IN]?.value)
        assertEquals(9_000, goals[GoalKind.STEPS]?.value)
        assertEquals(7_250, goals[GoalKind.WEIGHT_KG_X100]?.value)
        assertEquals(140, goals[GoalKind.PROTEIN_G]?.value)
        assertNull("5 ml of water is out of range", goals[GoalKind.WATER_ML])
        // The main goal is saved; the cards stay on the one default layout (never saved).
        val cards = CardStore(app.graph.settings)
        assertEquals(MainGoal.ATHLETIC, runBlocking { cards.mainGoal() })
        for (tab in CardTab.entries) assertFalse(runBlocking { cards.isSaved(tab) })

        vm.saveProfile(Profile(heightCm = 178, birthYear = 3000, sex = Sex.FEMALE, units = UnitSystem.METRIC))
        MeTestSupport.waitFor("pair step") { vm.state.value.step == OnboardingStep.PAIR }
        val p = runBlocking { app.graph.settings.profile() }
        assertEquals(178, p.heightCm)
        assertNull("a birth year in the future is dropped", p.birthYear)
        assertEquals(Sex.FEMALE, p.sex)
        assertEquals(UnitSystem.METRIC, p.units)
    }

    @Test
    fun weightLossPlanSavesDateAndWeighInAndRefinesCaloriesWithTheProfile() {
        val g = app.graph
        val vm = ViewModelProvider(store, viewModelFactory { initializer { OnboardingViewModel(g.settings, g.db.goals, body = g.db.body, today = { today }) } })[OnboardingViewModel::class.java]
        MeTestSupport.waitFor("loaded") { vm.state.value.mainGoal == MainGoal.GENERAL_HEALTH }
        vm.next()
        vm.pickMainGoal(MainGoal.WEIGHT_LOSS)
        val day = Day.of(today)
        val starter = StarterGoals(weightKgX100 = 86_00, currentWeightKgX100 = 90_00, goalDate = day.plusDays(70).yyyymmdd)
        // Before the profile the burn is the default (1,920 kcal): worked out and filled in.
        val first = OnboardingViewModel.calorieGoal(starter, Profile(null, null, Sex.UNSPECIFIED, UnitSystem.FOLLOW_PHONE), day)!!
        assertEquals(1_480, first)
        vm.saveGoals(starter.copy(kcalIn = first, kcalAuto = true))
        MeTestSupport.waitFor("profile step") { vm.state.value.step == OnboardingStep.PROFILE }
        assertEquals(day.plusDays(70).yyyymmdd, runBlocking { g.settings.getInt(WeightGoalMath.DATE_KEY) })
        assertEquals(90_00, runBlocking { g.db.body.latest(BodyKind.WEIGHT) }?.valueX100)
        assertEquals(first, runBlocking { g.db.goals.allOn(day) }[GoalKind.KCAL_IN]?.value)

        // With the profile (man, 180 cm, 46): burn 2,160, 440 kcal less a day → 1,720.
        vm.saveProfile(Profile(heightCm = 180, birthYear = 1980, sex = Sex.MALE, units = UnitSystem.METRIC))
        MeTestSupport.waitFor("pair step") { vm.state.value.step == OnboardingStep.PAIR }
        assertEquals(1_720, runBlocking { g.db.goals.allOn(day) }[GoalKind.KCAL_IN]?.value)
    }

    @Test
    fun aTypedCalorieGoalIsNotReworkedByTheProfile() {
        val g = app.graph
        val vm = ViewModelProvider(store, viewModelFactory { initializer { OnboardingViewModel(g.settings, g.db.goals, body = g.db.body, today = { today }) } })[OnboardingViewModel::class.java]
        MeTestSupport.waitFor("loaded") { vm.state.value.mainGoal == MainGoal.GENERAL_HEALTH }
        vm.next()
        vm.pickMainGoal(MainGoal.WEIGHT_LOSS)
        val day = Day.of(today)
        vm.saveGoals(StarterGoals(kcalIn = 1_900, weightKgX100 = 86_00, currentWeightKgX100 = 90_00, goalDate = day.plusDays(70).yyyymmdd))
        MeTestSupport.waitFor("profile step") { vm.state.value.step == OnboardingStep.PROFILE }
        vm.saveProfile(Profile(heightCm = 180, birthYear = 1980, sex = Sex.MALE, units = UnitSystem.METRIC))
        MeTestSupport.waitFor("pair step") { vm.state.value.step == OnboardingStep.PAIR }
        assertEquals(1_900, runBlocking { g.db.goals.allOn(day) }[GoalKind.KCAL_IN]?.value)
    }

    @Test
    fun skipSetupFinishesFromAnyStep() {
        val vm = vm()
        vm.next()
        vm.setFoodEnabled(false)
        vm.finish()
        MeTestSupport.waitFor("finished") { vm.state.value.finished }
        assertTrue(runBlocking { app.graph.settings.getBool(SettingKeys.ONBOARDING_DONE) })
        MeTestSupport.waitFor("food off") { !runBlocking { app.graph.settings.getBool(SettingKeys.FOOD_ENABLED, true) } }
    }

    @Test
    fun setupRunAgainKeepsTheUsersCards() {
        val cards = CardStore(app.graph.settings)
        val own = listOf(SleepCard)
        runBlocking {
            cards.setMainGoal(MainGoal.HEART_HEALTH)
            cards.save(CardTab.BODY, own)
        }
        val vm = vm()
        MeTestSupport.waitFor("loaded") { vm.state.value.mainGoal == MainGoal.HEART_HEALTH }
        vm.next()
        vm.pickMainGoal(MainGoal.ENDURANCE)
        vm.saveGoals(StarterGoals())
        MeTestSupport.waitFor("profile step") { vm.state.value.step == OnboardingStep.PROFILE }
        assertEquals(MainGoal.ENDURANCE, runBlocking { cards.mainGoal() })
        assertEquals("kept", own, runBlocking { cards.visible(CardTab.BODY) })
    }
}
