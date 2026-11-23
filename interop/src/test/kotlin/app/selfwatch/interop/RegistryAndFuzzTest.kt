/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop

import app.selfwatch.interop.api.ImportRegistry
import app.selfwatch.interop.api.Importer
import app.selfwatch.interop.api.InputSource
import app.selfwatch.model.result.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/** Detection on every sample, and fuzzing of every importer (GPX/TCX need Robolectric's XmlPullParser). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RegistryAndFuzzTest {
    private val importers: List<Importer> = Interop.importers(zone = TestSupport.ZONE)
    private val registry = ImportRegistry(importers)

    private fun head(b: ByteArray): ByteArray = b.copyOf(minOf(b.size, ImportRegistry.HEAD_BYTES))

    private fun samples(): List<Pair<String, ByteArray>> = TestSupport.samples()

    @Test
    fun detectionPicksTheRightImporter() {
        val expected = mapOf(
            "cronometer-export.zip" to "cronometer", "loseit-export.zip" to "loseit", "mfp-export.zip" to "mfp",
            "servings.csv" to "cronometer", "food-logs.csv" to "loseit", "Nutrition-Summary-2026-02-01-to-2026-02-02.csv" to "mfp",
            "park-loop.gpx" to "gpx", "indoor-ride.tcx" to "tcx", "activity_run_gps.fit" to "fit", "foodlist.csv" to "foodlist-csv",
            "lemon-lentil-soup.json" to "recipe-jsonld", "recipe-page.html" to "recipe-jsonld",
            "paprika-export.paprikarecipes" to "paprika", "mealie-backup.zip" to "mealie", "tandoor-export.zip" to "tandoor",
            "two-recipes.mmf" to "mealmaster", "tomato-toast.cook" to "cooklang",
            "Gadgetbridge_backup.zip" to "gadgetbridge", "garmin-export.zip" to "garmin-export",
        )
        val all = samples().toMap()
        for ((name, id) in expected) {
            val found = registry.detect(name, head(all.getValue(name)))
            assertTrue("nothing detected for $name", found.isNotEmpty())
            assertEquals("for $name: $found", id, found.first().first.id)
        }
    }

    @Test
    fun everySampleImportsWithItsDetectedImporter() = runBlocking {
        for ((name, bytes) in samples()) {
            if (name.endsWith(".fit") && !name.startsWith("activity")) continue // monitoring, sleep, HRV and weight files are not workouts
            if (name == "dailySummary.csv") continue // on its own it has nothing to import (totals are recomputed)
            val importer = registry.detect(name, head(bytes)).firstOrNull()?.first ?: continue
            val r = importer.plan(InputSource.ofBytes(name, bytes))
            assertTrue("$name via ${importer.id}: $r", r is Outcome.Ok)
        }
    }

    private fun mustNotThrow(importer: Importer, name: String, bytes: ByteArray): Outcome<*> = runBlocking {
        try {
            importer.plan(InputSource.ofBytes(name, bytes))
        } catch (e: Exception) {
            fail("${importer.id} threw ${e.javaClass.simpleName} on $name (${bytes.size} bytes): ${e.message}")
            throw e
        }
    }

    @Test
    fun randomBytesFailCleanly() {
        val rnd = Random(42)
        for (importer in importers) {
            for (n in listOf(0, 1, 7, 64, 1000, 20_000)) {
                val r = mustNotThrow(importer, "random.${importer.id}", rnd.nextBytes(n))
                assertTrue("${importer.id} accepted $n random bytes", r is Outcome.Fail)
            }
            for (prefix in listOf("PK\u0003\u0004", "<?xml version=\"1.0\"?><gpx><trk><trkseg><trkpt lat=\"1\"", "Date,Name,Calories\n", "{\"@type\":\"Recipe\"")) {
                mustNotThrow(importer, "prefixed.csv", prefix.toByteArray() + rnd.nextBytes(500))
            }
        }
    }

    @Test
    fun truncatedAndCorruptedSamplesNeverCrash() {
        val rnd = Random(7)
        val samples = samples()
        for (importer in importers) {
            for ((name, bytes) in samples) {
                if (bytes.isEmpty()) continue
                for (cut in listOf(1, bytes.size / 3, bytes.size / 2, bytes.size - 1)) mustNotThrow(importer, name, bytes.copyOf(cut))
                repeat(3) {
                    val flipped = bytes.copyOf()
                    repeat(1 + bytes.size / 200) { flipped[rnd.nextInt(flipped.size)] = rnd.nextInt(256).toByte() }
                    mustNotThrow(importer, name, flipped)
                }
            }
        }
    }

    @Test
    fun hostileXmlIsRefused() {
        val bomb = """<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;">]><gpx>&b;</gpx>"""
        for (importer in importers) assertTrue(importer.id, mustNotThrow(importer, "bomb.gpx", bomb.toByteArray()) is Outcome.Fail)
        val deep = "[".repeat(100_000)
        for (importer in importers) mustNotThrow(importer, "deep.json", deep.toByteArray())
    }
}
