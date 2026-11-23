/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db.dao

import android.database.Cursor
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.db.args
import app.selfwatch.db.bindStrOrNull
import app.selfwatch.db.bool
import app.selfwatch.db.queryList
import app.selfwatch.db.queryOne
import app.selfwatch.db.read
import app.selfwatch.db.strOrNull
import app.selfwatch.db.withStatement
import app.selfwatch.db.write
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.json.Json
import app.selfwatch.model.json.JsonValue
import app.selfwatch.model.result.valueOrNull

/**
 * One import run.
 *
 * @property importer importer id, for example `gadgetbridge` or `csv.weight`.
 * @property counts rows imported per kind (importer-defined keys), stored as JSON.
 * @property undone true once [ImportDao.undo] removed its rows.
 */
public data class ImportBatch(
    val id: Long,
    val tSec: Long,
    val importer: String,
    val fileName: String?,
    val counts: Map<String, Long>,
    val undone: Boolean,
)

/** Import batches (table `import_batch`) and undo across every table with an `import_id`. */
public class ImportDao internal constructor(private val db: SelfWatchDb) {

    /** Starts a batch and returns its id; tag imported rows with it. */
    public suspend fun begin(tSec: Long, importer: String, fileName: String?): Long = db.write(setOf(Table.IMPORT_BATCH)) { d ->
        d.withStatement("INSERT INTO import_batch (t_sec, importer, file_name, counts_json) VALUES (?, ?, ?, '{}')") { st ->
            st.bindLong(1, tSec)
            st.bindString(2, importer)
            st.bindStrOrNull(3, fileName)
            st.executeInsert()
        }
    }

    /** Records how many rows of each kind batch [id] imported. */
    public suspend fun setCounts(id: Long, counts: Map<String, Long>) {
        val json = Json.write(JsonValue.Obj(counts.mapValues { JsonValue.Num.of(it.value) }))
        db.write(setOf(Table.IMPORT_BATCH)) { d ->
            d.withStatement("UPDATE import_batch SET counts_json = ? WHERE id = ?") { st ->
                st.bindString(1, json)
                st.bindLong(2, id)
                st.executeUpdateDelete()
            }
        }
    }

    /** Batch [id], or null. */
    public suspend fun get(id: Long): ImportBatch? = db.read { it.queryOne("$SELECT WHERE id = ?", args(id), ::map) }

    /** Every batch, newest first. */
    public suspend fun list(): List<ImportBatch> = db.read { it.queryList("$SELECT ORDER BY t_sec DESC, id DESC", null, ::map) }

    /**
     * Deletes every row tagged with import [id] from every table that has `import_id` (workouts, body
     * measurements, custom foods with servings and search entries, diary, water, recipes with items,
     * tags and search entries, and watch data: activity minutes, metrics, sleep sessions and stages),
     * in one transaction, and marks the batch undone. Returns the number of
     * rows deleted from those tables (child rows removed by cascade not counted).
     */
    public suspend fun undo(id: Long): Int = db.write(UNDO_TABLES) { d ->
        val a = args(id)
        val foodBase = MyFoodFts.docid(FoodOrigin.MY_FOOD, 0)
        val recipeBase = MyFoodFts.docid(FoodOrigin.RECIPE, 0)
        d.execSQL("DELETE FROM my_food_fts WHERE docid IN (SELECT $foodBase + id FROM custom_food WHERE import_id = ?)", arrayOf<Any>(id))
        d.execSQL("DELETE FROM my_food_fts WHERE docid IN (SELECT $recipeBase + id FROM recipe WHERE import_id = ?)", arrayOf<Any>(id))
        var n = 0
        for (t in IMPORT_TABLES) n += d.delete(t, "import_id = ?", a)
        d.withStatement("UPDATE import_batch SET undone = 1 WHERE id = ?") { st ->
            st.bindLong(1, id)
            st.executeUpdateDelete()
        }
        n
    }

    internal companion object {
        /** Tables with an `import_id` column. */
        internal val IMPORT_TABLES: List<String> = listOf(
            "workout", "body_measure", "custom_food", "diary_entry", "water", "recipe",
            "activity_minute", "metric", "sleep_session", "sleep_stage",
        )

        private val UNDO_TABLES = setOf(
            Table.WORKOUT, Table.BODY, Table.CUSTOM_FOOD, Table.CUSTOM_SERVING, Table.DIARY, Table.WATER, Table.RECIPE,
            Table.RECIPE_ITEM, Table.RECIPE_TAG, Table.MY_FOOD_FTS, Table.IMPORT_BATCH,
            Table.ACTIVITY, Table.METRIC, Table.SLEEP_SESSION, Table.SLEEP_STAGE,
        )
        private const val SELECT = "SELECT id, t_sec, importer, file_name, counts_json, undone FROM import_batch"

        private fun map(c: Cursor): ImportBatch = ImportBatch(
            id = c.getLong(0),
            tSec = c.getLong(1),
            importer = c.getString(2),
            fileName = c.strOrNull(3),
            counts = parseCounts(c.getString(4)),
            undone = c.bool(5),
        )

        private fun parseCounts(json: String): Map<String, Long> {
            val m = Json.parse(json).valueOrNull()?.asMap ?: return emptyMap()
            val out = LinkedHashMap<String, Long>()
            for ((k, v) in m) v.asLong?.let { out[k] = it }
            return out
        }
    }
}
