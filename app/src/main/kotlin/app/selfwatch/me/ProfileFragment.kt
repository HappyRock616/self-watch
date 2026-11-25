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
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.AppGraph
import app.selfwatch.R
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.graph
import app.selfwatch.model.food.Profile
import app.selfwatch.model.food.Sex
import app.selfwatch.model.food.UnitSystem
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.DisplayUnits
import app.selfwatch.shell.Views
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/** Pure profile-form rules. Height is stored in centimetres and shown as cm, or feet and inches. */
object ProfileForm {
    private const val CM_PER_INCH = 2.54
    private const val INCHES_PER_FOOT = 12

    /**
     * Height typed in [units] to centimetres, or null when not plausible. Metric: [main] is centimetres
     * ([inches] is ignored). Imperial: [main] is feet and [inches] inches (either may be empty, not both).
     */
    fun heightCm(main: String, inches: String, units: Units): Int? {
        val cm = if (units == Units.METRIC) {
            (Fmt.parseX100(main) ?: return null) / 100.0
        } else {
            if (main.isBlank() && inches.isBlank()) return null
            val ft = if (main.isBlank()) 0L else Fmt.parseX100(main) ?: return null
            val inch = if (inches.isBlank()) 0L else Fmt.parseX100(inches) ?: return null
            if (ft < 0 || inch < 0) return null
            (ft / 100.0 * INCHES_PER_FOOT + inch / 100.0) * CM_PER_INCH
        }
        return cm.roundToInt().takeIf { it in 50..272 }
    }

    /** Centimetres as the texts shown in [units]: (cm, "") or (feet, whole inches). */
    fun heightFields(cm: Int, units: Units): Pair<String, String> {
        if (units == Units.METRIC) return cm.toString() to ""
        val totalIn = (cm / CM_PER_INCH).roundToInt()
        return (totalIn / INCHES_PER_FOOT).toString() to (totalIn % INCHES_PER_FOOT).toString()
    }

    /** A plausible birth year (age 5 to 120 in [thisYear]), or null. */
    fun birthYear(text: String, thisYear: Int): Int? = text.trim().toIntOrNull()?.takeIf { it in thisYear - 120..thisYear - 5 }
}

/** Profile screen state. */
data class ProfileUiState(val profile: Profile? = null)

/** Profile view model: reads and saves `profile.*` and `units`. */
class ProfileViewModel(private val db: SelfWatchDb) : ViewModel() {
    private val stateFlow = MutableStateFlow(ProfileUiState())

    /** Screen state. */
    val state: StateFlow<ProfileUiState> = stateFlow.asStateFlow()

    init {
        viewModelScope.launch { stateFlow.value = ProfileUiState(db.settings.profile()) }
    }

    /** Saves [p]; [done] runs after the write. */
    fun save(p: Profile, done: () -> Unit) {
        viewModelScope.launch {
            db.settings.setProfile(p)
            stateFlow.value = ProfileUiState(p)
            done()
        }
    }

    /** Factory. */
    companion object {
        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProfileViewModel(graph.db) }
        }
    }
}

/** Profile (C13): height, birth year, sex (for the resting-calorie estimate) and units. */
class ProfileFragment : DetailFragment() {

    private val vm: ProfileViewModel by viewModels { ProfileViewModel.factory(requireContext().graph) }

