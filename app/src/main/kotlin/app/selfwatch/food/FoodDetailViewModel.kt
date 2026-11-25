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
import app.selfwatch.food.nutrition.DietConflict
import app.selfwatch.food.nutrition.DietPrefs
import app.selfwatch.food.nutrition.NutrientMath
import app.selfwatch.food.nutrition.NutrientTargets
import app.selfwatch.food.nutrition.Target
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.MealSlot
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Serving
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Day
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the food detail sheet is for: logging a food, or editing a diary entry. */
sealed interface FoodDetailTarget {
    /** Log [ref] on [day] in meal [slot] (0 = guess from the time of day). */
    data class Add(val ref: FoodRef, val day: Day?, val slot: Long) : FoodDetailTarget

    /** Edit diary entry [entryId]. */
    data class Edit(val entryId: Long) : FoodDetailTarget
}

/** Food detail state. */
data class FoodDetailState(
    val loading: Boolean = true,
    /** The food (null for a quick-add entry, or while loading, or when it is gone). */
    val food: Food? = null,
    /** The entry being edited (edit mode only). */
    val entry: DiaryEntry? = null,
    val servings: List<Serving> = emptyList(),
    val servingIndex: Int = 0,
    val amountX100: Int = FoodRows.ONE_X100,
    val slots: List<MealSlot> = emptyList(),
    val slotId: Long = 0,
    val day: Day? = null,
    /** Nutrients of the chosen amount; null when they cannot be worked out. */
    val preview: Nutrients? = null,
    val favourite: Boolean = false,
    /** True when the food could not be found. */
    val missing: Boolean = false,
    /** The user's diets and allergens. */
    val prefs: DietPrefs = DietPrefs.NONE,
    /** Daily targets for the "% of your day" bars and the nutrition facts. */
    val targets: Map<Nutrients.Field, Target> = emptyMap(),
) {
    /** The food's known clashes with the user's diet. */
    val conflicts: List<DietConflict> get() = prefs.conflicts(food?.dietInfo)

    /** The chosen serving. */
    val serving: Serving? get() = servings.getOrNull(servingIndex)

    /** True in edit mode. */
    val isEdit: Boolean get() = entry != null
}

/** One-shot food detail events. */
sealed interface FoodDetailEvent {
    /** Logged or saved; [entryId] is the (new) entry. */
    data class Saved(val name: String, val entryId: Long, val added: Boolean) : FoodDetailEvent

    /** Something failed. */
    data object Failed : FoodDetailEvent
}

