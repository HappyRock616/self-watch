/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import android.app.Application
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.TestDb
import app.selfwatch.model.fake.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Base for Robolectric tests against the real `selfwatch.db` (real SQLite): each test gets a fresh,
 * isolated database from core-db's [TestDb] fixture.
 */
@RunWith(RobolectricTestRunner::class)
abstract class DbTestBase {
    protected lateinit var app: Application
    protected lateinit var db: SelfWatchDb
    protected val clock: FakeClock = FakeClock()

    @Before
    fun openDb() {
        app = RuntimeEnvironment.getApplication()
        db = TestDb.fresh(app)
    }

    @After
    fun closeDb() {
        db.close()
    }

    /** Runs a suspending test body. */
    protected fun test(block: suspend () -> Unit): Unit = runBlocking { block() }
}
