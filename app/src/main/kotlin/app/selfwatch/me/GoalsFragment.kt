/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.me

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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
import app.selfwatch.activity.ActivityGoals
import app.selfwatch.cards.CardStore
import app.selfwatch.cards.MainGoal
import app.selfwatch.cards.MainGoalPicker
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.Table
import app.selfwatch.food.DietFragment
import app.selfwatch.food.NutrientUi
import app.selfwatch.food.WaterGlass
import app.selfwatch.food.cards.BurnSource
import app.selfwatch.food.cards.WeightGoalMath
import app.selfwatch.food.nutrition.DietPrefs
import app.selfwatch.graph
import app.selfwatch.model.food.Goal
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.food.Profile
import app.selfwatch.model.food.Sex
import app.selfwatch.model.food.UnitSystem
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.SystemClock
import app.selfwatch.service.health.HrZoneStore
import app.selfwatch.service.health.StepAutoGoal
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.DisplayUnits
import app.selfwatch.shell.Nav
import app.selfwatch.shell.Views
import app.selfwatch.ui.R as UiR
import app.selfwatch.ui.components.ListRow
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Goals screen's groups, in screen order. */
enum class GoalSection(@field:StringRes @get:StringRes val title: Int) {
    FOOD(R.string.goal_section_food),
    ACTIVITY(R.string.goal_section_activity),
    BODY(R.string.goal_section_body),
}

/**
 * Goals the app keeps: every goal the goal cards (Body today, Activity goals, Daily balance, Weight goal)
 * show. Most are dated rows in table `goal` ([kind]); goals the shared goal table has no kind for are
 * settings ([key]). Active minutes, workouts and active days are per week. Stored values stay metric;
 * weight, water and distance are shown and typed in the user's units. [WEIGHT_DATE] is a date (picked,
 * not typed), stored as `yyyymmdd`.
 */
