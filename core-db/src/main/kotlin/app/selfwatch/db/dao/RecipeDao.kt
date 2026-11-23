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
import app.selfwatch.db.RecipeOrigin
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.db.args
import app.selfwatch.db.bindIntOrNull
import app.selfwatch.db.bindLongOrNull
import app.selfwatch.db.bindStrOrNull
import app.selfwatch.db.intOrNull
import app.selfwatch.db.longOrNull
import app.selfwatch.db.queryList
import app.selfwatch.db.queryOne
import app.selfwatch.db.read
import app.selfwatch.db.strOrNull
import app.selfwatch.db.withStatement
import app.selfwatch.db.write
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.MatchState
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Recipe
import app.selfwatch.model.food.RecipeItem
import app.selfwatch.model.food.RecipeKind

/** A recipe-book row for lists: no steps or ingredient lines. */
public data class RecipeListItem(
    val id: Long,
    val kind: RecipeKind,
    val name: String,
    val servingsX10: Int,
    val statedPerServing: Nutrients?,
    val tags: Set<String>,
)

/** The recipe book (tables `recipe`, `recipe_item`, `recipe_tag`), kept in `my_food_fts` for search. */
public class RecipeDao internal constructor(private val db: SelfWatchDb) {

    /**
     * Stores [r] (its `id` is ignored) with its items, tags and search entry; returns the new id.
     * [origin] and [originRef] say where it came from; [nowSec] is the creation time.
     */
    public suspend fun insert(r: Recipe, nowSec: Long, origin: RecipeOrigin = RecipeOrigin.TYPED, originRef: String? = null): Long =
        db.write(TABLES) { insertIn(it, r, nowSec, origin, originRef) }

    /** Rewrites recipe `r.id` (origin, creation time and import id kept), replacing items and tags. Returns true if it existed. */
    public suspend fun update(r: Recipe, nowSec: Long): Boolean = db.write(TABLES) { d ->
        val n = d.withStatement("UPDATE recipe SET $SET_EDITABLE, updated_sec = ? WHERE id = ?") { st ->
            bindEditable(st, r)
            st.bindLong(EDITABLE + 1, nowSec)
            st.bindLong(EDITABLE + 2, r.id)
            st.executeUpdateDelete()
        }
        if (n == 0) return@write false
        d.delete("recipe_item", "recipe_id = ?", args(r.id))
        d.delete("recipe_tag", "recipe_id = ?", args(r.id))
        insertChildren(d, r.id, r)
        MyFoodFts.put(d, FoodOrigin.RECIPE, r.id, r.name, null)
        true
    }

    /** Recipe [id] with items and tags, or null. */
    public suspend fun get(id: Long): Recipe? = db.read { d ->
        d.queryOne("SELECT id, $EDITABLE_NAMES, import_id FROM recipe WHERE id = ?", args(id)) { c ->
            Recipe(
                id = c.getLong(0),
                kind = RecipeKind.fromCode(c.getInt(1)) ?: RecipeKind.RECIPE,
                name = c.getString(2),
                servingsX10 = c.getInt(3),
                servingLabel = c.strOrNull(4),
                prepMin = c.intOrNull(5),
                cookMin = c.intOrNull(6),
                steps = c.strOrNull(7),
                notes = c.strOrNull(8),
                sourceName = c.strOrNull(9),
                sourceUrl = c.strOrNull(10),
                statedPerServing = NutrientCols.readOrNull(c, 11),
                tags = tagsOf(d, id),
                items = itemsOf(d, id),
                importId = c.longOrNull(20),
            )
        }
    }

    /** Where recipe [id] came from, or null if there is no such recipe. */
    public suspend fun origin(id: Long): Pair<RecipeOrigin, String?>? = db.read { d ->
        d.queryOne("SELECT origin, origin_ref FROM recipe WHERE id = ?", args(id)) { c ->
            (RecipeOrigin.fromCode(c.getInt(0)) ?: RecipeOrigin.TYPED) to c.strOrNull(1)
        }
    }

    /**
     * Recipes by name, optionally only those tagged [tag] and only those whose name matches [query]
     * (word prefixes, accents ignored, as in `FoodDao.searchMine`).
     */
    public suspend fun list(tag: String? = null, query: String? = null): List<RecipeListItem> = db.read { d ->
        val where = ArrayList<String>()
        val a = ArrayList<String>()
        if (tag != null) {
            where += "r.id IN (SELECT recipe_id FROM recipe_tag WHERE tag = ?)"
            a += tag
        }
        if (query != null) {
            val match = MyFoodFts.matchQuery(query) ?: return@read emptyList()
            val range = MyFoodFts.docidRange(FoodOrigin.RECIPE)
            where += "r.id IN (SELECT docid - ${range.first} FROM my_food_fts WHERE my_food_fts MATCH ? " +
                "AND docid >= ${range.first} AND docid <= ${range.last})"
            a += match
        }
        val w = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        d.queryList(
            "SELECT r.id, r.kind, r.name, r.servings_x10, ${NutrientCols.NAMES.split(", ").joinToString(", ") { "r.$it" }} " +
                "FROM recipe r $w ORDER BY r.name COLLATE NOCASE, r.id",
            a.toTypedArray(),
        ) { c ->
            val id = c.getLong(0)
            RecipeListItem(
                id = id,
                kind = RecipeKind.fromCode(c.getInt(1)) ?: RecipeKind.RECIPE,
                name = c.getString(2),
                servingsX10 = c.getInt(3),
                statedPerServing = NutrientCols.readOrNull(c, 4),
                tags = tagsOf(d, id),
            )
        }
    }

