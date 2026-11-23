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
import android.view.inputmethod.EditorInfo
import android.widget.Toast
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
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Day
import app.selfwatch.ui.components.SwSheet
import app.selfwatch.ui.format.Fmt
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import app.selfwatch.ui.R as UiR

/** Quick add state: the meals and the chosen one. */
data class QuickAddState(val slots: List<MealSlot> = emptyList(), val slotId: Long = 0, val day: Day? = null)

/** Quick add: calories (and optional macros) into a meal, with no food behind them. */
class QuickAddViewModel(
    private val day: Day?,
    private val slot: Long,
    private val backendProvider: () -> FoodBackend,
) : ViewModel() {
    private val backend: FoodBackend by lazy(backendProvider)
    private val mutable = MutableStateFlow(QuickAddState())
    private val doneChannel = Channel<Boolean>(Channel.BUFFERED)

    /** Screen state. */
    val state: StateFlow<QuickAddState> = mutable.asStateFlow()

    /** Emits true when saved, false when saving failed. */
    val done: Flow<Boolean> = doneChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            val slots = backend.mealSlots()
            val hour = java.time.Instant.ofEpochSecond(backend.clock.nowSec()).atZone(backend.clock.zone()).hour
            val chosen = if (slot != 0L) slot else FoodRows.slotForHour(hour, slots)?.id ?: slots.firstOrNull()?.id ?: 1L
            mutable.value = QuickAddState(slots, chosen, day ?: backend.clock.today())
        }
    }

    /** Picks the meal. */
    fun selectSlot(id: Long) {
        mutable.value = mutable.value.copy(slotId = id)
    }

    /**
     * Logs [kcalX100] (calories ×100 as typed) with optional macros in grams ×100. Returns false (and
     * logs nothing) when the calories are missing or not positive.
     */
    fun save(name: String, kcalX100: Long?, proteinX100: Long?, fatX100: Long?, carbX100: Long?): Boolean {
        if (kcalX100 == null || kcalX100 <= 0) return false
        val s = mutable.value
        val n = Nutrients(
            kcalX10 = toX10(kcalX100),
            proteinX10 = proteinX100?.let { toX10(it) },
            fatX10 = fatX100?.let { toX10(it) },
            satfatX10 = null,
            carbX10 = carbX100?.let { toX10(it) },
            sugarX10 = null,
            fibreX10 = null,
            sodiumMg = null,
            cholesterolMg = null,
        )
        viewModelScope.launch {
            try {
                backend.quickAdd(s.day ?: backend.clock.today(), s.slotId, name.ifBlank { DEFAULT_NAME }, n)
                doneChannel.send(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "quick add failed", e)
                doneChannel.send(false)
            }
        }
        return true
    }

    /** Constants. */
    companion object {
        private const val TAG = "QuickAddVM"

        /** Name used when none is typed. */
        const val DEFAULT_NAME: String = "Quick add"

        /** Hundredths to tenths, rounded half up. */
        fun toX10(x100: Long): Int = ((x100 + 5) / 10).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

/** Quick add calories (WP-10 `QuickAddSheet`): two taps from the + button. */
class QuickAddSheet : SwSheet() {

    private val vm: QuickAddViewModel by viewModels {
        val graph = requireContext().graph
        val args = requireArguments()
        val day = args.getInt(ARG_DAY).takeIf { it != 0 }?.let { Day(it) }
        viewModelFactory { initializer { QuickAddViewModel(day, args.getLong(ARG_SLOT)) { GraphFoodBackend(graph.food) } } }
    }

    private lateinit var meals: ChipGroup

    override val sheetTitle: CharSequence get() = getString(R.string.food_quick_add)

    override fun onCreateSheetContent(inflater: LayoutInflater, container: ViewGroup?): View {
        val ctx = inflater.context
        val col = FoodViews.column(ctx)
        val gap = resources.getDimensionPixelSize(UiR.dimen.sw_space_1)
        val (kcalL, kcal) = FoodViews.numberField(ctx, R.string.food_calories)
        kcal.tag = "kcal"
        col.addView(kcalL, FoodViews.matchWrap())
        val macros = FoodViews.row(ctx)
        val (pL, p) = FoodViews.numberField(ctx, R.string.food_protein_g)
        val (fL, f) = FoodViews.numberField(ctx, R.string.food_fat_g)
        val (cL, c) = FoodViews.numberField(ctx, R.string.food_carbs_g)
        macros.addView(pL, FoodViews.weight())
        macros.addView(fL, FoodViews.weight().apply { marginStart = gap })
        macros.addView(cL, FoodViews.weight().apply { marginStart = gap })
        col.addView(macros, FoodViews.matchWrap().apply { topMargin = gap })
        val (nameL, name) = FoodViews.field(ctx, R.string.food_name_optional)
        name.imeOptions = EditorInfo.IME_ACTION_DONE
        col.addView(nameL, FoodViews.matchWrap().apply { topMargin = gap })
        meals = ChipGroup(ctx).apply { isSingleSelection = true; isSelectionRequired = true }
        col.addView(meals, FoodViews.matchWrap().apply { topMargin = gap })
        col.addView(
            FoodViews.filledButton(ctx, getString(R.string.food_add)) {
                val ok = vm.save(
                    name.text?.toString().orEmpty().ifBlank { getString(R.string.food_quick_add_label) },
                    Fmt.parseX100(kcal.text?.toString().orEmpty()),
                    Fmt.parseX100(p.text?.toString().orEmpty()),
                    Fmt.parseX100(f.text?.toString().orEmpty()),
                    Fmt.parseX100(c.text?.toString().orEmpty()),
                )
                if (!ok) kcalL.error = getString(R.string.food_calories_needed)
            }.apply { tag = "save" },
            FoodViews.matchWrap().apply { topMargin = gap },
        )
        kcal.requestFocus()
        return col
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.state.collect { renderSlots(it) } }
                launch {
                    vm.done.collect { ok ->
                        Toast.makeText(requireContext(), if (ok) R.string.food_quick_added else R.string.food_failed, Toast.LENGTH_SHORT).show()
                        if (ok) dismissAllowingStateLoss()
                    }
                }
            }
        }
    }

    private fun renderSlots(s: QuickAddState) {
        if (meals.childCount == s.slots.count { !it.hidden }) {
            for (i in 0 until meals.childCount) (meals.getChildAt(i) as Chip).let { it.isChecked = it.tag == s.slotId }
            return
        }
        meals.removeAllViews()
        for (slot in s.slots.filter { !it.hidden }) {
            meals.addView(
                Chip(requireContext()).apply {
                    id = View.generateViewId()
                    tag = slot.id
                    text = slot.name
                    isCheckable = true
                    isChecked = slot.id == s.slotId
                    setOnClickListener { vm.selectSlot(slot.id) }
                },
            )
        }
    }

    /** Arguments. */
    companion object {
        /** Fragment tag. */
        const val TAG: String = "quick_add"
        private const val ARG_DAY = "day"
        private const val ARG_SLOT = "slot"

        /** A sheet logging into [slot] (0: guess from the time) on [day] (null: today). */
        fun newInstance(day: Day?, slot: Long): QuickAddSheet = QuickAddSheet().apply {
            arguments = Bundle().apply {
                putInt(ARG_DAY, day?.yyyymmdd ?: 0)
                putLong(ARG_SLOT, slot)
            }
        }
    }
}
