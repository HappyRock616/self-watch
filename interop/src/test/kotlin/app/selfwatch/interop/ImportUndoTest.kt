/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop

import app.selfwatch.interop.api.ImportRegistry
import app.selfwatch.interop.api.InputSource
import app.selfwatch.interop.db.AppFiles
import app.selfwatch.interop.db.DbImportSink
import app.selfwatch.model.result.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * E15: every importer through the real database sink. Each sample is imported and then undone; the
 * database (every table but `import_batch`, which keeps the undone batch) and the files folder must
 * be exactly as before. Corrupted samples that still parse go through the same cycle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportUndoTest {
    private lateinit var files: AppFiles
    private lateinit var sink: DbImportSink

    @Before
    fun setUp() {
        files = DbTestSupport.fresh()
        sink = DbTestSupport.sink(files)
    }

    private fun registry() = ImportRegistry(
        Interop.importers(sink, TestSupport.ZONE, RuntimeEnvironment.getApplication().cacheDir).filter { it.id != "selfwatch-backup" },
    )

    /** Imports [bytes] with the best importer and undoes it; returns the importer id, or null if nothing was imported. */
    private fun cycle(name: String, bytes: ByteArray): String? = runBlocking {
        val importer = registry().detect(name, bytes.copyOf(minOf(bytes.size, ImportRegistry.HEAD_BYTES))).firstOrNull()?.first ?: return@runBlocking null
        val plan = (importer.plan(InputSource.ofBytes(name, bytes)) as? Outcome.Ok)?.value ?: return@runBlocking null
        val before = DbTestSupport.checksums()
        val r = importer.commit(plan, plan.defaultChoices())
        assertTrue("$name via ${importer.id}: $r", r is Outcome.Ok)
        val result = (r as Outcome.Ok).value
        sink.undo(result.importId)
        val after = DbTestSupport.checksums()
        for (t in before.keys - "import_batch") assertEquals("$name via ${importer.id}: table $t", before[t], after[t])
        assertEquals("$name: files left", 0, files.filesDir.walkTopDown().count { it.isFile })
        if (result.counts.isEmpty()) null else importer.id
    }

    @Test
    fun undoRemovesEverythingForEveryImporter() {
        val covered = HashSet<String>()
        for ((name, bytes) in TestSupport.samples()) cycle(name, bytes)?.let { covered.add(it) }
        val expected = setOf(
            "cronometer", "loseit", "mfp", "fit", "gpx", "tcx", "foodlist-csv", "recipe-jsonld", "paprika", "mealie",
            "tandoor", "mealmaster", "cooklang", "gadgetbridge", "garmin-export",
        )
        assertEquals(expected, covered)
    }

    @Test
    fun corruptedSamplesThatStillParseUndoCleanly() {
        val rnd = Random(15)
        for ((name, bytes) in TestSupport.samples()) {
            if (bytes.isEmpty()) continue
            repeat(2) {
                val flipped = bytes.copyOf()
                repeat(1 + bytes.size / 500) { flipped[rnd.nextInt(flipped.size)] = rnd.nextInt(256).toByte() }
                cycle(name, flipped)
            }
            cycle(name, bytes.copyOf(bytes.size / 2))
        }
    }
}
