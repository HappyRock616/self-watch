/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.source

import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/** FOODDB_SPEC §13 release checks and the app's spot checks, shared by the fixture and Tony's sample tests. */
object FoodDbChecks {

    /** Every §13 check that must hold for any foods.db (the FTS4 integrity check needs a writable connection). */
    fun structural(file: File) {
        val db = FoodDbFormat.openForChecks(file)
        try {
            assertEquals("ok", text(db, "PRAGMA integrity_check"))
            assertEquals(FoodDbFormat.FOODS_APPLICATION_ID.toLong(), long(db, "PRAGMA application_id"))
            assertEquals(FoodDbFormat.SCHEMA_VERSION.toLong(), long(db, "PRAGMA user_version"))
            assertEquals(FoodDbFormat.SCHEMA_VERSION.toString(), text(db, "SELECT value FROM meta WHERE key='schema_version'"))
            val zero = listOf(
                "SELECT COUNT(*) FROM food WHERE name IS NULL OR TRIM(name)='' OR LENGTH(name)>80",
                "SELECT COUNT(*) FROM food WHERE brand='' OR LENGTH(brand)>60",
                "SELECT COUNT(*) FROM food WHERE kind NOT IN (0,1,2) OR basis NOT IN (0,1,2) OR source NOT BETWEEN 1 AND 6",
                "SELECT COUNT(*) FROM food WHERE kind=2 AND chain_id IS NULL",
                "SELECT COUNT(*) FROM food WHERE kcal_x10 IS NULL",
                "SELECT COUNT(*) FROM food WHERE basis<2 AND (kcal_x10 NOT BETWEEN 0 AND 9000 " +
                    "OR COALESCE(protein_x10,0)+COALESCE(fat_x10,0)+COALESCE(carb_x10,0) > 1050)",
                "SELECT COUNT(*) FROM serving s LEFT JOIN food f ON f.id=s.food_id WHERE f.id IS NULL",
                "SELECT COUNT(*) FROM barcode b LEFT JOIN food f ON f.id=b.food_id WHERE f.id IS NULL",
                "SELECT COUNT(*) FROM food f WHERE NOT EXISTS (SELECT 1 FROM serving s WHERE s.food_id=f.id AND s.seq=0)",
                "SELECT COUNT(*) FROM barcode WHERE gtin < 10000000 OR gtin > 99999999999999",
                // Schema 2: every category exists, bits stay in range, a "decided" bit never claims an unknown diet.
                "SELECT COUNT(*) FROM food f LEFT JOIN category c ON c.id = f.category_id WHERE c.id IS NULL",
                "SELECT COUNT(*) FROM food WHERE diet < 0 OR diet > 15 OR diet_known < 0 OR diet_known > 3 OR allergens < 0 OR allergens > 511",
                "SELECT COUNT(*) FROM food WHERE allergens_full NOT IN (0, 1)",
                "SELECT COUNT(*) FROM food WHERE (diet & 3) & ~diet_known != 0",
            )
            for (q in zero) assertEquals(q, 0L, long(db, q))
            // Only features of Android 8.0's SQLite: FTS4, no triggers, no views.
            assertEquals(0L, long(db, "SELECT COUNT(*) FROM sqlite_master WHERE type IN ('trigger', 'view')"))
            assertTrue(text(db, "SELECT sql FROM sqlite_master WHERE name='food_fts'")!!.contains("fts4", ignoreCase = true))
        } finally {
            db.close()
        }
    }

    /**
     * The app-side spot checks through [FoodsDbSource]: "mcdonalds big" finds Big Mac, "creme brulee"
     * finds Crème brûlée, and the barcode [gtin] (or, when null, the first barcode in the file) finds
     * its food.
     */
    fun spotChecks(file: File, gtin: Long?) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val handle = FoodsDbHandle(file, scope)
        val src = FoodsDbSource(handle)
        try {
            runBlocking {
                val big = src.search("mcdonalds big", 50)
                assertTrue("Big Mac in ${big.map { it.name }}", big.any { it.name == "Big Mac" })
                val creme = src.search("creme brulee", 50)
                assertTrue("Crème brûlée in ${creme.map { it.name }}", creme.any { it.name.equals("Crème brûlée", ignoreCase = true) })
                val code = gtin ?: handle.use { d ->
                    d.rawQuery("SELECT gtin FROM barcode ORDER BY gtin LIMIT 1", null).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                }
                assertNotNull("a barcode row", code)
                val expectedId = handle.use { d ->
                    d.rawQuery("SELECT food_id FROM barcode WHERE gtin = ?", arrayOf(code.toString())).use { c -> c.moveToFirst(); c.getLong(0) }
                }
                val food = src.byBarcode(code!!)
                assertEquals(expectedId, food?.ref?.id)
                assertNotNull(food!!.servings.firstOrNull { it.seq == 0 })
                handle.close()
            }
        } finally {
            scope.cancel()
        }
    }

    private fun long(db: SQLiteDatabase, sql: String): Long = db.rawQuery(sql, null).use { c -> c.moveToFirst(); c.getLong(0) }

    private fun text(db: SQLiteDatabase, sql: String): String? = db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
}
