/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import app.selfwatch.FoodGraph
import app.selfwatch.db.CustomFoodOrigin
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.dao.CustomFood
import app.selfwatch.food.budget.Budget
import app.selfwatch.food.diary.DiaryDay
import app.selfwatch.food.diary.FastStatus
import app.selfwatch.food.diary.WaterDay
import app.selfwatch.food.nutrition.DietPrefs
import app.selfwatch.food.nutrition.FoodCategories
import app.selfwatch.food.nutrition.TargetInputs
import app.selfwatch.food.nutrition.TargetOverrides
import app.selfwatch.model.food.FoodCategory
import app.selfwatch.food.recipes.RecipeNutrition
import app.selfwatch.food.recipes.RecipeSummary
import app.selfwatch.food.source.BarcodeLookup
import app.selfwatch.food.source.FoodHit
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.food.MealSlot
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Recipe
import app.selfwatch.model.food.Serving
import app.selfwatch.shell.DisplayUnits
import app.selfwatch.ui.format.Units
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Everything the food screens ask of the food module, in one place so view models can be tested with
 * an in-memory fake. [GraphFoodBackend] is the real one, over [FoodGraph].
 */
interface FoodBackend {
    /** Time source (today, now). */
    val clock: Clock

    /** Meal slots in display order (hidden ones included). */
    suspend fun mealSlots(): List<MealSlot>

    /** The diary for [day], now and after every change. */
    fun diaryDay(day: Day): Flow<DiaryDay>

    /** Water for [day], now and after every change. */
    fun waterDay(day: Day): Flow<WaterDay>

    /** The running fast, now and after every change. */
    fun fastStatus(): Flow<FastStatus?>

    /** The energy budget for [day]. */
    suspend fun budget(day: Day): Budget

    /** Copies meal [slot] of [from] into [to]; returns the count. */
    suspend fun copyMeal(from: Day, slot: Long, to: Day): Int

    /** Copies the entries of [from] (only meal [slot] when not null) into the same meals on [to]; returns the new entry ids. */
    suspend fun copyEntries(from: Day, to: Day, slot: Long?): List<Long>

    /** Deletes entries [ids] (undo of a copy); returns how many went. */
    suspend fun deleteEntries(ids: List<Long>): Int

    /** Adds a meal named [name] after the others; returns its id. */
    suspend fun addMealSlot(name: String): Long

    /** Saves the names, order and hidden flags of [slots] (ids never change, so entries keep their meal). */
    suspend fun saveMealSlots(slots: List<MealSlot>)

    /** Changes the amount of an entry. */
    suspend fun changeAmount(entryId: Long, amountX100: Int): Boolean

    /** Moves an entry to another meal and day. */
    suspend fun move(entryId: Long, slot: Long, day: Day): Boolean

    /** Deletes an entry and returns it for undo. */
    suspend fun delete(entryId: Long): DiaryEntry?

    /** Puts a deleted entry back. */
    suspend fun restore(entry: DiaryEntry): Long

    /** One diary entry, or null. */
    suspend fun entry(entryId: Long): DiaryEntry?

    /** Logs a food; returns the entry id. */
    suspend fun logFood(day: Day, slot: Long, food: Food, serving: Serving, amountX100: Int): Long

    /** Logs calories (and optional macros) without a food; returns the entry id. */
    suspend fun quickAdd(day: Day, slot: Long, name: String, nutrients: Nutrients): Long

    /** The serving and amount [ref] was last logged with, or null. */
    suspend fun lastServing(ref: FoodRef): Pair<Serving, Int>?

    /** Logs [ml] of water on [day]. */
    suspend fun addWater(ml: Int, day: Day)

    /** Removes the newest drink of [day]. */
    suspend fun removeWater(day: Day)

    /** Starts a fast aiming at [goalMin] minutes. */
    suspend fun startFast(goalMin: Int)

    /** Ends the running fast. */
    suspend fun endFast()

    /** Text search over every local source, emitting as each answers. */
    fun search(query: String): Flow<List<FoodHit>>

    /** Recently logged foods, newest first. */
    suspend fun recents(limit: Int): List<FoodHit>

    /** Favourite foods, newest first. */
    suspend fun favourites(): List<FoodHit>

    /** The user's own foods. */
    suspend fun myFoods(): List<FoodHit>

    /** Recipes and saved meals as loggable rows. */
    suspend fun recipeHits(): List<FoodHit>

    /** The food [ref] points to, or null. */
    suspend fun food(ref: FoodRef): Food?

    /** Whether [ref] is a favourite. */
    suspend fun isFavourite(ref: FoodRef): Boolean

