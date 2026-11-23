/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import app.selfwatch.db.CustomFoodOrigin
import app.selfwatch.db.dao.CustomFood
import app.selfwatch.food.budget.Budget
import app.selfwatch.food.budget.EnergyBudget
import app.selfwatch.food.budget.GoalMode
import app.selfwatch.food.diary.DiaryDay
import app.selfwatch.food.diary.DiaryMeal
import app.selfwatch.food.diary.FastStatus
import app.selfwatch.food.diary.WaterDay
import app.selfwatch.food.nutrition.NutrientMath
import app.selfwatch.food.recipes.NutritionSource
import app.selfwatch.food.recipes.RecipeNutrition
import app.selfwatch.food.recipes.RecipeSummary
import app.selfwatch.food.source.BarcodeLookup
import app.selfwatch.food.source.FoodHit
import app.selfwatch.db.dao.Fast
import app.selfwatch.model.fake.FakeClock
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.MatchState
import app.selfwatch.model.food.MealSlot
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Recipe
import app.selfwatch.model.food.Serving
import app.selfwatch.model.time.Day
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** In-memory [FoodBackend] for view model tests. */
class FakeFoodBackend(override val clock: FakeClock = FakeClock()) : FoodBackend {
    var slots = listOf(MealSlot(1, "Breakfast", 1, false), MealSlot(2, "Lunch", 2, false), MealSlot(3, "Dinner", 3, false), MealSlot(4, "Snacks", 4, false))
    val entries = ArrayList<DiaryEntry>()
    val foods = LinkedHashMap<FoodRef, Food>()
    val last = HashMap<FoodRef, Pair<Serving, Int>>()
    val recipes = LinkedHashMap<Long, Recipe>()
    val favourites = LinkedHashSet<FoodRef>()
    var water = 0
    var fast: Fast? = null
    var localBarcodes = HashMap<Long, Food>()
    var online: BarcodeLookup = BarcodeLookup.NotFound
    var onlineAllowed = true
    var onlineCalls = 0

    /** Search answers: query → list of emissions (as sources answer). */
    val searchAnswers = HashMap<String, List<List<FoodHit>>>()
    val searched = ArrayList<String>()
    private val version = MutableStateFlow(0)
    private var nextId = 1L

    private fun bump() {
        version.value++
    }

    fun entry(day: Day, slot: Long, name: String, kcal: Int, food: FoodRef? = null): DiaryEntry {
        val e = DiaryEntry(nextId++, day, slot, clock.nowSec(), food, name, null, 100, "1 serving", null, n(kcal), null)
        entries.add(e)
        bump()
        return e
    }

    override suspend fun mealSlots(): List<MealSlot> = slots.sortedBy { it.order }

    override fun diaryDay(day: Day): Flow<DiaryDay> = version.map {
        val list = entries.filter { it.day == day }
        val meals = slots.sortedBy { it.order }.filter { s -> !s.hidden || list.any { it.mealSlotId == s.id } }.map { s ->
            val es = list.filter { it.mealSlotId == s.id }
            DiaryMeal(s, es, es.fold(Nutrients.UNKNOWN) { a, e -> a + e.nutrients })
        }
        DiaryDay(day, meals, list.fold(Nutrients.UNKNOWN) { a, e -> a + e.nutrients })
    }

    override fun waterDay(day: Day): Flow<WaterDay> = version.map { WaterDay(day, emptyList(), water, 2000) }
    override fun fastStatus(): Flow<FastStatus?> = version.map { fast?.let { FastStatus.of(it, clock.nowSec()) } }
    override suspend fun budget(day: Day): Budget {
        val eaten = FoodRows.kcal(entries.filter { it.day == day }.fold(Nutrients.UNKNOWN) { a, e -> a + e.nutrients })
        return Budget(2000, eaten, 300, 1600, EnergyBudget.remaining(2000, eaten, 300), GoalMode.MANUAL)
    }

    override suspend fun copyMeal(from: Day, slot: Long, to: Day): Int {
        val src = entries.filter { it.day == from && it.mealSlotId == slot }
        src.forEach { entries.add(it.copy(id = nextId++, day = to)) }
        bump()
        return src.size
    }

