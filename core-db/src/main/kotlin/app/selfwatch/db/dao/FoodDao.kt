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
import app.selfwatch.model.food.Food
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
        private const val COLS = 19
        private const val COL_NAMES = "name, brand, basis, ${NutrientCols.NAMES}, barcode, origin, origin_ref, " +
            "created_sec, updated_sec, hidden, import_id"
        private val SET_COLS = COL_NAMES.split(", ").joinToString(", ") { "$it = ?" }
        private const val SELECT = "SELECT id, $COL_NAMES FROM custom_food"

        private fun bindCols(st: SQLiteStatement, f: CustomFood) {
            st.bindString(1, f.food.name)
            st.bindStrOrNull(2, f.food.brand)
            st.bindLong(3, f.food.basis.code.toLong())
            NutrientCols.bind(st, 4, f.food.per)
            st.bindLongOrNull(13, f.food.barcode)
            st.bindLong(14, f.origin.code.toLong())
            st.bindStrOrNull(15, f.originRef)
            st.bindLong(16, f.createdSec)
            st.bindLong(17, f.updatedSec)
            st.bindBool(18, f.hidden)
            st.bindLongOrNull(19, f.importId)
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
                    barcode = c.longOrNull(13),
                ),
                origin = CustomFoodOrigin.fromCode(c.getInt(14)) ?: CustomFoodOrigin.TYPED,
                originRef = c.strOrNull(15),
                createdSec = c.getLong(16),
                updatedSec = c.getLong(17),
                hidden = c.bool(18),
                importId = c.longOrNull(19),
            )
        }
    }
}
