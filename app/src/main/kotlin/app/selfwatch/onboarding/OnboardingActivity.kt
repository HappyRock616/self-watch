/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.selfwatch.R
import app.selfwatch.ble.pair.PairFilter
import app.selfwatch.cards.MainGoal
import app.selfwatch.cards.MainGoalPicker
import app.selfwatch.cards.MainGoalSwitchDialog
import app.selfwatch.ble.pair.Pairing
import app.selfwatch.data.ImportActivity
import app.selfwatch.devices.AppDeviceActions
import app.selfwatch.devices.DevicesViewModel
import app.selfwatch.food.FoodViews
import app.selfwatch.graph
import app.selfwatch.me.FoodDataActivity
import app.selfwatch.me.MeScreenActivity
import app.selfwatch.me.MeViews
import app.selfwatch.me.SwitchRow
import app.selfwatch.model.food.Profile
import app.selfwatch.model.food.Sex
import app.selfwatch.model.food.UnitSystem
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.service.device.WatchStatus
import app.selfwatch.ui.format.Units
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import app.selfwatch.ui.R as UiR

/**
 * Onboarding (C21, APP_LAYOUT §2): 1 welcome and promise, 2 main goal and goals, 3 profile, 4 pair watch, 5 bring
 * your history, 6 permissions (only what's needed, each with its reason, asked when the user taps),
 * 7 food data. Every step can be skipped, and "Skip setup" ends it at once. Shown on first launch
 * until `onboarding.done` is set.
 */
class OnboardingActivity : MeScreenActivity() {

    private val vm: OnboardingViewModel by viewModels { OnboardingViewModel.factory(graph) }
    private val devices: DevicesViewModel by viewModels { DevicesViewModel.factory(graph) }
    private val pairing: Pairing by lazy { Pairing(this) }