    override suspend fun copyEntries(from: Day, to: Day, slot: Long?): List<Long> {
        val src = entries.filter { it.day == from && (slot == null || it.mealSlotId == slot) }
        val ids = src.map { e -> nextId++.also { entries.add(e.copy(id = it, day = to)) } }
        bump()
        return ids
    }

    override suspend fun deleteEntries(ids: List<Long>): Int {
        val n = entries.count { it.id in ids }
        entries.removeAll { it.id in ids }
        bump()
        return n
    }

    override suspend fun addMealSlot(name: String): Long {
        val id = (slots.maxOfOrNull { it.id } ?: 0L) + 1
        slots = slots + MealSlot(id, name, (slots.maxOfOrNull { it.order } ?: 0) + 1, false)
        bump()
        return id
    }

    override suspend fun saveMealSlots(slots: List<MealSlot>) {
        val byId = slots.associateBy { it.id }
        this.slots = this.slots.map { byId[it.id] ?: it }
        bump()
    }

    override suspend fun changeAmount(entryId: Long, amountX100: Int): Boolean = replace(entryId) { it.copy(amountX100 = amountX100) }
    override suspend fun move(entryId: Long, slot: Long, day: Day): Boolean = replace(entryId) { it.copy(mealSlotId = slot, day = day) }
    private fun replace(id: Long, f: (DiaryEntry) -> DiaryEntry): Boolean {
        val i = entries.indexOfFirst { it.id == id }
        if (i < 0) return false
        entries[i] = f(entries[i])
        bump()
        return true
    }

    override suspend fun delete(entryId: Long): DiaryEntry? {
        val e = entries.firstOrNull { it.id == entryId } ?: return null
        entries.remove(e)
        bump()
        return e
    }

    override suspend fun restore(entry: DiaryEntry): Long {
        entries.add(entry)
        bump()
        return entry.id
    }

    override suspend fun entry(entryId: Long): DiaryEntry? = entries.firstOrNull { it.id == entryId }

    val logged = ArrayList<Triple<Food, Serving, Int>>()
    override suspend fun logFood(day: Day, slot: Long, food: Food, serving: Serving, amountX100: Int): Long {
        val nut = requireNotNull(NutrientMath.forServing(food, serving, amountX100))
        logged.add(Triple(food, serving, amountX100))
        val e = DiaryEntry(nextId++, day, slot, clock.nowSec(), food.ref, food.name, food.brand, amountX100, serving.label, NutrientMath.gramsX10(serving, amountX100), nut, null)
        entries.add(e)
        bump()
        return e.id
    }

    override suspend fun quickAdd(day: Day, slot: Long, name: String, nutrients: Nutrients): Long {
        val e = DiaryEntry(nextId++, day, slot, clock.nowSec(), null, name, null, 100, "quick add", null, nutrients, null)
        entries.add(e)
        bump()
        return e.id
    }

    override suspend fun lastServing(ref: FoodRef): Pair<Serving, Int>? = last[ref]
    override suspend fun addWater(ml: Int, day: Day) {
        water += ml
        bump()
    }

    override suspend fun removeWater(day: Day) {
        water = (water - 250).coerceAtLeast(0)
        bump()
    }

    override suspend fun startFast(goalMin: Int) {
        fast = Fast(1, clock.nowSec(), null, goalMin)
        bump()
    }

    override suspend fun endFast() {
        fast = null
        bump()
    }

    override fun search(query: String): Flow<List<FoodHit>> = flow {
        searched.add(query)
        for (answer in searchAnswers[query] ?: listOf(emptyList())) emit(answer)
    }

    override suspend fun recents(limit: Int): List<FoodHit> =
        entries.reversed().mapNotNull { e -> e.food?.let { foods[it] } }.distinct().map { FoodRows.hitOf(it, FoodHit.TAG_HISTORY) }

