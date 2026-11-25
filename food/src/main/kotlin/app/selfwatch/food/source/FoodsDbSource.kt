/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.source

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.selfwatch.food.nutrition.NutrientMath
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.DietInfo
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodCategory
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Serving

/**
 * Tony's offline food database (`foods.db`, FOODDB_SPEC Part B, schema 2) through a [FoodsDbHandle].
 * Every call returns empty or null when the file is not installed.
 *
 * Search ([search], rules in [FoodsDbSearch]):
 * 1. Normalize: lowercase, apostrophes dropped (McDonald's = mcdonalds), words are letter/digit runs.
 * 2. Brand mode: a query that starts with a chain name searches only that chain; a bare chain name
 *    lists its menu by popularity.
 * 3. FTS: every word whole except the last, which is a prefix (as you type); 500 rows by popularity.
 * 4. Rank tiers: everyday food for this exact term > everyday phrase > name equals or starts with the
 *    query > all words whole in the name > words found via brand or keywords > the rest; then
 *    popularity, then the shorter name.
 */
class FoodsDbSource(private val handle: FoodsDbHandle) : FoodSource {

    override val origin: FoodOrigin = FoodOrigin.FOODS_DB

    /** True when foods.db is installed. */
    val isInstalled: Boolean get() = handle.isInstalled

    override suspend fun search(query: String, limit: Int): List<FoodHit> {
        val words = FoodsDbSearch.words(query)
        if (words.isEmpty() || limit <= 0) return emptyList()
        return handle.use { d -> searchIn(d, words, limit) } ?: emptyList()
    }

