/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.AppGraph
import app.selfwatch.db.SettingKeys
import app.selfwatch.food.budget.Budget
import app.selfwatch.food.diary.DiaryDay
import app.selfwatch.food.diary.FastStatus
import app.selfwatch.food.diary.WaterDay
import app.selfwatch.food.nutrition.Diet
import app.selfwatch.food.nutrition.VegetarianHint
import app.selfwatch.interop.api.OutputSink
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.MealSlot
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Day
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** "Add yesterday's dinner": yesterday's [count] entries in this meal, [kcal] in total. */
data class YesterdayOffer(val count: Int, val kcal: Int)

/** One meal section of the diary. [yesterday] is non-null when the copy-yesterday line should show; [totals] are the meal's nutrients. */
data class MealUi(
    val slot: MealSlot,
    val entries: List<DiaryEntry>,
    val kcal: Int,
    val yesterday: YesterdayOffer?,
    val totals: Nutrients = Nutrients.UNKNOWN,
    /** "Recent for <meal>": foods often logged in this meal and not in it yet, each as its last diary line. */
    val recents: List<DiaryEntry> = emptyList(),
)

/** Food diary state. */
data class DiaryUiState(
    /** Food tracking switch; null while loading. */
    val enabled: Boolean? = null,
    /** The day shown. */
    val day: Day? = null,
    /** Today, for the day label and the "next day" limit. */
    val today: Day? = null,
    /** Meal sections in display order. */
    val meals: List<MealUi> = emptyList(),
    /** Total kcal eaten on [day]. */
    val eatenKcal: Int = 0,
    /** Water for [day]. */
    val water: WaterDay? = null,
    /** The running fast (as of its last change; screens re-time it with the clock). */
    val fast: FastStatus? = null,
    /** Energy budget for [day]. */
    val budget: Budget? = null,
) {
    /** True when [day] is today (no "next day"). */
    val isToday: Boolean get() = day != null && day == today
}

/** One-shot diary events. */
sealed interface DiaryEvent {
    /** An entry was deleted; offer Undo. */
    data class Deleted(val entry: DiaryEntry) : DiaryEvent

    /** A recent food ([name]) was logged into meal [slot] as entry [id]; offer Undo. */
    data class Logged(val name: String, val slot: Long, val id: Long) : DiaryEvent

    /** [count] entries were copied from yesterday. */
    data class Copied(val count: Int) : DiaryEvent

    /** "Copy day": [ids] are the new entries on [to] (for Undo). */
    data class CopiedTo(val to: Day, val ids: List<Long>) : DiaryEvent

    /** A copy was undone. */
    data object CopyUndone : DiaryEvent

    /** Nothing to copy or export on the day shown. */
    data object NothingLogged : DiaryEvent

    /** "Export day" wrote [count] entries. */
    data class Exported(val count: Int) : DiaryEvent

    /** Something failed. */
    data object Failed : DiaryEvent
}

