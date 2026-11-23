/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.device

import app.selfwatch.fit.decode.WeightDecoder
import app.selfwatch.interop.DbTestSupport
import app.selfwatch.interop.TestSupport
import app.selfwatch.interop.api.ImportRegistry
import app.selfwatch.interop.api.InputSource
import app.selfwatch.interop.db.AppFiles
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.SleepStage
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
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceImportersTest {
    private lateinit var files: AppFiles
    private val cache: File get() = RuntimeEnvironment.getApplication().cacheDir

    @Before
    fun setUp() {
        files = DbTestSupport.fresh()
    }

    private fun head(b: ByteArray) = b.copyOf(minOf(b.size, ImportRegistry.HEAD_BYTES))

    @Test
    fun gadgetbridgeBackupPlans() {
        val zip = DeviceFixtures.gadgetbridgeZip(cache)
        val importer = GadgetbridgeImporter(cacheDir = cache)
        assertEquals(95, importer.sniff("Gadgetbridge_2026.zip", head(zip)))
        val plan = TestSupport.plan(importer, "Gadgetbridge_2026.zip", zip)
        assertEquals(
            mapOf("body" to 2, "workout" to 2, "activity" to 10, "metric" to 20, "sleep_stage" to 4, "sleep_session" to 1),
            plan.counts,
        )
        val data = TestSupport.data(plan)
        val dev = data.devices.single()
        assertEquals("AA:BB:CC:DD:EE:01", dev.sourceKey)
        assertEquals("Gadgetbridge: Forerunner 255", dev.name)
        assertEquals(10, dev.metrics.count { it.kind == MetricKind.HEART_RATE })
        assertEquals(2, dev.metrics.count { it.kind == MetricKind.STRESS })
        assertEquals(145, dev.metrics.first { it.kind == MetricKind.RESPIRATION }.value)
        assertEquals(listOf(SleepStage.LIGHT, SleepStage.DEEP, SleepStage.REM, SleepStage.AWAKE), dev.sleepStages.map { it.stage })
        val night = dev.sleepSessions.single()
        assertEquals(DeviceFixtures.T0 + 12 * 3600, night.startSec)
        assertEquals(DeviceFixtures.T0 + 12 * 3600 + 5460, night.endSec)
        assertEquals(80, night.score)
        assertEquals(30, dev.activity.first().activeKcalX10) // 3 kcal
        assertEquals(50, dev.activity.first().steps)
        assertEquals(7540, data.body.first().valueX100)
        assertTrue(data.body.all { it.kind == BodyKind.WEIGHT })
        val withFit = data.workouts.single { it.original.isNotEmpty() }
        assertTrue(withFit.points.size > 100)
        val timesOnly = data.workouts.single { it.original.isEmpty() }
        assertEquals(1800, timesOnly.workout.durationSec)
        assertTrue(plan.warnings.any { it.contains("no readable FIT") })
    }

    @Test
    fun bareGadgetbridgeDatabaseIsAccepted() {
        val db = DeviceFixtures.gadgetbridgeDb(cache)
        val bytes = db.readBytes()
        db.delete()
        val importer = GadgetbridgeImporter(cacheDir = cache)
        assertEquals(85, importer.sniff("Gadgetbridge", head(bytes)))
        val plan = TestSupport.plan(importer, "Gadgetbridge", bytes)
        assertEquals(10, plan.counts["activity"])
        assertEquals(2, plan.counts["workout"]) // FIT not available: both from their times
    }

    @Test
    fun gadgetbridgeCommitAndUndo() = runBlocking {
        val sink = DbTestSupport.sink(files)
        val importer = GadgetbridgeImporter(sink, cache)
        val before = DbTestSupport.checksums()
        val plan = (importer.plan(InputSource.ofBytes("gb.zip", DeviceFixtures.gadgetbridgeZip(cache))) as Outcome.Ok).value
        assertEquals(0, plan.duplicates)
        val r = (importer.commit(plan, plan.defaultChoices()) as Outcome.Ok).value
        assertEquals(plan.counts, r.counts)
        val after = DbTestSupport.counts()
        assertEquals(10L, after["activity_minute"])
        assertEquals(20L, after["metric"])
        // Importing the same backup again finds everything already there.
        val again = (importer.plan(InputSource.ofBytes("gb.zip", DeviceFixtures.gadgetbridgeZip(cache))) as Outcome.Ok).value
        assertEquals(plan.counts.values.sum(), again.duplicates)
        sink.undo(r.importId)
        val undone = DbTestSupport.checksums()
        for (t in before.keys - "import_batch") assertEquals(t, before[t], undone[t])
    }

    @Test
    fun garminExportPlans() {
        val zip = DeviceFixtures.garminExportZip()
        val importer = GarminExportImporter(zone = TestSupport.ZONE)
        assertEquals(90, importer.sniff("export.zip", head(zip)))
        val plan = TestSupport.plan(importer, "export.zip", zip)
        val data = TestSupport.data(plan)
        assertEquals(1, data.workouts.size)
        assertEquals(listOf(500, 250), data.water.map { it.ml })
        assertEquals(8 * 3600 + 1800, data.water[0].secOfDay)
        val weights = (WeightDecoder.decode(ByteArrayInputStream(TestSupport.bytes("fit/synthetic/weight.fit"))) as Outcome.Ok).value
        assertEquals(weights.distinctBy { it.tSec to it.kind }.size, data.body.size)
        val dev = data.devices.single()
        assertTrue(dev.activity.isNotEmpty())
        assertTrue(dev.metrics.any { it.kind == MetricKind.HRV_RMSSD })
        assertTrue(dev.metrics.any { it.kind == MetricKind.HEART_RATE })
        assertEquals(450, dev.sleepStages.size)
        assertEquals(1, dev.sleepSessions.size)
        assertTrue(plan.warnings.toString(), plan.warnings.any { it.contains("could not be read") })
        assertTrue(plan.warnings.any { it.contains("other files skipped") })
    }

    @Test
    fun garminExportCommitAndUndo() = runBlocking {
        val sink = DbTestSupport.sink(files)
        val importer = GarminExportImporter(sink, TestSupport.ZONE)
        val before = DbTestSupport.checksums()
        val plan = (importer.plan(InputSource.ofBytes("export.zip", DeviceFixtures.garminExportZip())) as Outcome.Ok).value
        val r = (importer.commit(plan, plan.defaultChoices()) as Outcome.Ok).value
        assertEquals(plan.counts, r.counts)
        assertEquals(2L, DbTestSupport.counts()["water"])
        sink.undo(r.importId)
        val undone = DbTestSupport.checksums()
        for (t in before.keys - "import_batch") assertEquals(t, before[t], undone[t])
    }

    @Test
    fun unknownLayoutsFailCleanly() {
        val notGarmin = TestSupport.zipOf("readme.txt" to "hello".toByteArray())
        assertTrue(runBlocking { GarminExportImporter().plan(InputSource.ofBytes("x.zip", notGarmin)) } is Outcome.Fail)
        val onlyJunk = TestSupport.zipOf("DI_CONNECT/DI-Connect-User/profile.json" to "{}".toByteArray())
        assertTrue(runBlocking { GarminExportImporter().plan(InputSource.ofBytes("x.zip", onlyJunk)) } is Outcome.Fail)
        val noDb = TestSupport.zipOf("preferences/global.json" to "{}".toByteArray())
        assertTrue(runBlocking { GadgetbridgeImporter(cacheDir = cache).plan(InputSource.ofBytes("gb.zip", noDb)) } is Outcome.Fail)
        val badDb = TestSupport.zipOf("database/Gadgetbridge" to ByteArray(4096) { it.toByte() })
        assertTrue(runBlocking { GadgetbridgeImporter(cacheDir = cache).plan(InputSource.ofBytes("gb.zip", badDb)) } is Outcome.Fail)
        assertTrue(GarminExportImporter().hydration("{\"foo\":[1,2]}").isEmpty())
    }
}
