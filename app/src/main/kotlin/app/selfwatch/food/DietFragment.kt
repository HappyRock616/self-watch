/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.R
import app.selfwatch.food.nutrition.Diet
import app.selfwatch.food.nutrition.DietPrefs
import app.selfwatch.food.nutrition.NutrientTargets
import app.selfwatch.food.nutrition.Target
import app.selfwatch.food.nutrition.TargetInputs
import app.selfwatch.graph
import app.selfwatch.model.food.Allergen
import app.selfwatch.model.food.Nutrients.Field
import app.selfwatch.model.log.SwLog
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.Views
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.selfwatch.ui.R as UiR

/** Diet screen state: the prefs being edited and what the targets are worked out from. */
data class DietUiState(
    val loading: Boolean = true,
    val prefs: DietPrefs = DietPrefs.NONE,
    val inputs: TargetInputs = TargetInputs(),
    /** Targets typed by hand (stored units), by field. */
    val overrides: Map<Field, Int> = emptyMap(),
) {
    /** The targets for the prefs as ticked now, with the hand-typed ones. */
    val targets: Map<Field, Target> get() = NutrientTargets.of(inputs.copy(prefs = prefs, overrides = overrides))

    /** The recommended targets for the prefs as ticked now (no hand-typed ones). */
    val recommended: Map<Field, Target> get() = NutrientTargets.recommended(inputs.copy(prefs = prefs))
}

/** Diet and allergens (FOOD_UI_PLAN §4.5): edits the prefs, shows the targets they lead to, saves. */
class DietViewModel(private val backendProvider: () -> FoodBackend) : ViewModel() {
    private val backend: FoodBackend by lazy(backendProvider)
    private val mutable = MutableStateFlow(DietUiState())

