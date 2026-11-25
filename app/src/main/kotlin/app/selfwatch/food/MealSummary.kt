/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.R
import app.selfwatch.food.nutrition.NutrientTargets
import app.selfwatch.graph
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.MealSlot
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Nutrients.Field
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Day
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.Views
import app.selfwatch.ui.components.ListRow
import app.selfwatch.ui.components.SwSnackbar
import app.selfwatch.ui.format.Fmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt
import app.selfwatch.ui.R as UiR

/** One food's part of a meal: its whole [kcal] and [percent] of the meal's calories (null when the meal has none). */
data class MealShare(val entry: DiaryEntry, val kcal: Int, val percent: Int?)

/** Pure rules for the meal summary and the "Recent for <meal>" strip. */
object MealMath {
    /** Foods offered under a meal. */
    const val RECENT_LIMIT: Int = 4

    /** Days of history the recent strip looks at (the day shown included). */
    const val RECENT_DAYS: Int = 30

    /**
     * The foods most often logged in meal [slotId] in [history] (ties: the latest first), skipping
     * quick adds and foods already in the meal ([inMeal]); each as its latest diary line, at most [limit].
     */
    fun recents(history: List<DiaryEntry>, slotId: Long, inMeal: List<DiaryEntry>, limit: Int = RECENT_LIMIT): List<DiaryEntry> {
        val have = inMeal.mapNotNull { it.food }.toSet()
        val latest = compareBy<DiaryEntry>({ it.day }, { it.tSec }, { it.id })
        return history
            .filter { it.mealSlotId == slotId && it.food != null && it.food !in have }
            .groupBy { it.food }
            .values
            .map { list -> list.size to list.maxWith(latest) }
            .sortedWith(compareByDescending<Pair<Int, DiaryEntry>> { it.first }.thenByDescending(latest) { it.second })
            .take(limit)
            .map { it.second }
    }

    /** Each food's calories and share of the meal, most first. */
    fun shares(entries: List<DiaryEntry>): List<MealShare> {
        val kcals = entries.map { FoodRows.kcal(it.nutrients) }
        val total = kcals.sum()
        return entries.indices
            .map { i -> MealShare(entries[i], kcals[i], if (total > 0) (kcals[i] * 100.0 / total).roundToInt() else null) }
            .sortedByDescending { it.kcal }
    }

    /** How many of [entries] know every nutrient. */
    fun complete(entries: List<DiaryEntry>): Int = entries.count { e -> Field.entries.all { e.nutrients[it] != null } }
}

/** Meal summary state: [meal] is null until loaded or when the slot is gone. */
data class MealSummaryState(
    val loading: Boolean = true,
    val day: Day? = null,
    val slot: MealSlot? = null,
    val entries: List<DiaryEntry> = emptyList(),
    val totals: Nutrients = Nutrients.UNKNOWN,
    /** "Recent for <meal>" (same rule as the Food tab's meal cards). */
    val recents: List<DiaryEntry> = emptyList(),
) {
    /** The same numbers as Day nutrition's, for its rows (no targets: a meal has none). */
    fun asNutrition(): DayNutritionState = DayNutritionState(loading = false, day = day, entries = entries, totals = totals)
}

/** Meal summary view model: one meal of one day, following the diary live. */
class MealSummaryViewModel(private val day: Day, private val slotId: Long, private val backendProvider: () -> FoodBackend) : ViewModel() {
    private val backend: FoodBackend by lazy(backendProvider)
    private val eventChannel = Channel<DiaryEvent>(Channel.BUFFERED)

    /** Deleted ([DiaryEvent.Deleted]) and recent-logged ([DiaryEvent.Logged]) events, for Undo snackbars. */
    val events: Flow<DiaryEvent> = eventChannel.receiveAsFlow()

