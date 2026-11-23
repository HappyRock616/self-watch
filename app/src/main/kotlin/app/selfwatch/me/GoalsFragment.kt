/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.me

import android.os.Bundle
import android.text.InputType
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.AppGraph
import app.selfwatch.R
import app.selfwatch.cards.CardStore
import app.selfwatch.cards.MainGoal
import app.selfwatch.cards.MainGoalPicker
import app.selfwatch.cards.MainGoalSwitchDialog
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.Table
import app.selfwatch.graph
import app.selfwatch.model.food.Goal
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.SystemClock
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.DisplayUnits
import app.selfwatch.shell.Views
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Goals the app keeps. Most are dated rows in table `goal` ([GoalKind]); active calories has no goal
 * kind in the shared model yet, so it is a setting ([ACTIVE_KCAL_KEY]).
 */
enum class GoalField(
    /** The stored goal kind, or null for the active-calories setting. */
    val kind: GoalKind?,
    /** Label. */
    @field:StringRes @get:StringRes val label: Int,
) {
    KCAL(GoalKind.KCAL_IN, R.string.goal_kcal),
    PROTEIN(GoalKind.PROTEIN_G, R.string.goal_protein),
    FAT(GoalKind.FAT_G, R.string.goal_fat),
    CARB(GoalKind.CARB_G, R.string.goal_carb),
    STEPS(GoalKind.STEPS, R.string.goal_steps),
    ACTIVE_MIN(GoalKind.ACTIVE_MIN, R.string.goal_active_min),
    ACTIVE_KCAL(null, R.string.goal_active_kcal),
    WEIGHT(GoalKind.WEIGHT_KG_X100, R.string.goal_weight),
    WATER(GoalKind.WATER_ML, R.string.goal_water),
    FAST(GoalKind.FAST_MIN, R.string.goal_fast),
    SLEEP(GoalKind.SLEEP_MIN, R.string.goal_sleep),
    ;

    /** Unit text shown in the field ("kcal", "g", "h"…). */
    fun unit(units: Units): String = when (this) {
        KCAL, ACTIVE_KCAL -> "kcal"
        PROTEIN, FAT, CARB -> "g"
        STEPS -> ""
        ACTIVE_MIN -> "min"
        WEIGHT -> if (units == Units.METRIC) "kg" else "lb"
        WATER -> "ml"
        FAST, SLEEP -> "h"
    }

    /** A stored value as the text typed in the field. */
    fun toText(stored: Int, units: Units): String = when (this) {
        WEIGHT -> Fmt.x100((if (units == Units.METRIC) stored.toDouble() else stored / KG_PER_LB).roundToInt().toLong(), 1)
        FAST, SLEEP -> Fmt.x100((stored * 100L) / 60, 2)
        else -> stored.toString()
    }

    /** Typed text to the stored value, or null when it is not a sensible number. */
    fun parse(text: String, units: Units): Int? {
        val x100 = Fmt.parseX100(text) ?: return null
        if (x100 <= 0) return null
        val v = when (this) {
            WEIGHT -> if (units == Units.METRIC) x100.toInt() else (x100 * KG_PER_LB).roundToInt()
            FAST, SLEEP -> (x100 * 60 / 100.0).roundToInt()
            else -> (x100 / 100.0).roundToInt()
        }
        val max = when (this) {
            KCAL, ACTIVE_KCAL -> 20_000
            PROTEIN, FAT, CARB -> 2_000
            STEPS -> 200_000
            ACTIVE_MIN -> 1_440
            WEIGHT -> 500_00
            WATER -> 20_000
            FAST, SLEEP -> 1_440
        }
        return v.takeIf { it in 1..max }
    }

    /** Defaults and keys. */
    companion object {
        /** Setting key of the active-calories goal. */
        const val ACTIVE_KCAL_KEY: String = SettingKeys.GOAL_ACTIVE_KCAL

        private const val KG_PER_LB = 0.45359237
    }
}

/** A goal value and the day it applies from (null when the goal was never set). */
data class GoalValue(val value: Int, val fromDay: Day?)

/** Goals screen state. */
data class GoalsUiState(
    val units: Units = Units.METRIC,
    val values: Map<GoalField, GoalValue> = emptyMap(),
    val loading: Boolean = true,
    /** The main goal (it picks the default cards on every tab). */
    val mainGoal: MainGoal = MainGoal.DEFAULT,
)

/** Goals view model: reads the goals in force today and saves changed ones as new dated rows. */
class GoalsViewModel(private val db: SelfWatchDb, private val clock: Clock) : ViewModel() {

    private val cards = CardStore(db.settings)

