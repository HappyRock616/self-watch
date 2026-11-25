/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db.dao

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import app.selfwatch.db.CustomFoodOrigin
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.db.args
import app.selfwatch.db.bindBool
import app.selfwatch.db.bindIntOrNull
import app.selfwatch.db.bindLongOrNull
import app.selfwatch.db.bindStrOrNull
import app.selfwatch.db.bool
import app.selfwatch.db.intOrNull
import app.selfwatch.db.longOrNull
import app.selfwatch.db.queryList
import app.selfwatch.db.queryOne
import app.selfwatch.db.read
import app.selfwatch.db.strOrNull
import app.selfwatch.db.withStatement
import app.selfwatch.db.write
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.DietInfo
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodCategory
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.Serving

/**
 * A food the user made or saved (table `custom_food` and its servings).
 *
 * [food] carries the shared fields: `ref` is `(MY_FOOD, row id)` (the id is ignored on insert),
 * `servings` are the `custom_serving` rows. Fields `custom_food` does not store read back as
 * `kind = 0`, `dataYear = null`, `sourceLabel = ""`.
 */
public data class CustomFood(
    val food: Food,
    val origin: CustomFoodOrigin,
    val originRef: String?,
    val hidden: Boolean,
    val createdSec: Long,
    val updatedSec: Long,
    val importId: Long?,
)

/** One search hit in the user's own foods and recipes. */
public data class MyFoodHit(val ref: FoodRef, val name: String, val brand: String?)

/** The user's own foods (tables `custom_food`, `custom_serving`) and the `my_food_fts` search. */
public class FoodDao internal constructor(private val db: SelfWatchDb) {

    /** Stores [f] with its servings and search entry; returns the new id. */
    public suspend fun insert(f: CustomFood): Long = db.write(TABLES) { insertIn(it, f) }

    /** Rewrites food `f.food.ref.id`, replacing its servings and search entry. Returns true if it existed. */
    public suspend fun update(f: CustomFood): Boolean = db.write(TABLES) { d ->
        val id = f.food.ref.id
        val n = d.withStatement("UPDATE custom_food SET $SET_COLS WHERE id = ?") { st ->
            bindCols(st, f)
            st.bindLong(COLS + 1, id)
            st.executeUpdateDelete()
        }
        if (n == 0) return@write false
        d.delete("custom_serving", "food_id = ?", args(id))
        insertServings(d, id, f.food.servings)
        index(d, id, f)
        true
    }

    /** The food [id] (hidden or not), or null. */
    public suspend fun get(id: Long): CustomFood? = db.read { d -> d.queryOne("$SELECT WHERE id = ?", args(id)) { map(d, it) } }

    /** The newest visible food with [barcode], or null. */
    public suspend fun byBarcode(barcode: Long): CustomFood? = db.read { d ->
        d.queryOne("$SELECT WHERE barcode = ? AND hidden = 0 ORDER BY updated_sec DESC, id DESC LIMIT 1", args(barcode)) { map(d, it) }
    }

    /** All foods by name; hidden ones only when [includeHidden]. */
    public suspend fun list(includeHidden: Boolean = false): List<CustomFood> = db.read { d ->
        val where = if (includeHidden) "" else "WHERE hidden = 0"
        d.queryList("$SELECT $where ORDER BY name COLLATE NOCASE, id", null) { map(d, it) }
    }

    /** The servings of food [id], in order. */
    public suspend fun servings(id: Long): List<Serving> = db.read { servingsIn(it, id) }

    /** Hides or shows food [id] (hidden foods leave search but stay in the diary). Returns true if it existed. */
    public suspend fun setHidden(id: Long, hidden: Boolean, nowSec: Long): Boolean = db.write(TABLES) { d ->
        val n = d.withStatement("UPDATE custom_food SET hidden = ?, updated_sec = ? WHERE id = ?") { st ->
            st.bindBool(1, hidden)
            st.bindLong(2, nowSec)
            st.bindLong(3, id)
            st.executeUpdateDelete()
        }
        if (n == 0) return@write false
        if (hidden) {
            MyFoodFts.remove(d, FoodOrigin.MY_FOOD, id)
        } else {
            d.queryOne("SELECT name, brand FROM custom_food WHERE id = ?", args(id)) { c ->
                MyFoodFts.put(d, FoodOrigin.MY_FOOD, id, c.getString(0), c.strOrNull(1))
            }
        }
        true
    }

    /** Deletes food [id], its servings and its search entry (diary lines keep their copy). Returns true if it existed. */
    public suspend fun delete(id: Long): Boolean = db.write(TABLES) { d ->
        MyFoodFts.remove(d, FoodOrigin.MY_FOOD, id)
        d.delete("custom_food", "id = ?", args(id)) > 0
    }

    /**
     * Searches the user's visible foods and recipes: every word of [query] must start a word of the
     * name or brand (accents ignored). Shorter names first. At most [limit] hits.
     */
    public suspend fun searchMine(query: String, limit: Int = 50): List<MyFoodHit> {
        val match = MyFoodFts.matchQuery(query) ?: return emptyList()
        return db.read { d ->
            d.queryList(
                "SELECT docid, name, brand FROM my_food_fts WHERE my_food_fts MATCH ? ORDER BY length(name), name, docid " +
                    "LIMIT ${limit.coerceAtLeast(0)}",
                arrayOf(match),
            ) { c -> MyFoodFts.refOf(c.getLong(0))?.let { MyFoodHit(it, c.getString(1), c.strOrNull(2)) } }.filterNotNull()
        }
    }

