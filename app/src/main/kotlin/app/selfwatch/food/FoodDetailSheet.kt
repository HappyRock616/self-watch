/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
import app.selfwatch.graph
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.time.Day
import app.selfwatch.ui.components.NumberStepper
import app.selfwatch.ui.components.SwSheet
import app.selfwatch.ui.format.Fmt
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
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
 * Food detail (WP-10 `FoodDetailSheet`): serving picker (named servings plus grams), amount, meal,
 * nutrient preview, source and data year; add to meal, favourite, edit as custom food. The same sheet
 * edits a diary entry (amount, serving, meal, delete).
 */
class FoodDetailSheet : SwSheet() {

    private val target: FoodDetailTarget by lazy { targetOf(requireArguments()) }

    private val vm: FoodDetailViewModel by viewModels {
        val graph = requireContext().graph
        viewModelFactory { initializer { FoodDetailViewModel(target) { GraphFoodBackend(graph.food) } } }
    }

    private lateinit var body: LinearLayout
    private var builtFor: Any? = null
    private var stepper: NumberStepper? = null
    private var preview: android.widget.TextView? = null
    private var saveButton: MaterialButton? = null
    private var favButton: MaterialButton? = null

    override val sheetTitle: CharSequence
        get() = getString(if (target is FoodDetailTarget.Edit) R.string.food_edit_entry else R.string.food_detail_title)

    override fun onCreateSheetContent(inflater: LayoutInflater, container: ViewGroup?): View {
        body = FoodViews.column(inflater.context)
        return body
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
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

    private fun render(s: FoodDetailState) {
        if (s.loading) return
        // Build once per loaded food/entry; later changes only update values.
        val key = Pair(s.food?.ref, s.entry?.id)
        if (builtFor != key || body.isEmpty()) {
            builtFor = key
            build(s)
        }
        stepper?.let { st ->
            val gram = s.serving == NutrientMath.GRAM
            st.decimals = if (gram) 0 else 2
            st.stepX100 = if (gram) GRAM_STEP_X100 else HALF_X100
            st.unit = if (gram) getString(R.string.food_unit_g) else getString(R.string.food_unit_servings)
            if (st.valueX100 != s.amountX100.toLong()) st.valueX100 = s.amountX100.toLong()
        }
        preview?.text = previewText(s.preview)
        saveButton?.isEnabled = s.amountX100 > 0 && (s.preview != null || s.isEdit)
        favButton?.let {
            it.setIconResource(if (s.favourite) R.drawable.ic_star else R.drawable.ic_star_outline)
            it.contentDescription = getString(if (s.favourite) R.string.food_unfavourite else R.string.food_favourite)
        }
        val slotName = s.slots.firstOrNull { it.id == s.slotId }?.name.orEmpty()
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
        val name = food?.name ?: s.entry?.name.orEmpty()
        val head = FoodViews.row(ctx)
        val names = FoodViews.column(ctx)
        names.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_BodyLarge, name))
        val source = food?.let { f ->
            listOfNotNull(f.brand?.takeIf { it.isNotBlank() }, f.sourceLabel.takeIf { it.isNotBlank() }, f.dataYear?.let { getString(R.string.food_data_from, it) })
                .joinToString(" · ")
        }
        if (!source.isNullOrBlank()) names.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, source))
        head.addView(names, FoodViews.weight())
        if (food != null) {
            favButton = FoodViews.iconButton(ctx, R.drawable.ic_star_outline, getString(R.string.food_favourite)) { vm.toggleFavourite() }
            head.addView(favButton)
        }
        body.addView(head, FoodViews.matchWrap())

        // Serving picker.
        if (s.servings.size > 1 || s.food != null) {
            body.addView(label(R.string.food_serving), labelParams(gap))
            val group = ChipGroup(ctx).apply { isSingleSelection = true; isSelectionRequired = true }
            s.servings.forEachIndexed { i, sv ->
                val chip = Chip(ctx).apply {
                    id = View.generateViewId()
                    text = servingLabel(sv)
                    isCheckable = true
                    isChecked = i == s.servingIndex
                    setOnClickListener { vm.selectServing(i) }
                }
                group.addView(chip)
            }
            body.addView(group, FoodViews.matchWrap())
        }

        body.addView(label(R.string.food_amount), labelParams(gap))
        val st = NumberStepper(ctx).apply {
            minX100 = 1
            valueX100 = s.amountX100.toLong()
            onValueChange = { vm.setAmount(it.toInt()) }
            tag = "amount"
        }
        stepper = st
        body.addView(st, FoodViews.matchWrap())

        body.addView(label(R.string.food_meal), labelParams(gap))
        val meals = ChipGroup(ctx).apply { isSingleSelection = true; isSelectionRequired = true }
        for (slot in s.slots.filter { !it.hidden || it.id == s.slotId }) {
            meals.addView(
                Chip(ctx).apply {
                    id = View.generateViewId()
                    text = slot.name
                    isCheckable = true
                    isChecked = slot.id == s.slotId
                    setOnClickListener { vm.selectSlot(slot.id) }
                },
            )
        }
        body.addView(meals, FoodViews.matchWrap())

        val p = FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Body_Number)
        preview = p
        body.addView(p, labelParams(gap))

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

    private fun label(res: Int) = FoodViews.text(requireContext(), UiR.style.TextAppearance_SelfWatch_Caption, getString(res))

    private fun labelParams(gap: Int) = FoodViews.matchWrap().apply { topMargin = gap }

    private fun servingLabel(sv: app.selfwatch.model.food.Serving): String {
        if (sv == NutrientMath.GRAM) return getString(R.string.food_unit_g)
        val g = sv.gramsX10
        return if (g != null && sv.label != "g") getString(R.string.food_serving_with_grams, sv.label, Fmt.x100(g * 10L, 0)) else sv.label
    }

    private fun previewText(n: Nutrients?): String {
        if (n == null) return getString(R.string.food_preview_unknown)
        val ctx = requireContext()
        return getString(
            R.string.food_preview,
            FoodViews.kcalText(ctx, FoodRows.kcal(n)),
            grams(n.proteinX10),
            grams(n.fatX10),
            grams(n.carbX10),
        )
    }

    private fun grams(x10: Int?): String = x10?.let { Fmt.x100(it * 10L, 1) } ?: getString(R.string.dash)

    /** Arguments and factories. */
    companion object {
        /** Fragment tag. */
        const val TAG: String = "food_detail"
        private const val ARG_ENTRY = "entry"
        private const val ARG_ORIGIN = "origin"
        private const val ARG_ID = "id"
        private const val ARG_DAY = "day"
        private const val ARG_SLOT = "slot"
        private const val GRAM_STEP_X100 = 1000L
        private const val HALF_X100 = 50L

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