    override suspend fun favourites(): List<FoodHit> = favourites.mapNotNull { foods[it] }.map { FoodRows.hitOf(it, FoodRows.tagFor(it.ref.origin)) }
    override suspend fun myFoods(): List<FoodHit> = foods.values.filter { it.ref.origin == FoodOrigin.MY_FOOD }.map { FoodRows.hitOf(it, FoodHit.TAG_MY_FOOD) }
    override suspend fun recipeHits(): List<FoodHit> = emptyList()
    override suspend fun food(ref: FoodRef): Food? = foods[ref]
    override suspend fun isFavourite(ref: FoodRef): Boolean = ref in favourites
    override suspend fun setFavourite(ref: FoodRef, on: Boolean) {
        if (on) favourites.add(ref) else favourites.remove(ref)
    }

    override suspend fun barcodeLocal(gtin: Long): BarcodeLookup =
        localBarcodes[gtin]?.let { BarcodeLookup.Found(it, FoodHit.TAG_MY_FOOD) } ?: BarcodeLookup.NotFound

    override suspend fun onlineLookupAllowed(): Boolean = onlineAllowed
    override suspend fun barcodeOnline(gtin: Long): BarcodeLookup {
        onlineCalls++
        return online
    }

    override suspend fun customFood(id: Long): CustomFood? =
        foods[FoodRef(FoodOrigin.MY_FOOD, id)]?.let { CustomFood(it, CustomFoodOrigin.TYPED, null, false, 0, 0, null) }

    override suspend fun saveCustomFood(food: Food): Long {
        val id = if (food.ref.id != 0L) food.ref.id else nextId++
        foods[FoodRef(FoodOrigin.MY_FOOD, id)] = food.copy(ref = FoodRef(FoodOrigin.MY_FOOD, id))
        return id
    }

    override suspend fun deleteCustomFood(id: Long): Boolean = foods.remove(FoodRef(FoodOrigin.MY_FOOD, id)) != null
    override fun recipeList(tag: String?, query: String?): Flow<List<RecipeSummary>> = version.map {
        recipes.values.filter { (tag == null || tag in it.tags) && (query == null || it.name.contains(query, true)) }
            .map { RecipeSummary(it.id, it.kind, it.name, it.servingsX10, it.tags, it.statedPerServing?.kcalX10) }
    }

    override suspend fun recipeTags(): List<String> = recipes.values.flatMap { it.tags }.distinct().sorted()
    override suspend fun recipe(id: Long): Recipe? = recipes[id]
    override suspend fun saveRecipe(r: Recipe): Long {
        val id = if (r.id != 0L) r.id else nextId++
        recipes[id] = r.copy(id = id)
        bump()
        return id
    }

    override suspend fun deleteRecipe(id: Long): Boolean = recipes.remove(id) != null
    override suspend fun nutrition(r: Recipe): RecipeNutrition {
        r.statedPerServing?.let { return RecipeNutrition(it, NutritionSource.STATED, 0) }
        val unmatched = r.items.count { it.match == MatchState.UNMATCHED }
        return RecipeNutrition(n(100), if (unmatched > 0) NutritionSource.PARTIAL else NutritionSource.COMPUTED, unmatched)
    }

    override suspend fun suggestMatches(r: Recipe): Recipe = r
    override fun confirmMatch(r: Recipe, seq: Int, food: Food): Recipe =
        r.copy(items = r.items.map { if (it.seq == seq) it.copy(food = food.ref, match = MatchState.CONFIRMED) else it })

    override fun skipMatch(r: Recipe, seq: Int): Recipe =
        r.copy(items = r.items.map { if (it.seq == seq) it.copy(food = null, match = MatchState.SKIPPED) else it })

    companion object {
        fun n(kcal: Int): Nutrients = Nutrients(kcal * 10, 10, 20, null, 30, null, null, null, null)

        fun food(id: Long, name: String, basis: Basis = Basis.PER_ITEM, kcal: Int = 100, servings: List<Serving> = listOf(Serving(0, "1 cup", 2400)), origin: FoodOrigin = FoodOrigin.MY_FOOD): Food =
            Food(FoodRef(origin, id), name, null, 0, basis, n(kcal), servings, null, "", null)
    }
}