    /** Screen state. */
    val state: StateFlow<GoalsUiState> = combine(
        db.changes.observe(setOf(Table.GOAL, Table.SETTING)).conflate(),
        DisplayUnits.observe(db),
    ) { _, u -> u }
        .map { u -> load(u) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), GoalsUiState())

    /** Loads the goals in force today. */
    suspend fun load(units: Units): GoalsUiState {
        val today = clock.today()
        val out = LinkedHashMap<GoalField, GoalValue>()
        for (f in GoalField.entries) {
            val k = f.kind
            if (k == null) {
                db.settings.getInt(GoalField.ACTIVE_KCAL_KEY)?.let { out[f] = GoalValue(it, null) }
            } else {
                db.goals.goalOn(k, today)?.let { out[f] = GoalValue(it.value, it.fromDay) }
            }
        }
        return GoalsUiState(units, out, loading = false, mainGoal = cards.mainGoal())
    }

    /** Saves [changes] (field → new stored value) from today; returns at once. */
    fun save(changes: Map<GoalField, Int>, done: () -> Unit) {
        viewModelScope.launch {
            saveNow(changes)
            done()
        }
    }

    /** Stores the main goal; with [switchCards] every card tab changes to its set too. */
    fun setMainGoal(goal: MainGoal, switchCards: Boolean) {
        viewModelScope.launch { setMainGoalNow(goal, switchCards) }
    }

    /** [setMainGoal] now (for tests). */
    suspend fun setMainGoalNow(goal: MainGoal, switchCards: Boolean) {
        if (switchCards) cards.setMainGoalWithCards(goal) else cards.setMainGoal(goal)
    }

    /** Saves [changes] now (for tests). */
    suspend fun saveNow(changes: Map<GoalField, Int>) {
        val today = clock.today()
        for ((f, v) in changes) {
            val k = f.kind
            if (k == null) db.settings.setInt(GoalField.ACTIVE_KCAL_KEY, v) else db.goals.set(Goal(k, v, today))
            // A calorie goal typed here replaces the automatic one on every screen.
            if (k == GoalKind.KCAL_IN) db.settings.set(SettingKeys.FOOD_GOAL_MODE, "manual")
        }
    }

    /** Factory. */
    companion object {
        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { GoalsViewModel(graph.db, SystemClock) }
        }
    }
}

/**
 * Goals (C13): the main goal (it picks the default cards), then calories, macros, steps, active minutes
 * and calories, weight, water, fasting window and sleep. A changed goal is saved as a new row dated
 * today, so past days keep the goal they had.
 */
class GoalsFragment : DetailFragment() {

    private val vm: GoalsViewModel by viewModels { GoalsViewModel.factory(requireContext().graph) }
    private val fields = LinkedHashMap<GoalField, Pair<TextInputLayout, TextInputEditText>>()
    private var filledOnce = false
    private lateinit var picker: MainGoalPicker

    override fun title(): CharSequence = getString(R.string.me_goals)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val mainCard = Views.card(column, getString(R.string.main_goal_title), first = true)
        mainCard.addView(Views.caption(ctx, getString(R.string.main_goal_help)), Views.matchWrap(ctx))
        val picker = MainGoalPicker(ctx) { goal -> askToSwitchCards(goal) }
        mainCard.addView(picker.view, Views.matchWrap(ctx, 8))
        this.picker = picker
        val card = Views.card(column, null)
        for (f in GoalField.entries) {
            val layout = TextInputLayout(ctx).apply { hint = getString(f.label) }
            val edit = TextInputEditText(ctx).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                id = android.view.View.generateViewId()
            }
            layout.addView(edit)
            card.addView(layout, Views.matchWrap(ctx, 8))
            fields[f] = layout to edit
        }
        column.addView(Views.caption(ctx, getString(R.string.goal_note)), Views.matchWrap(ctx, 8))
        val save = MaterialButton(ctx).apply {
            text = getString(R.string.measure_save)
            setOnClickListener { save() }
        }
        column.addView(save, Views.matchWrap(ctx, 16))
        filledOnce = savedInstanceState != null
        collectWhileStarted(vm.state) { s ->
            if (s.loading) return@collectWhileStarted
            picker.select(s.mainGoal)
            for ((f, pair) in fields) {
                val (layout, edit) = pair
                val unit = f.unit(s.units)
                layout.suffixText = unit.ifEmpty { null }
                val v = s.values[f]
                layout.helperText = v?.fromDay?.let { getString(R.string.goal_since, Fmt.day(ctx, it.yyyymmdd)) }
                if (!filledOnce) edit.setText(v?.let { f.toText(it.value, s.units) } ?: "")
            }
            filledOnce = true
        }
    }

    /** The main goal changed: keep it, and ask (once, for every tab) whether to switch to its cards. */
    private fun askToSwitchCards(goal: MainGoal) {
        if (goal == vm.state.value.mainGoal) return
        MainGoalSwitchDialog.ask(requireContext(), goal) { switch -> vm.setMainGoal(goal, switchCards = switch) }
    }

    private fun save() {
        val s = vm.state.value
        val changes = LinkedHashMap<GoalField, Int>()
        var ok = true
        for ((f, pair) in fields) {
            val (layout, edit) = pair
            val text = edit.text?.toString()?.trim().orEmpty()
            layout.error = null
            if (text.isEmpty()) continue
            val v = f.parse(text, s.units)
            if (v == null) {
                layout.error = getString(R.string.measure_invalid)
                ok = false
            } else if (v != s.values[f]?.value) {
                changes[f] = v
            }
        }
        if (!ok) return
        val app = requireContext().applicationContext
        vm.save(changes) {
            Toast.makeText(app, R.string.goal_saved, Toast.LENGTH_SHORT).show()
            close()
        }
    }
}
