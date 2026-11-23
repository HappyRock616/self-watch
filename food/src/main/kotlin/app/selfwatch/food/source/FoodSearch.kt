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
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.SwError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/** The result of a barcode lookup across every source. */
sealed interface BarcodeLookup {
    /** Found [food]; [sourceTag] is one of the `FoodHit.TAG_*` constants. */
    data class Found(val food: Food, val sourceTag: String) : BarcodeLookup

    /** No source knows the code. */
    data object NotFound : BarcodeLookup

    /**
     * No local source knows the code and the online lookup could not run: [error] is
     * `SwError.Blocked` (Open Food Facts switched off), `SwError.Offline` or `SwError.Io`.
     */
    data class Unavailable(val error: SwError) : BarcodeLookup
}

/** A source whose barcode lookup can say why it failed (Open Food Facts). */
interface DetailedBarcodeSource {
    /** Looks [gtin] up; never [BarcodeLookup.Unavailable] for a code the source simply does not know. */
    suspend fun lookup(gtin: Long): BarcodeLookup
}

/**
 * Searches every source at once. Build it with the sources in display order: `MyFoodsSource`, then
 * `FoodsDbSource` (when foods.db is installed), then `OffSource` (when allowed).
 */
class FoodSearch(private val sources: List<FoodSource>) : FoodResolver {

    /**
     * Searches every source in parallel for [query]. Emits the merged list each time a source answers,
     * always in source order (a fast later source never jumps ahead of a slow earlier one), so the
     * first emission may come before the slowest source is done. A food found by two sources is shown
     * once, where it first appears. A source that fails counts as empty (logged). Debounce typing in
     * the caller (150 ms).
     */
    fun search(query: String, limitPerSource: Int = DEFAULT_LIMIT): Flow<List<FoodHit>> = channelFlow {
        if (sources.isEmpty()) {
            send(emptyList())
            return@channelFlow
        }
        val answers = arrayOfNulls<List<FoodHit>>(sources.size)
        val done = Channel<Pair<Int, List<FoodHit>>>(Channel.UNLIMITED)
        sources.forEachIndexed { i, src ->
            launch {
                val hits = try {
                    src.search(query, limitPerSource)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SwLog.w(TAG, "search failed in ${src.origin}", e)
                    emptyList()
                }
                done.send(i to hits)
            }
        }
        repeat(sources.size) {
            val (i, hits) = done.receive()
            answers[i] = hits
            send(merge(answers))
        }
    }

    /**
     * Looks [gtin] up in each source in order and returns the first food found. If nothing local knows
     * it and the online source could not answer, returns [BarcodeLookup.Unavailable] with the reason.
     */
    suspend fun byBarcode(gtin: Long): BarcodeLookup {
        var unavailable: BarcodeLookup.Unavailable? = null
        for (src in sources) {
            val result = try {
                if (src is DetailedBarcodeSource) {
                    src.lookup(gtin)
                } else {
                    src.byBarcode(gtin)?.let { BarcodeLookup.Found(it, tagOf(it.ref)) } ?: BarcodeLookup.NotFound
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "barcode lookup failed in ${src.origin}", e)
                BarcodeLookup.NotFound
            }
            when (result) {
                is BarcodeLookup.Found -> return result
                is BarcodeLookup.Unavailable -> unavailable = result
                BarcodeLookup.NotFound -> Unit
            }
        }
        return unavailable ?: BarcodeLookup.NotFound
    }

    /** The food [ref] points to, from the first source that serves its origin, or null. */
    suspend fun get(ref: FoodRef): Food? {
        for (src in sources) {
            if (!src.serves(ref.origin)) continue
            val f = try {
                src.get(ref)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "get failed in ${src.origin}", e)
                null
            }
            if (f != null) return f
        }
        return null
    }

    override suspend fun food(ref: FoodRef): Food? = get(ref)

    /** Constants and the merge rule. */
    companion object {
        /** Hits asked from each source. */
        const val DEFAULT_LIMIT: Int = 50

        private const val TAG = "FoodSearch"

        /** The answers so far, in source order, each ref kept only at its first appearance. */
        internal fun merge(answers: Array<List<FoodHit>?>): List<FoodHit> {
            val seen = HashSet<FoodRef>()
            val out = ArrayList<FoodHit>()
            for (list in answers) {
                if (list == null) continue
                for (h in list) if (seen.add(h.ref)) out.add(h)
            }
            return out
        }

        private fun tagOf(ref: FoodRef): String = when (ref.origin) {
            FoodOrigin.MY_FOOD -> FoodHit.TAG_MY_FOOD
            FoodOrigin.RECIPE -> FoodHit.TAG_RECIPE
            FoodOrigin.FOODS_DB -> FoodHit.TAG_FOODS_DB
            FoodOrigin.OPEN_FOOD_FACTS -> FoodHit.TAG_OPEN_FOOD_FACTS
        }
    }
}
