/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.recipes

import app.selfwatch.food.source.FoodHit
import app.selfwatch.food.source.FoodResolver
import app.selfwatch.food.source.FoodSearch
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.MatchState
import app.selfwatch.model.food.RecipeItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.last

/**
 * Suggests a food for each recipe ingredient line (no AI):
 * - searches with the parsed words, then with size and prep words dropped, then the last two words,
 *   then the last word, until something is found; plural words are cut to a stem first ("tomatoes" →
 *   "tomato", "berries" → "berr") so prefix search finds both forms;
 * - prefers the user's own foods and foods they logged before, then generic foods (foods.db kind 0)
 *   for raw ingredients, then the first hit; recipes are never suggested;
 * - lines with no amount that say "to taste" are skipped (they add nothing measurable).
 *
 * The suggestion is [MatchState.SUGGESTED]; the user confirms it.
 *
 * @param search runs a query to its final merged list (by default `FoodSearch.search(...).last()`).
 */
class IngredientMatcher(
    private val resolver: FoodResolver,
    private val search: suspend (String) -> List<FoodHit>,
) {
    /** A matcher over [foodSearch]. */
    constructor(foodSearch: FoodSearch) : this(foodSearch, { q -> lastOf(foodSearch.search(q, CANDIDATES)) })

    /** The best food for [p], or null when nothing matches. */
    suspend fun match(p: ParsedIngredient): FoodHit? {
        for (q in queries(p.words)) {
            val hits = search(q).filter { it.ref.origin != FoodOrigin.RECIPE }
            if (hits.isEmpty()) continue
            return pick(hits)
        }
        return null
    }

    /**
     * [item] with a suggestion filled in when it has no food yet (state UNMATCHED): parsed amount and
     * unit, the matched food, its weight when it can be worked out, and state SUGGESTED. "To taste"
     * lines become SKIPPED. Items that already have a food or a state other than UNMATCHED come back
     * unchanged; so do lines nothing matches.
     */
    suspend fun suggest(item: RecipeItem): RecipeItem {
        if (item.match != MatchState.UNMATCHED || item.food != null) return item
        val p = IngredientParser.parse(item.line)
        if (p.toTaste || p.words.isBlank()) return item.copy(match = MatchState.SKIPPED)
        val hit = match(p) ?: return item.copy(amountX100 = item.amountX100 ?: p.amountX100, unit = item.unit ?: p.unit)
        val food = resolver.food(hit.ref)
        val parsed = p.copy(amountX100 = item.amountX100 ?: p.amountX100, unit = item.unit ?: p.unit)
        return item.copy(
            food = hit.ref,
            amountX100 = parsed.amountX100,
            unit = parsed.unit,
            gramsX10 = food?.let { IngredientWeights.gramsX10(parsed, it) },
            match = MatchState.SUGGESTED,
        )
    }

    /** Constants and the query rules. */
    companion object {
        private const val CANDIDATES = 20

        private suspend fun lastOf(flow: Flow<List<FoodHit>>): List<FoodHit> = flow.last()

        /** Words dropped on the second try: sizes, prep and state words. */
        internal val DESCRIPTORS: Set<String> = setOf(
            "large", "small", "medium", "big", "extra", "jumbo", "fresh", "freshly", "chopped", "minced", "diced", "sliced",
            "thinly", "thickly", "finely", "coarsely", "roughly", "peeled", "seeded", "cored", "grated", "shredded",
            "crushed", "softened", "melted", "cold", "warm", "room", "temperature", "packed", "lightly", "beaten",
            "divided", "trimmed", "rinsed", "drained", "cubed", "halved", "quartered", "julienned", "toasted",
            "uncooked", "cooked", "boneless", "skinless", "organic", "good", "quality", "ripe", "about", "plus",
            "more", "heaping", "level", "and", "or", "of", "for", "the", "to", "into", "in", "pieces", "piece",
        )

        /** The search queries to try for [words], best first, without repeats. */
        internal fun queries(words: String): List<String> {
            val all = words.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            if (all.isEmpty()) return emptyList()
            val core = all.filter { it !in DESCRIPTORS }.map { stem(it) }
            val out = LinkedHashSet<String>()
            out.add(all.map { stem(it) }.joinToString(" "))
            if (core.isNotEmpty()) {
                out.add(core.joinToString(" "))
                if (core.size > 2) out.add(core.takeLast(2).joinToString(" "))
                out.add(core.last())
            }
            return out.toList()
        }

        /** A prefix that matches both the singular and plural of [w]. */
        internal fun stem(w: String): String = when {
            w.length <= 3 -> w
            w.endsWith("ies") -> w.dropLast(3)
            w.endsWith("oes") || w.endsWith("ses") || w.endsWith("xes") || w.endsWith("ches") || w.endsWith("shes") -> w.dropLast(2)
            w.endsWith("ss") || w.endsWith("us") -> w
            w.endsWith("s") -> w.dropLast(1)
            else -> w
        }

        private fun pick(hits: List<FoodHit>): FoodHit =
            hits.firstOrNull { it.sourceTag == FoodHit.TAG_MY_FOOD || it.sourceTag == FoodHit.TAG_HISTORY }
                ?: hits.firstOrNull { it.kind == 0 }
                ?: hits.first()
    }
}
