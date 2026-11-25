/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isEmpty
import androidx.core.view.isNotEmpty
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.R
import app.selfwatch.food.nutrition.NutrientMath
import app.selfwatch.food.nutrition.NutrientTargets
import app.selfwatch.graph
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Nutrients.Field
import app.selfwatch.model.food.Serving
import app.selfwatch.model.time.Day
import app.selfwatch.ui.components.SwSheet
import app.selfwatch.ui.format.Fmt
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import app.selfwatch.ui.R as UiR

/** A screen that hosts the food detail sheet and wants to know when something was logged. */
interface FoodLoggedListener {
    /** [name] was logged as entry [entryId]. */
    fun onFoodLogged(name: String, entryId: Long)
}

/** A screen that deletes diary entries with Undo (the diary). */
interface EntryDeleteHost {
    /** Deletes [entry] and offers Undo. */
    fun deleteEntry(entry: DiaryEntry)
}

/**
 * Food detail (FOOD_UI_PLAN §4.1). Top to bottom: the food's name as the title; brand · source · year
 * and a completeness note; the diet line (badges and "Contains: …", red when it clashes with the
 * user's diet; tap for "check the label"); Serving size (one row: named servings, then 100 g, g, oz);
 * Number of servings with quick ½ 1 1½ 2; Meal (one row, preselected); calories with the macro split;
 * "% of your day" bars; the full nutrition facts (folded); Add to <meal>. The same sheet edits a
 * diary entry (amount, serving, meal, delete).
 */
class FoodDetailSheet : SwSheet() {

    private val target: FoodDetailTarget by lazy { targetOf(requireArguments()) }

    private val vm: FoodDetailViewModel by viewModels {
        val graph = requireContext().graph
        viewModelFactory { initializer { FoodDetailViewModel(target) { GraphFoodBackend(graph.food) } } }
    }

    private lateinit var body: LinearLayout
    private var builtFor: Any? = null
    private var servingValue: TextView? = null
    private var mealValue: TextView? = null
    private var amountLayout: TextInputLayout? = null
    private var amountEdit: TextInputEditText? = null
    private var quickRow: View? = null
    private var nutrition: LinearLayout? = null
    private var saveButton: MaterialButton? = null
    private var favButton: MaterialButton? = null
    private var settingAmount = false
    private var factsOpen = false
    private var lastNutritionKey: Any? = null

    override val sheetTitle: CharSequence
        get() = getString(if (target is FoodDetailTarget.Edit) R.string.food_edit_entry else R.string.food_detail_title)

