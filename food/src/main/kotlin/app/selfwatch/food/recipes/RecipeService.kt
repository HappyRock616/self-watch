/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.recipes

import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.food.nutrition.NutrientMath
import app.selfwatch.food.source.FoodResolver
import app.selfwatch.food.source.FoodSearch
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.MatchState
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Recipe
import app.selfwatch.model.food.RecipeItem
import app.selfwatch.model.food.RecipeKind
import app.selfwatch.model.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Where a recipe's per-serving nutrition came from. */
enum class NutritionSource {
    /** The recipe's source stated it. */
    STATED,

    /** Worked out from every measurable ingredient. */
    COMPUTED,

    /** Worked out, but some ingredients could not be counted: label it "partial". */
    PARTIAL,
}

/**
 * Nutrition per serving of a recipe and how honest it is.
 *
 * @property unmatched ingredient lines that could not be counted (no food, food gone, or no weight).
 *   Lines the user skipped (and "to taste" lines) are not counted here.
 */
data class RecipeNutrition(val perServing: Nutrients, val source: NutritionSource, val unmatched: Int)

/** A recipe-book list row. [kcalX10PerServing] is the stated value, when there is one. */
data class RecipeSummary(
    val id: Long,
    val kind: RecipeKind,
    val name: String,
    val servingsX10: Int,
    val tags: Set<String>,
    val kcalX10PerServing: Int?,
)

/**
 * Works out a recipe's nutrition per serving: the stated values when the source gave them, else the
 * sum of its matched ingredients (SUGGESTED or CONFIRMED) divided by the number of servings.
 */
class RecipeNutritionCalculator(private val resolver: FoodResolver) {

    /** Nutrition per serving of [r]. */
    suspend fun perServing(r: Recipe): RecipeNutrition {
        r.statedPerServing?.let { return RecipeNutrition(it, NutritionSource.STATED, 0) }
        var total = Nutrients.UNKNOWN
        var counted = 0
        var unmatched = 0
        for (item in r.items) {
            if (item.match == MatchState.SKIPPED) continue
            val n = itemNutrients(item)
            if (n == null) {
                unmatched++
            } else {
                total += n
                counted++
            }
        }
        val servings = r.servingsX10.coerceAtLeast(1).toLong()
        val per = if (counted == 0) Nutrients.UNKNOWN else NutrientMath.scale(total, 10L, servings)
        val source = if (unmatched > 0 || counted == 0) NutritionSource.PARTIAL else NutritionSource.COMPUTED
        return RecipeNutrition(per, source, unmatched)
    }

    /** Nutrients of one ingredient line, or null when it cannot be counted. */
    suspend fun itemNutrients(item: RecipeItem): Nutrients? {
        if (item.match != MatchState.SUGGESTED && item.match != MatchState.CONFIRMED) return null
        val ref = item.food ?: return null
        val food = resolver.food(ref) ?: return null
        return nutrientsFor(item, food)
    }

    /** Constants and the per-item rule. */
    companion object {
        /** Nutrients of [item] made with [food]; null when its weight (or count) cannot be worked out. */
        fun nutrientsFor(item: RecipeItem, food: Food): Nutrients? {
            if (food.basis == Basis.PER_ITEM) {
                val unit = item.unit?.let { Units.byName(it) }
                if (unit != null && unit.kind != UnitKind.COUNT) return null
                return NutrientMath.scale(food.per, (item.amountX100 ?: 100).toLong(), 100L)
            }
            val grams = item.gramsX10 ?: run {
                val p = IngredientParser.parse(item.line)
                IngredientWeights.gramsX10(p.copy(amountX100 = item.amountX100 ?: p.amountX100, unit = item.unit ?: p.unit), food)
            } ?: return null
            return NutrientMath.forGrams(food.basis, food.per, grams.toLong())
        }
    }
}

/**
 * The recipe book: save, delete, list, nutrition per serving, and ingredient match suggestions.
 *
 * @param search every food source, used to suggest matches and to resolve matched foods.
 */
class RecipeService(
    private val db: SelfWatchDb,
    search: FoodSearch,
    private val clock: Clock,
    private val matcher: IngredientMatcher = IngredientMatcher(search),
    private val calculator: RecipeNutritionCalculator = RecipeNutritionCalculator(search),
) {
    /** Saves [r]: a new recipe when `r.id` is 0, else an update. Returns its id. */
    suspend fun save(r: Recipe): Long {
        val clean = r.copy(items = r.items.mapIndexed { i, it -> it.copy(seq = i) }, tags = r.tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet())
        if (clean.id != 0L && db.recipes.update(clean, clock.nowSec())) return clean.id
        return db.recipes.insert(clean.copy(id = 0), clock.nowSec())
    }

    /** Recipe [id], or null. */
    suspend fun get(id: Long): Recipe? = db.recipes.get(id)

    /** Deletes recipe [id]. Returns true if it existed. */
    suspend fun delete(id: Long): Boolean = db.recipes.delete(id)

    /** Recipes (optionally only [tag], optionally matching [q]) now and after every recipe change. */
    fun list(tag: String?, q: String?): Flow<List<RecipeSummary>> =
        db.changes.observe(setOf(Table.RECIPE, Table.RECIPE_TAG)).map {
            db.recipes.list(tag, q?.takeIf { it.isNotBlank() }).map { r ->
                RecipeSummary(r.id, r.kind, r.name, r.servingsX10, r.tags, r.statedPerServing?.kcalX10)
            }
        }

    /** Every tag in use. */
    suspend fun tags(): List<String> = db.recipes.tags()

    /** Nutrition per serving: stated, or computed from matched items (partial when some are not countable). */
    suspend fun nutritionPerServing(r: Recipe): RecipeNutrition = calculator.perServing(r)

    /** [r] with a SUGGESTED food filled in for every UNMATCHED line (and "to taste" lines SKIPPED). Not saved. */
    suspend fun suggestMatches(r: Recipe): Recipe = r.copy(items = r.items.map { matcher.suggest(it) })

    /**
     * [r] with line [seq] confirmed as [food] (the user picked or accepted it): amount and unit from
     * the line unless already set, and its weight recomputed. Not saved.
     */
    fun confirm(r: Recipe, seq: Int, food: Food): Recipe = r.copy(
        items = r.items.map { item ->
            if (item.seq != seq) return@map item
            val p = IngredientParser.parse(item.line)
            val parsed = p.copy(amountX100 = item.amountX100 ?: p.amountX100, unit = item.unit ?: p.unit)
            item.copy(
                food = food.ref,
                amountX100 = parsed.amountX100,
                unit = parsed.unit,
                gramsX10 = IngredientWeights.gramsX10(parsed, food),
                match = MatchState.CONFIRMED,
            )
        },
    )

    /** [r] with line [seq] marked SKIPPED (not counted, not flagged). Not saved. */
    fun skip(r: Recipe, seq: Int): Recipe =
        r.copy(items = r.items.map { if (it.seq == seq) it.copy(food = null, match = MatchState.SKIPPED) else it })
}
