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

    /** [resetShared], then the new shared database. */
    public fun freshShared(ctx: Context): SelfWatchDb {
        resetShared(ctx)
        return SelfWatchDb.get(ctx)
    }
}
