/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.selfwatch.food.nutrition.DietFilter
import app.selfwatch.food.nutrition.DietPrefs
import app.selfwatch.food.nutrition.FilterFit
import app.selfwatch.food.source.FoodHit
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.MealSlot
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Day
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Add food tabs, in order. */
enum class AddFoodTab { RECENT, FAVORITES, MINE, RECIPES }

/** Add food state. */
data class AddFoodState(
    /** Day and meal being added to. */
    val day: Day? = null,
    val slot: MealSlot? = null,
    val query: String = "",
    /** Search results while a query is typed (null when the search box is empty). */
    val results: List<FoodHit>? = null,
    val searching: Boolean = false,
    val tab: AddFoodTab = AddFoodTab.RECENT,
    val tabRows: List<FoodHit> = emptyList(),
    val tabLoading: Boolean = true,
    /** The user's diets and allergens (rows that clash get a red badge). */
    val prefs: DietPrefs = DietPrefs.NONE,
    /** The filter chips on offer (only with a diet or allergen set). */
    val filters: List<DietFilter> = emptyList(),
    /** The chips switched on (all of them to start with). */
    val activeFilters: Set<DietFilter> = emptySet(),
) {
    /** Results when searching, else the tab, before the diet filters. */
    val unfiltered: List<FoodHit> get() = results ?: tabRows

    /** The rows on screen: [unfiltered] without the foods known to clash with an active filter. */
    val rows: List<FoodHit>
        get() = if (activeFilters.isEmpty()) unfiltered else unfiltered.filter { DietFilter.fitAll(it.dietInfo, activeFilters) != FilterFit.CONFLICTS }

    /** Rows whose diet data is not known for an active filter (marked "?"). */
    val unsure: Set<FoodRef>
        get() = if (activeFilters.isEmpty()) emptySet() else rows.filter { DietFilter.fitAll(it.dietInfo, activeFilters) == FilterFit.UNKNOWN }.mapTo(HashSet()) { it.ref }

    /** How many rows the active filters hide. */
    val hiddenCount: Int get() = unfiltered.size - rows.size

    /** The chain whose menu the search shows (brand mode, "McDonald's menu"), or null. */
    val chain: String? get() = results?.firstOrNull { it.chain != null }?.chain
}

/** One-shot Add food events. */
sealed interface AddFoodEvent {
    /** [name] was logged as [entryId] ([kcal] kcal); offer Undo. */
    data class Logged(val name: String, val entryId: Long, val kcal: Int) : AddFoodEvent

    /** Open the food detail for [hit]. */
    data class OpenDetail(val hit: FoodHit) : AddFoodEvent

    /** Something failed. */
    data object Failed : AddFoodEvent
}

