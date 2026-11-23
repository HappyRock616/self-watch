/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.AppGraph
import app.selfwatch.cards.CardStore
import app.selfwatch.cards.MainGoal
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.SettingsStore
import app.selfwatch.db.dao.GoalDao
import app.selfwatch.model.food.Goal
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.food.Profile
import app.selfwatch.model.food.Sex
import app.selfwatch.model.food.UnitSystem
import app.selfwatch.model.time.Day
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The seven onboarding steps, in order. Goals come first: the main goal shapes the Body screen. */
enum class OnboardingStep {
    WELCOME, GOALS, PROFILE, PAIR, IMPORT, PERMISSIONS, FOOD,
}

/** Goals asked for during onboarding; null means "not set". */
data class StarterGoals(
    val kcalIn: Int? = null,
    val steps: Int? = null,
    val weightKgX100: Int? = null,
    val waterMl: Int? = null,
    val proteinG: Int? = null,
)

/** Onboarding state. */
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val profile: Profile = Profile(null, null, Sex.UNSPECIFIED, UnitSystem.FOLLOW_PHONE),
    val goals: StarterGoals = StarterGoals(),
    /** The main goal chosen on the goals step (saved with the goals). */
    val mainGoal: MainGoal = MainGoal.DEFAULT,
    val foodEnabled: Boolean = true,
    /** The main goal stored before onboarding, when the user already had cards of their own (null otherwise). */
    val ownCardsGoal: MainGoal? = null,
    /** True once onboarding is done (finished or skipped); the screen closes. */
    val finished: Boolean = false,
) {
    /** 1-based position. */
    val number: Int get() = step.ordinal + 1

    /** True on the last step. */
    val isLast: Boolean get() = step == OnboardingStep.entries.last()
}

/**
 * Onboarding (C21): seven steps, each skippable. [next] saves nothing; [saveProfile] and [saveGoals]
 * save and move on; [finish] (from the last step, or "Skip setup" on any step) sets
 * `onboarding.done` so it is not shown again.
 */
class OnboardingViewModel(
    private val settings: SettingsStore,
    private val goals: GoalDao,
    private val today: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {

    private val mutable = MutableStateFlow(OnboardingUiState())
    private val cards = CardStore(settings)

    /** Screen state. */
    val state: StateFlow<OnboardingUiState> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            val p = settings.profile()
            val food = settings.getBool(SettingKeys.FOOD_ENABLED, true)
            val main = cards.mainGoal()
            val own = if (cards.anySaved()) main else null
            // A goal picked before this load finished wins.
            mutable.update {
                it.copy(profile = p, foodEnabled = food, ownCardsGoal = own, mainGoal = if (it.mainGoal == MainGoal.DEFAULT) main else it.mainGoal)
            }
        }
    }

    /** Next step without saving (Skip, or Next on steps with nothing to save). On the last step: [finish]. */
    fun next() {
        val s = mutable.value
        if (s.isLast) finish() else mutable.update { it.copy(step = OnboardingStep.entries[s.step.ordinal + 1]) }
    }

    /** The previous step; false when already on the first. */
    fun back(): Boolean {
        val s = mutable.value
        if (s.step.ordinal == 0) return false
        mutable.update { it.copy(step = OnboardingStep.entries[s.step.ordinal - 1]) }
        return true
    }

    /** Saves [p] (blank fields stay unset) and moves on. Out-of-range values are dropped. */
    fun saveProfile(p: Profile) {
        val year = today().year
        val clean = p.copy(
            heightCm = p.heightCm?.takeIf { it in MIN_HEIGHT_CM..MAX_HEIGHT_CM },
            birthYear = p.birthYear?.takeIf { it in MIN_BIRTH_YEAR..(year - MIN_AGE) },
        )
        mutable.update { it.copy(profile = clean) }
        viewModelScope.launch {
            settings.setProfile(clean)
            next()
        }
    }

    /** Chooses the main goal (saved by [saveGoals]). */
    fun pickMainGoal(goal: MainGoal) {
        mutable.update { it.copy(mainGoal = goal) }
    }

    /**
     * True when saving the chosen goal should first ask whether to switch the cards: the user already
     * had cards of their own and picked a different goal. Otherwise the goal's cards are just applied.
     */
    val asksToSwitchCards: Boolean
        get() = mutable.value.let { s -> s.ownCardsGoal != null && s.ownCardsGoal != s.mainGoal }

    /**
     * Saves the main goal (with [switchCards], every card tab switches to its set) and the goals that
     * are set (dated today), then moves on.
     */
    fun saveGoals(g: StarterGoals, switchCards: Boolean = true) {
        mutable.update { it.copy(goals = g) }
        val main = mutable.value.mainGoal
        viewModelScope.launch {
            if (switchCards) cards.setMainGoalWithCards(main) else cards.setMainGoal(main)
            val day = Day.of(today())
            g.proteinG?.takeIf { it in 10..1_000 }?.let { goals.set(Goal(GoalKind.PROTEIN_G, it, day)) }
            g.kcalIn?.takeIf { it in 500..10_000 }?.let { goals.set(Goal(GoalKind.KCAL_IN, it, day)) }
            g.steps?.takeIf { it in 100..100_000 }?.let { goals.set(Goal(GoalKind.STEPS, it, day)) }
            g.weightKgX100?.takeIf { it in 2_000..40_000 }?.let { goals.set(Goal(GoalKind.WEIGHT_KG_X100, it, day)) }
            g.waterMl?.takeIf { it in 100..10_000 }?.let { goals.set(Goal(GoalKind.WATER_ML, it, day)) }
            next()
        }
    }

    /** Food tracking on or off (the food step). */
    fun setFoodEnabled(on: Boolean) {
        mutable.update { it.copy(foodEnabled = on) }
        viewModelScope.launch { settings.setBool(SettingKeys.FOOD_ENABLED, on) }
    }

    /** Marks onboarding done and closes it. */
    fun finish() {
        viewModelScope.launch {
            settings.setBool(SettingKeys.ONBOARDING_DONE, true)
            mutable.update { it.copy(finished = true) }
        }
    }

    /** Limits and factory. */
    companion object {
        private const val MIN_HEIGHT_CM = 50
        private const val MAX_HEIGHT_CM = 272
        private const val MIN_BIRTH_YEAR = 1900
        private const val MIN_AGE = 5

        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { OnboardingViewModel(graph.settings, graph.db.goals) }
        }

        /** Whether onboarding should be shown (not done yet). */
        suspend fun needed(settings: SettingsStore): Boolean = !settings.getBool(SettingKeys.ONBOARDING_DONE)
    }
}
