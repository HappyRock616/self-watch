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
import app.selfwatch.food.nutrition.FoodCategories
import app.selfwatch.food.nutrition.NutrientMath
import app.selfwatch.model.food.Allergen
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.DietInfo
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodCategory
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
    // Food database v2 nutrients ("More nutrients"); g, mg or µg as labeled.
    val transfat: String = "",
    val monofat: String = "",
    val polyfat: String = "",
    val addsugar: String = "",
    val potassiumMg: String = "",
    val calciumMg: String = "",
    val ironMg: String = "",
    val vitaUg: String = "",
    val vitcMg: String = "",
    val vitdUg: String = "",
    /** Ticked diet claims (unticked means unknown, never "no"). */
    val vegan: Boolean = false,
    val vegetarian: Boolean = false,
    val glutenFree: Boolean = false,
    /** Allergens the label lists. */
    val allergens: Set<Allergen> = emptySet(),
    /** Food category id (foods.db `category` table), or null for none. */
    val categoryId: Int? = null,
    /** Extra named servings after the first ("+ serving size"), in order. */
    val extraServings: List<ServingForm> = emptyList(),
)

/** One extra serving as typed: a name ("1 slice") and its weight in grams (decimal text). */
data class ServingForm(val label: String = "", val grams: String = "")

/** Form problems. */
enum class FoodFormError { NAME, KCAL, SERVING_GRAMS, BARCODE, EXTRA_SERVING }

