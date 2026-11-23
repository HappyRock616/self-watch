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
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Serving

/**
 * Tony's offline food database (`foods.db`, FOODDB_SPEC Part B) through a [FoodsDbHandle].
 * Every call returns empty or null when the file is not installed.
 *
 * Search is FOODDB_SPEC §13's query: each typed word becomes a prefix term (`mcdonalds* big*`), all
 * must match `food_fts` (name, brand, keywords; accents ignored by the tokenizer), ordered by
 * `popularity`.
 */
class FoodsDbSource(private val handle: FoodsDbHandle) : FoodSource {

    override val origin: FoodOrigin = FoodOrigin.FOODS_DB

    /** True when foods.db is installed. */
    val isInstalled: Boolean get() = handle.isInstalled

    override suspend fun search(query: String, limit: Int): List<FoodHit> {
        val match = matchQuery(query) ?: return emptyList()
        if (limit <= 0) return emptyList()
        return handle.use { d ->
            d.rawQuery("$SEARCH_SQL $limit", arrayOf(match)).use { c ->
                val out = ArrayList<FoodHit>(c.count.coerceAtLeast(0))
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
                    out.add(
                        FoodHit(
                            ref = FoodRef(FoodOrigin.FOODS_DB, c.getLong(0)),
                            name = c.getString(1),
                            brand = c.strOrNull(2),
                            kcalX10PerDefault = kcal,
                            defaultServing = label,
                            sourceTag = FoodHit.TAG_FOODS_DB,
                            rank = out.size,
                            kind = c.intOrNull(7),
                            sourceCode = c.intOrNull(8),
                            dataYear = c.intOrNull(9),
                        ),
                    )
                }
                out
            }
        } ?: emptyList()
    }

    override suspend fun byBarcode(gtin: Long): Food? = handle.use { d ->
        d.rawQuery("SELECT f.id FROM barcode b JOIN food f ON f.id = b.food_id WHERE b.gtin = ?", arrayOf(gtin.toString())).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }?.let { foodIn(d, it, gtin) }
    }

    override suspend fun get(id: Long): Food? = handle.use { d -> foodIn(d, id, null) }

    /** The servings of food [id], default (seq 0) first. */
    suspend fun servings(id: Long): List<Serving> = handle.use { d -> servingsIn(d, id) } ?: emptyList()

    /** The `meta` table (FOODDB_SPEC §9), for the About and data screens; empty when not installed. */
    suspend fun meta(): Map<String, String> = handle.use { d ->
        d.rawQuery("SELECT key, value FROM meta", null).use { c ->
            val m = LinkedHashMap<String, String>()
            while (c.moveToNext()) m[c.getString(0)] = c.getString(1)
            m
        }
    } ?: emptyMap()

    /** Constants and the query builder. */
    companion object {
        private const val SEARCH_SQL =
            "SELECT f.id, f.name, f.brand, f.kcal_x10, f.basis, s.label, s.grams_x10, f.kind, f.source, f.data_year " +
                "FROM food_fts JOIN food f ON f.id = food_fts.docid " +
                "LEFT JOIN serving s ON s.food_id = f.id AND s.seq = 0 " +
                "WHERE food_fts MATCH ? ORDER BY f.popularity DESC LIMIT"

        private const val FOOD_SQL =
            "SELECT id, name, brand, kind, source, data_year, basis, kcal_x10, protein_x10, fat_x10, satfat_x10, " +
                "carb_x10, sugar_x10, fibre_x10, sodium_mg, cholesterol_mg FROM food WHERE id = ?"

        /**
         * The FTS4 MATCH text for what the user typed: words are runs of letters and digits, lowercased
         * (so `AND`/`OR`/`NOT` can never act as operators), and each becomes a prefix term. Quotes,
         * apostrophes and every other symbol only separate words. Null when there are no words.
         */
        fun matchQuery(text: String): String? {
            val words = ArrayList<String>()
            val sb = StringBuilder()
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) {
                    sb.appendCodePoint(cp)
                } else if (sb.isNotEmpty()) {
                    words.add(sb.toString().lowercase())
                    sb.setLength(0)
                }
                i += Character.charCount(cp)
            }
            if (sb.isNotEmpty()) words.add(sb.toString().lowercase())
            if (words.isEmpty()) return null
            return words.joinToString(" ") { "$it*" }
        }

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
                    per = Nutrients(
                        kcalX10 = c.intOrNull(7),
                        proteinX10 = c.intOrNull(8),
                        fatX10 = c.intOrNull(9),
                        satfatX10 = c.intOrNull(10),
                        carbX10 = c.intOrNull(11),
                        sugarX10 = c.intOrNull(12),
                        fibreX10 = c.intOrNull(13),
                        sodiumMg = c.intOrNull(14),
                        cholesterolMg = c.intOrNull(15),
                    ),
                    servings = servingsIn(d, id),
                    dataYear = c.intOrNull(5),
                    sourceLabel = sourceLabel(c.getInt(4)),
                    barcode = barcode,
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