    /** Adds or removes a favourite. */
    suspend fun setFavourite(ref: FoodRef, on: Boolean)

    /** Barcode lookup in the local sources only. */
    suspend fun barcodeLocal(gtin: Long): BarcodeLookup

    /** Whether the firewall lets Open Food Facts lookups out. */
    suspend fun onlineLookupAllowed(): Boolean

    /** Open Food Facts barcode lookup (saves a found product as a custom food). */
    suspend fun barcodeOnline(gtin: Long): BarcodeLookup

    /** A custom food, or null. */
    suspend fun customFood(id: Long): CustomFood?

    /** Saves [food] as a custom food (new when its id is 0); returns its id. */
    suspend fun saveCustomFood(food: Food): Long

    /** Deletes custom food [id]. */
    suspend fun deleteCustomFood(id: Long): Boolean

    /** Recipe-book rows (optionally one [tag], optionally matching [query]), live. */
    fun recipeList(tag: String?, query: String?): Flow<List<RecipeSummary>>

    /** Every recipe tag in use. */
    suspend fun recipeTags(): List<String>

    /** Recipe [id], or null. */
    suspend fun recipe(id: Long): Recipe?

    /** Saves a recipe; returns its id. */
    suspend fun saveRecipe(r: Recipe): Long

    /** Deletes recipe [id]. */
    suspend fun deleteRecipe(id: Long): Boolean

    /** Nutrition per serving of [r]. */
    suspend fun nutrition(r: Recipe): RecipeNutrition

    /** [r] with suggested matches filled in (not saved). */
    suspend fun suggestMatches(r: Recipe): Recipe

    /** [r] with line [seq] confirmed as [food] (not saved). */
    fun confirmMatch(r: Recipe, seq: Int, food: Food): Recipe

    /** [r] with line [seq] skipped (not saved). */
    fun skipMatch(r: Recipe, seq: Int): Recipe

    /** The user's diets and the allergens they avoid (Me → Goals → Diet). */
    suspend fun dietPrefs(): DietPrefs = DietPrefs.NONE

    /** The display units from the Units setting (Me → Settings). */
    suspend fun displayUnits(): Units = Units.METRIC

    /** Saves the user's diets and allergens. */
    suspend fun setDietPrefs(prefs: DietPrefs) {}

    /** What the nutrient targets for [day] are worked out from (profile, weight, goals, diet). */
    suspend fun targetInputs(day: Day): TargetInputs = TargetInputs(prefs = dietPrefs(), overrides = targetOverrides())

    /** Nutrient targets the user typed by hand (stored units), by field. */
    suspend fun targetOverrides(): Map<Nutrients.Field, Int> = emptyMap()

    /** Saves the hand-typed targets (an empty map goes back to the recommended ones). */
    suspend fun setTargetOverrides(overrides: Map<Nutrients.Field, Int>) {}

    /** The food categories for the custom food editor (foods.db's table, else the built-in list). */
    suspend fun foodCategories(): List<FoodCategory> = FoodCategories.ALL

    /**
     * The category ids of the foods logged on each day from [from] to [to] that has entries (null for a
     * food whose category is unknown, quick adds and recipes included), oldest day first.
     */
    suspend fun loggedCategories(from: Day, to: Day): List<List<Int?>> = emptyList()

    /** Every diary entry from [from] to [to] (inclusive), by day; for the Food tab's "Recent for <meal>". */
    suspend fun entriesBetween(from: Day, to: Day): List<DiaryEntry> = emptyList()

    /** True once the "Set vegetarian?" offer was answered. */
    suspend fun vegHintDone(): Boolean = true

    /** Records that the "Set vegetarian?" offer was answered, so it never shows again. */
    suspend fun setVegHintDone() {}
}

/** The real [FoodBackend] over the app's [FoodGraph]. */
class GraphFoodBackend(private val g: FoodGraph) : FoodBackend {
    override val clock: Clock get() = g.clock

    override suspend fun mealSlots(): List<MealSlot> = g.db.diary.mealSlots(includeHidden = true)
    override fun diaryDay(day: Day): Flow<DiaryDay> = g.diary.day(day)
    override fun waterDay(day: Day): Flow<WaterDay> = g.water.day(day)
    override fun fastStatus(): Flow<FastStatus?> = g.fast.observeCurrent()
    override suspend fun budget(day: Day): Budget = g.budget.forDay(day)
    override suspend fun copyMeal(from: Day, slot: Long, to: Day): Int = g.diary.copyMeal(from, slot, to)
    override suspend fun copyEntries(from: Day, to: Day, slot: Long?): List<Long> {
        val before = g.db.diary.day(to).map { it.id }.toHashSet()
        if (slot == null) g.diary.copyDay(from, to) else g.diary.copyMeal(from, slot, to)
        return g.db.diary.day(to).map { it.id }.filter { it !in before }
    }

