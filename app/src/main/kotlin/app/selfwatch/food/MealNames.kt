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
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.R
import app.selfwatch.graph
import app.selfwatch.model.food.MealSlot
import app.selfwatch.model.log.SwLog
import app.selfwatch.ui.components.SwSheet
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.selfwatch.ui.R as UiR

/** The meal list: [visible] meals in diary order, then the removed ([hidden]) ones that can come back. */
data class MealNamesState(
    val loading: Boolean = true,
    val visible: List<MealSlot> = emptyList(),
    val hidden: List<MealSlot> = emptyList(),
) {
    /** The last visible meal can't be removed. */
    val canRemove: Boolean get() = visible.size > 1
}

/**
 * "Meal names" (Food menu): rename, reorder, add and remove meals. A meal's id never changes, so its
 * entries keep their meal; removing only hides it (days that have entries in it still show it).
 */
class MealNamesViewModel(private val backendProvider: () -> FoodBackend) : ViewModel() {
    private val backend: FoodBackend by lazy(backendProvider)
    private val mutable = MutableStateFlow(MealNamesState())
    private val lock = Mutex()

    /** Screen state. */
    val state: StateFlow<MealNamesState> = mutable.asStateFlow()

    init {
        act()
    }

    /** Renames meal [id]; blank names are ignored. */
    fun rename(id: Long, name: String) {
        val clean = clean(name) ?: return
        act { all -> all.filter { it.id == id && it.name != clean }.map { it.copy(name = clean) } }
    }

    /** Moves visible meal [id] up (negative [delta]) or down. */
    fun move(id: Long, delta: Int) = act { all ->
        val (visible, hidden) = all.partition { !it.hidden }
        val from = visible.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in visible.indices) return@act emptyList()
        val list = visible.toMutableList()
        list.add(to, list.removeAt(from))
        renumber(list + hidden)
    }

    /** Removes (hides) meal [id], unless it is the last one shown. */
    fun remove(id: Long) = act { all ->
        if (all.count { !it.hidden } <= 1) return@act emptyList()
        all.filter { it.id == id && !it.hidden }.map { it.copy(hidden = true) }
    }

    /** Brings removed meal [id] back, at the end of the list. */
    fun restore(id: Long) = act { all ->
        val (visible, hidden) = all.partition { !it.hidden }
        val back = hidden.filter { it.id == id }.map { it.copy(hidden = false) }
        renumber(visible + back + hidden.filter { it.id != id })
    }

    /** Adds a meal named [name] at the end. */
    fun add(name: String) {
        val clean = clean(name) ?: return
        act {
            backend.addMealSlot(clean)
            emptyList()
        }
    }

    /** Runs [change] on the current slots (in order), saves what it returns, then reloads. */
    private fun act(change: suspend (List<MealSlot>) -> List<MealSlot> = { emptyList() }) {
        viewModelScope.launch {
            lock.withLock {
                try {
                    val changed = change(backend.mealSlots())
                    if (changed.isNotEmpty()) backend.saveMealSlots(changed)
                    val all = backend.mealSlots()
                    mutable.value = MealNamesState(false, all.filter { !it.hidden }, all.filter { it.hidden })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SwLog.w(TAG, "meal names failed", e)
                    mutable.value = mutable.value.copy(loading = false)
                }
            }
        }
    }

    /** Constants and helpers. */
    companion object {
        private const val TAG = "MealNamesVM"

        /** Longest meal name. */
        const val MAX_NAME: Int = 40

        /** [name] trimmed and cut to [MAX_NAME], or null when blank. */
        fun clean(name: String): String? = name.trim().take(MAX_NAME).trim().ifEmpty { null }

        /** [slots] with orders 1, 2, 3… in list order. */
        fun renumber(slots: List<MealSlot>): List<MealSlot> = slots.mapIndexed { i, s -> s.copy(order = i + 1) }
    }
}

/** The "Meal names" sheet over the Food tab. */
class MealNamesSheet : SwSheet() {