/** Food detail / entry editor: serving picker, amount, meal, preview, favourite. */
class FoodDetailViewModel(
    private val target: FoodDetailTarget,
    private val backendProvider: () -> FoodBackend,
) : ViewModel() {

    private val backend: FoodBackend by lazy(backendProvider)
    private val mutable = MutableStateFlow(FoodDetailState())
    private val eventChannel = Channel<FoodDetailEvent>(Channel.BUFFERED)

    /** Screen state. */
    val state: StateFlow<FoodDetailState> = mutable.asStateFlow()

    /** One-shot events. */
    val events: Flow<FoodDetailEvent> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            try {
                mutable.value = load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "load failed", e)
                mutable.value = FoodDetailState(loading = false, missing = true)
            }
        }
    }

    private suspend fun load(): FoodDetailState {
        val slots = backend.mealSlots()
        val prefs = backend.dietPrefs()
        val units = backend.displayUnits()
        return when (target) {
            is FoodDetailTarget.Add -> {
                val food = backend.food(target.ref) ?: return FoodDetailState(loading = false, missing = true, slots = slots)
                val options = FoodRows.detailServingOptions(food, units).toMutableList()
                val (serving, amount) = FoodRows.quickServing(food, backend.lastServing(target.ref))
                var index = options.indexOf(serving)
                if (index < 0) {
                    options.add(0, serving)
                    index = 0
                }
                val day = target.day ?: backend.clock.today()
                val slot = if (target.slot != 0L) target.slot else guessSlot(slots)
                withPreview(
                    FoodDetailState(
                        loading = false,
                        food = food,
                        servings = options,
                        servingIndex = index,
                        amountX100 = amount,
                        slots = slots,
                        slotId = slot,
                        day = day,
                        favourite = backend.isFavourite(target.ref),
                        prefs = prefs,
                        targets = NutrientTargets.of(backend.targetInputs(day)),
                    ),
                )
            }
            is FoodDetailTarget.Edit -> {
                val entry = backend.entry(target.entryId) ?: return FoodDetailState(loading = false, missing = true, slots = slots)
                val food = entry.food?.let { backend.food(it) }
                val perServing = entry.gramsX10?.let {
                    NutrientMath.clampInt(NutrientMath.mulDivRound(it.toLong(), 100L, entry.amountX100.toLong().coerceAtLeast(1)))
                }
                val logged = Serving(-1, entry.servingLabel, perServing)
                val options = if (food != null) FoodRows.detailServingOptions(food, units).toMutableList() else mutableListOf(logged)
                var index = options.indexOfFirst { it.label == entry.servingLabel }
                if (index < 0) {
                    options.add(0, logged)
                    index = 0
                }
                withPreview(
                    FoodDetailState(
                        loading = false,
                        food = food,
                        entry = entry,
                        servings = options,
                        servingIndex = index,
                        amountX100 = entry.amountX100,
                        slots = slots,
                        slotId = entry.mealSlotId,
                        day = entry.day,
                        favourite = entry.food?.let { backend.isFavourite(it) } ?: false,
                        prefs = prefs,
                        targets = NutrientTargets.of(backend.targetInputs(entry.day)),
                    ),
                )
            }
        }
    }

    private fun guessSlot(slots: List<MealSlot>): Long {
        val hour = java.time.Instant.ofEpochSecond(backend.clock.nowSec()).atZone(backend.clock.zone()).hour
        return FoodRows.slotForHour(hour, slots)?.id ?: slots.firstOrNull()?.id ?: 1L
    }

    /** Picks serving [index]; switching between grams and a named serving resets the amount. */
    fun selectServing(index: Int) {
        mutable.update { s ->
            val next = s.servings.getOrNull(index) ?: return@update s
            val wasGram = s.serving == NutrientMath.GRAM
            val isGram = next == NutrientMath.GRAM
            val amount = when {
                isGram && !wasGram -> FoodRows.HUNDRED_GRAMS_X100
                !isGram && wasGram -> FoodRows.ONE_X100
                else -> s.amountX100
            }
            withPreview(s.copy(servingIndex = index, amountX100 = amount))
        }
    }

    /** Sets the amount (×100). */
    fun setAmount(amountX100: Int) {
        mutable.update { withPreview(it.copy(amountX100 = amountX100)) }
    }

    /** Picks the meal. */
    fun selectSlot(slotId: Long) {
        mutable.update { it.copy(slotId = slotId) }
    }

    /** Adds or removes the food from favourites. */
    fun toggleFavourite() {
        val s = mutable.value
        val ref = s.food?.ref ?: return
        viewModelScope.launch {
            try {
                backend.setFavourite(ref, !s.favourite)
                mutable.update { it.copy(favourite = !s.favourite) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "favourite failed", e)
                eventChannel.send(FoodDetailEvent.Failed)
            }
        }
    }

    /** Logs the food, or saves the edited entry. */
    fun save() {
        val s = mutable.value
        val serving = s.serving ?: return
        if (s.amountX100 <= 0) return
        viewModelScope.launch {
            try {
                val entry = s.entry
                val food = s.food
                val day = s.day ?: backend.clock.today()
                if (entry == null) {
                    if (food == null) return@launch
                    val id = backend.logFood(day, s.slotId, food, serving, s.amountX100)
                    eventChannel.send(FoodDetailEvent.Saved(food.name, id, added = true))
                    return@launch
                }
                val servingChanged = serving.label != entry.servingLabel
                var id = entry.id
                if (servingChanged && food != null) {
                    backend.delete(entry.id)
                    id = backend.logFood(entry.day, s.slotId, food, serving, s.amountX100)
                } else {
                    if (s.amountX100 != entry.amountX100) backend.changeAmount(entry.id, s.amountX100)
                    if (s.slotId != entry.mealSlotId) backend.move(entry.id, s.slotId, entry.day)
                }
                eventChannel.send(FoodDetailEvent.Saved(entry.name, id, added = false))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "save failed", e)
                eventChannel.send(FoodDetailEvent.Failed)
            }
        }
    }

    /** Constants and the preview rule. */
    companion object {
        private const val TAG = "FoodDetailVM"

        /** [s] with its preview worked out for the chosen serving and amount. */
        fun withPreview(s: FoodDetailState): FoodDetailState {
            val serving = s.serving ?: return s.copy(preview = null)
            val food = s.food
            val entry = s.entry
            val preview = when {
                s.amountX100 <= 0 -> null
                food != null -> NutrientMath.forServing(food, serving, s.amountX100)
                entry != null && entry.amountX100 > 0 ->
                    NutrientMath.scale(entry.nutrients, s.amountX100.toLong(), entry.amountX100.toLong())
                else -> null
            }
            return s.copy(preview = preview)
        }
    }
}