    override suspend fun byBarcode(gtin: Long): Food? = handle.use { d ->
        d.rawQuery("SELECT f.id FROM barcode b JOIN food f ON f.id = b.food_id WHERE b.gtin = ?", arrayOf(gtin.toString())).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }?.let { foodIn(d, it, gtin) }
    }

    override suspend fun get(id: Long): Food? = handle.use { d -> foodIn(d, id, null) }

    /** The servings of food [id], default (seq 0) first. */
    suspend fun servings(id: Long): List<Serving> = handle.use { d -> servingsIn(d, id) } ?: emptyList()

    /** The food categories (the `category` table) by id; empty when not installed. */
    suspend fun categories(): List<FoodCategory> = handle.use { d ->
        d.rawQuery("SELECT id, name, grp FROM category ORDER BY id", null).use { c ->
            val out = ArrayList<FoodCategory>()
            while (c.moveToNext()) out.add(FoodCategory(c.getInt(0), c.getString(1), c.getString(2)))
            out
        }
    } ?: emptyList()

    /** The `meta` table (FOODDB_SPEC §9), for the About and data screens; empty when not installed. */
    suspend fun meta(): Map<String, String> = handle.use { d ->
        d.rawQuery("SELECT key, value FROM meta", null).use { c ->
            val m = LinkedHashMap<String, String>()
            while (c.moveToNext()) m[c.getString(0)] = c.getString(1)
            m
        }
    } ?: emptyMap()

    /** Constants and the SQL side of search. */
    companion object {
        /** Rows fetched by popularity before ranking. */
        const val FETCH: Int = 500

        private const val HIT_COLS =
            "f.id, f.name, f.brand, f.kcal_x10, f.basis, s.label, s.grams_x10, f.kind, f.source, f.data_year, " +
                "f.popularity, f.keywords, f.diet, f.diet_known, f.allergens, f.allergens_full"

        private const val FOOD_SQL =
            "SELECT f.id, f.name, f.brand, f.kind, f.source, f.data_year, f.basis, f.kcal_x10, f.protein_x10, f.fat_x10, " +
                "f.satfat_x10, f.carb_x10, f.sugar_x10, f.fibre_x10, f.sodium_mg, f.cholesterol_mg, f.transfat_x10, " +
                "f.monofat_x10, f.polyfat_x10, f.addsugar_x10, f.potassium_mg, f.calcium_mg, f.iron_x10, f.vita_ug, " +
                "f.vitc_x10, f.vitd_x10, f.diet, f.diet_known, f.allergens, f.allergens_full, f.category_id, c.name, c.grp " +
                "FROM food f LEFT JOIN category c ON c.id = f.category_id WHERE f.id = ?"

        /** `Food.sourceLabel` for a foods.db `source` code (FOODDB_SPEC §6). */
        fun sourceLabel(source: Int): String = when (source) {
            1 -> "Open Food Facts"
            2 -> "USDA Branded Foods"
            3 -> "USDA Foundation Foods"
            4 -> "USDA SR Legacy"
            5 -> "USDA FNDDS"
            6 -> "NYC MenuStat"
            else -> "Food database"
        }

        private fun searchIn(d: SQLiteDatabase, typed: List<String>, limit: Int): List<FoodHit> {
            val chains = d.rawQuery("SELECT id, name FROM chain", null).use { c ->
                val out = ArrayList<Pair<Long, String>>()
                while (c.moveToNext()) out.add(c.getLong(0) to c.getString(1))
                out
            }
            val brand = FoodsDbSearch.brandMode(typed, chains)
            val words = brand?.rest ?: typed
            val rows: List<Row> = if (brand != null && words.isEmpty()) {
                d.rawQuery(
                    "SELECT $HIT_COLS FROM food f LEFT JOIN serving s ON s.food_id = f.id AND s.seq = 0 " +
                        "WHERE f.chain_id = ? ORDER BY f.popularity DESC, length(f.name) LIMIT $limit",
                    arrayOf(brand.chainId.toString()),
                ).use(::rowsOf)
            } else {
                val chainFilter = if (brand != null) " AND f.chain_id = ${brand.chainId}" else ""
                val fetched = d.rawQuery(
                    "SELECT $HIT_COLS FROM food_fts JOIN food f ON f.id = food_fts.docid " +
                        "LEFT JOIN serving s ON s.food_id = f.id AND s.seq = 0 " +
                        "WHERE food_fts MATCH ?$chainFilter ORDER BY f.popularity DESC LIMIT $FETCH",
                    arrayOf(FoodsDbSearch.ftsMatch(words)),
                ).use(::rowsOf)
                FoodsDbSearch.rank(words, fetched) { it.candidate }.take(limit)
            }
            return rows.mapIndexed { i, r -> r.hit.copy(rank = i, chain = brand?.chainName) }
        }

        /** One fetched search row: the hit and what ranking needs. */
        private class Row(val hit: FoodHit, val candidate: FoodsDbSearch.Candidate)

        private fun rowsOf(c: Cursor): List<Row> {
            val out = ArrayList<Row>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) {
                val basis = Basis.fromCode(c.getInt(4)) ?: Basis.PER_100G
                val kcalPer = c.intOrNull(3)
                val label = c.strOrNull(5)
                val grams = c.intOrNull(6)
                val kcal = kcalPer?.let {
                    when {
                        basis == Basis.PER_ITEM -> it
                        grams != null -> NutrientMath.clampInt(NutrientMath.mulDivRound(it.toLong(), grams.toLong(), 1000L))
                        else -> null
                    }
                }
                val name = c.getString(1)
                val brand = c.strOrNull(2)
                val hit = FoodHit(
                    ref = FoodRef(FoodOrigin.FOODS_DB, c.getLong(0)),
                    name = name,
                    brand = brand,
                    kcalX10PerDefault = kcal,
                    defaultServing = label,
                    sourceTag = FoodHit.TAG_FOODS_DB,
                    rank = 0,
                    kind = c.intOrNull(7),
                    sourceCode = c.intOrNull(8),
                    dataYear = c.intOrNull(9),
                    dietInfo = DietInfo(c.getInt(12), c.getInt(13), c.getInt(14), c.getInt(15) != 0),
                )
                out.add(Row(hit, FoodsDbSearch.Candidate(name, brand, c.strOrNull(11), c.getInt(10))))
            }
            return out
        }

        private fun foodIn(d: SQLiteDatabase, id: Long, gtin: Long?): Food? =
            d.rawQuery(FOOD_SQL, arrayOf(id.toString())).use { c ->
                if (!c.moveToFirst()) return@use null
                val barcode = gtin ?: d.rawQuery("SELECT gtin FROM barcode WHERE food_id = ? ORDER BY gtin LIMIT 1", arrayOf(id.toString()))
                    .use { b -> if (b.moveToFirst()) b.getLong(0) else null }
                Food(
                    ref = FoodRef(FoodOrigin.FOODS_DB, c.getLong(0)),
                    name = c.getString(1),
                    brand = c.strOrNull(2),
                    kind = c.getInt(3),
                    basis = Basis.fromCode(c.getInt(6)) ?: Basis.PER_100G,
                    per = Nutrients.fromList(List(Nutrients.Field.entries.size) { c.intOrNull(7 + it) }),
                    servings = servingsIn(d, id),
                    dataYear = c.intOrNull(5),
                    sourceLabel = sourceLabel(c.getInt(4)),
                    barcode = barcode,
                    category = c.intOrNull(30)?.let { cat -> FoodCategory(cat, c.strOrNull(31).orEmpty(), c.strOrNull(32).orEmpty()) },
                    dietInfo = DietInfo(c.getInt(26), c.getInt(27), c.getInt(28), c.getInt(29) != 0),
                )
            }

        private fun servingsIn(d: SQLiteDatabase, id: Long): List<Serving> =
            d.rawQuery("SELECT seq, label, grams_x10 FROM serving WHERE food_id = ? ORDER BY seq", arrayOf(id.toString())).use { c ->
                val out = ArrayList<Serving>()
                while (c.moveToNext()) out.add(Serving(c.getInt(0), c.getString(1), c.intOrNull(2)))
                out
            }

        private fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)

        private fun Cursor.strOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
    }
}