/**
 * Food diary view model: the chosen day's meals with "copy yesterday" offers, water, the running fast
 * and the energy budget. The [backend] (and so the food graph) is only asked for when food tracking is
 * on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiaryViewModel(
    foodEnabled: Flow<Boolean>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val backendProvider: () -> FoodBackend,
) : ViewModel() {

    private val backend: FoodBackend by lazy(backendProvider)
    private val selectedDay = MutableStateFlow<Day?>(null)
    private val eventChannel = Channel<DiaryEvent>(Channel.BUFFERED)

    /** One-shot events (undo snackbar, copy result). */
    val events: Flow<DiaryEvent> = eventChannel.receiveAsFlow()

    /** Screen state. */
    val state: StateFlow<DiaryUiState> = foodEnabled
        .catch { emit(true) }
        .flatMapLatest { enabled ->
            if (!enabled) flowOf(DiaryUiState(enabled = false)) else dayState()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DiaryUiState())

    private fun dayState(): Flow<DiaryUiState> = selectedDay.flatMapLatest { chosen ->
        val today = backend.clock.today()
        val day = chosen ?: today
        val yesterday = day.plusDays(-1)
        combine(
            backend.diaryDay(day),
            backend.diaryDay(yesterday),
            backend.waterDay(day).onStart<WaterDay?> { emit(null) },
            backend.fastStatus().onStart { emit(null) },
        ) { d, y, water, fast ->
            val budget = try {
                backend.budget(day)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "budget failed", e)
                null
            }
            val history = try {
                backend.entriesBetween(day.plusDays(-(MealMath.RECENT_DAYS - 1)), day)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "meal history failed", e)
                emptyList()
            }
            DiaryUiState(
                enabled = true,
                day = day,
                today = today,
                meals = meals(d, y).map { m -> m.copy(recents = MealMath.recents(history, m.slot.id, m.entries)) },
                eatenKcal = FoodRows.kcal(d.totals),
                water = water,
                fast = fast,
                budget = budget,
            )
        }
    }

    /** Shows the day before. */
    fun previousDay() {
        val d = state.value.day ?: backend.clock.today()
        selectedDay.value = d.plusDays(-1)
    }

    /** Shows the day after (never past today). */
    fun nextDay() {
        val today = backend.clock.today()
        val d = state.value.day ?: today
        if (d < today) selectedDay.value = d.plusDays(1)
    }

    /** Shows [day] (clamped to today). */
    fun showDay(day: Day) {
        val today = backend.clock.today()
        selectedDay.value = if (day > today) today else day
    }

    /** Jumps back to today. */
    fun goToToday() {
        selectedDay.value = backend.clock.today()
    }

    /** Copies yesterday's [slot] into the day shown. */
    fun copyYesterday(slot: Long) = act {
        val day = state.value.day ?: backend.clock.today()
        val n = backend.copyMeal(day.plusDays(-1), slot, day)
        eventChannel.send(DiaryEvent.Copied(n))
    }

    /**
     * "Copy day": copies the day shown (only meal [slot] when not null) into [to] and offers Undo.
     * Copying a day onto itself does nothing.
     */
    fun copyTo(to: Day, slot: Long? = null) = act {
        val from = state.value.day ?: backend.clock.today()
        if (to == from) return@act
        val ids = backend.copyEntries(from, to, slot)
        eventChannel.send(if (ids.isEmpty()) DiaryEvent.NothingLogged else DiaryEvent.CopiedTo(to, ids))
    }

    /** Undoes a [copyTo]: removes the copied entries. */
    fun undoCopy(ids: List<Long>) = act {
        backend.deleteEntries(ids)
        eventChannel.send(DiaryEvent.CopyUndone)
    }

    /** "Export day": writes [day]'s diary as CSV to [out]. */
    fun exportDay(day: Day, out: OutputSink, zone: ZoneId = ZoneId.systemDefault()) = act {
        val rows = DayExport.rows(backend.diaryDay(day).first(), zone)
        if (rows.isEmpty()) {
            eventChannel.send(DiaryEvent.NothingLogged)
            return@act
        }
        withContext(io) { out.open().use { DayExport.write(it, rows) } }
        eventChannel.send(DiaryEvent.Exported(rows.size))
    }

    /** True when the day shown has at least one entry. */
    fun hasEntries(): Boolean = state.value.meals.any { it.entries.isNotEmpty() }

    /** Deletes [entry] and offers Undo. */
    fun delete(entry: DiaryEntry) = act {
        val gone = backend.delete(entry.id) ?: return@act
        eventChannel.send(DiaryEvent.Deleted(gone))
    }

    /** Puts a deleted entry back. */
    fun undoDelete(entry: DiaryEntry) = act { backend.restore(entry) }

    /** "Recent for <meal>" +: logs [last]'s food into [slot] on the day shown, with its last serving, and offers Undo. */
    fun logRecent(slot: Long, last: DiaryEntry) = act {
        val day = state.value.day ?: backend.clock.today()
        val id = QuickLog.relog(backend, last, day, slot)
        eventChannel.send(DiaryEvent.Logged(last.name, slot, id))
    }

    /** Undoes a [logRecent]. */
    fun undoLog(id: Long) = act { backend.deleteEntries(listOf(id)) }

    /** Adds one glass of water to the day shown. */
    fun addWater() = act { backend.addWater(GLASS_ML, state.value.day ?: backend.clock.today()) }

    /** Removes the last glass of the day shown. */
    fun removeWater() = act { backend.removeWater(state.value.day ?: backend.clock.today()) }

    /** Taps on water square [index] (0-based): fills up to it, or empties it when it is the last full one. */
    fun tapWater(index: Int) {
        val glasses = (state.value.water?.totalMl ?: 0) / GLASS_ML
        if (index < glasses) removeWater() else addWater()
    }

    /** Starts a fast, or ends the running one. */
    fun toggleFast() = act {
        if (state.value.fast != null) backend.endFast() else backend.startFast(DEFAULT_FAST_MIN)
    }

    private val vegHintFlow = MutableStateFlow(false)
    private var vegHintChecked = false

    /** True while the one-time "Set vegetarian?" card should show (FOOD_UI_PLAN §4.5). */
    val vegHint: StateFlow<Boolean> = vegHintFlow

    /**
     * Works out once per screen whether to offer "Set vegetarian?": [VegetarianHint.DAYS] logged days in
     * the last [VegetarianHint.LOOKBACK_DAYS] with no meat, no vegetarian or vegan diet set, never
     * answered before.
     */
    fun checkVegHint() {
        if (vegHintChecked) return
        vegHintChecked = true
        viewModelScope.launch {
            try {
                if (backend.vegHintDone()) return@launch
                val prefs = backend.dietPrefs()
                if (Diet.VEGETARIAN in prefs.diets || Diet.VEGAN in prefs.diets) return@launch
                val today = backend.clock.today()
                val days = withContext(io) { backend.loggedCategories(today.plusDays(-(VegetarianHint.LOOKBACK_DAYS - 1)), today) }
                vegHintFlow.value = VegetarianHint.shouldOffer(days, prefs, dismissed = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "vegetarian hint check failed", e)
            }
        }
    }

    /** Answers the vegetarian offer: [accept] adds the vegetarian diet. Either way it never shows again. */
    fun answerVegHint(accept: Boolean) {
        vegHintFlow.value = false
        act {
            if (accept) {
                val prefs = backend.dietPrefs()
                backend.setDietPrefs(prefs.copy(diets = prefs.diets + Diet.VEGETARIAN))
            }
            backend.setVegHintDone()
        }
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "diary action failed", e)
                eventChannel.send(DiaryEvent.Failed)
            }
        }
    }

    /** Constants, the meal rule and the factory. */
    companion object {
        private const val TAG = "DiaryViewModel"
        private const val STOP_TIMEOUT_MS = 5_000L

        /** One water square here (a metric glass; the cards use [WaterGlass.ml] for the user's units). */
        const val GLASS_ML: Int = WaterGlass.METRIC_ML

        /** Squares shown when no water goal is set. */
        const val DEFAULT_GLASSES: Int = WaterGlass.DEFAULT_GLASSES

        /** Default fast: 16 hours. */
        const val DEFAULT_FAST_MIN: Int = 16 * 60

        /**
         * The meal sections of [today] with a copy offer wherever [yesterday] had entries in that meal
         * and [today] has none.
         */
        fun meals(today: DiaryDay, yesterday: DiaryDay): List<MealUi> {
            val prev = yesterday.meals.associateBy { it.slot.id }
            return today.meals.map { m ->
                val y = prev[m.slot.id]
                val offer = if (m.entries.isEmpty() && y != null && y.entries.isNotEmpty()) {
                    YesterdayOffer(y.entries.size, FoodRows.kcal(y.totals))
                } else {
                    null
                }
                MealUi(m.slot, m.entries, FoodRows.kcal(m.totals), offer, m.totals)
            }
        }

        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DiaryViewModel(
                    graph.settings.observe(SettingKeys.FOOD_ENABLED).map { it?.toBooleanStrictOrNull() ?: true },
                ) { GraphFoodBackend(graph.food) }
            }
        }
    }
}
