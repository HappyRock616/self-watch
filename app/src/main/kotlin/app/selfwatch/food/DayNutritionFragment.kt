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
import app.selfwatch.food.nutrition.DietPrefs
import app.selfwatch.food.nutrition.NutrientTargets
import app.selfwatch.food.nutrition.Target
import app.selfwatch.food.nutrition.WeekTotals
import app.selfwatch.graph
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Nutrients.Field
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Day
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.components.BarSeries
import app.selfwatch.ui.components.BarsView
import app.selfwatch.ui.format.Fmt
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.format.TextStyle
import app.selfwatch.ui.R as UiR

/** Day or week view of Day nutrition. */
enum class NutritionRange { DAY, WEEK }

/** Day nutrition state. */
data class DayNutritionState(
    val loading: Boolean = true,
    /** The day shown; in the week view, the last day of the seven. */
    val day: Day? = null,
    val range: NutritionRange = NutritionRange.DAY,
    /** True: macro bars show what is left; false: what was eaten. */
    val remaining: Boolean = false,
    /** The foods behind the numbers: the day's, or the whole week's in the week view. */
    val entries: List<DiaryEntry> = emptyList(),
    /** The day's totals, or the daily averages in the week view. */
    val totals: Nutrients = Nutrients.UNKNOWN,
    val targets: Map<Field, Target> = emptyMap(),
    val prefs: DietPrefs = DietPrefs.NONE,
    /** The seven days of totals (week view only). */
    val week: WeekTotals? = null,
) {
    /** How many of the foods know [f]. */
    fun known(f: Field): Int = NutrientTargets.known(entries.map { it.nutrients }, f)

    /** The foods that list [f], most first, at most [limit]. */
    fun topFoods(f: Field, limit: Int = TOP_FOODS): List<DiaryEntry> =
        entries.filter { (it.nutrients[f] ?: 0) > 0 }.sortedByDescending { it.nutrients[f] ?: 0 }.take(limit)

    private companion object {
        const val TOP_FOODS = 5
    }
}

/**
 * Day nutrition (FOOD_UI_PLAN §4.4): the day's totals (or a week's daily averages with a bar per day)
 * against the macro goals and every nutrient target, with the foods behind each number. Follows the
 * diary live.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DayNutritionViewModel(start: Day, private val backendProvider: () -> FoodBackend) : ViewModel() {

    private val backend: FoodBackend by lazy(backendProvider)
    private val ui = MutableStateFlow(Triple(start, NutritionRange.DAY, false))

    /** Screen state. */
    val state: StateFlow<DayNutritionState> = ui
        .flatMapLatest { (day, range, remaining) ->
            if (range == NutritionRange.WEEK) weekState(day, remaining) else dayState(day, remaining)
        }
        .catch { e ->
            SwLog.w(TAG, "load failed", e)
            emit(DayNutritionState(loading = false, day = ui.value.first, range = ui.value.second))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), DayNutritionState())

    private fun dayState(day: Day, remaining: Boolean): Flow<DayNutritionState> = backend.diaryDay(day).map { d ->
        DayNutritionState(
            loading = false,
            day = day,
            range = NutritionRange.DAY,
            remaining = remaining,
            entries = d.meals.flatMap { it.entries },
            totals = d.totals,
            targets = targets(day),
            prefs = backend.dietPrefs(),
        )
    }

    private fun weekState(last: Day, remaining: Boolean): Flow<DayNutritionState> {
        val days = WeekTotals.weekEnding(last)
        return combine(days.map { backend.diaryDay(it) }) { all ->
            val totals = all.map { d -> if (d.meals.any { it.entries.isNotEmpty() }) d.totals else null }
            val week = WeekTotals(days, totals)
            DayNutritionState(
                loading = false,
                day = last,
                range = NutritionRange.WEEK,
                remaining = remaining,
                entries = all.flatMap { d -> d.meals.flatMap { it.entries } },
                totals = week.averages,
                targets = targets(last),
                prefs = backend.dietPrefs(),
                week = week,
            )
        }
    }

    private suspend fun targets(day: Day): Map<Field, Target> = try {
        NutrientTargets.of(backend.targetInputs(day))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SwLog.w(TAG, "targets failed", e)
        emptyMap()
    }

    /** Shows [day]. */
    fun showDay(day: Day) = ui.update { it.copy(first = day) }

    /** One step back or forward: a day, or a week in the week view. */
    fun step(forward: Boolean) = ui.update { (day, range, remaining) ->
        val n = if (range == NutritionRange.WEEK) WeekTotals.WEEK else 1
        val today = backend.clock.today()
        val next = day.plusDays(if (forward) n else -n)
        Triple(if (next > today) today else next, range, remaining)
    }

    /** Day or week. */
    fun setRange(range: NutritionRange) = ui.update { it.copy(second = range) }

    /** Eaten or left on the macro bars. */
    fun setRemaining(remaining: Boolean) = ui.update { it.copy(third = remaining) }

    private companion object {
        const val TAG = "DayNutritionVM"
        const val STOP_MS = 5_000L
    }
}