/** Editor state. */
data class FoodEditorState(
    val loading: Boolean = true,
    val form: FoodForm = FoodForm(),
    /** Id of the custom food being edited; 0 for a new one. */
    val id: Long = 0,
    /** The categories to pick from. */
    val categories: List<FoodCategory> = FoodCategories.ALL,
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
    private var per100Basis: Basis = Basis.PER_100G
    private var baseDiet: DietInfo = DietInfo.UNKNOWN

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
            val categories = try {
                backend.foodCategories().ifEmpty { FoodCategories.ALL }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "categories failed", e)
                FoodCategories.ALL
            }
            if (food == null) {
                mutable.value = FoodEditorState(loading = false, form = FoodForm(barcode = barcode?.let { Gtin.shortForm(it) }.orEmpty()), categories = categories)
                return@launch
            }
            if (food.basis == Basis.PER_100ML) per100Basis = Basis.PER_100ML
            baseDiet = food.dietInfo
            val id = if (food.ref.origin == FoodOrigin.MY_FOOD) food.ref.id else 0L
            mutable.value = FoodEditorState(loading = false, form = formOf(food), id = id, categories = categories)
        }
    }

    /** Validates and saves [form]. */
    fun save(form: FoodForm) {
        val category = form.categoryId?.let { id -> mutable.value.categories.firstOrNull { it.id == id } ?: FoodCategory(id, "", "") }
        val result = toFood(form, mutable.value.id, per100Basis, baseDiet, category)
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
            val sorted = food.servings.sortedBy { it.seq }
            val first = sorted.firstOrNull()
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
                transfat = g(food.per.transfatX10),
                monofat = g(food.per.monofatX10),
                polyfat = g(food.per.polyfatX10),
                addsugar = g(food.per.addsugarX10),
                potassiumMg = mg(food.per.potassiumMg),
                calciumMg = mg(food.per.calciumMg),
                ironMg = g(food.per.ironX10),
                vitaUg = mg(food.per.vitaUg),
                vitcMg = g(food.per.vitcX10),
                vitdUg = g(food.per.vitdX10),
                vegan = food.dietInfo.vegan == true,
                vegetarian = food.dietInfo.vegetarian == true,
                glutenFree = food.dietInfo.glutenFree,
                allergens = food.dietInfo.contains.toSet(),
                categoryId = food.category?.id,
                extraServings = sorted.drop(1).map { ServingForm(it.label, g(it.gramsX10)) },
            )
        }

        /**
         * The diet info [form] ticks, on top of what [base] (the food copied or edited) already knew:
         * a ticked claim is a decided "yes"; unticking one [base] had decided records "no"; anything
         * never decided stays unknown.
         */
        fun dietOf(form: FoodForm, base: DietInfo): DietInfo {
            val veg = form.vegetarian || form.vegan
            var diet = 0
            var known = base.dietKnown
            if (form.vegan) {
                diet = diet or DietInfo.VEGAN
                known = known or DietInfo.VEGAN
            }
            if (veg) {
                diet = diet or DietInfo.VEGETARIAN
                known = known or DietInfo.VEGETARIAN
            }
            if (form.glutenFree) diet = diet or if (base.glutenFreeNatural) DietInfo.GLUTEN_FREE_NATURAL else DietInfo.GLUTEN_FREE_LABEL
            return DietInfo(diet, known, Allergen.maskOf(form.allergens), base.allergensFull)
        }

        /**
         * The food [form] describes (custom food [id], 0 for new), or null with the problems found:
         * a name and calories are required; a typed serving weight must parse; a typed barcode must be
         * a valid GTIN; each extra serving needs a name and a weight (blank rows are dropped).
         *
         * Extra servings are only meaningful by weight, so a food typed "per serving" that has extra
         * servings is stored per 100 g (its first serving then needs grams), and every serving keeps
         * its own weight. [category] defaults to the form's category id.
         */
        fun toFood(
            form: FoodForm,
            id: Long,
            per100Basis: Basis = Basis.PER_100G,
            baseDiet: DietInfo = DietInfo.UNKNOWN,
            category: FoodCategory? = form.categoryId?.let { FoodCategories.byId(it) ?: FoodCategory(it, "", "") },
        ): Pair<Food?, Set<FoodFormError>> {
            val errors = HashSet<FoodFormError>()
            if (form.name.isBlank()) errors.add(FoodFormError.NAME)
            val kcal = x10(form.kcal)
            if (kcal == null || kcal < 0) errors.add(FoodFormError.KCAL)
            val grams = if (form.servingGrams.isBlank()) null else x10(form.servingGrams)
            if (form.servingGrams.isNotBlank() && (grams == null || grams <= 0)) errors.add(FoodFormError.SERVING_GRAMS)
            val code = if (form.barcode.isBlank()) null else Gtin.normalize(form.barcode)
            if (form.barcode.isNotBlank() && code == null) errors.add(FoodFormError.BARCODE)
            val extras = ArrayList<Serving>()
            for (s in form.extraServings) {
                if (s.label.isBlank() && s.grams.isBlank()) continue
                val g = x10(s.grams)
                if (s.label.isBlank() || g == null || g <= 0) {
                    errors.add(FoodFormError.EXTRA_SERVING)
                } else {
                    extras.add(Serving(extras.size + 1, s.label.trim(), g))
                }
            }
            if (form.perServing && extras.isNotEmpty() && grams == null) errors.add(FoodFormError.SERVING_GRAMS)
            if (errors.isNotEmpty()) return null to errors
            val label = form.servingLabel.trim().ifEmpty { if (grams != null) "" else FoodRows.ONE_SERVING }
            val servings = ArrayList<Serving>()
            if (label.isNotEmpty() || grams != null) {
                servings.add(Serving(0, label.ifEmpty { FoodRows.ONE_SERVING }, grams))
            }
            servings.addAll(extras)
            val byWeight = form.perServing && extras.isNotEmpty() && grams != null
            val food = Food(
                ref = FoodRef(FoodOrigin.MY_FOOD, id),
                name = form.name.trim(),
                brand = form.brand.trim().ifEmpty { null },
                kind = 0,
                basis = if (form.perServing && !byWeight) Basis.PER_ITEM else per100Basis,
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
                    transfatX10 = x10(form.transfat),
                    monofatX10 = x10(form.monofat),
                    polyfatX10 = x10(form.polyfat),
                    addsugarX10 = x10(form.addsugar),
                    potassiumMg = whole(form.potassiumMg),
                    calciumMg = whole(form.calciumMg),
                    ironX10 = x10(form.ironMg),
                    vitaUg = whole(form.vitaUg),
                    vitcX10 = x10(form.vitcMg),
                    vitdX10 = x10(form.vitdUg),
                ),
                servings = servings,
                dataYear = null,
                sourceLabel = "",
                barcode = code,
                category = category,
                dietInfo = dietOf(form, baseDiet),
            )
            // Per serving of `grams` → per 100 g: × 100 / grams = × 1000 / gramsX10.
            val perGrams = grams?.takeIf { byWeight }
            if (perGrams != null) return food.copy(per = NutrientMath.scale(food.per, 1000L, perGrams.toLong())) to emptySet()
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
