/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import android.database.sqlite.SQLiteDatabase
import app.selfwatch.food.source.FoodDbFormat
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Builds small foods.db / recipes.db files that follow FOODDB_SPEC Part B exactly (§2 settings, §3
 * tables with the FTS4 external-content index, §9 meta), standing in for Tony's `foods-sample.db`.
 */
object FoodsDbFixture {
    /** UPC-A 0 12345 67890 5 as a GTIN-14 number: Fage Total 0%. */
    const val YOGURT_GTIN: Long = 12345678905L

    /** EAN-8 9638 5074: the pancake mix. */
    const val PANCAKE_GTIN: Long = 96385074L

    /** Food ids in the fixture. */
    const val BIG_MAC: Long = 1001
    const val BIG_BREAKFAST: Long = 1002
    const val HAMBURGER: Long = 1003
    const val CREME_BRULEE: Long = 2001
    const val YOGURT: Long = 3001
    const val PANCAKE_MIX: Long = 3002
    const val EGG: Long = 4001
    const val FLOUR: Long = 4002
    const val BLACK_BEANS: Long = 4003
    const val MILK: Long = 4004
    const val ONION: Long = 4005

    private val FOOD_SQL = listOf(
        // id, name, brand, keywords, kind, chain_id, source, data_year, popularity, basis, kcal, protein, fat, satfat, carb, sugar, fibre, sodium, chol
        "($BIG_MAC, 'Big Mac', 'McDonald''s', 'mcdonalds', 2, 1, 6, 2018, 900, 0, 2570, 118, 150, 50, 204, 40, 16, 460, 35)",
        "($BIG_BREAKFAST, 'Big Breakfast', 'McDonald''s', 'mcdonalds', 2, 1, 6, 2018, 500, 0, 2210, 95, 150, 55, 120, 10, 10, 520, 180)",
        "($HAMBURGER, 'Hamburger', 'McDonald''s', 'mcdonalds', 2, 1, 6, 2018, 800, 2, 2500, 120, 90, 35, 310, 60, 10, 510, 30)",
        "($CREME_BRULEE, 'Crème brûlée', NULL, 'creme brulee custard', 0, NULL, 5, 2020, 300, 0, 3150, 45, 260, 150, 170, 160, 0, 40, 210)",
        "($YOGURT, 'Total 0% Greek Yogurt', 'Fage', 'yoghurt', 1, NULL, 2, 2025, 700, 0, 540, 103, 0, 0, 36, 36, 0, 36, 5)",
        "($PANCAKE_MIX, 'Buttermilk Pancake Mix', 'Kitchen Basics', 'flapjack', 1, NULL, 1, 2026, 400, 0, 3600, 90, 30, 10, 750, 120, 20, 1100, NULL)",
        "($EGG, 'Egg, whole, raw', NULL, 'eggs', 0, NULL, 4, 2018, 1000, 0, 1430, 126, 95, 31, 7, 4, 0, 142, 372)",
        "($FLOUR, 'Flour, wheat, all-purpose', NULL, NULL, 0, NULL, 4, 2018, 950, 0, 3640, 103, 10, 2, 763, 3, 27, 2, 0)",
        "($BLACK_BEANS, 'Black beans, canned, drained', NULL, NULL, 0, NULL, 4, 2018, 600, 0, 910, 60, 3, 1, 165, 3, 69, 384, 0)",
        "($MILK, 'Milk, whole', NULL, NULL, 0, NULL, 4, 2018, 990, 1, 610, 32, 33, 19, 48, 51, 0, 43, 10)",
        "($ONION, 'Onion, raw', NULL, 'onions', 0, NULL, 3, 2019, 980, 0, 400, 11, 1, 0, 93, 42, 17, 4, 0)",
    )