/**
 * Add food: search (debounced 150 ms, streaming every source), the Recent / Favorites / My foods /
 * Recipes tabs, and one-tap "+" that logs the last serving (or the default serving).
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class AddFoodViewModel(
    private val day: Day?,
    private val slotId: Long,
    private val backendProvider: () -> FoodBackend,
) : ViewModel() {

    private val backend: FoodBackend by lazy(backendProvider)
    private val mutable = MutableStateFlow(AddFoodState())
    private val queries = MutableStateFlow("")
    private val eventChannel = Channel<AddFoodEvent>(Channel.BUFFERED)

    /** Screen state. */
    val state: StateFlow<AddFoodState> = mutable.asStateFlow()

    /** One-shot events. */
    val events: Flow<AddFoodEvent> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            val slots = backend.mealSlots()
            val hour = java.time.Instant.ofEpochSecond(backend.clock.nowSec()).atZone(backend.clock.zone()).hour
            val slot = slots.firstOrNull { it.id == slotId } ?: FoodRows.slotForHour(hour, slots) ?: slots.firstOrNull()
            mutable.update { it.copy(day = day ?: backend.clock.today(), slot = slot) }
            val prefs = try {
                backend.dietPrefs()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "diet failed", e)
                DietPrefs.NONE
            }
            val filters = DietFilter.forPrefs(prefs)
            mutable.update { it.copy(prefs = prefs, filters = filters, activeFilters = filters.toSet()) }
        }
        viewModelScope.launch {
            queries
                .debounce(DEBOUNCE_MS)
                .map { it.trim() }
                .distinctUntilChanged()
                .onEach { q -> mutable.update { it.copy(searching = q.isNotEmpty()) } }
                .flatMapLatest { q ->
                    if (q.isEmpty()) {
                        flowOf(null)
                    } else {
                        backend.search(q).catch { e ->
                            SwLog.w(TAG, "search failed", e)
                            emit(emptyList())
                        }
                    }
                }
                .collect { hits -> mutable.update { it.copy(results = hits, searching = false) } }
        }
        loadTab(AddFoodTab.RECENT)
    }

    /** The search box changed. */
    fun setQuery(q: String) {
        mutable.update { it.copy(query = q, results = if (q.isBlank()) null else it.results) }
        queries.value = q
    }

    /** Switches filter chip [filter] on or off. */
    fun setFilter(filter: DietFilter, on: Boolean) = mutable.update { s ->
        if (filter !in s.filters) s else s.copy(activeFilters = if (on) s.activeFilters + filter else s.activeFilters - filter)
    }

    /** Switches tab. */
    fun selectTab(tab: AddFoodTab) = loadTab(tab)

    /** Reloads the current tab (after returning from another screen). */
    fun refresh() = loadTab(mutable.value.tab)

    private fun loadTab(tab: AddFoodTab) {
        mutable.update { it.copy(tab = tab, tabLoading = true) }
        viewModelScope.launch {
            val rows = try {
                when (tab) {
                    AddFoodTab.RECENT -> backend.recents(RECENT_LIMIT)
                    AddFoodTab.FAVORITES -> backend.favourites()
                    AddFoodTab.MINE -> backend.myFoods()
                    AddFoodTab.RECIPES -> backend.recipeHits()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "tab load failed", e)
                emptyList()
            }
            mutable.update { if (it.tab == tab) it.copy(tabRows = rows, tabLoading = false) else it }
        }
    }

    /** Row tapped: open the detail sheet. */
    fun open(hit: FoodHit) {
        viewModelScope.launch { eventChannel.send(AddFoodEvent.OpenDetail(hit)) }
    }

    /**
     * One-tap "+": logs [hit] with the serving and amount it was last logged with, else its default
     * serving, into the chosen meal. Opens the detail instead when the food cannot be logged as is.
     */
    fun quickLog(hit: FoodHit) {
        viewModelScope.launch {
            try {
                val food = backend.food(hit.ref)
                if (food == null) {
                    eventChannel.send(AddFoodEvent.Failed)
                    return@launch
                }
                val (serving, amount) = FoodRows.quickServing(food, backend.lastServing(hit.ref))
                val s = mutable.value
                val slot = s.slot?.id ?: slotId
                val d = s.day ?: backend.clock.today()
                val id = try {
                    backend.logFood(d, slot, food, serving, amount)
                } catch (e: IllegalArgumentException) {
                    SwLog.w(TAG, "cannot log as is; opening detail", e)
                    eventChannel.send(AddFoodEvent.OpenDetail(hit))
                    return@launch
                }
                val kcal = FoodRows.kcal(app.selfwatch.food.nutrition.NutrientMath.forServing(food, serving, amount))
                eventChannel.send(AddFoodEvent.Logged(food.name, id, kcal))
                if (s.tab == AddFoodTab.RECENT && s.results == null) loadTab(AddFoodTab.RECENT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "quick log failed", e)
                eventChannel.send(AddFoodEvent.Failed)
            }
        }
    }

    /** Undoes a one-tap log. */
    fun undo(entryId: Long) {
        viewModelScope.launch {
            try {
                val gone: DiaryEntry? = backend.delete(entryId)
                if (gone != null && mutable.value.tab == AddFoodTab.RECENT) loadTab(AddFoodTab.RECENT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "undo failed", e)
            }
        }
    }

    /** Constants. */
    companion object {
        private const val TAG = "AddFoodVM"

        /** Typing pause before a search runs. */
        const val DEBOUNCE_MS: Long = 150L

        /** Rows in the Recent tab. */
        const val RECENT_LIMIT: Int = 30
    }
}