    /** Screen state. */
    val state: StateFlow<DietUiState> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val prefs = backend.dietPrefs()
                val inputs = backend.targetInputs(backend.clock.today())
                mutable.value = DietUiState(loading = false, prefs = prefs, inputs = inputs, overrides = backend.targetOverrides())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "load failed", e)
                mutable.value = DietUiState(loading = false)
            }
        }
    }

    /** Ticks or unticks [diet]. */
    fun setDiet(diet: Diet, on: Boolean) = mutable.update { s ->
        s.copy(prefs = s.prefs.copy(diets = if (on) s.prefs.diets + diet else s.prefs.diets - diet))
    }

    /** Ticks or unticks [allergen]. */
    fun setAllergen(allergen: Allergen, on: Boolean) = mutable.update { s ->
        s.copy(prefs = s.prefs.copy(allergens = if (on) s.prefs.allergens + allergen else s.prefs.allergens - allergen))
    }

    /** Sets [field]'s target by hand to [value] (stored unit); 0 or less goes back to the recommended one. */
    fun setOverride(field: Field, value: Int) = mutable.update { s ->
        if (field !in NutrientTargets.EDITABLE) return@update s
        s.copy(overrides = if (value > 0) s.overrides + (field to value) else s.overrides - field)
    }

    /** "Reset to recommended" for [field]. */
    fun resetOverride(field: Field) = mutable.update { s -> s.copy(overrides = s.overrides - field) }

    /** Saves the prefs and the hand-typed targets, then calls [done]. */
    fun save(done: () -> Unit) {
        viewModelScope.launch {
            try {
                backend.setDietPrefs(mutable.value.prefs)
                backend.setTargetOverrides(mutable.value.overrides)
                done()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "save failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "DietVM"
    }
}

/** Me → Goals → Diet and allergens. */
class DietFragment : DetailFragment() {

    private val vm: DietViewModel by viewModels {
        val graph = requireContext().graph
        viewModelFactory { initializer { DietViewModel { GraphFoodBackend(graph.food) } } }
    }

    private val dietBoxes = LinkedHashMap<Diet, MaterialCheckBox>()
    private val allergenBoxes = LinkedHashMap<Allergen, MaterialCheckBox>()
    private lateinit var targetsBox: LinearLayout
    private var filled = false

    override fun title(): CharSequence = getString(R.string.diet_title)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val diets = Views.card(column, getString(R.string.diet_section), first = true)
        diets.addView(Views.caption(ctx, getString(R.string.diet_help)), Views.matchWrap(ctx))
        for (d in Diet.entries) {
            val box = checkBox(getString(NutrientUi.dietName(d))) { on -> vm.setDiet(d, on) }
            box.tag = "diet_${d.id}"
            dietBoxes[d] = box
            diets.addView(box, Views.matchWrap(ctx))
        }
        val allergens = Views.card(column, getString(R.string.diet_allergens))
        allergens.addView(Views.caption(ctx, getString(R.string.diet_allergens_help)), Views.matchWrap(ctx))
        for (a in Allergen.entries) {
            val box = checkBox(getString(NutrientUi.allergenName(a))) { on -> vm.setAllergen(a, on) }
            box.tag = "allergen_${a.name.lowercase()}"
            allergenBoxes[a] = box
            allergens.addView(box, Views.matchWrap(ctx))
        }
        val targets = Views.card(column, getString(R.string.diet_targets))
        targets.addView(Views.caption(ctx, getString(R.string.diet_targets_help)), Views.matchWrap(ctx))
        targetsBox = FoodViews.column(ctx)
        targets.addView(targetsBox, Views.matchWrap(ctx, 8))
        val save = MaterialButton(ctx).apply {
            text = getString(R.string.food_save)
            tag = "save"
            minHeight = resources.getDimensionPixelSize(UiR.dimen.sw_touch_target)
            setOnClickListener {
                val app = ctx.applicationContext
                vm.save {
                    Toast.makeText(app, R.string.diet_saved, Toast.LENGTH_SHORT).show()
                    close()
                }
            }
        }
        column.addView(save, Views.matchWrap(ctx, 16))
        filled = savedInstanceState != null
        collectWhileStarted(vm.state) { s ->
            if (s.loading) return@collectWhileStarted
            if (!filled) {
                filled = true
                for ((d, b) in dietBoxes) b.isChecked = d in s.prefs.diets
                for ((a, b) in allergenBoxes) b.isChecked = a in s.prefs.allergens
            }
            renderTargets(s)
        }
    }

    private fun checkBox(label: String, onChange: (Boolean) -> Unit): MaterialCheckBox =
        MaterialCheckBox(requireContext()).apply {
            text = label
            minHeight = resources.getDimensionPixelSize(UiR.dimen.sw_touch_target)
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }

    private fun renderTargets(s: DietUiState) {
        val ctx = requireContext()
        targetsBox.removeAllViews()
        val focus = NutrientTargets.focus(s.prefs)
        val order = (focus + listOf(Field.KCAL, Field.PROTEIN, Field.FAT, Field.CARB, Field.FIBER, Field.SODIUM, Field.SATFAT, Field.ADDSUGAR,
            Field.POTASSIUM, Field.CALCIUM, Field.IRON, Field.VITA, Field.VITC, Field.VITD)).distinct()
        val targets = s.targets
        val recommended = s.recommended
        for (f in order) {
            val editable = f in NutrientTargets.EDITABLE
            // Fat and carbs have no target until a goal or a hand-typed number sets one; offer them anyway.
            val t = targets[f] ?: if (editable) null else continue
            val custom = f in s.overrides
            val name = getString(NutrientUi.name(f))
            val col = FoodViews.column(ctx).apply {
                tag = "target_${f.name.lowercase()}"
                minimumHeight = resources.getDimensionPixelSize(UiR.dimen.sw_touch_target)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val row = FoodViews.row(ctx)
            row.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, name), FoodViews.weight())
            val value = t?.let { describe(f, it) } ?: getString(R.string.diet_target_none)
            row.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Body_Number, value).apply { textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_END }, FoodViews.weight())
            col.addView(row, FoodViews.matchWrap())
            if (custom) {
                val rec = recommended[f]?.let { describe(f, it) } ?: getString(R.string.diet_target_none)
                col.addView(Views.caption(ctx, getString(R.string.diet_target_custom, rec)), FoodViews.matchWrap())
            }
            if (editable) {
                val out = android.util.TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                col.setBackgroundResource(out.resourceId)
                col.isClickable = true
                col.isFocusable = true
                col.setOnClickListener { editTarget(f, t, custom) }
                col.contentDescription = getString(if (custom) R.string.diet_target_edit_custom_description else R.string.diet_target_edit_description, name, value)
            }
            targetsBox.addView(col, FoodViews.matchWrap())
        }
    }

    /** The hand-typed target dialog: a number in the field's unit, Save, and "Reset to recommended". */
    private fun editTarget(f: Field, current: Target?, custom: Boolean) {
        val ctx = requireContext()
        val isLimit = f in NutrientTargets.LIMITS || current?.limit != null
        val hint = getString(if (isLimit) R.string.diet_target_limit_hint else R.string.diet_target_goal_hint, f.unit)
        val layout = com.google.android.material.textfield.TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        layout.hint = hint
        val edit = com.google.android.material.textfield.TextInputEditText(layout.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            tag = "target_value"
            current?.let { NutrientTargets.editValue(it) }?.let { setText(NutrientUi.editText(f, it)) }
        }
        layout.addView(edit, FoodViews.matchWrap())
        val pad = resources.getDimensionPixelSize(UiR.dimen.sw_space_2)
        val box = android.widget.FrameLayout(ctx).apply { setPaddingRelative(pad, pad / 2, pad, 0) }
        box.addView(layout)
        val b = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.diet_target_edit_title, getString(NutrientUi.name(f))))
            .setView(box)
            .setPositiveButton(R.string.food_save) { _, _ ->
                val v = NutrientUi.parse(f, edit.text?.toString().orEmpty())
                if (v != null && v > 0) vm.setOverride(f, v) else vm.resetOverride(f)
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (custom) b.setNeutralButton(R.string.diet_target_reset) { _, _ -> vm.resetOverride(f) }
        b.show()
    }

    private fun describe(f: Field, t: Target): String {
        val ctx = requireContext()
        val limit = t.limit
        val caution = t.caution
        val low = t.low
        val high = t.high
        return when {
            limit != null && caution != null -> getString(R.string.diet_target_amber, NutrientUi.amount(ctx, f, caution), NutrientUi.amount(ctx, f, limit))
            limit != null -> getString(R.string.diet_target_under, NutrientUi.amount(ctx, f, limit))
            low != null && high != null -> getString(R.string.diet_target_range, NutrientUi.number(f, low), NutrientUi.amount(ctx, f, high))
            low != null -> getString(R.string.diet_target_at_least, NutrientUi.amount(ctx, f, low))
            else -> getString(R.string.dash)
        }
    }
}