    /** Screen state. */
    val state: StateFlow<MealSummaryState> = backend.diaryDay(day)
        .map { d ->
            val m = d.meals.firstOrNull { it.slot.id == slotId }
            val entries = m?.entries.orEmpty()
            val history = try {
                backend.entriesBetween(day.plusDays(-(MealMath.RECENT_DAYS - 1)), day)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "meal history failed", e)
                emptyList()
            }
            MealSummaryState(false, day, m?.slot, entries, m?.totals ?: Nutrients.UNKNOWN, MealMath.recents(history, slotId, entries))
        }
        .catch { e ->
            SwLog.w(TAG, "load failed", e)
            emit(MealSummaryState(loading = false, day = day))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), MealSummaryState())

    /** Deletes [entry] (the entry editor's Delete) and offers Undo. */
    fun delete(entry: DiaryEntry) = act { backend.delete(entry.id)?.let { eventChannel.send(DiaryEvent.Deleted(it)) } }

    /** Puts a deleted entry back. */
    fun undoDelete(entry: DiaryEntry) = act { backend.restore(entry) }

    /** "Recent for <meal>" +: logs [last]'s food into this meal with its last serving, and offers Undo. */
    fun logRecent(last: DiaryEntry) = act {
        val id = QuickLog.relog(backend, last, day, slotId)
        eventChannel.send(DiaryEvent.Logged(last.name, slotId, id))
    }

    /** Undoes a [logRecent]. */
    fun undoLog(id: Long) = act { backend.deleteEntries(listOf(id)) }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "action failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "MealSummaryVM"
        const val STOP_MS = 5_000L
    }
}

/**
 * Meal summary, opened by tapping a meal's header on the Food tab: the meal's calories, macros with
 * their share of the calories, every nutrient (with how many foods know it, as on Day nutrition) and
 * what each food brings.
 */
class MealSummaryFragment : DetailFragment(), EntryDeleteHost {

    private val vm: MealSummaryViewModel by viewModels {
        val graph = requireContext().graph
        val args = requireArguments()
        viewModelFactory {
            initializer { MealSummaryViewModel(Day(args.getInt(ARG_DAY)), args.getLong(ARG_SLOT)) { GraphFoodBackend(graph.food) } }
        }
    }

    private lateinit var content: LinearLayout

    override fun title(): CharSequence = requireArguments().getString(ARG_NAME).orEmpty().ifBlank { getString(R.string.food_other_meal) }

