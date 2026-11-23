/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.source

import app.selfwatch.db.SelfWatchDb
import app.selfwatch.food.nutrition.NutrientMath
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Recipe
import app.selfwatch.model.food.Serving
import java.text.Normalizer

/**
 * The user's own foods and recipes (`my_food_fts`), plus foods from any source the user logged before
 * (diary history). Always first in [FoodSearch].
 *
 * - A blank query lists recently logged foods.
 * - Otherwise: custom foods and recipes whose name or brand words start with every typed word
 *   (shortest names first), then previously logged foods that match the same way (so a foods.db item
 *   the user eats often ranks above the rest of foods.db).
 *
 * Recipes resolve to a per-item [Food] with one serving; their nutrients come from
 * [recipePerServing] (by default the stated values; `RecipeService.nutritionPerServing` is the full rule).
 */
class MyFoodsSource(
    private val db: SelfWatchDb,
    private val recipePerServing: suspend (Recipe) -> Nutrients? = { it.statedPerServing },
) : FoodSource {

    override val origin: FoodOrigin = FoodOrigin.MY_FOOD

    override fun serves(o: FoodOrigin): Boolean = o == FoodOrigin.MY_FOOD || o == FoodOrigin.RECIPE

    override suspend fun search(query: String, limit: Int): List<FoodHit> {
        if (limit <= 0) return emptyList()
        if (query.isBlank()) {
            return db.diary.recents(limit).mapNotNull { historyHit(it, 0) }.mapIndexed { i, h -> h.copy(rank = i) }
        }
        val out = ArrayList<FoodHit>()
        val seen = HashSet<FoodRef>()
        for (h in db.foods.searchMine(query, limit)) {
            val hit = when (h.ref.origin) {
                FoodOrigin.RECIPE -> {
                    val r = db.recipes.get(h.ref.id)
                    val per = r?.let { recipePerServing(it) }
                    FoodHit(h.ref, h.name, h.brand, per?.kcalX10, r?.servingLabel ?: DEFAULT_RECIPE_SERVING, FoodHit.TAG_RECIPE, 0)
                }
                else -> {
                    val f = db.foods.get(h.ref.id)?.food
                    val (label, kcal) = f?.let { defaultKcal(it) } ?: (null to null)
                    FoodHit(h.ref, h.name, h.brand, kcal, label, FoodHit.TAG_MY_FOOD, 0)
                }
            }
            if (seen.add(hit.ref)) out.add(hit)
        }
        if (out.size < limit) {
            val words = words(query)
            for (e in db.diary.recents(HISTORY_SCAN)) {
                if (out.size >= limit) break
                val ref = e.food ?: continue
                if (ref in seen || !matchesAll(words, e.name, e.brand)) continue
                val hit = historyHit(e, 0) ?: continue
                seen.add(ref)
                out.add(hit)
            }
        }
        return out.mapIndexed { i, h -> h.copy(rank = i) }
    }

    override suspend fun byBarcode(gtin: Long): Food? = db.foods.byBarcode(gtin)?.food?.withLabel()

    override suspend fun get(id: Long): Food? = db.foods.get(id)?.food?.withLabel()

    override suspend fun get(ref: FoodRef): Food? = when (ref.origin) {
        FoodOrigin.MY_FOOD -> get(ref.id)
        FoodOrigin.RECIPE -> db.recipes.get(ref.id)?.let { recipeAsFood(it) }
        else -> null
    }

    /** [r] as a loggable food: per item, one serving ("1 serving" or the recipe's own label). */
    suspend fun recipeAsFood(r: Recipe): Food = Food(
        ref = FoodRef(FoodOrigin.RECIPE, r.id),
        name = r.name,
        brand = null,
        kind = 0,
        basis = Basis.PER_ITEM,
        per = recipePerServing(r) ?: Nutrients.UNKNOWN,
        servings = listOf(Serving(0, r.servingLabel ?: DEFAULT_RECIPE_SERVING, null)),
        dataYear = null,
        sourceLabel = SOURCE_RECIPE,
        barcode = null,
    )

    private fun Food.withLabel(): Food = if (sourceLabel.isEmpty()) copy(sourceLabel = SOURCE_MY_FOOD) else this

    /** Constants and helpers shared with other sources. */
    companion object {
        /** Serving label for recipes that do not name one. */
        const val DEFAULT_RECIPE_SERVING: String = "1 serving"

        /** `Food.sourceLabel` of the user's own foods. */
        const val SOURCE_MY_FOOD: String = "My food"

        /** `Food.sourceLabel` of recipes. */
        const val SOURCE_RECIPE: String = "Recipe"

        private const val HISTORY_SCAN = 300

        /** The default serving of [f] and the energy of one of it; per-100 g foods without servings use 100 g. */
        internal fun defaultKcal(f: Food): Pair<String?, Int?> {
            val s = f.servings.firstOrNull { it.seq == 0 } ?: f.servings.firstOrNull()
            if (s == null) {
                return if (f.basis == Basis.PER_ITEM) null to f.per.kcalX10 else "100 g" to f.per.kcalX10
            }
            return s.label to NutrientMath.forServing(f, s, 100)?.kcalX10
        }

        private fun historyHit(e: DiaryEntry, rank: Int): FoodHit? {
            val ref = e.food ?: return null
            val kcal = e.nutrients.kcalX10?.let {
                if (e.amountX100 > 0) NutrientMath.clampInt(NutrientMath.mulDivRound(it.toLong(), 100L, e.amountX100.toLong())) else null
            }
            return FoodHit(ref, e.name, e.brand, kcal, e.servingLabel, FoodHit.TAG_HISTORY, rank)
        }

        /** Lowercase words without accents, split on anything that is not a letter or digit. */
        internal fun words(text: String): List<String> =
            fold(text).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

        /** [text] lowercased with accents removed ("Crème" → "creme"). */
        internal fun fold(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

        /** True when every query word starts some word of [name] or [brand]. */
        internal fun matchesAll(query: List<String>, name: String, brand: String?): Boolean {
            if (query.isEmpty()) return false
            val have = words(name) + (brand?.let { words(it) } ?: emptyList())
            return query.all { q -> have.any { it.startsWith(q) } }
        }
    }
}