    override fun onCreateSheetContent(inflater: LayoutInflater, container: ViewGroup?): View {
        body = FoodViews.column(inflater.context)
        return body
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        factsOpen = savedInstanceState?.getBoolean(KEY_FACTS) ?: false
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.state.collect { render(it) } }
                launch {
                    vm.events.collect { e ->
                        when (e) {
                            is FoodDetailEvent.Saved -> {
                                if (e.added) (activity as? FoodLoggedListener)?.onFoodLogged(e.name, e.entryId)
                                dismissAllowingStateLoss()
                            }
                            FoodDetailEvent.Failed -> android.widget.Toast.makeText(requireContext(), R.string.food_failed, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_FACTS, factsOpen)
    }

    private fun render(s: FoodDetailState) {
        if (s.loading) return
        // Build once per loaded food/entry; later changes only update values.
        val key = Pair(s.food?.ref, s.entry?.id)
        if (builtFor != key || body.isEmpty()) {
            builtFor = key
            lastNutritionKey = null
            build(s)
        }
        if (s.missing) return
        val gram = s.serving == NutrientMath.GRAM
        servingValue?.text = s.serving?.let { servingLabel(it) }.orEmpty()
        amountLayout?.hint = getString(if (gram) R.string.detail_grams_count else R.string.detail_servings_count)
        quickRow?.let { FoodViews.show(it, !gram) }
        amountEdit?.let { edit ->
            // Mirror the amount into the field unless the user is mid-way through typing a new one.
            val typed = Fmt.parseX100(edit.text?.toString().orEmpty())
            if (s.amountX100 > 0 && typed != s.amountX100.toLong()) {
                settingAmount = true
                amountLayout?.error = null
                edit.setText(Fmt.x100(s.amountX100.toLong(), 2))
                edit.setSelection(edit.text?.length ?: 0)
                settingAmount = false
            }
        }
        val slotName = s.slots.firstOrNull { it.id == s.slotId }?.name.orEmpty().ifBlank { getString(R.string.food_other_meal) }
        mealValue?.text = slotName
        val nKey = Triple(s.preview, s.targets, factsOpen)
        if (nKey != lastNutritionKey && (s.amountX100 > 0 || lastNutritionKey == null)) {
            lastNutritionKey = nKey
            nutrition?.let { renderNutrition(it, s) }
        }
        saveButton?.isEnabled = s.amountX100 > 0 && (s.preview != null || s.isEdit)
        favButton?.let {
            it.setIconResource(if (s.favourite) R.drawable.ic_star else R.drawable.ic_star_outline)
            it.contentDescription = getString(if (s.favourite) R.string.food_unfavourite else R.string.food_favourite)
        }
        saveButton?.text = if (s.isEdit) getString(R.string.food_save) else getString(R.string.food_add_to, slotName)
    }

    private fun build(s: FoodDetailState) {
        val ctx = requireContext()
        body.removeAllViews()
        val gap = resources.getDimensionPixelSize(UiR.dimen.sw_space_2)
        if (s.missing) {
            body.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, getString(R.string.food_missing)))
            return
        }
        val food = s.food
        updateSheetTitle(food?.name ?: s.entry?.name.orEmpty())

        // Brand · source · year, completeness, favorite.
        val head = FoodViews.row(ctx)
        val names = FoodViews.column(ctx)
        val source = food?.let { f ->
            listOfNotNull(f.brand?.takeIf { it.isNotBlank() }, f.sourceLabel.takeIf { it.isNotBlank() }, f.dataYear?.let { getString(R.string.food_data_from, it) })
                .joinToString(" · ")
        } ?: s.entry?.brand
        if (!source.isNullOrBlank()) names.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, source))
        completeness(food)?.let { names.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, it).apply { tag = "completeness" }) }
        head.addView(names, FoodViews.weight())
        if (food != null) {
            favButton = FoodViews.iconButton(ctx, R.drawable.ic_star_outline, getString(R.string.food_favourite)) { vm.toggleFavourite() }
            head.addView(favButton)
        }
        body.addView(head, FoodViews.matchWrap())

        // Diet line: only what is known; red when it clashes with the user's diet.
        if (food != null) {
            val badges = NutrientUi.badges(ctx, food.dietInfo, s.conflicts)
            if (badges.isNotEmpty()) {
                val group = ChipGroup(ctx).apply {
                    chipSpacingHorizontal = FoodViews.dp(ctx, 6f)
                    chipSpacingVertical = FoodViews.dp(ctx, 4f)
                    tag = "diet"
                    isClickable = true
                    isFocusable = true
                    minimumHeight = resources.getDimensionPixelSize(UiR.dimen.sw_touch_target)
                    setOnClickListener { showCheckLabel() }
                    contentDescription = badges.joinToString(", ") { it.text } + ". " + getString(R.string.diet_check_label)
                }
                for (b in badges) {
                    group.addView(
                        DietBadgeView(ctx).apply {
                            text = b.text
                            conflict = b.conflict
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            setOnClickListener { showCheckLabel() }
                        },
                    )
                }
                body.addView(group, FoodViews.matchWrap().apply { topMargin = gap / 2 })
            }
        }

        // Serving size: one row that opens the list.
        val (servingRow, servingText) = pickerRow(getString(R.string.detail_serving_size)) { pickServing() }
        servingRow.tag = "serving"
        servingValue = servingText
        body.addView(servingRow, FoodViews.matchWrap().apply { topMargin = gap })

        // Number of servings with quick picks.
        val (layout, edit) = FoodViews.numberField(ctx, R.string.detail_servings_count)
        edit.tag = "amount"
        edit.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(e: Editable?) {
                    if (settingAmount) return
                    val v = Fmt.parseX100(e?.toString().orEmpty())
                    if (v == null || v <= 0 || v > Int.MAX_VALUE) {
                        layout.error = getString(R.string.detail_bad_amount)
                        vm.setAmount(0)
                    } else {
                        layout.error = null
                        vm.setAmount(v.toInt())
                    }
                }
            },
        )
        amountLayout = layout
        amountEdit = edit
        body.addView(layout, FoodViews.matchWrap().apply { topMargin = gap / 2 })
        val quick = FoodViews.row(ctx)
        val picks = listOf(
            R.string.detail_quick_half to HALF_X100,
            R.string.detail_quick_one to FoodRows.ONE_X100,
            R.string.detail_quick_one_half to ONE_HALF_X100,
            R.string.detail_quick_two to TWO_X100,
        )
        picks.forEachIndexed { i, (label, x100) ->
            val b = FoodViews.outlinedButton(ctx, getString(label)) { vm.setAmount(x100) }.apply {
                contentDescription = getString(R.string.detail_quick_description, Fmt.x100(x100.toLong()))
                minWidth = 0
                minimumWidth = 0
            }
            quick.addView(b, FoodViews.weight().apply { if (i > 0) marginStart = FoodViews.dp(ctx, 8f) })
        }
        quickRow = quick
        body.addView(quick, FoodViews.matchWrap())

        // Meal: one row, preselected.
        val (mealRow, mealText) = pickerRow(getString(R.string.food_meal)) { pickMeal() }
        mealRow.tag = "meal"
        mealValue = mealText
        body.addView(mealRow, FoodViews.matchWrap().apply { topMargin = gap / 2 })

        // Calories, macro split, % of your day and the nutrition facts (redrawn when the amount changes).
        val n = FoodViews.column(ctx)
        nutrition = n
        body.addView(n, FoodViews.matchWrap().apply { topMargin = gap })

        val save = FoodViews.filledButton(ctx, getString(R.string.food_save)) { vm.save() }.apply { tag = "save" }
        saveButton = save
        body.addView(save, FoodViews.matchWrap().apply { topMargin = gap })

        val more = FoodViews.row(ctx)
        if (food != null && food.ref.origin != FoodOrigin.RECIPE) {
            more.addView(
                FoodViews.textButton(ctx, getString(if (food.ref.origin == FoodOrigin.MY_FOOD) R.string.food_edit_custom else R.string.food_edit_as_custom)) {
                    startActivity(FoodEditorActivity.intent(ctx, food.ref))
                    dismissAllowingStateLoss()
                },
            )
        }
        val entry = s.entry
        if (entry != null) {
            more.addView(
                FoodViews.textButton(ctx, getString(R.string.food_delete)) {
                    (parentFragment as? EntryDeleteHost ?: activity as? EntryDeleteHost)?.deleteEntry(entry)
                    dismissAllowingStateLoss()
                }.apply { tag = "delete" },
            )
        }
        if (more.isNotEmpty()) body.addView(more, FoodViews.matchWrap())
    }

    /** A 48 dp row: a caption label above the value, with a drop-down mark; tapping it calls [onClick]. */
    private fun pickerRow(label: String, onClick: () -> Unit): Pair<View, TextView> {
        val ctx = requireContext()
        val row = FoodViews.row(ctx).apply {
            minimumHeight = resources.getDimensionPixelSize(UiR.dimen.sw_touch_target)
            isClickable = true
            isFocusable = true
            val out = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
            setBackgroundResource(out.resourceId)
        }
        val col = FoodViews.column(ctx)
        col.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, label))
        val value = FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_BodyLarge)
        col.addView(value)
        row.addView(col, FoodViews.weight())
        row.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_BodyLarge, "▾").apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        row.setOnClickListener { onClick() }
        ViewCompat.setAccessibilityDelegate(
            row,
            object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.contentDescription = getString(R.string.detail_row_value_description, label, value.text)
                    info.className = android.widget.Button::class.java.name
                }
            },
        )
        return row to value
    }

    private fun pickServing() {
        val s = vm.state.value
        if (s.servings.size < 2) return
        val labels = s.servings.map { servingLabel(it) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.detail_pick_serving)
            .setSingleChoiceItems(labels, s.servingIndex) { d, which ->
                vm.selectServing(which)
                d.dismiss()
            }
            .show()
    }

    private fun pickMeal() {
        val s = vm.state.value
        val slots = s.slots.filter { !it.hidden || it.id == s.slotId }
        if (slots.isEmpty()) return
        val names = slots.map { it.name.ifBlank { getString(R.string.food_other_meal) } }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.detail_pick_meal)
            .setSingleChoiceItems(names, slots.indexOfFirst { it.id == s.slotId }) { d, which ->
                vm.selectSlot(slots[which].id)
                d.dismiss()
            }
            .show()
    }

    private fun showCheckLabel() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.diet_check_label_title)
            .setMessage(R.string.diet_check_label)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** A note when the food is missing nutrients: vitamins and minerals absent, or "12 of 19 listed". */
    private fun completeness(food: Food?): String? {
        if (food == null) return null
        val per = food.per
        val total = Field.entries.size
        val known = per.knownCount
        if (known >= total) return null
        val micro = listOf(Field.POTASSIUM, Field.CALCIUM, Field.IRON, Field.VITA, Field.VITC, Field.VITD)
        return if (micro.all { per[it] == null }) getString(R.string.detail_vitamins_missing) else getString(R.string.detail_completeness, known, total)
    }

    private fun renderNutrition(box: LinearLayout, s: FoodDetailState) {
        val ctx = requireContext()
        box.removeAllViews()
        val n = s.preview
        if (n == null) {
            box.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, getString(R.string.food_preview_unknown)).apply { tag = "preview" })
            return
        }
        val gap = resources.getDimensionPixelSize(UiR.dimen.sw_space_2)

        // Calories and the macro split.
        val kcalRow = FoodViews.row(ctx).apply { gravity = android.view.Gravity.BOTTOM }
        kcalRow.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Headline_Number, Fmt.number(FoodRows.kcal(n).toLong())).apply { tag = "kcal" })
        kcalRow.addView(
            FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, Field.KCAL.unit),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = FoodViews.dp(ctx, 6f) },
        )
        box.addView(kcalRow, FoodViews.matchWrap())
        val split = NutrientUi.macroSplit(n)
        val gramsLine = getString(R.string.detail_macro_grams, g(n.carbX10), g(n.fatX10), g(n.proteinX10))
        if (split != null) {
            val bar = MacroSplitView(ctx).apply { set(split.first, split.second, split.third) }
            bar.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            box.addView(bar, FoodViews.matchWrap().apply { topMargin = FoodViews.dp(ctx, 6f) })
            val legend = FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, getString(R.string.detail_macro_split, split.first.toString(), split.second.toString(), split.third.toString()))
            box.addView(legend, FoodViews.matchWrap().apply { topMargin = FoodViews.dp(ctx, 4f) })
            box.contentDescription = getString(
                R.string.detail_macro_description,
                FoodViews.kcalText(ctx, FoodRows.kcal(n)),
                g(n.carbX10), split.first.toString(), g(n.fatX10), split.second.toString(), g(n.proteinX10), split.third.toString(),
            )
        }
        box.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Body_Number, gramsLine).apply { tag = "preview" })

        // % of your day: calories and the macros, then what the user's diet watches.
        val dayFields = (listOf(Field.KCAL, Field.PROTEIN, Field.CARB, Field.FAT) + NutrientTargets.focus(s.prefs)).distinct()
            .filter { s.targets[it]?.reference != null }
        if (dayFields.isNotEmpty()) {
            box.addView(
                FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_BodyLarge, getString(R.string.detail_of_your_day)).also { ViewCompat.setAccessibilityHeading(it, true) },
                FoodViews.matchWrap().apply { topMargin = gap },
            )
            for (f in dayFields) box.addView(dayBar(f, n, s), FoodViews.matchWrap().apply { topMargin = FoodViews.dp(ctx, 6f) })
        }

        // The full nutrition facts, folded.
        val toggle = FoodViews.textButton(ctx, getString(if (factsOpen) R.string.detail_hide_all else R.string.detail_show_all)) {
            factsOpen = !factsOpen
            lastNutritionKey = null
            render(vm.state.value)
        }.apply { tag = "facts_toggle" }
        box.addView(toggle, FoodViews.matchWrap().apply { topMargin = gap / 2 })
        if (factsOpen) box.addView(facts(n, s), FoodViews.matchWrap())
    }

    private fun dayBar(f: Field, n: Nutrients, s: FoodDetailState): View {
        val ctx = requireContext()
        val t = s.targets.getValue(f)
        val v = n[f]
        val pct = t.percent(v)
        val col = FoodViews.column(ctx)
        val row = FoodViews.row(ctx)
        row.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, getString(NutrientUi.name(f))), FoodViews.weight())
        row.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Body_Number, pct?.let { getString(R.string.detail_of_day_value, it.toString()) } ?: getString(R.string.dash)))
        col.addView(row)
        val bar = NutrientBarView(ctx)
        val ref = t.reference ?: 1
        val color = if (f in NutrientTargets.LIMITS) NutrientUi.statusColor(ctx, t.status(v)) else NutrientUi.statusColor(ctx, app.selfwatch.food.nutrition.TargetStatus.MET)
        bar.set((v ?: 0).toFloat() / ref, color)
        bar.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        col.addView(bar, FoodViews.matchWrap().apply { topMargin = FoodViews.dp(ctx, 2f) })
        col.contentDescription = if (pct != null) {
            getString(R.string.nutrient_row_description, getString(NutrientUi.name(f)), NutrientUi.amount(ctx, f, v), pct.toString())
        } else {
            getString(R.string.nutrient_unknown_description, getString(NutrientUi.name(f)))
        }
        col.isFocusable = true
        return col
    }

    private fun facts(n: Nutrients, s: FoodDetailState): View {
        val ctx = requireContext()
        val box = FoodViews.column(ctx).apply { tag = "facts" }
        for ((key, fields) in NutrientTargets.GROUPS) {
            box.addView(
                FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, getString(NutrientUi.group(key))).also { ViewCompat.setAccessibilityHeading(it, true) },
                FoodViews.matchWrap().apply { topMargin = FoodViews.dp(ctx, 8f) },
            )
            for (f in fields) {
                val v = n[f]
                val row = FoodViews.row(ctx).apply { minimumHeight = FoodViews.dp(ctx, 32f) }
                row.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, getString(NutrientUi.name(f))), FoodViews.weight())
                row.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Body_Number, NutrientUi.amount(ctx, f, v)))
                val pct = s.targets[f]?.percent(v)
                row.addView(
                    FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, pct?.let { getString(R.string.nutrient_percent, it.toString()) }.orEmpty()).apply {
                        textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                    },
                    LinearLayout.LayoutParams(FoodViews.dp(ctx, 56f), ViewGroup.LayoutParams.WRAP_CONTENT),
                )
                row.contentDescription = when {
                    v == null -> getString(R.string.nutrient_unknown_description, getString(NutrientUi.name(f)))
                    pct != null -> getString(R.string.nutrient_row_description, getString(NutrientUi.name(f)), NutrientUi.amount(ctx, f, v), pct.toString())
                    else -> getString(R.string.nutrient_row_description_plain, getString(NutrientUi.name(f)), NutrientUi.amount(ctx, f, v))
                }
                box.addView(row, FoodViews.matchWrap())
            }
        }
        return box
    }

    private fun servingLabel(sv: Serving): String {
        if (sv == NutrientMath.GRAM) return getString(R.string.detail_serving_g)
        if (sv == FoodRows.HUNDRED_G) return getString(R.string.detail_serving_100g)
        if (sv == FoodRows.OUNCE) return getString(R.string.detail_serving_oz)
        val g = sv.gramsX10
        return if (g != null && sv.label != "g") getString(R.string.food_serving_with_grams, sv.label, Fmt.x100(g * 10L, 0)) else sv.label
    }

    private fun g(x10: Int?): String = x10?.let { Fmt.x100(it * 10L, 1) } ?: getString(R.string.dash)

    /** Arguments and factories. */
    companion object {
        /** Fragment tag. */
        const val TAG: String = "food_detail"
        private const val ARG_ENTRY = "entry"
        private const val ARG_ORIGIN = "origin"
        private const val ARG_ID = "id"
        private const val ARG_DAY = "day"
        private const val ARG_SLOT = "slot"
        private const val KEY_FACTS = "facts_open"
        private const val HALF_X100 = 50
        private const val ONE_HALF_X100 = 150
        private const val TWO_X100 = 200

        /** A sheet that logs [ref] on [day] (null: today) in [slot] (0: guess). */
        fun forFood(ref: FoodRef, day: Day?, slot: Long): FoodDetailSheet = FoodDetailSheet().apply {
            arguments = Bundle().apply {
                putInt(ARG_ORIGIN, ref.origin.code)
                putLong(ARG_ID, ref.id)
                putInt(ARG_DAY, day?.yyyymmdd ?: 0)
                putLong(ARG_SLOT, slot)
            }
        }

        /** A sheet that edits diary entry [entryId]. */
        fun forEntry(entryId: Long): FoodDetailSheet = FoodDetailSheet().apply {
            arguments = Bundle().apply { putLong(ARG_ENTRY, entryId) }
        }

        internal fun targetOf(b: Bundle): FoodDetailTarget {
            if (b.containsKey(ARG_ENTRY)) return FoodDetailTarget.Edit(b.getLong(ARG_ENTRY))
            val origin = FoodOrigin.fromCode(b.getInt(ARG_ORIGIN)) ?: FoodOrigin.MY_FOOD
            val day = b.getInt(ARG_DAY).takeIf { it != 0 }?.let { Day(it) }
            return FoodDetailTarget.Add(FoodRef(origin, b.getLong(ARG_ID)), day, b.getLong(ARG_SLOT))
        }
    }
}