enum class GoalField(
    /** The stored goal kind, or null for a setting. */
    val kind: GoalKind?,
    /** The setting key when [kind] is null. */
    val key: String?,
    /** Label. */
    @field:StringRes @get:StringRes val label: Int,
    /** The group it is shown in. */
    val section: GoalSection,
) {
    KCAL(GoalKind.KCAL_IN, null, R.string.goal_kcal, GoalSection.FOOD),
    PROTEIN(GoalKind.PROTEIN_G, null, R.string.goal_protein, GoalSection.FOOD),
    FAT(GoalKind.FAT_G, null, R.string.goal_fat, GoalSection.FOOD),
    CARB(GoalKind.CARB_G, null, R.string.goal_carb, GoalSection.FOOD),
    STEPS(GoalKind.STEPS, null, R.string.goal_steps, GoalSection.ACTIVITY),
    ACTIVE_MIN(GoalKind.ACTIVE_MIN, null, R.string.goal_active_min, GoalSection.ACTIVITY),
    ACTIVE_KCAL(null, SettingKeys.GOAL_ACTIVE_KCAL, R.string.goal_active_kcal, GoalSection.ACTIVITY),
    WORKOUTS(null, ActivityGoals.WORKOUTS_WEEK_KEY, R.string.goal_workouts_week, GoalSection.ACTIVITY),
    ACTIVE_DAYS(null, ActivityGoals.ACTIVE_DAYS_KEY, R.string.goal_active_days_week, GoalSection.ACTIVITY),
    DISTANCE(null, ActivityGoals.DISTANCE_KEY, R.string.goal_distance_day, GoalSection.ACTIVITY),
    FLOORS(null, ActivityGoals.FLOORS_KEY, R.string.goal_floors_day, GoalSection.ACTIVITY),
    WEIGHT(GoalKind.WEIGHT_KG_X100, null, R.string.goal_weight, GoalSection.BODY),
    WEIGHT_DATE(null, WeightGoalMath.DATE_KEY, R.string.goal_weight_date, GoalSection.BODY),
    WATER(GoalKind.WATER_ML, null, R.string.goal_water, GoalSection.BODY),
    SLEEP(GoalKind.SLEEP_MIN, null, R.string.goal_sleep, GoalSection.BODY),
    FAST(GoalKind.FAST_MIN, null, R.string.goal_fast, GoalSection.FOOD),
    ;

    /** Unit shown in the field ("kcal", "g", "min/week"…), or 0 for none. */
    @StringRes
    fun unit(units: Units): Int = when (this) {
        KCAL, ACTIVE_KCAL -> R.string.goal_unit_kcal
        PROTEIN, FAT, CARB -> R.string.goal_unit_g
        STEPS, FLOORS, WEIGHT_DATE -> 0
        ACTIVE_MIN -> R.string.goal_unit_min_week
        WORKOUTS -> R.string.goal_unit_per_week
        ACTIVE_DAYS -> R.string.goal_unit_days_week
        DISTANCE -> if (units == Units.METRIC) R.string.goal_unit_km else R.string.goal_unit_mi
        WEIGHT -> if (units == Units.METRIC) R.string.goal_unit_kg else R.string.goal_unit_lb
        WATER -> if (units == Units.METRIC) R.string.goal_unit_ml else R.string.goal_unit_fl_oz
        FAST, SLEEP -> R.string.goal_unit_h
    }

    /** The value used (and shown) while no goal is set in [units], or null when there is none. */
    fun defaultValue(units: Units): Int? = when (this) {
        STEPS -> ActivityGoals.DEFAULT_STEPS
        ACTIVE_MIN -> ActivityGoals.DEFAULT_ACTIVE_MIN_WEEK
        WORKOUTS -> ActivityGoals.DEFAULT_WORKOUTS_WEEK
        ACTIVE_DAYS -> ActivityGoals.DEFAULT_ACTIVE_DAYS
        // 8 glasses: 96 fl oz, or 2,800 ml.
        WATER -> WaterGlass.defaultGoalMl(units)
        else -> null
    }

    /**
     * True when [text] is what the field already shows for [stored] (or the default) in [units]: saving
     * it again would only add rounding (96 fl oz is 2,839 ml, not the 2,840 stored), so it is left alone.
     */
    fun unchanged(text: String, stored: Int?, units: Units): Boolean {
        val shown = stored ?: defaultValue(units) ?: return false
        val a = Fmt.parseX100(text) ?: return false
        val b = Fmt.parseX100(toText(shown, units)) ?: return false
        return a == b
    }

    /** A stored value as the text typed in the field (a date for [WEIGHT_DATE]). */
    fun toText(stored: Int, units: Units): String = when (this) {
        WEIGHT -> Fmt.x100((if (units == Units.METRIC) stored.toDouble() else stored / KG_PER_LB).roundToInt().toLong(), 1)
        WATER -> if (units == Units.METRIC) stored.toString() else (stored / ML_PER_FL_OZ).roundToInt().toString()
        DISTANCE -> Fmt.x100((stored / (if (units == Units.METRIC) M_PER_KM else M_PER_MI) * 100).roundToInt().toLong(), 1)
        FAST, SLEEP -> Fmt.x100((stored * 100L) / 60, 2)
        WEIGHT_DATE -> Fmt.dayToDate(stored).toString()
        else -> stored.toString()
    }

    /** Typed text to the stored value, or null when it is not a sensible number. */
    fun parse(text: String, units: Units): Int? {
        if (this == WEIGHT_DATE) return null
        val x100 = Fmt.parseX100(text) ?: return null
        // Floors: 0 means no goal (watches without a barometer can't count floors).
        if (this == FLOORS && x100 == 0L) return 0
        if (x100 <= 0) return null
        val v = when (this) {
            WEIGHT -> if (units == Units.METRIC) x100.toInt() else (x100 * KG_PER_LB).roundToInt()
            WATER -> if (units == Units.METRIC) (x100 / 100.0).roundToInt() else (x100 / 100.0 * ML_PER_FL_OZ).roundToInt()
            DISTANCE -> (x100 / 100.0 * (if (units == Units.METRIC) M_PER_KM else M_PER_MI)).roundToInt()
            FAST, SLEEP -> (x100 * 60 / 100.0).roundToInt()
            else -> (x100 / 100.0).roundToInt()
        }
        val max = when (this) {
            KCAL, ACTIVE_KCAL -> 20_000
            PROTEIN, FAT, CARB -> 2_000
            STEPS -> 200_000
            ACTIVE_MIN -> MINUTES_PER_WEEK
            WORKOUTS -> MAX_WORKOUTS_WEEK
            ACTIVE_DAYS -> DAYS_PER_WEEK
            DISTANCE -> MAX_DISTANCE_M
            FLOORS -> MAX_FLOORS
            WEIGHT -> 500_00
            WATER -> 20_000
            FAST, SLEEP -> 1_440
            WEIGHT_DATE -> 0
        }
        return v.takeIf { it in 1..max }
    }

    /** Defaults and keys. */
    companion object {
        /** Setting key of the active-calories goal. */
        const val ACTIVE_KCAL_KEY: String = SettingKeys.GOAL_ACTIVE_KCAL

        private const val KG_PER_LB = 0.45359237
        private const val M_PER_KM = 1000.0
        private const val M_PER_MI = 1609.344
        private const val MAX_WORKOUTS_WEEK = 21
        private const val DAYS_PER_WEEK = 7
        private const val MAX_DISTANCE_M = 200_000
        private const val MAX_FLOORS = 500

        /** Millilitres in one US fluid ounce. */
        const val ML_PER_FL_OZ: Double = 29.5735295625

        /** The largest active-minutes goal: every minute of a week. */
        const val MINUTES_PER_WEEK: Int = 7 * 24 * 60
    }
}