    private var permissionWait: CompletableDeferred<Boolean>? = null
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionWait?.complete(granted)
        permissionWait = null
    }

    private var shownStep: OnboardingStep? = null
    private var readForm: (() -> Unit)? = null
    private var pairStatus: TextView? = null
    private var notifyStatus: TextView? = null

    private lateinit var back: com.google.android.material.button.MaterialButton
    private lateinit var skip: com.google.android.material.button.MaterialButton
    private lateinit var next: com.google.android.material.button.MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bar = FoodViews.row(this).apply {
            val pad = resources.getDimensionPixelSize(UiR.dimen.sw_space_2)
            setPaddingRelative(pad, pad / 2, pad, pad / 2)
        }
        back = FoodViews.textButton(this, getString(R.string.onboarding_back)) { vm.back() }
        skip = FoodViews.textButton(this, getString(R.string.onboarding_skip)) { vm.next() }
        next = FoodViews.filledButton(this, getString(R.string.onboarding_next)) { onNext() }
        bar.addView(back, FoodViews.weight())
        bar.addView(skip, FoodViews.weight())
        bar.addView(next, FoodViews.weight(WEIGHT_NEXT))
        buildScreen(R.string.onboarding_title, bar)
        toolbar.navigationIcon = null
        toolbar.menu.add(0, MENU_SKIP_ALL, 0, R.string.onboarding_skip_all)
        toolbar.setOnMenuItemClickListener {
            vm.finish()
            true
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!vm.back()) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )

        collect(vm.state) { s ->
            if (s.finished) {
                finish()
                return@collect
            }
            toolbar.title = getString(R.string.onboarding_step, s.number, OnboardingStep.entries.size)
            back.visibility = if (s.step == OnboardingStep.WELCOME) android.view.View.INVISIBLE else android.view.View.VISIBLE
            skip.visibility = if (s.step == OnboardingStep.WELCOME || s.isLast) android.view.View.INVISIBLE else android.view.View.VISIBLE
            next.text = getString(
                when {
                    s.step == OnboardingStep.WELCOME -> R.string.onboarding_start
                    s.isLast -> R.string.onboarding_done
                    else -> R.string.onboarding_next
                },
            )
            if (shownStep != s.step) {
                shownStep = s.step
                render(s)
            }
        }
        collect(devices.state) { d ->
            pairStatus?.text = d.watch?.let { getString(R.string.onboarding_paired, it.name) } ?: getString(R.string.onboarding_not_paired)
        }
    }

    override fun onResume() {
        super.onResume()
        updateNotifyStatus()
    }

    private fun onNext() {
        val f = readForm
        if (f != null) f() else vm.next()
    }

    private fun render(s: OnboardingUiState) {
        column.removeAllViews()
        readForm = null
        pairStatus = null
        notifyStatus = null
        when (s.step) {
            OnboardingStep.WELCOME -> welcome()
            OnboardingStep.PROFILE -> profile(s.profile)
            OnboardingStep.GOALS -> goals(s)
            OnboardingStep.PAIR -> pair()
            OnboardingStep.IMPORT -> history()
            OnboardingStep.PERMISSIONS -> permissions()
            OnboardingStep.FOOD -> food(s)
        }
        androidx.core.view.ViewCompat.setAccessibilityPaneTitle(column, toolbar.title)
    }

    // ---- Steps ------------------------------------------------------------------------------------

    private fun welcome() {
        MeViews.add(column, FoodViews.text(this, UiR.style.TextAppearance_SelfWatch_Display, getString(R.string.app_name)).also {
            androidx.core.view.ViewCompat.setAccessibilityHeading(it, true)
        })
        MeViews.add(column, FoodViews.text(this, UiR.style.TextAppearance_SelfWatch_Title, getString(R.string.onboarding_slogan)))
        val promise = MeViews.card(column, getString(R.string.privacy_promise_title))
        for (line in resources.getStringArray(R.array.privacy_promises)) MeViews.add(promise.content, MeViews.body(this, "• $line"))
        for (line in resources.getStringArray(R.array.onboarding_more_slogans)) MeViews.add(column, MeViews.caption(this, line))
    }

    private fun profile(p: Profile) {
        heading(R.string.onboarding_profile_title, R.string.onboarding_profile_help)
        val imperial = isImperial(p.units)
        val units = radios(
            R.string.settings_units,
            listOf(R.string.settings_units_phone, R.string.settings_units_metric, R.string.settings_units_imperial),
            UNIT_ORDER.indexOf(p.units),
        )
        val height = field(if (imperial) R.string.onboarding_height_in else R.string.onboarding_height_cm, number = true)
        p.heightCm?.let { height.setText(numText(if (imperial) (it / CM_PER_IN).roundToInt() else it)) }
        val year = field(R.string.onboarding_birth_year, number = true)
        p.birthYear?.let { year.setText(numText(it)) }
        val sex = radios(R.string.onboarding_sex, listOf(R.string.onboarding_sex_female, R.string.onboarding_sex_male, R.string.onboarding_sex_unspecified),
            listOf(Sex.FEMALE, Sex.MALE, Sex.UNSPECIFIED).indexOf(p.sex))
        MeViews.add(column, MeViews.caption(this, getString(R.string.onboarding_sex_help)))
        readForm = {
            val unitSystem = UNIT_ORDER.getOrElse(units.checkedIndex()) { UnitSystem.FOLLOW_PHONE }
            val heightValue = height.text?.toString()?.trim()?.toDoubleOrNull()
            val cm = heightValue?.let { if (imperial) (it * CM_PER_IN).roundToInt() else it.roundToInt() }
            vm.saveProfile(
                Profile(
                    heightCm = cm,
                    birthYear = year.text?.toString()?.trim()?.toIntOrNull(),
                    sex = listOf(Sex.FEMALE, Sex.MALE, Sex.UNSPECIFIED).getOrElse(sex.checkedIndex()) { Sex.UNSPECIFIED },
                    units = unitSystem,
                ),
            )
        }
    }

    private fun goals(s: OnboardingUiState) {
        heading(R.string.main_goal_title, R.string.main_goal_help)
        var showProtein: (MainGoal) -> Unit = {}
        val picker = MainGoalPicker(this) { goal ->
            vm.pickMainGoal(goal)
            showProtein(goal)
        }
        picker.select(s.mainGoal)
        MeViews.add(column, picker.view)
        heading(R.string.onboarding_goals_title, R.string.onboarding_goals_help)
        val imperial = isImperial(s.profile.units)
        val proteinLayout = FoodViews.field(this, R.string.onboarding_goal_protein, InputType.TYPE_CLASS_NUMBER)
        MeViews.add(column, proteinLayout.first)
        val protein = proteinLayout.second
        s.goals.proteinG?.let { protein.setText(numText(it)) }
        showProtein = { goal -> proteinLayout.first.visibility = if (goal.asksProtein) View.VISIBLE else View.GONE }
        showProtein(s.mainGoal)
        val kcal = field(R.string.onboarding_goal_kcal, number = true)
        val steps = field(R.string.onboarding_goal_steps, number = true)
        val weight = field(if (imperial) R.string.onboarding_goal_weight_lb else R.string.onboarding_goal_weight_kg, number = true)
        val water = field(if (imperial) R.string.onboarding_goal_water_oz else R.string.onboarding_goal_water_ml, number = true)
        s.goals.kcalIn?.let { kcal.setText(numText(it)) }
        s.goals.steps?.let { steps.setText(numText(it)) }
        MeViews.add(column, MeViews.caption(this, getString(R.string.onboarding_goals_later)))
        readForm = {
            fun num(e: TextInputEditText): Double? = e.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()
            val starter = StarterGoals(
                kcalIn = num(kcal)?.roundToInt(),
                steps = num(steps)?.roundToInt(),
                weightKgX100 = num(weight)?.let { (if (imperial) it * KG_PER_LB else it) * HUNDRED }?.roundToInt(),
                waterMl = num(water)?.let { if (imperial) it * ML_PER_OZ else it }?.roundToInt(),
                proteinG = if (picker.selected?.asksProtein == true) num(protein)?.roundToInt() else null,
            )
            // First setup: the goal's cards are applied on every tab. With cards of their own already
            // (setup run again), one question covers all tabs.
            if (vm.asksToSwitchCards) {
                MainGoalSwitchDialog.ask(this, vm.state.value.mainGoal) { switch -> vm.saveGoals(starter, switchCards = switch) }
            } else {
                vm.saveGoals(starter)
            }
        }
    }

    private fun pair() {
        heading(R.string.onboarding_pair_title, R.string.onboarding_pair_help)
        MeViews.add(column, MeViews.caption(this, getString(R.string.onboarding_pair_garmin_connect)))
        val status = MeViews.body(this, getString(R.string.onboarding_not_paired))
        MeViews.add(column, status)
        pairStatus = status
        MeViews.add(column, FoodViews.filledButton(this, getString(R.string.onboarding_pair_button)) { startPairing() })
    }

    private fun history() {
        heading(R.string.onboarding_import_title, R.string.onboarding_import_help)
        MeViews.add(column, MeViews.caption(this, getString(R.string.import_formats)))
        MeViews.add(column, FoodViews.filledButton(this, getString(R.string.import_choose)) { startActivity(ImportActivity.intent(this, pick = true)) })
    }

    private fun permissions() {
        heading(R.string.onboarding_permissions_title, R.string.onboarding_permissions_help)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val card = MeViews.card(column, getString(R.string.perm_notifications))
            MeViews.add(card.content, MeViews.body(this, getString(R.string.devices_notify_reason)), gap = false)
            val st = MeViews.caption(this, null)
            notifyStatus = st
            MeViews.add(card.content, st)
            MeViews.add(card.content, FoodViews.outlinedButton(this, getString(R.string.onboarding_allow)) {
                lifecycleScope.launch {
                    request(Manifest.permission.POST_NOTIFICATIONS)
                    updateNotifyStatus()
                }
            })
            updateNotifyStatus()
        }
        val later = MeViews.card(column, getString(R.string.onboarding_permissions_later_title))
        for (line in resources.getStringArray(R.array.onboarding_later_permissions)) MeViews.add(later.content, MeViews.body(this, "• $line"))
    }

    private fun food(s: OnboardingUiState) {
        heading(R.string.onboarding_food_title, R.string.onboarding_food_help)
        val sw = SwitchRow(this, getString(R.string.settings_food_tracking), getString(R.string.settings_food_tracking_help))
        sw.checked = s.foodEnabled
        sw.onChange = { vm.setFoodEnabled(it) }
        MeViews.add(column, sw)
        MeViews.add(column, FoodViews.outlinedButton(this, getString(R.string.onboarding_food_get)) { startActivity(Intent(this, FoodDataActivity::class.java)) })
        MeViews.add(column, MeViews.caption(this, getString(R.string.fooddata_attribution)))
    }

    // ---- Pairing (same flow as Devices) -------------------------------------------------------------

    private fun startPairing() {
        lifecycleScope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
                if (!askReason(R.string.devices_bt_reason) || !request(Manifest.permission.BLUETOOTH_CONNECT)) {
                    toast(getString(R.string.devices_bt_denied))
                    return@launch
                }
            }
            when (val r = pairing.pair(PairFilter.GARMIN, bond = false)) {
                is Outcome.Ok -> {
                    devices.savePaired(r.value)
                    toast(getString(R.string.devices_paired, r.value.name ?: AppDeviceActions.UNNAMED))
                    if (!devices.startService()) toast(getString(R.string.devices_service_refused))
                }
                is Outcome.Fail -> {
                    SwLog.i(TAG, "pairing ended: ${r.error}")
                    toast(getString(R.string.devices_pair_failed, WatchStatus.reason(r.error)))
                }
            }
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private suspend fun request(permission: String): Boolean {
        if (granted(permission)) return true
        val wait = CompletableDeferred<Boolean>()
        permissionWait = wait
        permissionLauncher.launch(permission)
        return wait.await()
    }

    private suspend fun askReason(@StringRes reason: Int): Boolean = suspendCancellableCoroutine { cont ->
        val dialog = MaterialAlertDialogBuilder(this)
            .setMessage(reason)
            .setPositiveButton(R.string.devices_continue) { _, _ -> if (cont.isActive) cont.resume(true) }
            .setNegativeButton(R.string.devices_not_now) { _, _ -> if (cont.isActive) cont.resume(false) }
            .setOnCancelListener { if (cont.isActive) cont.resume(false) }
            .show()
        cont.invokeOnCancellation { dialog.dismiss() }
    }

    private fun updateNotifyStatus() {
        val st = notifyStatus ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        st.text = getString(if (granted(Manifest.permission.POST_NOTIFICATIONS)) R.string.privacy_allowed else R.string.privacy_not_allowed)
    }

    // ---- Form helpers -----------------------------------------------------------------------------

    private fun heading(@StringRes title: Int, @StringRes help: Int) {
        MeViews.add(column, FoodViews.text(this, UiR.style.TextAppearance_SelfWatch_Title, getString(title)).also {
            androidx.core.view.ViewCompat.setAccessibilityHeading(it, true)
        })
        MeViews.add(column, MeViews.body(this, getString(help)))
    }

    private fun field(@StringRes hint: Int, number: Boolean): TextInputEditText {
        val type = if (number) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT
        val (layout, edit) = FoodViews.field(this, hint, type)
        MeViews.add(column, layout)
        return edit
    }

    private fun radios(@StringRes label: Int, options: List<Int>, selected: Int): RadioGroup {
        MeViews.add(column, MeViews.caption(this, getString(label)))
        val group = RadioGroup(this)
        options.forEachIndexed { i, res ->
            group.addView(
                RadioButton(this).apply {
                    id = RADIO_BASE + i
                    text = getString(res)
                    minHeight = resources.getDimensionPixelSize(UiR.dimen.sw_touch_target)
                    minimumHeight = minHeight
                },
                FoodViews.matchWrap(),
            )
        }
        group.check(RADIO_BASE + selected.coerceAtLeast(0))
        MeViews.add(column, group, gap = false)
        return group
    }

    private fun numText(n: Int): String = String.format(Locale.getDefault(), "%d", n)

    private fun RadioGroup.checkedIndex(): Int = (checkedRadioButtonId - RADIO_BASE).coerceAtLeast(0)

    private fun isImperial(u: UnitSystem): Boolean = when (u) {
        UnitSystem.IMPERIAL -> true
        UnitSystem.METRIC -> false
        UnitSystem.FOLLOW_PHONE -> Units.forLocale(Locale.getDefault()) == Units.IMPERIAL
    }

    private companion object {
        const val TAG = "OnboardingActivity"
        const val MENU_SKIP_ALL = 1
        const val RADIO_BASE = 10
        const val WEIGHT_NEXT = 1.4f
        const val CM_PER_IN = 2.54
        const val KG_PER_LB = 0.45359237
        const val ML_PER_OZ = 29.5735
        const val HUNDRED = 100.0
        val UNIT_ORDER = listOf(UnitSystem.FOLLOW_PHONE, UnitSystem.METRIC, UnitSystem.IMPERIAL)
    }
}