    override suspend fun deleteEntries(ids: List<Long>): Int = ids.count { g.diary.delete(it) != null }
    override suspend fun addMealSlot(name: String): Long = g.db.diary.addMealSlot(name)
    override suspend fun saveMealSlots(slots: List<MealSlot>) {
        for (s in slots) g.db.diary.updateMealSlot(s)
    }

    override suspend fun changeAmount(entryId: Long, amountX100: Int): Boolean = g.diary.changeAmount(entryId, amountX100)
    override suspend fun move(entryId: Long, slot: Long, day: Day): Boolean = g.diary.move(entryId, slot, day)
    override suspend fun delete(entryId: Long): DiaryEntry? = g.diary.delete(entryId)
    override suspend fun restore(entry: DiaryEntry): Long = g.diary.restore(entry)
    override suspend fun entry(entryId: Long): DiaryEntry? = g.db.diary.get(entryId)

    override suspend fun logFood(day: Day, slot: Long, food: Food, serving: Serving, amountX100: Int): Long =
        g.diary.add(day, slot, food, serving, amountX100)

    override suspend fun quickAdd(day: Day, slot: Long, name: String, nutrients: Nutrients): Long =
        g.diary.quickAdd(day, slot, name, nutrients)

    override suspend fun lastServing(ref: FoodRef): Pair<Serving, Int>? = g.diary.lastServingFor(ref)
    override suspend fun addWater(ml: Int, day: Day) {
        g.water.add(ml, day)
    }

    override suspend fun removeWater(day: Day) {
        g.water.undoLast(day)
    }

    override suspend fun startFast(goalMin: Int) {
        g.fast.start(goalMin)
    }

    override suspend fun endFast() {
        g.fast.end()
    }

    override fun search(query: String): Flow<List<FoodHit>> = g.search.search(query)

    override suspend fun recents(limit: Int): List<FoodHit> = g.myFoods.search("", limit)

    override suspend fun favourites(): List<FoodHit> =
        g.db.favourites.list().mapNotNull { ref -> food(ref)?.let { FoodRows.hitOf(it, FoodRows.tagFor(ref.origin)) } }

    override suspend fun myFoods(): List<FoodHit> =
        g.db.foods.list().map { FoodRows.hitOf(it.food, FoodHit.TAG_MY_FOOD) }

    override suspend fun recipeHits(): List<FoodHit> =
        g.db.recipes.list().mapNotNull { item -> recipe(item.id)?.let { FoodRows.hitOf(g.myFoods.recipeAsFood(it), FoodHit.TAG_RECIPE) } }

    override suspend fun food(ref: FoodRef): Food? = g.search.get(ref)
    override suspend fun isFavourite(ref: FoodRef): Boolean = g.db.favourites.isFavourite(ref)
    override suspend fun setFavourite(ref: FoodRef, on: Boolean) {
        if (on) g.db.favourites.add(ref, g.clock.nowSec()) else g.db.favourites.remove(ref)
    }

    override suspend fun barcodeLocal(gtin: Long): BarcodeLookup = g.search.byBarcode(gtin)
    override suspend fun onlineLookupAllowed(): Boolean = g.onlineLookupAllowed()
    override suspend fun barcodeOnline(gtin: Long): BarcodeLookup = g.off.lookup(gtin)
    override suspend fun customFood(id: Long): CustomFood? = g.db.foods.get(id)

    override suspend fun saveCustomFood(food: Food): Long {
        val now = g.clock.nowSec()
        val existing = if (food.ref.origin == FoodOrigin.MY_FOOD && food.ref.id != 0L) g.db.foods.get(food.ref.id) else null
        if (existing != null) {
            g.db.foods.update(existing.copy(food = food, updatedSec = now))
            return existing.food.ref.id
        }
        val fresh = food.copy(ref = FoodRef(FoodOrigin.MY_FOOD, 0))
        return g.db.foods.insert(CustomFood(fresh, CustomFoodOrigin.TYPED, null, false, now, now, null))
    }

    override suspend fun deleteCustomFood(id: Long): Boolean = g.db.foods.delete(id)
    override fun recipeList(tag: String?, query: String?): Flow<List<RecipeSummary>> =
        g.recipes.list(tag, query).map { list ->
            list.map { s ->
                if (s.kcalX10PerServing != null) {
                    s
                } else {
                    val kcal = recipe(s.id)?.let { nutrition(it).perServing.kcalX10 }
                    s.copy(kcalX10PerServing = kcal)
                }
            }
        }

