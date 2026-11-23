/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop

import android.database.Cursor
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.interop.backup.BackupWriter
import app.selfwatch.interop.db.AppFiles
import app.selfwatch.interop.db.DbImportSink
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.security.MessageDigest

/**
 * The real `selfwatch.db` under Robolectric. `SelfWatchDb.get` is a per-process singleton, so every
 * test starts with [fresh], which empties all tables (meal slots back to the four seeds) and gives a
 * new files folder.
 */
object DbTestSupport {
    /** The database (the process singleton). */
    val db: SelfWatchDb get() = SelfWatchDb.get(RuntimeEnvironment.getApplication())

    /** Empties every table and returns files rooted in a new temp folder. */
    fun fresh(): AppFiles {
        val d = db.writableDatabase
        d.beginTransaction()
        try {
            for (t in BackupWriter.tableCounts(d).keys) d.execSQL("DELETE FROM \"$t\"")
            d.execSQL("INSERT INTO meal_slot (id, name, ord) VALUES (1, 'Breakfast', 1), (2, 'Lunch', 2), (3, 'Dinner', 3), (4, 'Snacks', 4)")
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        val dir = File(RuntimeEnvironment.getApplication().filesDir, "t-${System.nanoTime()}")
        dir.mkdirs()
        return AppFiles(dir)
    }

    /** A sink on the test database in [TestSupport.ZONE]. */
    fun sink(files: AppFiles): DbImportSink = DbImportSink(db, files, TestSupport.ZONE)

    /** Rows per table. */
    fun counts(): Map<String, Long> = BackupWriter.tableCounts(db.readableDatabase)

    /** A SHA-256 of every row of every table (rows in a fixed order), by table name. */
    fun checksums(): Map<String, String> {
        val d = db.readableDatabase
        val out = LinkedHashMap<String, String>()
        for (t in BackupWriter.tableCounts(d).keys) {
            val sql = if (t == "my_food_fts") {
                "SELECT docid, name, brand FROM my_food_fts ORDER BY docid"
            } else {
                val cols = ArrayList<String>()
                d.rawQuery("PRAGMA table_info(\"$t\")", null).use { c -> while (c.moveToNext()) cols.add(c.getString(c.getColumnIndexOrThrow("name"))) }
                "SELECT * FROM \"$t\" ORDER BY " + cols.indices.joinToString(", ") { "${it + 1}" }
            }
            val md = MessageDigest.getInstance("SHA-256")
            d.rawQuery(sql, null).use { c ->
                while (c.moveToNext()) {
                    for (i in 0 until c.columnCount) md.update((cell(c, i) + "\u0001").toByteArray())
                    md.update(byteArrayOf(2))
                }
            }
            out[t] = md.digest().joinToString("") { "%02x".format(it) }
        }
        return out
    }

    private fun cell(c: Cursor, i: Int): String = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> "null"
        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).joinToString("") { "%02x".format(it) }
        else -> c.getString(i)
    }
}
