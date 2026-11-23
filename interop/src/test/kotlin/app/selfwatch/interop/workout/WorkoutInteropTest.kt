/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.workout

import app.selfwatch.interop.TestSupport
import app.selfwatch.interop.api.ExportRequest
import app.selfwatch.interop.api.MemorySink
import app.selfwatch.interop.api.TrackFormat
import app.selfwatch.interop.io.Zips
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.result.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/** GPX and TCX reading needs Android's XmlPullParser, so these run under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkoutInteropTest {
    @Test
    fun readsSampleGpx() {
        val r = GpxReader.read("park-loop.gpx", TestSupport.bytes("imports-synthetic/workouts/park-loop.gpx"))
        val w = (r as Outcome.Ok).value
        assertEquals(6, w.points.size)
        assertEquals(Sports.RUNNING, w.workout.sport)
        assertEquals(1_775_975_400L, w.workout.startSec)
        assertEquals(180, w.workout.durationSec)
        assertEquals(150, w.workout.maxHr)
        assertEquals(139, w.workout.avgHr)
        assertEquals(95, w.points[0].tempC10)
        assertEquals(80, w.points[0].cadence)
        assertEquals(1200, w.points[0].altCm)
        assertEquals(476_000_000, w.points[0].latE7)
        assertTrue(w.workout.hasTrack)
        assertNotNull(w.workout.routePreview)
        assertEquals(DataSource.IMPORT, w.workout.source)
        assertTrue("distance ${w.workout.distanceM}", w.workout.distanceM!! in 400..700)
        assertEquals(6 to 5, w.workout.ascentM to w.workout.descentM)
    }

    @Test
    fun readsSampleTcxWithLapTotals() {
        val r = TcxReader.read("indoor-ride.tcx", TestSupport.bytes("imports-synthetic/workouts/indoor-ride.tcx"))
        val list = (r as Outcome.Ok).value
        assertEquals(1, list.size)
        val w = list[0].workout
        assertEquals(Sports.CYCLING, w.sport)
        assertEquals(900, w.durationSec)
        assertEquals(7000, w.distanceM)
        assertEquals(170, w.kcal)
        assertEquals(126, w.avgHr)
        assertEquals(150, w.maxHr)
        assertFalse(w.hasTrack)
        assertEquals(4, list[0].points.size)
        assertEquals(8000, list[0].points[0].speedMms)
        assertEquals(110, list[0].points[0].hrBpm)
        assertEquals(80, list[0].points[0].cadence)
    }

    @Test
    fun gpxExportThenImportKeepsEveryPoint() {
        val points = TestSupport.track()
        val out = ByteArrayOutputStream()
        GpxWriter.write(out, TestSupport.workout(), points, "x")
        val back = (GpxReader.read("x.gpx", out.toByteArray()) as Outcome.Ok).value
        assertEquals(points.map { it.copy(speedMms = null) }, back.points)
        assertEquals(Sports.RUNNING, back.workout.sport)
    }

    @Test
    fun tcxExportThenImportKeepsPointsAndTotals() {
        val points = TestSupport.track()
        val w = TestSupport.workout(sport = 2)
        val out = ByteArrayOutputStream()
        TcxWriter.write(out, w, points)
        val back = (TcxReader.read("x.tcx", out.toByteArray()) as Outcome.Ok).value.single()
        assertEquals(points.map { it.copy(tempC10 = null, speedMms = null) }, back.points)
        assertEquals(w.durationSec, back.workout.durationSec)
        assertEquals(w.distanceM, back.workout.distanceM)
        assertEquals(w.kcal, back.workout.kcal)
        assertEquals(w.avgHr, back.workout.avgHr)
        assertEquals(w.maxHr, back.workout.maxHr)
        assertEquals(Sports.CYCLING, back.workout.sport)
    }

    @Test
    fun importersReadSingleFilesAndZips() {
        val gpx = TestSupport.bytes("imports-synthetic/workouts/park-loop.gpx")
        val importer = WorkoutFileImporter(TrackFormat.GPX)
        val single = TestSupport.plan(importer, "park-loop.gpx", gpx)
        assertEquals(mapOf("workout" to 1), single.counts)
        val zip = TestSupport.zipOf("a.gpx" to gpx, "b.gpx" to gpx, "notes.txt" to "x".toByteArray(), "bad.gpx" to "<gpx".toByteArray())
        val plan = TestSupport.plan(importer, "tracks.zip", zip)
        assertEquals(mapOf("workout" to 2), plan.counts)
        assertEquals(1, plan.warnings.size)
        val data = TestSupport.data(plan)
        assertArrayEquals(gpx, data.workouts[0].original)
        assertEquals(TrackFormat.GPX, data.workouts[0].format)
    }

    @Test
    fun fitImporterUsesTheFitDecoder() {
        val fit = TestSupport.bytes("fit/synthetic/activity_run_gps.fit")
        val importer = WorkoutFileImporter(TrackFormat.FIT)
        assertEquals(95, importer.sniff("x.bin", fit.copyOf(64)))
        val plan = TestSupport.plan(importer, "activity_run_gps.fit", fit)
        val w = TestSupport.data(plan).workouts.single()
        assertTrue(w.points.isNotEmpty())
        assertEquals(TrackFormat.FIT, w.format)
        assertTrue(w.workout.hasTrack)
    }

    @Test
    fun exporterWritesOneFileOrARangeZip() = runBlocking {
        val a = TestSupport.workout(id = 1)
        val b = TestSupport.workout(id = 2, start = 1_776_100_000)
        val indoor = TestSupport.workout(id = 3, start = 1_776_200_000, sport = 2)
        val fitBytes = TestSupport.bytes("fit/synthetic/activity_run_gps.fit")
        val source = TestSupport.FakeSource(
            workouts = listOf(a, b, indoor),
            tracks = mapOf(1L to TestSupport.track(a.startSec), 2L to TestSupport.track(b.startSec)),
            fits = mapOf(1L to fitBytes),
        )
        val req = ExportRequest(zone = TestSupport.ZONE, createdSec = 1_776_300_000)

        val gpx = WorkoutExporter(TrackFormat.GPX, source)
        assertEquals("selfwatch-gpx-2026-04-16.zip", gpx.suggestedName(req))
        val sink = MemorySink()
        val res = (gpx.export(req, sink) as Outcome.Ok).value
        assertEquals(3, res.counts["workout"])
        val names = Zips.readAll(sink.bytes()).map { it.name }
        assertEquals(listOf("2026-04-12_1520_running.gpx", "2026-04-13_1906_running.gpx", "2026-04-14_2253_cycling.tcx"), names)

        val one = MemorySink()
        val single = req.copy(workoutIds = listOf(1))
        assertEquals("application/gpx+xml", gpx.mimeType(single))
        gpx.export(single, one)
        assertTrue(String(one.bytes(), Charsets.UTF_8).contains("<trkpt"))

        val fitSink = MemorySink()
        val fitRes = (WorkoutExporter(TrackFormat.FIT, source).export(req, fitSink) as Outcome.Ok).value
        assertEquals(1, fitRes.counts["workout"])
        assertEquals(2, fitRes.warnings.size)
        assertArrayEquals(fitBytes, Zips.readAll(fitSink.bytes()).single().bytes)

        val fitOne = MemorySink()
        WorkoutExporter(TrackFormat.FIT, source).export(single, fitOne)
        assertArrayEquals(fitBytes, fitOne.bytes())
        assertTrue(WorkoutExporter(TrackFormat.FIT, source).export(req.copy(workoutIds = listOf(3)), MemorySink()) is Outcome.Fail)
    }
}