/** A goal value and the day it applies from (null when the goal was never set or is a setting). */
data class GoalValue(val value: Int, val fromDay: Day?)

/** Goals screen state. */
data class GoalsUiState(
    val units: Units = Units.METRIC,
    val values: Map<GoalField, GoalValue> = emptyMap(),
    val loading: Boolean = true,
    /** The main goal (it sets the charts' first view; cards are picked with Edit cards). */
    val mainGoal: MainGoal = MainGoal.DEFAULT,
    /** The diet and allergens set under Diet. */
    val diet: DietPrefs = DietPrefs.NONE,
    /** The latest weigh-in, kg ×100 (the weight loss plan starts from it). */
    val latestWeightX100: Int? = null,
    /** The estimated daily burn and where it comes from (the weight loss plan). */
    val burnKcal: Int = 0,
    val burnSource: BurnSource = BurnSource.ESTIMATED,
    /** The profile (sex for the safe minimum; height and age for the estimated burn). */
    val profile: Profile = Profile(null, null, Sex.UNSPECIFIED, UnitSystem.FOLLOW_PHONE),
    /** True while the step goal is automatic (Garmin-style Auto Goal, only going up). */
    val stepsAuto: Boolean = true,
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
                f.key?.let { key -> db.settings.getInt(key)?.let { out[f] = GoalValue(it, null) } }
            } else {
                db.goals.goalOn(k, today)?.let { out[f] = GoalValue(it.value, it.fromDay) }
            }
        }
        val diet = DietPrefs.of(db.settings.getList(SettingKeys.FOOD_DIETS), db.settings.getList(SettingKeys.FOOD_ALLERGENS))
        val weight = db.body.latest(BodyKind.WEIGHT)?.valueX100
        val (burn, source) = WeightLossPlanner.burn(db, today, weight)
        return GoalsUiState(
            units, out, loading = false, mainGoal = cards.mainGoal(), diet = diet,
            latestWeightX100 = weight, burnKcal = burn, burnSource = source, profile = db.settings.profile(),
            stepsAuto = StepAutoGoal.isOn(db),
        )
    }

    /** Saves [changes] (field → new stored value) from today, and a typed [weighInX100]; returns at once. */
    fun save(changes: Map<GoalField, Int>, weighInX100: Int? = null, done: () -> Unit) {
        viewModelScope.launch {
            saveNow(changes, weighInX100)
            done()
        }
    }

    /** Stores the main goal (the cards stay as they are). */
    fun setMainGoal(goal: MainGoal) {
        viewModelScope.launch { setMainGoalNow(goal) }
    }

    /** [setMainGoal] now (for tests). */
    suspend fun setMainGoalNow(goal: MainGoal) {
        cards.setMainGoal(goal)
    }

    /** Saves [changes] now (for tests); [weighInX100] is stored as a manual weigh-in now. */
    suspend fun saveNow(changes: Map<GoalField, Int>, weighInX100: Int? = null) {
        weighInX100?.let { db.body.insert(BodyMeasure(0, clock.nowSec(), BodyKind.WEIGHT, null, it, DataSource.MANUAL, null, null)) }
        val today = clock.today()
        for ((f, v) in changes) {
            val k = f.kind
            if (k == null) f.key?.let { db.settings.setInt(it, v) } else db.goals.set(Goal(k, v, today))
            // A calorie goal typed here replaces the automatic one on every screen.
            if (k == GoalKind.KCAL_IN) db.settings.set(SettingKeys.FOOD_GOAL_MODE, "manual")
            // A step goal typed here is a fixed goal: the automatic one stops.
            if (k == GoalKind.STEPS) db.settings.set(StepAutoGoal.AUTO_KEY, "0")
        }
    }

    /** Turns the automatic step goal on or off; on, it starts from the goal in force (or 7,500). */
    fun setStepsAuto(on: Boolean) {
        viewModelScope.launch { setStepsAutoNow(on) }
    }

    /** [setStepsAuto] now (for tests). */
    suspend fun setStepsAutoNow(on: Boolean) {
        db.settings.set(StepAutoGoal.AUTO_KEY, if (on) null else "0")
        if (on) {
            db.settings.set(StepAutoGoal.CHECKED_KEY, null)
            StepAutoGoal.update(db, clock)
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
 * Goals (C13): the main goal, then every goal the goal cards show, grouped Food, Activity, Body:
 * calories, macros and fasting; steps, active minutes, active calories, workouts, active days, distance
 * and floors; goal weight and date, water and sleep. A changed goal is saved as a new row dated today,
 * so past days keep the goal they had.
 */
class GoalsFragment : DetailFragment() {

    private val vm: GoalsViewModel by viewModels { GoalsViewModel.factory(requireContext().graph) }
    private val fields = LinkedHashMap<GoalField, Pair<TextInputLayout, TextInputEditText>>()
    private var filledOnce = false
    private var unitsShown: Units? = null
    private var pickedDate: Int? = null
    private lateinit var picker: MainGoalPicker

    // The weight loss plan: goal weight, goal date and calories move into it while the main goal is weight loss.
    private lateinit var planCard: View
    private lateinit var planFields: LinearLayout
    private lateinit var currentWeight: Pair<TextInputLayout, TextInputEditText>
    private lateinit var planStep: android.widget.TextView
    private lateinit var planExplain: android.widget.TextView
    private lateinit var planTooSoon: android.widget.TextView
    private lateinit var planUseDate: MaterialButton
    private val homes = LinkedHashMap<GoalField, Pair<ViewGroup, Int>>()
    private var planShown = false
    private var settingText = false
    private var currentFilled = false
    private lateinit var stepsAuto: MaterialSwitch

    override fun title(): CharSequence = getString(R.string.me_goals)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val mainCard = Views.card(column, getString(R.string.main_goal_title), first = true)
        mainCard.addView(Views.caption(ctx, getString(R.string.main_goal_help)), Views.matchWrap(ctx))
        val picker = MainGoalPicker(ctx) { goal -> if (goal != vm.state.value.mainGoal) vm.setMainGoal(goal) }
        mainCard.addView(picker.view, Views.matchWrap(ctx, 8))
        this.picker = picker
        val dietCard = Views.card(column, null)
        val dietRow = ListRow(ctx).apply {
            title = getString(R.string.goal_diet_row)
            subtitle = getString(R.string.goal_diet_none)
            showChevron = true
            tag = "diet_row"
            onClick = { Nav.open(this@GoalsFragment, DietFragment()) }
        }
        dietCard.addView(dietRow, Views.matchWrap(ctx))
        buildPlanCard(column)
        pickedDate = savedInstanceState?.getInt(KEY_DATE, 0)?.takeIf { it != 0 }
        for (section in GoalSection.entries) {
            val card = Views.card(column, getString(section.title))
            for (f in GoalField.entries.filter { it.section == section }) {
                val layout = TextInputLayout(ctx).apply { hint = getString(f.label) }
                val edit = TextInputEditText(ctx).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    id = android.view.View.generateViewId()
                    tag = "goal_${f.name.lowercase()}"
                }
                if (f == GoalField.WEIGHT_DATE) {
                    edit.inputType = InputType.TYPE_NULL
                    edit.isFocusable = false
                    edit.isCursorVisible = false
                    edit.setOnClickListener { pickDate() }
                    layout.helperText = getString(R.string.goal_weight_date_help)
                }
                layout.addView(edit)
                card.addView(layout, Views.matchWrap(ctx, 8))
                fields[f] = layout to edit
                if (f in PLAN_FIELDS) {
                    val home = layout.parent as ViewGroup
                    homes[f] = home to home.indexOfChild(layout)
                }
            }
            if (section == GoalSection.ACTIVITY) {
                // Heart-rate zones decide what counts as an active minute (zone 2 and up).
                val zonesRow = ListRow(ctx).apply {
                    title = getString(R.string.zones_title)
                    showChevron = true
                    tag = "zones_row"
                    onClick = { Nav.open(this@GoalsFragment, HrZonesFragment()) }
                }
                card.addView(zonesRow, Views.matchWrap(ctx, 8))
                stepsAuto = MaterialSwitch(ctx).apply {
                    text = getString(R.string.goal_steps_auto)
                    tag = "goal_steps_auto"
                    setOnCheckedChangeListener { _, on -> if (!settingText && on != vm.state.value.stepsAuto) vm.setStepsAuto(on) }
                }
                val stepsLayout = fields[GoalField.STEPS]?.first
                val home = stepsLayout?.parent as? ViewGroup
                if (home != null) home.addView(stepsAuto, home.indexOfChild(stepsLayout) + 1, Views.matchWrap(ctx, 4))
                val db = ctx.graph.db
                collectWhileStarted(flow { emit(HrZoneStore(db, SystemClock).current()) }.flowOn(Dispatchers.IO)) { z ->
                    zonesRow.subtitle = getString(
                        if (z.manual) R.string.zones_row_sub_manual else R.string.zones_row_sub_auto,
                        Fmt.number(z.bounds[1].toLong()),
                    )
                }
            }
        }
        wirePlanInputs()
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
            settingText = true
            stepsAuto.isChecked = s.stepsAuto
            settingText = false
            dietRow.subtitle = NutrientUi.prefsSummary(ctx, s.diet)
            for ((f, pair) in fields) {
                val (layout, edit) = pair
                val unit = f.unit(s.units)
                layout.suffixText = if (unit == 0) null else getString(unit)
                val v = s.values[f]
                if (f != GoalField.WEIGHT_DATE) layout.helperText = v?.fromDay?.let { getString(R.string.goal_since, Fmt.day(ctx, it.yyyymmdd)) }
                if (f == GoalField.WEIGHT_DATE) {
                    val day = pickedDate ?: v?.value
                    edit.setText(day?.let { Fmt.day(ctx, it) } ?: "")
                    continue
                }
                val shown = v?.value ?: f.defaultValue(s.units)
                if (!filledOnce || unitsShown != s.units) edit.setText(shown?.let { f.toText(it, s.units) } ?: "")
            }
            if (!currentFilled || unitsShown != s.units) {
                setQuietly(currentWeight.second, s.latestWeightX100?.let { GoalField.WEIGHT.toText(it, s.units) } ?: "")
                currentFilled = true
            }
            currentWeight.first.suffixText = getString(GoalField.WEIGHT.unit(s.units))
            filledOnce = true
            unitsShown = s.units
            showPlan(s.mainGoal == MainGoal.WEIGHT_LOSS)
            updatePlan(fill = false)
        }
    }

    // ---- Weight loss plan -------------------------------------------------------------------------

    private fun buildPlanCard(column: LinearLayout) {
        val ctx = requireContext()
        val card = Views.card(column, getString(R.string.wl_plan_title))
        card.tag = "weight_loss_plan"
        card.addView(Views.caption(ctx, getString(R.string.wl_plan_help)), Views.matchWrap(ctx))
        val layout = TextInputLayout(ctx).apply { hint = getString(R.string.wl_current_weight) }
        val edit = TextInputEditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            id = View.generateViewId()
            tag = "current_weight"
        }
        layout.addView(edit)
        card.addView(layout, Views.matchWrap(ctx, 8))
        currentWeight = layout to edit
        planFields = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        card.addView(planFields, Views.matchWrap(ctx))
        planStep = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_BodyLarge).apply {
            tag = "plan_step"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        card.addView(planStep, Views.matchWrap(ctx, 8))
        planExplain = Views.caption(ctx).apply { tag = "plan_explain" }
        card.addView(planExplain, Views.matchWrap(ctx, 4))
        planTooSoon = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body).apply {
            tag = "plan_too_soon"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        card.addView(planTooSoon, Views.matchWrap(ctx, 8))
        planUseDate = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { tag = "plan_use_date" }
        card.addView(planUseDate, Views.matchWrap(ctx, 4))
        planCard = card
        card.visibility = View.GONE
    }

    private fun wirePlanInputs() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!settingText) updatePlan(fill = true)
            }
        }
        currentWeight.second.addTextChangedListener(watcher)
        fields[GoalField.WEIGHT]?.second?.let { goal ->
            goal.addTextChangedListener(watcher)
            goal.imeOptions = EditorInfo.IME_ACTION_DONE
            // Goal weight first, then straight on to the goal date.
            goal.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE && planShown && planDate() == null && planGoal() != null) pickDate()
                false
            }
        }
    }

    /** Moves goal weight, goal date and calories into the plan card (weight loss) or back to their sections. */
    private fun showPlan(show: Boolean) {
        if (show == planShown) return
        planShown = show
        planCard.visibility = if (show) View.VISIBLE else View.GONE
        val ctx = requireContext()
        if (show) {
            for (f in PLAN_FIELDS) {
                val layout = fields[f]?.first ?: continue
                (layout.parent as? ViewGroup)?.removeView(layout)
                planFields.addView(layout, Views.matchWrap(ctx, 8))
            }
        } else {
            for ((f, home) in homes.entries.sortedBy { it.value.second }) {
                val layout = fields[f]?.first ?: continue
                (layout.parent as? ViewGroup)?.removeView(layout)
                home.first.addView(layout, home.second.coerceAtMost(home.first.childCount), Views.matchWrap(ctx, 8))
            }
        }
    }

    private fun planGoal(): Int? = vm.state.value.let { s -> fields[GoalField.WEIGHT]?.second?.text?.toString()?.trim()?.let { GoalField.WEIGHT.parse(it, s.units) } }

    private fun planCurrent(): Int? = vm.state.value.let { s -> currentWeight.second.text?.toString()?.trim()?.let { GoalField.WEIGHT.parse(it, s.units) } }

    private fun planDate(): Int? = pickedDate ?: vm.state.value.values[GoalField.WEIGHT_DATE]?.value

    /**
     * Guides goal weight → goal date and works out the calorie goal. [fill]: put the result in the calorie
     * field (after the user changed an input; not on load, so a calorie goal the user typed stays).
     */
    private fun updatePlan(fill: Boolean) {
        val s = vm.state.value
        if (!planShown || s.loading) return
        val ctx = requireContext()
        val current = planCurrent()
        val goal = planGoal()
        val date = planDate()
        val step = when {
            current == null -> R.string.wl_step_current
            goal == null -> R.string.wl_step_weight
            date == null -> R.string.wl_step_date
            else -> 0
        }
        planStep.text = if (step == 0) null else getString(step)
        planStep.visibility = if (step == 0) View.GONE else View.VISIBLE
        if (current == null || goal == null || date == null) {
            planExplain.visibility = View.GONE
            planTooSoon.visibility = View.GONE
            planUseDate.visibility = View.GONE
            return
        }
        val today = Day.of(LocalDate.now())
        val burn = if (s.burnSource == BurnSource.MEASURED) s.burnKcal else WeightLossPlanner.estimatedBurn(current, s.profile, today)
        val a = WeightLossPlanner.advise(current, goal, today, Day(date), burn, s.burnSource, s.profile.sex)
        val kcal = fields[GoalField.KCAL]
        if (kcal != null && (fill || kcal.second.text.isNullOrBlank())) setQuietly(kcal.second, GoalField.KCAL.toText(a.kcal, s.units))
        planExplain.text = WeightLossPlanner.explain(ctx, a, s.units)
        planExplain.visibility = View.VISIBLE
        val earliest = a.earliestSafe
        if (a.tooSoon && earliest != null) {
            val day = Fmt.day(ctx, earliest.yyyymmdd)
            planTooSoon.text = getString(R.string.wl_too_soon, day)
            planUseDate.text = getString(R.string.wl_use_date, day)
            planUseDate.setOnClickListener {
                pickedDate = earliest.yyyymmdd
                fields[GoalField.WEIGHT_DATE]?.second?.let { setQuietly(it, Fmt.day(ctx, earliest.yyyymmdd)) }
                updatePlan(fill = true)
            }
            planTooSoon.visibility = View.VISIBLE
            planUseDate.visibility = View.VISIBLE
        } else {
            planTooSoon.visibility = View.GONE
            planUseDate.visibility = View.GONE
        }
    }

    private fun setQuietly(edit: TextInputEditText, text: String) {
        settingText = true
        try {
            edit.setText(text)
        } finally {
            settingText = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pickedDate?.let { outState.putInt(KEY_DATE, it) }
    }

    /** Picks the goal date (today or later). */
    private fun pickDate() {
        val current = pickedDate ?: vm.state.value.values[GoalField.WEIGHT_DATE]?.value
        val startMs = (current?.let { Fmt.dayToDate(it) } ?: LocalDate.now().plusMonths(3)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val dialog = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.goal_weight_date)
            .setSelection(startMs)
            .setCalendarConstraints(CalendarConstraints.Builder().setValidator(DateValidatorPointForward.now()).build())
            .build()
        dialog.addOnPositiveButtonClickListener { ms ->
            val date = java.time.Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
            pickedDate = Fmt.dateToDay(date)
            fields[GoalField.WEIGHT_DATE]?.second?.let { setQuietly(it, Fmt.day(requireContext(), Fmt.dateToDay(date))) }
            updatePlan(fill = true)
        }
        dialog.show(childFragmentManager, "goal_date")
    }

    private fun save() {
        val s = vm.state.value
        val changes = LinkedHashMap<GoalField, Int>()
        var ok = true
        for ((f, pair) in fields) {
            if (f == GoalField.WEIGHT_DATE) continue
            val (layout, edit) = pair
            val text = edit.text?.toString()?.trim().orEmpty()
            layout.error = null
            if (text.isEmpty()) continue
            // Text left as shown keeps the stored value exactly (no round trip through the other units).
            if (f.unchanged(text, s.values[f]?.value, s.units)) continue
            val v = f.parse(text, s.units)
            if (v == null) {
                layout.error = getString(R.string.measure_invalid)
                ok = false
            } else if (v != s.values[f]?.value && !(s.values[f] == null && v == f.defaultValue(s.units))) {
                // An unset goal left at its default stays unset (it keeps following the default).
                changes[f] = v
            }
        }
        pickedDate?.takeIf { it != s.values[GoalField.WEIGHT_DATE]?.value }?.let { changes[GoalField.WEIGHT_DATE] = it }
        // A current weight typed in the weight loss plan is saved as a weigh-in.
        val weighIn = if (planShown) planCurrent()?.takeIf { it != s.latestWeightX100 } else null
        if (!ok) return
        val app = requireContext().applicationContext
        vm.save(changes, weighIn) {
            Toast.makeText(app, R.string.goal_saved, Toast.LENGTH_SHORT).show()
            close()
        }
    }

    private companion object {
        const val KEY_DATE = "goal_date"

        /** The goals the weight loss plan shows, in its order: goal weight, goal date, then calories. */
        val PLAN_FIELDS = listOf(GoalField.WEIGHT, GoalField.WEIGHT_DATE, GoalField.KCAL)
    }
}