    override fun deleteEntry(entry: DiaryEntry) {
        vm.delete(entry)
    }

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        content = FoodViews.column(requireContext())
        column.addView(content, Views.matchWrap(requireContext()))
        collectWhileStarted(vm.state) { render(it) }
        collectWhileStarted(vm.events) { e ->
            when (e) {
                is DiaryEvent.Deleted -> SwSnackbar.undo(requireView(), getString(R.string.food_deleted, e.entry.name), null) { vm.undoDelete(e.entry) }
                is DiaryEvent.Logged -> SwSnackbar.undo(requireView(), getString(R.string.food_recent_logged, e.name, title()), null) { vm.undoLog(e.id) }
                else -> Unit
            }
        }
    }

    private fun render(s: MealSummaryState) {
        if (s.loading) return
        val ctx = requireContext()
        val day = s.day ?: return
        val name = title().toString()
        s.slot?.name?.takeIf { it.isNotBlank() }?.let { toolbar.title = it }
        val kcal = FoodRows.kcal(s.totals)
        toolbar.subtitle = Fmt.day(ctx, day.yyyymmdd, LocalDate.now())
        content.removeAllViews()
        // This screen's own add: Add food with this meal and day chosen.
        val slotId = s.slot?.id ?: requireArguments().getLong(ARG_SLOT)
        val add = FoodViews.filledButton(ctx, getString(R.string.food_add_to_meal, name)) {
            startActivity(AddFoodActivity.intent(ctx, day, slotId))
        }.apply {
            tag = "meal_add"
            setIconResource(UiR.drawable.sw_ic_plus)
        }
        content.addView(add, Views.matchWrap(ctx))
        if (s.entries.isEmpty()) {
            val card = Views.card(content, null)
            card.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, getString(R.string.meal_summary_empty, name)).apply { tag = "meal_empty" }, Views.matchWrap(ctx))
            recents(s, day, slotId, name)
            return
        }

        // Calories and the macro split.
        val macros = Views.card(content, getString(R.string.meal_summary_energy))
        val energy = FoodViews.row(ctx)
        energy.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, getString(R.string.nutrient_kcal)), FoodViews.weight())
        energy.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Title_Number, FoodViews.kcalText(ctx, kcal)).apply { tag = "meal_kcal" })
        macros.addView(energy, Views.matchWrap(ctx))
        val split = NutrientUi.macroSplit(s.totals)
        val bar = MacroSplitView(ctx).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        if (split != null) bar.set(split.first, split.second, split.third)
        macros.addView(bar, Views.matchWrap(ctx, 8))
        // Protein, carbs, fat; the share of calories when the macros are known.
        val shares = mapOf(Field.CARB to split?.first, Field.FAT to split?.second, Field.PROTEIN to split?.third)
        for ((i, f) in listOf(Field.PROTEIN, Field.CARB, Field.FAT).withIndex()) {
            val amount = NutrientUi.amount(ctx, f, s.totals[f])
            val pct = shares[f]
            val label = getString(NutrientUi.name(f))
            val line = FoodViews.row(ctx).apply {
                tag = "meal_macro_${f.name.lowercase()}"
                minimumHeight = resources.getDimensionPixelSize(UiR.dimen.sw_touch_target)
            }
            val dot = View(ctx).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(bar.color(SPLIT_INDEX[i]))
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val size = Views.dp(ctx, DOT_DP)
            line.addView(dot, LinearLayout.LayoutParams(size, size).apply { marginEnd = Views.dp(ctx, 8) })
            line.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, label), FoodViews.weight())
            val value = if (pct != null && s.totals[f] != null) getString(R.string.meal_summary_macro_value, amount, pct) else amount
            line.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Body_Number, value).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_END }, FoodViews.weight())
            line.contentDescription = "$label, $value"
            macros.addView(line, Views.matchWrap(ctx))
        }

        // What each food brings, then foods often eaten in this meal.
        val foods = Views.card(content, getString(R.string.meal_summary_foods))
        for (share in MealMath.shares(s.entries)) foods.addView(foodRow(share), Views.matchWrap(ctx))
        recents(s, day, slotId, name)

        // Every nutrient, grouped, with how many foods know it.
        val n = s.asNutrition()
        val all = Views.card(content, getString(R.string.day_nutrition_all))
        val complete = MealMath.complete(s.entries)
        all.addView(Views.caption(ctx, getString(R.string.meal_summary_complete, complete, s.entries.size)).apply { tag = "meal_complete" }, Views.matchWrap(ctx))
        for ((key, fields) in NutrientTargets.GROUPS) {
            all.addView(
                FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, getString(NutrientUi.group(key))).also { ViewCompat.setAccessibilityHeading(it, true) },
                Views.matchWrap(ctx, 12),
            )
            for (f in fields) all.addView(NutrientRows.row(ctx, n, f, showLeft = false) { NutrientRows.showTopFoods(ctx, n, f) }, Views.matchWrap(ctx, 6))
        }
    }

    /** "Recent for <meal>" in its own card, hidden without history. */
    private fun recents(s: MealSummaryState, day: Day, slotId: Long, name: String) {
        if (s.recents.isEmpty()) return
        val card = Views.card(content, null)
        card.addView(RecentStrip.build(childFragmentManager, requireContext(), day, slotId, name, s.recents) { vm.logRecent(it) }, FoodViews.matchWrap())
    }

    private fun foodRow(share: MealShare): View {
        val ctx = requireContext()
        val e = share.entry
        val t = e.nutrients
        val macros = getString(R.string.meal_macros, grams(t.carbX10), grams(t.fatX10), grams(t.proteinX10))
        val kcal = FoodViews.kcalText(ctx, share.kcal)
        return ListRow(ctx).apply {
            tag = "meal_food_${e.id}"
            title = e.name
            subtitle = FoodFragment.amountText(ctx, e) + " · " + macros
            trailing = if (share.percent != null) getString(R.string.meal_summary_food_share, kcal, share.percent) else kcal
            showChevron = false
            onClick = { FoodDetailSheet.forEntry(e.id).show(childFragmentManager, FoodDetailSheet.TAG) }
            contentDescription = listOf(e.name, subtitle, trailing).joinToString(", ")
        }
    }

    private fun grams(x10: Int?): String = Fmt.number(((x10 ?: 0) + 5L) / 10)

    /** Arguments. */
    companion object {
        private const val ARG_DAY = "day"
        private const val ARG_SLOT = "slot"
        private const val ARG_NAME = "name"
        private const val DOT_DP = 10

        /** MacroSplitView's segment for protein, carbs, fat (it draws carbs, fat, protein). */
        private val SPLIT_INDEX = intArrayOf(2, 0, 1)

        /** The summary of meal [slot] (shown as [name]) on [day]. */
        fun newInstance(day: Day, slot: Long, name: String): MealSummaryFragment = MealSummaryFragment().apply {
            arguments = Bundle().apply {
                putInt(ARG_DAY, day.yyyymmdd)
                putLong(ARG_SLOT, slot)
                putString(ARG_NAME, name)
            }
        }
    }
}
