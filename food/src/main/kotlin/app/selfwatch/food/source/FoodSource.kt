/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.source

import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef

/** One place foods come from: the user's own foods, the installed foods.db, or Open Food Facts. */
interface FoodSource {
    /** The origin of the foods this source returns (see [serves] for sources with more than one). */
    val origin: FoodOrigin

    /** Text search; at most [limit] hits, best first. Sources without text search return an empty list. */
    suspend fun search(query: String, limit: Int): List<FoodHit>

    /** The food with the GTIN-14 [gtin] (see `Gtin.normalize`), or null. */
    suspend fun byBarcode(gtin: Long): Food?

    /** The food [id] of [origin], or null. */
    suspend fun get(id: Long): Food?

    /** True when this source can resolve refs of origin [o]. */
    fun serves(o: FoodOrigin): Boolean = o == origin

    /** The food [ref] points to, or null when this source does not serve its origin or has no such food. */
    suspend fun get(ref: FoodRef): Food? = if (ref.origin == origin) get(ref.id) else null
}

/**
 * One search result.
 *
 * @property kcalX10PerDefault energy of one default serving (kcal ×10), if known.
 * @property defaultServing label of the default serving, if any.
 * @property sourceTag which list it came from: one of the `TAG_*` constants (the screens map it to
 *   their own words).
 * @property rank position within its source, 0 first.
 * @property sourceCode foods.db `source` code (FOODDB_SPEC §6: 1 Open Food Facts, 2–5 USDA, 6 MenuStat), if
 *   known; other sources report null.
 * @property dataYear year of the foods.db source data, if known.
 * @property kind foods.db kind (0 generic, 1 packaged, 2 restaurant), if known; recipes and custom
 *   foods report null.
 */
data class FoodHit(
    val ref: FoodRef,
    val name: String,
    val brand: String?,
    val kcalX10PerDefault: Int?,
    val defaultServing: String?,
    val sourceTag: String,
    val rank: Int,
    val kind: Int? = null,
    val sourceCode: Int? = null,
    val dataYear: Int? = null,
) {
    /** Source tags. */
    companion object {
        /** A food the user made or saved. */
        const val TAG_MY_FOOD: String = "my_food"

        /** A recipe or saved meal from the recipe book. */
        const val TAG_RECIPE: String = "recipe"

        /** A food the user logged before (from diary history). */
        const val TAG_HISTORY: String = "history"

        /** The installed food database. */
        const val TAG_FOODS_DB: String = "foods_db"

        /** Open Food Facts. */
        const val TAG_OPEN_FOOD_FACTS: String = "open_food_facts"
    }
}

/** Resolves a [FoodRef] to a [Food] (used for recipe nutrition, where items can point anywhere). */
fun interface FoodResolver {
    /** The food [ref] points to, or null. */
    suspend fun food(ref: FoodRef): Food?
}