/** The Day nutrition screen, opened from the Food tab's menu. */
class DayNutritionFragment : DetailFragment() {

    private val vm: DayNutritionViewModel by viewModels {
        val graph = requireContext().graph
        val start = Day(requireArguments().getInt(ARG_DAY))
        viewModelFactory { initializer { DayNutritionViewModel(start) { GraphFoodBackend(graph.food) } } }
    }

    private lateinit var content: LinearLayout
    private lateinit var rangeGroup: MaterialButtonToggleGroup
    private lateinit var dayLabel: android.widget.TextView
    private lateinit var prevDay: View
    private lateinit var nextDay: View

    override fun title(): CharSequence = getString(R.string.day_nutrition_title)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        rangeGroup = Views.segments(ctx, listOf(getString(R.string.day_nutrition_day), getString(R.string.day_nutrition_week))) { i ->
            vm.setRange(if (i == 0) NutritionRange.DAY else NutritionRange.WEEK)
        }
        column.addView(rangeGroup, Views.matchWrap(ctx))
        val switcher = FoodViews.row(ctx)
        prevDay = FoodViews.iconButton(ctx, R.drawable.ic_day_prev, getString(R.string.food_day_prev)) { vm.step(forward = false) }
        dayLabel = FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_BodyLarge).apply {
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            tag = "range_label"
        }
        nextDay = FoodViews.iconButton(ctx, R.drawable.ic_day_next, getString(R.string.food_day_next)) { vm.step(forward = true) }
        switcher.addView(prevDay)
        switcher.addView(dayLabel, FoodViews.weight())
        switcher.addView(nextDay)
        column.addView(switcher, Views.matchWrap(ctx, 8))
        content = FoodViews.column(ctx)
        column.addView(content, Views.matchWrap(ctx))
        collectWhileStarted(vm.state) { render(it) }
    }

    private fun render(s: DayNutritionState) {
        if (s.loading) return
        val ctx = requireContext()
        val day = s.day ?: return
        Views.selectSegment(rangeGroup, s.range.ordinal)
        val today = LocalDate.now()
        val week = s.range == NutritionRange.WEEK
        dayLabel.text = if (week) {
            getString(R.string.day_nutrition_week_range, Fmt.day(ctx, day.plusDays(-(WeekTotals.WEEK - 1)).yyyymmdd, today), Fmt.day(ctx, day.yyyymmdd, today))
        } else {
            Fmt.day(ctx, day.yyyymmdd, today)
        }
        prevDay.contentDescription = getString(if (week) R.string.day_nutrition_week_prev else R.string.food_day_prev)
        nextDay.contentDescription = getString(if (week) R.string.day_nutrition_week_next else R.string.food_day_next)
        val isToday = day.toLocalDate() >= today
        nextDay.isEnabled = !isToday
        nextDay.alpha = if (isToday) DISABLED_ALPHA else 1f
        content.removeAllViews()
        if (s.entries.isEmpty()) {
            Views.card(content, null, first = true).addView(
                FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, getString(if (week) R.string.day_nutrition_week_empty else R.string.day_nutrition_empty))
                    .apply { tag = "day_empty" },
                Views.matchWrap(ctx),
            )
            return
        }

        // Macro targets, eaten or left (the daily average in the week view).
        val macros = Views.card(content, getString(if (week) R.string.day_nutrition_week_macros else R.string.day_nutrition_macros), first = true)
        val w = s.week
        if (w != null) {
            macros.addView(
                Views.caption(ctx, resources.getQuantityString(R.plurals.day_nutrition_week_logged, w.logged, w.logged)).apply { tag = "week_logged" },
                Views.matchWrap(ctx),
            )
        }
        val toggle = Views.segments(ctx, listOf(getString(R.string.day_nutrition_consumed), getString(R.string.day_nutrition_remaining))) { i ->
            vm.setRemaining(i == 1)
        }
        Views.selectSegment(toggle, if (s.remaining) 1 else 0)
        macros.addView(toggle, Views.matchWrap(ctx))
        for (f in MACROS) {
            macros.addView(row(s, f, showLeft = s.remaining), Views.matchWrap(ctx, 8))
            if (w != null) macros.addView(weekBars(w, f, s.targets[f]), Views.matchWrap(ctx, 4))
        }

        // The diet's own targets.
        val focus = NutrientTargets.focus(s.prefs)
        val yours = Views.card(content, getString(R.string.day_nutrition_your_targets))
        if (focus.isEmpty()) {
            yours.addView(Views.caption(ctx, getString(R.string.day_nutrition_no_targets)), Views.matchWrap(ctx))
        } else {
            for (f in focus) {
                yours.addView(row(s, f, showLeft = false), Views.matchWrap(ctx, 8))
                if (w != null) yours.addView(weekBars(w, f, s.targets[f]), Views.matchWrap(ctx, 4))
            }
        }

        // Every nutrient, grouped.
        val all = Views.card(content, getString(R.string.day_nutrition_all))
        for ((key, fields) in NutrientTargets.GROUPS) {
            all.addView(
                FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, getString(NutrientUi.group(key))).also { ViewCompat.setAccessibilityHeading(it, true) },
                Views.matchWrap(ctx, 12),
            )
            for (f in fields) all.addView(row(s, f, showLeft = false), Views.matchWrap(ctx, 6))
        }
    }

    /** Seven bars, one per day, with the target as a dashed line; days with nothing logged stay empty. */
    private fun weekBars(w: WeekTotals, f: Field, t: Target?): View {
        val ctx = requireContext()
        val locale = resources.configuration.locales[0]
        val values = w.perDay(f)
        val p = ChartPalette.of(ctx)
        val avg = w.average(f)
        val color = if (t != null && f in NutrientTargets.LIMITS) NutrientUi.statusColor(ctx, t.status(avg)) else p.primary
        val name = getString(NutrientUi.name(f))
        return BarsView(ctx).apply {
            tag = "week_bars_${f.name.lowercase()}"
            series = listOf(BarSeries(name, color, IntArray(values.size) { values[it] ?: 0 }))
            labels = w.days.map { it.toLocalDate().dayOfWeek.getDisplayName(TextStyle.NARROW, locale) }
            goalLine = t?.reference?.toFloat()
            contentDescription = getString(R.string.day_nutrition_week_bars, name) + ": " + w.days.indices.joinToString("; ") { i ->
                val dayName = w.days[i].toLocalDate().dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                val v = values[i]
                val amount = when {
                    w.totals[i] == null -> getString(R.string.day_nutrition_week_not_logged)
                    else -> NutrientUi.amount(ctx, f, v)
                }
                getString(R.string.day_nutrition_week_bar_day, dayName, amount)
            }
        }
    }

    /** One nutrient row (see [NutrientRows.row]); tap: the foods behind it. */
    private fun row(s: DayNutritionState, f: Field, showLeft: Boolean): View =
        NutrientRows.row(requireContext(), s, f, showLeft) { NutrientRows.showTopFoods(requireContext(), s, f) }

    /** Arguments. */
    companion object {
        private const val ARG_DAY = "day"
        private const val DISABLED_ALPHA = 0.38f
        private val MACROS = listOf(Field.KCAL, Field.PROTEIN, Field.CARB, Field.FAT)

        /** Day nutrition for [day]. */
        fun newInstance(day: Day): DayNutritionFragment = DayNutritionFragment().apply {
            arguments = Bundle().apply { putInt(ARG_DAY, day.yyyymmdd) }
        }
    }
}