    private val SERVING_SQL = listOf(
        "($BIG_MAC, 0, '1 sandwich', 2190)",
        "($BIG_BREAKFAST, 0, '1 serving', 2700)",
        "($HAMBURGER, 0, '1 sandwich', NULL)",
        "($CREME_BRULEE, 0, '1 serving', 1050)",
        "($YOGURT, 0, '1 container', 1700)",
        "($YOGURT, 1, '1 cup', 2270)",
        "($PANCAKE_MIX, 0, '1/3 cup', 400)",
        "($EGG, 0, '1 large', 500)",
        "($EGG, 1, '1 cup', 2430)",
        "($FLOUR, 0, '1 cup', 1250)",
        "($FLOUR, 1, '1 tbsp', 78)",
        "($BLACK_BEANS, 0, '1 cup', 1720)",
        "($MILK, 0, '1 cup', 2440)",
        "($ONION, 0, '1 medium', 1100)",
        "($ONION, 1, '1 cup', 1600)",
    )

    /**
     * Writes a spec-following foods.db to [file]. [applicationId] and [userVersion] can be broken on
     * purpose; [withRows] false leaves the food table empty; [dataVersion] goes into `meta`.
     */
    fun buildFoods(
        file: File,
        applicationId: Int = FoodDbFormat.FOODS_APPLICATION_ID,
        userVersion: Int = 1,
        withRows: Boolean = true,
        dataVersion: Int = 20261122,
    ) {
        file.parentFile?.mkdirs()
        file.delete()
        val db = create(file, applicationId, userVersion)
        try {
            db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID")
            db.execSQL("CREATE TABLE chain (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)")
            db.execSQL(
                """
                CREATE TABLE food (
                  id INTEGER PRIMARY KEY, name TEXT NOT NULL, brand TEXT, keywords TEXT, kind INTEGER NOT NULL,
                  chain_id INTEGER, source INTEGER NOT NULL, data_year INTEGER, popularity INTEGER NOT NULL DEFAULT 0,
                  basis INTEGER NOT NULL, kcal_x10 INTEGER, protein_x10 INTEGER, fat_x10 INTEGER, satfat_x10 INTEGER,
                  carb_x10 INTEGER, sugar_x10 INTEGER, fibre_x10 INTEGER, sodium_mg INTEGER, cholesterol_mg INTEGER)
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX food_chain ON food(chain_id) WHERE chain_id IS NOT NULL")
            db.execSQL(
                "CREATE TABLE serving (food_id INTEGER NOT NULL, seq INTEGER NOT NULL, label TEXT NOT NULL, grams_x10 INTEGER, " +
                    "PRIMARY KEY (food_id, seq)) WITHOUT ROWID",
            )
            db.execSQL("CREATE TABLE barcode (gtin INTEGER PRIMARY KEY, food_id INTEGER NOT NULL) WITHOUT ROWID")
            db.execSQL(
                "CREATE VIRTUAL TABLE food_fts USING fts4(content=\"food\", name, brand, keywords, prefix=\"2,3\", " +
                    "tokenize=unicode61 \"remove_diacritics=1\")",
            )
            val meta = mapOf(
                "schema_version" to "1",
                "data_version" to dataVersion.toString(),
                "built_at" to "2026-11-22T21:30:00Z",
                "min_app_version" to "1",
                "sources" to "test fixture",
                "license" to "ODbL-1.0",
                "attribution" to "Test data written for Self Watch tests.",
                "row_counts" to "food=${FOOD_SQL.size};serving=${SERVING_SQL.size};barcode=2;chain=1",
            )
            for ((k, v) in meta) db.execSQL("INSERT INTO meta (key, value) VALUES (?, ?)", arrayOf(k, v))
            if (withRows) {
                db.execSQL("INSERT INTO chain (id, name) VALUES (1, 'McDonald''s')")
                db.execSQL(
                    "INSERT INTO food (id, name, brand, keywords, kind, chain_id, source, data_year, popularity, basis, kcal_x10, " +
                        "protein_x10, fat_x10, satfat_x10, carb_x10, sugar_x10, fibre_x10, sodium_mg, cholesterol_mg) VALUES " +
                        FOOD_SQL.joinToString(", "),
                )
                db.execSQL("INSERT INTO serving (food_id, seq, label, grams_x10) VALUES " + SERVING_SQL.joinToString(", "))
                db.execSQL("INSERT INTO barcode (gtin, food_id) VALUES ($YOGURT_GTIN, $YOGURT), ($PANCAKE_GTIN, $PANCAKE_MIX)")
            }
            finish(db, "food_fts")
        } finally {
            db.close()
        }
    }

    /** Writes a spec-following recipes.db (§10) with one recipe to [file]. */
    fun buildRecipes(file: File, applicationId: Int = FoodDbFormat.RECIPES_APPLICATION_ID, dataVersion: Int = 20261201) {
        file.parentFile?.mkdirs()
        file.delete()
        val db = create(file, applicationId, 1)
        try {
            db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID")
            db.execSQL(
                "CREATE TABLE recipe (id INTEGER PRIMARY KEY, name TEXT NOT NULL, servings_x10 INTEGER NOT NULL, serving_label TEXT, " +
                    "prep_min INTEGER, cook_min INTEGER, steps TEXT NOT NULL, source_name TEXT NOT NULL, source_url TEXT, " +
                    "license TEXT NOT NULL, kcal_x10 INTEGER, protein_x10 INTEGER, fat_x10 INTEGER, satfat_x10 INTEGER, " +
                    "carb_x10 INTEGER, sugar_x10 INTEGER, fibre_x10 INTEGER, sodium_mg INTEGER, cholesterol_mg INTEGER)",
            )
            db.execSQL(
                "CREATE TABLE recipe_item (recipe_id INTEGER NOT NULL, seq INTEGER NOT NULL, line TEXT NOT NULL, amount_x100 INTEGER, " +
                    "unit TEXT, food_name TEXT, food_id INTEGER, grams_x10 INTEGER, PRIMARY KEY (recipe_id, seq)) WITHOUT ROWID",
            )
            db.execSQL("CREATE TABLE recipe_tag (recipe_id INTEGER NOT NULL, tag TEXT NOT NULL, PRIMARY KEY (recipe_id, tag)) WITHOUT ROWID")
            db.execSQL(
                "CREATE VIRTUAL TABLE recipe_fts USING fts4(content=\"recipe\", name, steps, prefix=\"2,3\", " +
                    "tokenize=unicode61 \"remove_diacritics=1\")",
            )
            db.execSQL("INSERT INTO meta (key, value) VALUES ('schema_version', '1'), ('data_version', '$dataVersion')")
            db.execSQL(
                "INSERT INTO recipe (id, name, servings_x10, steps, source_name, license, kcal_x10) VALUES " +
                    "(1, 'Bean Bowl', 40, 'Warm the beans.\nServe.', 'Test kitchen', 'Public domain', 3200)",
            )
            db.execSQL("INSERT INTO recipe_item (recipe_id, seq, line) VALUES (1, 0, '1 can (15 oz) black beans, drained')")
            finish(db, "recipe_fts")
        } finally {
            db.close()
        }
    }

    /** gzip of [file] (level 9 is the spec's; the level does not matter to the reader). */
    fun gzip(file: File, out: File) {
        out.parentFile?.mkdirs()
        GZIPOutputStream(FileOutputStream(out)).use { gz -> file.inputStream().use { it.copyTo(gz) } }
    }

    private fun create(file: File, applicationId: Int, userVersion: Int): SQLiteDatabase {
        val db = SQLiteDatabase.openDatabase(
            file.path,
            null,
            SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        db.execSQL("PRAGMA page_size = 4096")
        db.rawQuery("PRAGMA journal_mode = DELETE", null).use { it.moveToFirst() }
        db.execSQL("PRAGMA application_id = $applicationId")
        db.version = userVersion
        return db
    }

    private fun finish(db: SQLiteDatabase, fts: String) {
        db.execSQL("INSERT INTO $fts($fts) VALUES('rebuild')")
        db.execSQL("INSERT INTO $fts($fts) VALUES('optimize')")
        db.execSQL("ANALYZE")
        db.execSQL("VACUUM")
        val ok = db.rawQuery("PRAGMA integrity_check", null).use { c -> c.moveToFirst() && c.getString(0) == "ok" }
        check(ok) { "fixture failed integrity_check" }
    }
}
