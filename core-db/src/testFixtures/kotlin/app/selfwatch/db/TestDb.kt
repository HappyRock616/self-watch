/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db

import android.content.Context
import java.util.UUID

/**
 * Databases for tests in other modules (`testImplementation(testFixtures(project(":core-db")))`),
 * without reflection on core-db's singletons. Robolectric keeps statics between tests, so use one of
 * these in every test's setup and close what you opened in tear-down.
 */
public object TestDb {
    /**
     * A new, empty database file [name] (deleted first if it exists) with its own [DbChanges],
     * separate from the shared instances; the shared [DbChanges] is reset too. Close it after the test.
     */
    public fun fresh(ctx: Context, name: String = "test-" + UUID.randomUUID() + ".db"): SelfWatchDb {
        DbChanges.resetSharedForTest()
        ctx.deleteDatabase(name)
        return SelfWatchDb.createForTest(ctx, name, DbChanges.createForTest(ctx))
    }

    /**
     * Closes and forgets the shared [SelfWatchDb.get] and [DbChanges.get] instances and deletes the
     * shared database file, so code under test that calls `get` starts from an empty database.
     */
    public fun resetShared(ctx: Context) {
        SelfWatchDb.resetSharedForTest()
        DbChanges.resetSharedForTest()
        ctx.deleteDatabase(SelfWatchDb.NAME)
    }

    /**
     * Empties the shared database in place, keeping the one open instance. Use this in setup when the
     * app under test already opened the shared database (its `onCreate` observers hold that instance):
     * replacing the instance there would leave two connections to one file, and their writes then
     * collide ("database is locked"). The seed rows of a new database are put back.
     */
    public fun wipeShared(ctx: Context): SelfWatchDb {
        val db = SelfWatchDb.get(ctx)
        val w = db.writableDatabase
        val tables = w.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' " +
                "AND sql NOT LIKE 'CREATE VIRTUAL%' AND name NOT LIKE '%_content' AND name NOT LIKE '%_segments' " +
                "AND name NOT LIKE '%_segdir' AND name NOT LIKE '%_docsize' AND name NOT LIKE '%_stat'",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        val virtual = w.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND sql LIKE 'CREATE VIRTUAL%'", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        w.beginTransaction()
        try {
            w.execSQL("PRAGMA defer_foreign_keys = ON")
            for (t in virtual + tables) w.execSQL("DELETE FROM \"$t\"")
            for (line in ctx.assets.open("db/V1.sql").bufferedReader().readLines()) {
                if (line.startsWith("INSERT INTO meal_slot")) w.execSQL(line.trim().removeSuffix(";"))
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        return db
    }

    /** [resetShared], then the new shared database. */
    public fun freshShared(ctx: Context): SelfWatchDb {
        resetShared(ctx)
        return SelfWatchDb.get(ctx)
    }
}