    private val vm: MealNamesViewModel by viewModels {
        val g = requireContext().graph
        viewModelFactory { initializer { MealNamesViewModel { GraphFoodBackend(g.food) } } }
    }

    private var list: LinearLayout? = null

    override val sheetTitle: CharSequence get() = getString(R.string.menu_meal_names)

    override fun onCreateSheetContent(inflater: LayoutInflater, container: ViewGroup?): View {
        val ctx = inflater.context
        val col = FoodViews.column(ctx)
        col.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, getString(R.string.meals_help)), FoodViews.matchWrap())
        val rows = FoodViews.column(ctx).apply { tag = "meal_rows" }
        list = rows
        col.addView(rows, FoodViews.matchWrap())
        col.addView(
            FoodViews.outlinedButton(ctx, getString(R.string.meals_add)) { askName(null) }.apply { tag = "meal_add" },
            FoodViews.matchWrap(),
        )
        return col
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.state.collect { render(it) } }
        }
    }

    private fun render(s: MealNamesState) {
        val rows = list ?: return
        val ctx = requireContext()
        rows.removeAllViews()
        s.visible.forEachIndexed { i, slot ->
            val row = FoodViews.row(ctx).apply {
                tag = "meal_${slot.id}"
                minimumHeight = resources.getDimensionPixelSize(UiR.dimen.sw_touch_target)
            }
            val name = FoodViews.textButton(ctx, slot.name.ifBlank { getString(R.string.food_other_meal) }) { askName(slot) }.apply {
                contentDescription = getString(R.string.meals_rename_description, text)
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }
            row.addView(name, FoodViews.weight())
            row.addView(FoodViews.iconButton(ctx, UiR.drawable.sw_ic_arrow_up, getString(R.string.meals_up, slot.name)) { vm.move(slot.id, -1) }.apply { isEnabled = i > 0 })
            row.addView(FoodViews.iconButton(ctx, UiR.drawable.sw_ic_arrow_down, getString(R.string.meals_down, slot.name)) { vm.move(slot.id, 1) }.apply { isEnabled = i < s.visible.lastIndex })
            row.addView(FoodViews.iconButton(ctx, UiR.drawable.sw_ic_close, getString(R.string.meals_remove, slot.name)) { vm.remove(slot.id) }.apply { isEnabled = s.canRemove })
            rows.addView(row, FoodViews.matchWrap())
        }
        if (s.hidden.isNotEmpty()) {
            rows.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, getString(R.string.meals_removed)), FoodViews.matchWrap())
            for (slot in s.hidden) {
                val row = FoodViews.row(ctx).apply { tag = "meal_${slot.id}" }
                row.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, slot.name), FoodViews.weight())
                row.addView(FoodViews.textButton(ctx, getString(R.string.meals_restore)) { vm.restore(slot.id) }.apply {
                    contentDescription = getString(R.string.meals_restore_description, slot.name)
                })
                rows.addView(row, FoodViews.matchWrap())
            }
        }
    }

    /** Asks for a name: renames [slot], or adds a meal when it is null. */
    private fun askName(slot: MealSlot?) {
        val ctx = requireContext()
        val (layout, edit) = FoodViews.field(ctx, R.string.meals_name_hint)
        edit.setText(slot?.name.orEmpty())
        edit.setSelection(edit.text?.length ?: 0)
        layout.counterMaxLength = MealNamesViewModel.MAX_NAME
        layout.isCounterEnabled = true
        val pad = resources.getDimensionPixelSize(UiR.dimen.sw_space_2)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(pad, pad / 2, pad, 0)
            addView(layout, FoodViews.matchWrap())
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (slot == null) R.string.meals_add else R.string.meals_rename)
            .setView(box)
            .setPositiveButton(R.string.food_save) { _, _ ->
                val text = edit.text?.toString().orEmpty()
                if (slot == null) vm.add(text) else vm.rename(slot.id, text)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Tag. */
    companion object {
        /** Fragment tag. */
        const val TAG: String = "meal_names"
    }
}
