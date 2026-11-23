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
import app.selfwatch.food.barcode.Gtin
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Serving
import app.selfwatch.model.log.SwLog
import app.selfwatch.ui.format.Fmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** The custom food form as typed (decimal text in the phone's locale; empty means unknown). */
data class FoodForm(
    val name: String = "",
    val brand: String = "",
    /** True: nutrients per serving; false: per 100 g (or 100 ml). */
    val perServing: Boolean = true,
    val servingLabel: String = "",
    val servingGrams: String = "",
    val kcal: String = "",
    val protein: String = "",
    val fat: String = "",
    val satfat: String = "",
    val carbs: String = "",
    val sugar: String = "",
    val fibre: String = "",
    val sodiumMg: String = "",
    val cholesterolMg: String = "",
    val barcode: String = "",
)

/** Form problems. */
enum class FoodFormError { NAME, KCAL, SERVING_GRAMS, BARCODE }

/** Editor state. */
data class FoodEditorState(
    val loading: Boolean = true,
    val form: FoodForm = FoodForm(),
    /** Id of the custom food being edited; 0 for a new one. */
    val id: Long = 0,
)

/** Editor events. */
sealed interface FoodEditorEvent {
    /** Saved as custom food [id]. */
    data class Saved(val id: Long) : FoodEditorEvent

    /** Deleted. */
    data object Deleted : FoodEditorEvent

    /** Form has problems. */
    data class Invalid(val errors: Set<FoodFormError>) : FoodEditorEvent

    /** Something failed. */
    data object Failed : FoodEditorEvent
}

/**
 * Custom food editor: name, brand, one serving, nutrients and barcode. [source] is the food to edit
 * (a custom food) or to copy (any other food); null starts empty with [barcode] filled in.
 */
class FoodEditorViewModel(
    private val source: FoodRef?,
    private val barcode: Long?,
    private val backendProvider: () -> FoodBackend,
) : ViewModel() {

    private val backend: FoodBackend by lazy(backendProvider)
    private val mutable = MutableStateFlow(FoodEditorState())
    private val eventChannel = Channel<FoodEditorEvent>(Channel.BUFFERED)
    private var extraServings: List<Serving> = emptyList()
    private var per100Basis: Basis = Basis.PER_100G

    /** Screen state. */
    val state: StateFlow<FoodEditorState> = mutable.asStateFlow()

    /** One-shot events. */
    val events: Flow<FoodEditorEvent> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            val food = try {
                source?.let { backend.food(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "load failed", e)
                null
            }
            if (food == null) {
                mutable.value = FoodEditorState(loading = false, form = FoodForm(barcode = barcode?.let { Gtin.shortForm(it) }.orEmpty()))
                return@launch
            }
            val sorted = food.servings.sortedBy { it.seq }
            extraServings = sorted.drop(1)
            if (food.basis == Basis.PER_100ML) per100Basis = Basis.PER_100ML
            val id = if (food.ref.origin == FoodOrigin.MY_FOOD) food.ref.id else 0L
            mutable.value = FoodEditorState(loading = false, form = formOf(food), id = id)
        }
    }

    /** Validates and saves [form]. */
    fun save(form: FoodForm) {
        val result = toFood(form, mutable.value.id, per100Basis, extraServings)
        val food = result.first
        if (food == null) {
            viewModelScope.launch { eventChannel.send(FoodEditorEvent.Invalid(result.second)) }
            return
        }
        viewModelScope.launch {
            try {
                val id = backend.saveCustomFood(food)
                mutable.value = mutable.value.copy(form = form, id = id)
                eventChannel.send(FoodEditorEvent.Saved(id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "save failed", e)
                eventChannel.send(FoodEditorEvent.Failed)
            }
        }
    }

    /** Deletes the custom food being edited. */
    fun delete() {
        val id = mutable.value.id
        if (id == 0L) return
        viewModelScope.launch {
            try {
                backend.deleteCustomFood(id)
                eventChannel.send(FoodEditorEvent.Deleted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "delete failed", e)
                eventChannel.send(FoodEditorEvent.Failed)
            }
        }
    }

    /** Constants and the form rules. */
    companion object {
        private const val TAG = "FoodEditorVM"

        /** [food] as form text. */
        fun formOf(food: Food): FoodForm {
            val first = food.servings.minByOrNull { it.seq }
            fun g(x10: Int?) = x10?.let { Fmt.x100(it * 10L, 1) }.orEmpty()
            fun mg(v: Int?) = v?.toString().orEmpty()
            return FoodForm(
                name = food.name,
                brand = food.brand.orEmpty(),
                perServing = food.basis == Basis.PER_ITEM,
                servingLabel = first?.label.orEmpty(),
                servingGrams = g(first?.gramsX10),
                kcal = g(food.per.kcalX10),
                protein = g(food.per.proteinX10),
                fat = g(food.per.fatX10),
                satfat = g(food.per.satfatX10),
                carbs = g(food.per.carbX10),
                sugar = g(food.per.sugarX10),
                fibre = g(food.per.fibreX10),
                sodiumMg = mg(food.per.sodiumMg),
                cholesterolMg = mg(food.per.cholesterolMg),
                barcode = food.barcode?.let { Gtin.shortForm(it) }.orEmpty(),
            )
        }

        /**
         * The food [form] describes (custom food [id], 0 for new), or null with the problems found:
         * a name and calories are required; a typed serving weight must parse; a typed barcode must be
         * a valid GTIN.
         */
        fun toFood(form: FoodForm, id: Long, per100Basis: Basis = Basis.PER_100G, extra: List<Serving> = emptyList()): Pair<Food?, Set<FoodFormError>> {
            val errors = HashSet<FoodFormError>()
            if (form.name.isBlank()) errors.add(FoodFormError.NAME)
            val kcal = x10(form.kcal)
            if (kcal == null || kcal < 0) errors.add(FoodFormError.KCAL)
            val grams = if (form.servingGrams.isBlank()) null else x10(form.servingGrams)
            if (form.servingGrams.isNotBlank() && (grams == null || grams <= 0)) errors.add(FoodFormError.SERVING_GRAMS)
            val code = if (form.barcode.isBlank()) null else Gtin.normalize(form.barcode)
            if (form.barcode.isNotBlank() && code == null) errors.add(FoodFormError.BARCODE)
            if (errors.isNotEmpty()) return null to errors
            val label = form.servingLabel.trim().ifEmpty { if (grams != null) "" else FoodRows.ONE_SERVING }
            val servings = ArrayList<Serving>()
            if (label.isNotEmpty() || grams != null) {
                servings.add(Serving(0, label.ifEmpty { FoodRows.ONE_SERVING }, grams))
            }
            extra.forEachIndexed { i, s -> servings.add(s.copy(seq = i + 1)) }
            val food = Food(
                ref = FoodRef(FoodOrigin.MY_FOOD, id),
                name = form.name.trim(),
                brand = form.brand.trim().ifEmpty { null },
                kind = 0,
                basis = if (form.perServing) Basis.PER_ITEM else per100Basis,
                per = Nutrients(
                    kcalX10 = kcal,
                    proteinX10 = x10(form.protein),
                    fatX10 = x10(form.fat),
                    satfatX10 = x10(form.satfat),
                    carbX10 = x10(form.carbs),
                    sugarX10 = x10(form.sugar),
                    fibreX10 = x10(form.fibre),
                    sodiumMg = whole(form.sodiumMg),
                    cholesterolMg = whole(form.cholesterolMg),
                ),
                servings = servings,
                dataYear = null,
                sourceLabel = "",
                barcode = code,
            )
            return food to emptySet()
        }

        private fun x10(text: String): Int? {
            val v = Fmt.parseX100(text.trim()) ?: return null
            return ((v + if (v >= 0) 5 else -5) / 10).toInt()
        }

        private fun whole(text: String): Int? {
            val v = Fmt.parseX100(text.trim()) ?: return null
            return ((v + 50) / 100).toInt()
        }
    }
}