    /** Every tag in use, sorted. */
    public suspend fun tags(): List<String> = db.read { d ->
        d.queryList("SELECT DISTINCT tag FROM recipe_tag ORDER BY tag COLLATE NOCASE", null) { it.getString(0) }
    }

    /** Deletes recipe [id] with its items, tags and search entry. Returns true if it existed. */
    public suspend fun delete(id: Long): Boolean = db.write(TABLES) { d ->
        MyFoodFts.remove(d, FoodOrigin.RECIPE, id)
        d.delete("recipe", "id = ?", args(id)) > 0
    }

    internal companion object {
        private val TABLES = setOf(Table.RECIPE, Table.RECIPE_ITEM, Table.RECIPE_TAG, Table.MY_FOOD_FTS)
        private const val EDITABLE = 19
        private const val EDITABLE_NAMES = "kind, name, servings_x10, serving_label, prep_min, cook_min, steps, notes, " +
            "source_name, source_url, ${NutrientCols.NAMES}"
        private val SET_EDITABLE = EDITABLE_NAMES.split(", ").joinToString(", ") { "$it = ?" }

        private fun bindEditable(st: SQLiteStatement, r: Recipe) {
            st.bindLong(1, r.kind.code.toLong())
            st.bindString(2, r.name)
            st.bindLong(3, r.servingsX10.toLong())
            st.bindStrOrNull(4, r.servingLabel)
            st.bindIntOrNull(5, r.prepMin)
            st.bindIntOrNull(6, r.cookMin)
            st.bindStrOrNull(7, r.steps)
            st.bindStrOrNull(8, r.notes)
            st.bindStrOrNull(9, r.sourceName)
            st.bindStrOrNull(10, r.sourceUrl)
            NutrientCols.bind(st, 11, r.statedPerServing)
        }

        internal fun insertIn(d: SQLiteDatabase, r: Recipe, nowSec: Long, origin: RecipeOrigin, originRef: String?): Long {
            val id = d.withStatement(
                "INSERT INTO recipe ($EDITABLE_NAMES, origin, origin_ref, created_sec, updated_sec, import_id) " +
                    "VALUES (${"?, ".repeat(EDITABLE + 4)}?)",
            ) { st ->
                bindEditable(st, r)
                st.bindLong(EDITABLE + 1, origin.code.toLong())
                st.bindStrOrNull(EDITABLE + 2, originRef)
                st.bindLong(EDITABLE + 3, nowSec)
                st.bindLong(EDITABLE + 4, nowSec)
                st.bindLongOrNull(EDITABLE + 5, r.importId)
                st.executeInsert()
            }
            insertChildren(d, id, r)
            MyFoodFts.put(d, FoodOrigin.RECIPE, id, r.name, null)
            return id
        }

        private fun insertChildren(d: SQLiteDatabase, id: Long, r: Recipe) {
            if (r.items.isNotEmpty()) {
                d.withStatement(
                    "INSERT OR REPLACE INTO recipe_item (recipe_id, seq, line, food_origin, food_id, amount_x100, unit, grams_x10, match_state) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ) { st ->
                    for (it in r.items) {
                        st.bindLong(1, id)
                        st.bindLong(2, it.seq.toLong())
                        st.bindString(3, it.line)
                        st.bindIntOrNull(4, it.food?.origin?.code)
                        st.bindLongOrNull(5, it.food?.id)
                        st.bindIntOrNull(6, it.amountX100)
                        st.bindStrOrNull(7, it.unit)
                        st.bindIntOrNull(8, it.gramsX10)
                        st.bindLong(9, it.match.code.toLong())
                        st.executeInsert()
                    }
                }
            }
            if (r.tags.isNotEmpty()) {
                d.withStatement("INSERT OR IGNORE INTO recipe_tag (recipe_id, tag) VALUES (?, ?)") { st ->
                    for (t in r.tags) {
                        st.bindLong(1, id)
                        st.bindString(2, t)
                        st.executeInsert()
                    }
                }
            }
        }

        private fun tagsOf(d: SQLiteDatabase, id: Long): Set<String> =
            d.queryList("SELECT tag FROM recipe_tag WHERE recipe_id = ? ORDER BY tag", args(id)) { it.getString(0) }.toCollection(LinkedHashSet())

        private fun itemsOf(d: SQLiteDatabase, id: Long): List<RecipeItem> = d.queryList(
            "SELECT seq, line, food_origin, food_id, amount_x100, unit, grams_x10, match_state FROM recipe_item WHERE recipe_id = ? ORDER BY seq",
            args(id),
            ::mapItem,
        )

        private fun mapItem(c: Cursor): RecipeItem {
            val origin = c.intOrNull(2)?.let { FoodOrigin.fromCode(it) }
            val foodId = c.longOrNull(3)
            return RecipeItem(
                seq = c.getInt(0),
                line = c.getString(1),
                food = if (origin != null && foodId != null) FoodRef(origin, foodId) else null,
                amountX100 = c.intOrNull(4),
                unit = c.strOrNull(5),
                gramsX10 = c.intOrNull(6),
                match = MatchState.fromCode(c.getInt(7)) ?: MatchState.UNMATCHED,
            )
        }
    }
}
