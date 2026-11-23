/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaTest : DbTest() {

    private fun pragma(name: String): String = raw.rawQuery("PRAGMA $name", null).use { it.moveToFirst(); it.getString(0) }

    @Test
    fun createsDatabaseWithSettings() {
        assertEquals("1", pragma("user_version"))
        assertEquals("wal", pragma("journal_mode").lowercase())
        assertEquals("1", pragma("foreign_keys"))
        // Writes run on the primary connection (inside a transaction), which onConfigure set up.
        raw.beginTransactionNonExclusive()
        try {
            assertEquals("5000", pragma("busy_timeout"))
            assertEquals("1", pragma("synchronous")) // NORMAL
        } finally {
            raw.endTransaction()
        }
    }

    @Test
    fun everyTableEnumExistsAndViceVersa() {
        val tables = raw.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' " +
                "AND name NOT LIKE 'my_food_fts_%'",
            null,
        ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        assertEquals(Table.entries.map { it.sql }.toSet(), tables)
    }

    @Test
    fun seedsMealSlots() {
        val slots = raw.rawQuery("SELECT id, name, ord FROM meal_slot ORDER BY ord", null).use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getString(1), c.getInt(2))) }
        }
        assertEquals(
            listOf(Triple(1L, "Breakfast", 1), Triple(2L, "Lunch", 2), Triple(3L, "Dinner", 3), Triple(4L, "Snacks", 4)),
            slots,
        )
        assertEquals(0L, count("setting")) // defaults live in code
    }

    @Test
    fun fts4WithUnicode61Works() {
        raw.execSQL("INSERT INTO my_food_fts (docid, name, brand) VALUES (5, 'Crème brûlée', NULL)")
        val hits = raw.rawQuery("SELECT docid FROM my_food_fts WHERE my_food_fts MATCH 'creme* brul*'", null).use { it.count }
        assertEquals(1, hits)
    }

    @Test
    fun sqlScriptSplitter() {
        val s = """
            -- comment; with semicolon
            CREATE TABLE a (x TEXT DEFAULT 'a;b'); /* block ; comment */
            INSERT INTO a VALUES ('it''s; fine');
            CREATE TRIGGER t AFTER INSERT ON a BEGIN INSERT INTO a VALUES ('x'); DELETE FROM a WHERE x = 'y'; END;
            SELECT "col;name" FROM a
        """.trimIndent()
        val parts = SqlScript.split(s)
        assertEquals(4, parts.size)
        assertEquals("CREATE TABLE a (x TEXT DEFAULT 'a;b')", parts[0])
        assertEquals("INSERT INTO a VALUES ('it''s; fine')", parts[1])
        assertTrue(parts[2].startsWith("CREATE TRIGGER") && parts[2].endsWith("END"))
        assertEquals("SELECT \"col;name\" FROM a", parts[3])
    }
}
