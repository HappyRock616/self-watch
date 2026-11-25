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
 * Builds small foods.db / recipes.db files that follow FOODDB_SPEC Part B (schema 2: §2 settings, §3
 * tables with the FTS4 external-content index, the `category` table and the v2 nutrient, diet and
 * allergen columns, §9 meta), standing in for Tony's `foods-sample.db`.
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

    /** Search fixtures (everyday names, brand mode, whole words). */
    const val CARROTS_RAW: Long = 5001
    const val CARROT_CAKE: Long = 5002
    const val BABY_CARROTS: Long = 5003
    const val COLA: Long = 5010
    const val COKE_ZERO: Long = 5011
    const val COKE_FLOAT: Long = 5012
    const val SALMON: Long = 5020
    const val SALMONBERRIES: Long = 5021
    const val SALMON_SALAD: Long = 5022
    const val ICE_CREAM: Long = 5030
    const val ICED_TEA: Long = 5031
    const val ICE_CREAM_SANDWICH: Long = 5032
    const val SPICY_DELUXE: Long = 5040
    const val NUGGETS: Long = 5041

    /** One `food` row; every v2 column defaults to unknown (NULL, or 0 bits). */
    private data class Row(
        val id: Long, val name: String, val brand: String?, val keywords: String?, val kind: Int, val chain: Int?,
        val source: Int, val year: Int?, val popularity: Int, val basis: Int, val kcal: Int?, val protein: Int?,
        val fat: Int?, val satfat: Int?, val carb: Int?, val sugar: Int?, val fibre: Int?, val sodium: Int?, val chol: Int?,
        val category: Int = 999, val transfat: Int? = null, val monofat: Int? = null, val polyfat: Int? = null,
        val addsugar: Int? = null, val potassium: Int? = null, val calcium: Int? = null, val iron: Int? = null,
        val vita: Int? = null, val vitc: Int? = null, val vitd: Int? = null,
        val diet: Int = 0, val dietKnown: Int = 0, val allergens: Int = 0, val allergensFull: Int = 0,
    ) {
        fun values(): Array<Any?> = arrayOf(
            id, name, brand, keywords, kind, chain, source, year, popularity, basis, kcal, protein, fat, satfat, carb, sugar,
            fibre, sodium, chol, category, transfat, monofat, polyfat, addsugar, potassium, calcium, iron, vita, vitc, vitd,
            diet, dietKnown, allergens, allergensFull,
        )
    }

    private val ROWS = listOf(
        Row(
            BIG_MAC, "Big Mac", "McDonald's", "mcdonalds", 2, 1, 6, 2018, 900, 0, 2570, 118, 150, 50, 204, 40, 16, 460, 35,
            category = 603, transfat = 5, potassium = 180, allergens = 1 or 2 or 32 or 256,
        ),
        Row(BIG_BREAKFAST, "Big Breakfast", "McDonald's", "mcdonalds", 2, 1, 6, 2018, 500, 0, 2210, 95, 150, 55, 120, 10, 10, 520, 180, category = 602),
        Row(HAMBURGER, "Hamburger", "McDonald's", "mcdonalds", 2, 1, 6, 2018, 800, 2, 2500, 120, 90, 35, 310, 60, 10, 510, 30, category = 603),
        Row(CREME_BRULEE, "Crème brûlée", null, "creme brulee custard", 0, null, 5, 2020, 300, 0, 3150, 45, 260, 150, 170, 160, 0, 40, 210, category = 705),
        Row(
            YOGURT, "Total 0% Greek Yogurt", "Fage", "yoghurt", 1, null, 2, 2025, 700, 0, 540, 103, 0, 0, 36, 36, 0, 36, 5,
            category = 302, transfat = 0, addsugar = 0, potassium = 141, calcium = 110, iron = 0, vita = 0, vitc = 0, vitd = 0,
            diet = 2, dietKnown = 3, allergens = 2, allergensFull = 1,
        ),
        Row(
            PANCAKE_MIX, "Buttermilk Pancake Mix", "Kitchen Basics", "flapjack", 1, null, 1, 2026, 400, 0, 3600, 90, 30, 10, 750, 120, 20, 1100, null,
            category = 203, diet = 2, dietKnown = 3, allergens = 1 or 2 or 4, allergensFull = 1,
        ),
        Row(
            EGG, "Egg, whole, raw", null, "eggs", 0, null, 4, 2018, 1000, 0, 1430, 126, 95, 31, 7, 4, 0, 142, 372,
            category = 304, transfat = 0, monofat = 37, polyfat = 19, potassium = 138, calcium = 56, iron = 18, vita = 160, vitc = 0, vitd = 20,
            diet = 2 or 8, dietKnown = 3, allergens = 4, allergensFull = 1,
        ),
        Row(
            FLOUR, "Flour, wheat, all-purpose", null, null, 0, null, 4, 2018, 950, 0, 3640, 103, 10, 2, 763, 3, 27, 2, 0,
            category = 201, potassium = 107, calcium = 15, iron = 12, diet = 1 or 2, dietKnown = 3, allergens = 1, allergensFull = 1,
        ),
        Row(BLACK_BEANS, "Black beans, canned, drained", null, null, 0, null, 4, 2018, 600, 0, 910, 60, 3, 1, 165, 3, 69, 384, 0, category = 104),
        Row(MILK, "Milk, whole", null, null, 0, null, 4, 2018, 990, 1, 610, 32, 33, 19, 48, 51, 0, 43, 10, category = 301),
        Row(ONION, "Onion, raw", null, "onions", 0, null, 3, 2019, 980, 0, 400, 11, 1, 0, 93, 42, 17, 4, 0, category = 102),
        Row(
            CARROTS_RAW, "Carrots, raw", null, ", carrot, carrots", 0, null, 4, 2019, 1000, 0, 410, 9, 2, 0, 96, 47, 28, 69, 0,
            category = 102, transfat = 0, potassium = 320, calcium = 33, iron = 3, vita = 835, vitc = 59, vitd = 0,
            diet = 1 or 2 or 8, dietKnown = 3, allergensFull = 1,
        ),
        Row(
            CARROT_CAKE, "Carrot cake", null, "carrot cake", 0, null, 5, 2020, 700, 0, 4150, 47, 230, 50, 500, 360, 12, 320, 50,
            category = 704, diet = 2, dietKnown = 3, allergens = 1 or 2 or 4, allergensFull = 1,
        ),
        Row(BABY_CARROTS, "Baby carrots", null, null, 0, null, 4, 2019, 650, 0, 350, 6, 1, 0, 82, 48, 29, 78, 0, category = 102),
        Row(COLA, "Cola (Coke or Pepsi)", null, ", coke, cola, soda", 0, null, 5, 2020, 1000, 1, 420, 0, 0, 0, 106, 99, 0, 4, 0, category = 802),
        Row(COKE_ZERO, "Coke Zero", "Coca-Cola", "coca cola", 1, null, 2, 2025, 674, 1, 0, 0, 0, 0, 0, 0, 0, 12, 0, category = 802),
        Row(COKE_FLOAT, "Coke Float, Large", "Baskin Robbins", "baskin robbins", 2, 2, 6, 2018, 597, 2, 6900, 80, 210, 140, 1180, 1100, 0, 190, 60, category = 705),
        Row(
            SALMON, "Salmon (cooked)", null, ", salmon", 0, null, 5, 2020, 1000, 0, 2060, 220, 124, 25, 0, 0, 0, 61, 63,
            category = 403, diet = 8, dietKnown = 3, allergens = 64, allergensFull = 1,
        ),
        Row(SALMONBERRIES, "Salmonberries, raw", null, null, 0, null, 4, 2019, 900, 0, 470, 10, 1, 0, 100, 0, 19, 0, 0, category = 101),
        Row(SALMON_SALAD, "Salmon salad", null, null, 0, null, 5, 2020, 754, 0, 1890, 150, 130, 20, 30, 10, 2, 400, 40, category = 605),
        Row(
            ICE_CREAM, "Ice cream, vanilla", null, ", ice cream, vanilla ice cream", 0, null, 4, 2019, 1000, 0, 2070, 35, 110, 68, 236, 212, 7, 80, 44,
            category = 705, diet = 2, dietKnown = 3, allergens = 2, allergensFull = 1,
        ),
        Row(ICED_TEA, "Iced tea, lemon", null, "iced tea", 0, null, 5, 2020, 990, 1, 360, 0, 0, 0, 90, 88, 0, 7, 0, category = 803),
        Row(ICE_CREAM_SANDWICH, "Ice cream sandwich", null, null, 0, null, 5, 2020, 600, 0, 2370, 44, 87, 47, 360, 190, 9, 150, 30, category = 705),
        Row(SPICY_DELUXE, "Spicy Deluxe Sandwich", "Chick-fil-A", "chickfila", 2, 3, 6, 2018, 703, 2, 5500, 330, 250, 70, 490, 80, 30, 1750, 80, category = 603),
        Row(NUGGETS, "Nuggets, 8 count", "Chick-fil-A", "chickfila", 2, 3, 6, 2018, 720, 2, 2500, 270, 110, 25, 110, 10, 0, 1020, 80, category = 402),
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
        "($CARROTS_RAW, 0, '1 medium', 610)",
        "($CARROT_CAKE, 0, '1 slice', 1100)",
        "($BABY_CARROTS, 0, '100 g', 1000)",
        "($COLA, 0, '1 can (12 fl oz)', 3680)",
        "($COKE_ZERO, 0, '1 can', 3550)",
        "($COKE_FLOAT, 0, '1 float', NULL)",
        "($SALMON, 0, '1 fillet', 1540)",
        "($SALMONBERRIES, 0, '1 cup', 1440)",
        "($SALMON_SALAD, 0, '1 cup', 2080)",
        "($ICE_CREAM, 0, '1/2 cup', 660)",
        "($ICED_TEA, 0, '1 bottle', 4730)",
        "($ICE_CREAM_SANDWICH, 0, '1 sandwich', 590)",
        "($SPICY_DELUXE, 0, '1 sandwich', NULL)",
        "($NUGGETS, 0, '8 nuggets', NULL)",
    )

    /** The categories the rows above use (the real file has all 42 of `categories.csv`). */
    private val CATEGORIES = listOf(
        Triple(101, "Fruits", "Fruits & vegetables"), Triple(102, "Vegetables", "Fruits & vegetables"),
        Triple(104, "Beans, lentils & legumes", "Fruits & vegetables"), Triple(201, "Rice, grains & pasta", "Grains"),
        Triple(203, "Breakfast cereals & oatmeal", "Grains"), Triple(301, "Milk & cream", "Dairy & eggs"),
        Triple(302, "Yogurt", "Dairy & eggs"), Triple(304, "Eggs", "Dairy & eggs"),
        Triple(402, "Chicken & turkey", "Meat & protein"), Triple(403, "Fish & seafood", "Meat & protein"),
        Triple(602, "Entrées & mixed dishes", "Meals"), Triple(603, "Sandwiches, burgers & wraps", "Meals"),
        Triple(605, "Salads", "Meals"), Triple(704, "Cookies, cakes & pastries", "Snacks & sweets"),
        Triple(705, "Ice cream & desserts", "Snacks & sweets"), Triple(802, "Soda & energy drinks", "Drinks"),
        Triple(803, "Coffee & tea", "Drinks"), Triple(999, "Other", "Other"),
    )

    /** Number of food rows [buildFoods] writes. */
    val FOOD_COUNT: Int get() = ROWS.size

    /**
     * Writes a spec-following foods.db (schema 2) to [file]. [applicationId] and [userVersion] can be
     * broken on purpose; [withRows] false leaves the food table empty; [dataVersion] goes into `meta`.
     */
    fun buildFoods(
        file: File,
        applicationId: Int = FoodDbFormat.FOODS_APPLICATION_ID,
        userVersion: Int = FoodDbFormat.SCHEMA_VERSION,
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
                  carb_x10 INTEGER, sugar_x10 INTEGER, fibre_x10 INTEGER, sodium_mg INTEGER, cholesterol_mg INTEGER,
                  category_id INTEGER NOT NULL DEFAULT 999, transfat_x10 INTEGER, monofat_x10 INTEGER, polyfat_x10 INTEGER,
                  addsugar_x10 INTEGER, potassium_mg INTEGER, calcium_mg INTEGER, iron_x10 INTEGER, vita_ug INTEGER,
                  vitc_x10 INTEGER, vitd_x10 INTEGER, diet INTEGER NOT NULL DEFAULT 0, diet_known INTEGER NOT NULL DEFAULT 0,
                  allergens INTEGER NOT NULL DEFAULT 0, allergens_full INTEGER NOT NULL DEFAULT 0)
                """.trimIndent(),
            )
            db.execSQL("CREATE TABLE category (id INTEGER PRIMARY KEY, name TEXT NOT NULL, grp TEXT NOT NULL)")
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
                "schema_version" to userVersion.toString(),
                "data_version" to dataVersion.toString(),
                "built_at" to "2026-11-22T21:30:00Z",
                "min_app_version" to "1",
                "sources" to "test fixture",
                "license" to "ODbL-1.0",
                "attribution" to "Test data written for Self Watch tests.",
                "row_counts" to "food=${ROWS.size};serving=${SERVING_SQL.size};barcode=2;chain=3",
            )
            for ((k, v) in meta) db.execSQL("INSERT INTO meta (key, value) VALUES (?, ?)", arrayOf(k, v))
            for ((id, name, grp) in CATEGORIES) db.execSQL("INSERT INTO category (id, name, grp) VALUES (?, ?, ?)", arrayOf<Any>(id, name, grp))
            if (withRows) {
                db.execSQL("INSERT INTO chain (id, name) VALUES (1, 'McDonald''s'), (2, 'Baskin Robbins'), (3, 'Chick-fil-A')")
                val cols = "id, name, brand, keywords, kind, chain_id, source, data_year, popularity, basis, kcal_x10, protein_x10, " +
                    "fat_x10, satfat_x10, carb_x10, sugar_x10, fibre_x10, sodium_mg, cholesterol_mg, category_id, transfat_x10, " +
                    "monofat_x10, polyfat_x10, addsugar_x10, potassium_mg, calcium_mg, iron_x10, vita_ug, vitc_x10, vitd_x10, " +
                    "diet, diet_known, allergens, allergens_full"
                val marks = cols.split(", ").joinToString(", ") { "?" }
                for (r in ROWS) db.execSQL("INSERT INTO food ($cols) VALUES ($marks)", r.values())
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
        val db = create(file, applicationId, FoodDbFormat.RECIPES_SCHEMA_VERSION)
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