    override suspend fun recipeTags(): List<String> = g.recipes.tags()
    override suspend fun recipe(id: Long): Recipe? = g.recipes.get(id)
    override suspend fun saveRecipe(r: Recipe): Long = g.recipes.save(r)
    override suspend fun deleteRecipe(id: Long): Boolean = g.recipes.delete(id)
    override suspend fun nutrition(r: Recipe): RecipeNutrition = g.recipes.nutritionPerServing(r)
    override suspend fun suggestMatches(r: Recipe): Recipe = g.recipes.suggestMatches(r)
    override fun confirmMatch(r: Recipe, seq: Int, food: Food): Recipe = g.recipes.confirm(r, seq, food)
    override fun skipMatch(r: Recipe, seq: Int): Recipe = g.recipes.skip(r, seq)

    override suspend fun dietPrefs(): DietPrefs =
        DietPrefs.of(g.db.settings.getList(SettingKeys.FOOD_DIETS), g.db.settings.getList(SettingKeys.FOOD_ALLERGENS))

    override suspend fun displayUnits(): Units = DisplayUnits.of(g.db.settings.profile().units)

    override suspend fun setDietPrefs(prefs: DietPrefs) {
        g.db.settings.setList(SettingKeys.FOOD_DIETS, DietPrefs.dietIds(prefs))
        g.db.settings.setList(SettingKeys.FOOD_ALLERGENS, DietPrefs.allergenIds(prefs))
    }

    override suspend fun targetInputs(day: Day): TargetInputs {
        val profile = g.db.settings.profile()
        val zone = g.clock.zone()
        val weight = g.db.body.series(BodyKind.WEIGHT, DayRange(day.plusDays(-WEIGHT_LOOKBACK_DAYS), day), zone).lastOrNull()?.valueX100
            ?: g.db.body.latest(BodyKind.WEIGHT)?.valueX100
        val kcal = g.db.goals.goalOn(GoalKind.KCAL_IN, day)?.value
            ?: runCatching { g.budget.forDay(day).goalKcal }.getOrNull()?.takeIf { it > 0 }
        return TargetInputs(
            age = profile.birthYear?.let { day.toLocalDate().year - it }?.takeIf { it in 1..120 },
            sex = profile.sex,
            weightKgX100 = weight,
            kcalGoal = kcal,
            proteinGoalG = g.db.goals.goalOn(GoalKind.PROTEIN_G, day)?.value,
            fatGoalG = g.db.goals.goalOn(GoalKind.FAT_G, day)?.value,
            carbGoalG = g.db.goals.goalOn(GoalKind.CARB_G, day)?.value,
            prefs = dietPrefs(),
            overrides = targetOverrides(),
        )
    }

    override suspend fun targetOverrides(): Map<Nutrients.Field, Int> =
        TargetOverrides.decode(g.db.settings.getList(SettingKeys.FOOD_TARGET_OVERRIDES))

    override suspend fun setTargetOverrides(overrides: Map<Nutrients.Field, Int>) {
        val items = TargetOverrides.encode(overrides)
        g.db.settings.set(SettingKeys.FOOD_TARGET_OVERRIDES, if (items.isEmpty()) null else items.joinToString(","))
    }

    override suspend fun foodCategories(): List<FoodCategory> =
        runCatching { g.foodsDb.categories() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: FoodCategories.ALL

    override suspend fun entriesBetween(from: Day, to: Day): List<DiaryEntry> = g.db.diary.range(DayRange(from, to))

    override suspend fun loggedCategories(from: Day, to: Day): List<List<Int?>> {
        val cache = HashMap<FoodRef, Int?>()
        val byDay = g.db.diary.range(DayRange(from, to)).groupBy { it.day }.toSortedMap()
        return byDay.values.map { entries ->
            entries.map { e ->
                val ref = e.food ?: return@map null
                if (ref.origin == FoodOrigin.RECIPE) return@map null
                cache.getOrPut(ref) { runCatching { food(ref)?.category?.id }.getOrNull() }
            }
        }
    }

    override suspend fun vegHintDone(): Boolean = g.db.settings.getBool(SettingKeys.FOOD_VEG_HINT_DONE)

    override suspend fun setVegHintDone() {
        g.db.settings.setBool(SettingKeys.FOOD_VEG_HINT_DONE, true)
    }

    private companion object {
        const val WEIGHT_LOOKBACK_DAYS = 90
    }
}