    override fun title(): CharSequence = getString(R.string.me_profile)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val card = Views.card(column, null, first = true)
        // Height: one field in cm, or two (feet and inches) in imperial units.
        val heightRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val heightLayout = TextInputLayout(ctx).apply { hint = getString(R.string.profile_height) }
        val height = TextInputEditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            id = View.generateViewId()
        }
        heightLayout.addView(height)
        val inchLayout = TextInputLayout(ctx).apply { suffixText = getString(R.string.profile_unit_in) }
        val inches = TextInputEditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            id = View.generateViewId()
            contentDescription = getString(R.string.profile_height_inches)
        }
        inchLayout.addView(inches)
        heightRow.addView(heightLayout, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        heightRow.addView(
            inchLayout,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (8 * ctx.resources.displayMetrics.density).roundToInt()
            },
        )
        card.addView(heightRow, Views.matchWrap(ctx))
        val yearLayout = TextInputLayout(ctx).apply { hint = getString(R.string.profile_birth_year) }
        val year = TextInputEditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            id = View.generateViewId()
        }
        yearLayout.addView(year)
        card.addView(yearLayout, Views.matchWrap(ctx, 8))

        card.addView(Views.caption(ctx, getString(R.string.profile_sex)), Views.matchWrap(ctx, 16))
        val sexIds = Sex.entries.associateWith { View.generateViewId() }
        val sexGroup = RadioGroup(ctx).apply {
            for (s in Sex.entries) {
                addView(
                    RadioButton(ctx).apply {
                        id = sexIds.getValue(s)
                        text = getString(
                            when (s) {
                                Sex.FEMALE -> R.string.profile_female
                                Sex.MALE -> R.string.profile_male
                                Sex.UNSPECIFIED -> R.string.profile_unspecified
                            },
                        )
                        minHeight = Views.dp(ctx, 48)
                    },
                )
            }
        }
        card.addView(sexGroup, Views.matchWrap(ctx))

        card.addView(Views.caption(ctx, getString(R.string.profile_units)), Views.matchWrap(ctx, 16))
        val unitIds = UnitSystem.entries.associateWith { View.generateViewId() }
        val unitGroup = RadioGroup(ctx).apply {
            for (u in UnitSystem.entries) {
                addView(
                    RadioButton(ctx).apply {
                        id = unitIds.getValue(u)
                        text = getString(
                            when (u) {
                                UnitSystem.FOLLOW_PHONE -> R.string.profile_units_phone
                                UnitSystem.METRIC -> R.string.profile_units_metric
                                UnitSystem.IMPERIAL -> R.string.profile_units_imperial
                            },
                        )
                        minHeight = Views.dp(ctx, 48)
                    },
                )
            }
        }
        card.addView(unitGroup, Views.matchWrap(ctx))
        column.addView(Views.caption(ctx, getString(R.string.profile_note)), Views.matchWrap(ctx, 8))
        val save = MaterialButton(ctx).apply { text = getString(R.string.measure_save) }
        column.addView(save, Views.matchWrap(ctx, 16))

        var shownUnits = Units.METRIC
        var filled = savedInstanceState != null
        collectWhileStarted(vm.state) { s ->
            val p = s.profile ?: return@collectWhileStarted
            shownUnits = DisplayUnits.of(p.units)
            val metric = shownUnits == Units.METRIC
            heightLayout.suffixText = getString(if (metric) R.string.profile_unit_cm else R.string.profile_unit_ft)
            inchLayout.visibility = if (metric) View.GONE else View.VISIBLE
            if (!filled) {
                val texts = p.heightCm?.let { ProfileForm.heightFields(it, shownUnits) }
                height.setText(texts?.first ?: "")
                inches.setText(texts?.second ?: "")
                year.setText(p.birthYear?.toString() ?: "")
                sexGroup.check(sexIds.getValue(p.sex))
                unitGroup.check(unitIds.getValue(p.units))
                filled = true
            }
        }
        save.setOnClickListener {
            val hText = height.text?.toString().orEmpty().trim()
            val inText = if (shownUnits == Units.METRIC) "" else inches.text?.toString().orEmpty().trim()
            val yText = year.text?.toString().orEmpty().trim()
            val hasHeight = hText.isNotEmpty() || inText.isNotEmpty()
            val h = if (!hasHeight) null else ProfileForm.heightCm(hText, inText, shownUnits)
            val y = if (yText.isEmpty()) null else ProfileForm.birthYear(yText, LocalDate.now().year)
            heightLayout.error = if (hasHeight && h == null) getString(R.string.measure_invalid) else null
            yearLayout.error = if (yText.isNotEmpty() && y == null) getString(R.string.measure_invalid) else null
            if (heightLayout.error != null || yearLayout.error != null) return@setOnClickListener
            val sex = sexIds.entries.firstOrNull { it.value == sexGroup.checkedRadioButtonId }?.key ?: Sex.UNSPECIFIED
            val units = unitIds.entries.firstOrNull { it.value == unitGroup.checkedRadioButtonId }?.key ?: UnitSystem.FOLLOW_PHONE
            val app = requireContext().applicationContext
            vm.save(Profile(h, y, sex, units)) {
                Toast.makeText(app, R.string.profile_saved, Toast.LENGTH_SHORT).show()
                close()
            }
        }
    }
}