    internal companion object {
        private val TABLES = setOf(Table.CUSTOM_FOOD, Table.CUSTOM_SERVING, Table.MY_FOOD_FTS)
        /** Position of `barcode` (1-based bind index; the cursor index in [SELECT] is the same, after `id`). */
        private const val AFTER = 4 + NutrientCols.COUNT
        private const val COLS = AFTER + 11
        private const val COL_NAMES = "name, brand, basis, ${NutrientCols.NAMES}, barcode, origin, origin_ref, " +
            "created_sec, updated_sec, hidden, import_id, category_id, diet, diet_known, allergens, allergens_full"
        private val SET_COLS = COL_NAMES.split(", ").joinToString(", ") { "$it = ?" }
        private const val SELECT = "SELECT id, $COL_NAMES FROM custom_food"

        private fun bindCols(st: SQLiteStatement, f: CustomFood) {
            st.bindString(1, f.food.name)
            st.bindStrOrNull(2, f.food.brand)
            st.bindLong(3, f.food.basis.code.toLong())
            NutrientCols.bind(st, 4, f.food.per)
            st.bindLongOrNull(AFTER, f.food.barcode)
            st.bindLong(AFTER + 1, f.origin.code.toLong())
            st.bindStrOrNull(AFTER + 2, f.originRef)
            st.bindLong(AFTER + 3, f.createdSec)
            st.bindLong(AFTER + 4, f.updatedSec)
            st.bindBool(AFTER + 5, f.hidden)
            st.bindLongOrNull(AFTER + 6, f.importId)
            st.bindIntOrNull(AFTER + 7, f.food.category?.id)
            val diet = f.food.dietInfo
            st.bindLong(AFTER + 8, diet.diet.toLong())
            st.bindLong(AFTER + 9, diet.dietKnown.toLong())
            st.bindLong(AFTER + 10, diet.allergens.toLong())
            st.bindBool(AFTER + 11, diet.allergensFull)
        }

        internal fun insertIn(d: SQLiteDatabase, f: CustomFood): Long {
            val id = d.withStatement("INSERT INTO custom_food ($COL_NAMES) VALUES (${"?, ".repeat(COLS - 1)}?)") { st ->
                bindCols(st, f)
                st.executeInsert()
            }
            insertServings(d, id, f.food.servings)
            index(d, id, f)
            return id
        }

        private fun index(d: SQLiteDatabase, id: Long, f: CustomFood) {
            if (f.hidden) MyFoodFts.remove(d, FoodOrigin.MY_FOOD, id) else MyFoodFts.put(d, FoodOrigin.MY_FOOD, id, f.food.name, f.food.brand)
        }

        private fun insertServings(d: SQLiteDatabase, id: Long, servings: List<Serving>) {
            if (servings.isEmpty()) return
            d.withStatement("INSERT OR REPLACE INTO custom_serving (food_id, seq, label, grams_x10) VALUES (?, ?, ?, ?)") { st ->
                for (s in servings) {
                    st.bindLong(1, id)
                    st.bindLong(2, s.seq.toLong())
                    st.bindString(3, s.label)
                    st.bindIntOrNull(4, s.gramsX10)
                    st.executeInsert()
                }
            }
        }

        private fun servingsIn(d: SQLiteDatabase, id: Long): List<Serving> =
            d.queryList("SELECT seq, label, grams_x10 FROM custom_serving WHERE food_id = ? ORDER BY seq", args(id)) { c ->
                Serving(c.getInt(0), c.getString(1), c.intOrNull(2))
            }

        private fun map(d: SQLiteDatabase, c: Cursor): CustomFood {
            val id = c.getLong(0)
            return CustomFood(
                food = Food(
                    ref = FoodRef(FoodOrigin.MY_FOOD, id),
                    name = c.getString(1),
                    brand = c.strOrNull(2),
                    kind = 0,
                    basis = Basis.fromCode(c.getInt(3)) ?: Basis.PER_100G,
                    per = NutrientCols.read(c, 4),
                    servings = servingsIn(d, id),
                    dataYear = null,
                    sourceLabel = "",
                    barcode = c.longOrNull(AFTER),
                    // Custom foods store only the category id; screens look the name up in foods.db.
                    category = c.intOrNull(AFTER + 7)?.let { FoodCategory(it, "", "") },
                    dietInfo = DietInfo(c.getInt(AFTER + 8), c.getInt(AFTER + 9), c.getInt(AFTER + 10), c.bool(AFTER + 11)),
                ),
                origin = CustomFoodOrigin.fromCode(c.getInt(AFTER + 1)) ?: CustomFoodOrigin.TYPED,
                originRef = c.strOrNull(AFTER + 2),
                createdSec = c.getLong(AFTER + 3),
                updatedSec = c.getLong(AFTER + 4),
                hidden = c.bool(AFTER + 5),
                importId = c.longOrNull(AFTER + 6),
            )
        }
    }
}
