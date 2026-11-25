/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.me

import android.os.Looper
import app.selfwatch.SelfWatchApp
import app.selfwatch.db.TestDb
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Shared helpers for the Me, Data and onboarding tests (Robolectric, real database). */
object MeTestSupport {
    private const val TIMEOUT_MS = 10_000L
    private const val POLL_MS = 20L

    /** The app, with a fresh database. */
    fun freshApp(): SelfWatchApp {
        val app = RuntimeEnvironment.getApplication() as SelfWatchApp
        // The app's onCreate already opened the shared database: empty it in place (see TestDb.wipeShared).
        TestDb.wipeShared(app)
        return app
    }

    /**
     * For tearDown: stops the app's background observers, then closes and forgets the shared database
     * (core-db's [TestDb] fixture), so the next test starts empty and unlocked.
     */
    fun resetDb() {
        (RuntimeEnvironment.getApplication() as? SelfWatchApp)?.stopBackgroundForTests()
        TestDb.resetShared(RuntimeEnvironment.getApplication())
    }

    /** Runs the main looper until [cond] holds (database work runs on other threads). */
    fun waitFor(what: String, dump: () -> String = { "" }, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(POLL_MS))
            if (cond()) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out waiting for $what: ${dump()}")
    }

    /** The repository's `testdata` folder (unit tests run in the module folder). */
    val testdata: File = listOf(File("../testdata"), File("testdata")).first { it.isDirectory }

    /** A zip of every file in the folder [path] under `testdata`. */
    fun zipOfDir(path: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            File(testdata, path).listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach { f ->
                z.putNextEntry(ZipEntry(f.name))
                z.write(f.readBytes())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
