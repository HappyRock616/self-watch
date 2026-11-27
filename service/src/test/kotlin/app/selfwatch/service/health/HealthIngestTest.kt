/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.health

import app.selfwatch.db.Table
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.health.Workout
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.time.Day
import app.selfwatch.model.watch.IngestBatch
import app.selfwatch.model.watch.SyncFileKey
import app.selfwatch.service.ServiceDbTest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HealthIngestTest : ServiceDbTest() {
    @get:Rule
    val tmp = TemporaryFolder()

    private val day = Day(20261122)

    private fun key(deviceId: Long, index: Int = 1) =
        SyncFileKey(deviceId, fileIndex = index, fileType = 128, fileSubtype = 32, tSec = at(day, 6), size = 4096)

    private fun minutes(deviceId: Long, n: Int): List<ActivityMinute> =
        (0 until n).map { ActivityMinute(deviceId, at(day, 9) + it * 60L, 10, 15, 800, 2, null) }

    @Test
    fun ingestStoresEverythingAndMarksTheFile() = runBlocking {
        val dev = addDevice()
        val ingest = HealthIngest(db, clock, SummaryBuilder(db, clock))
        val k = key(dev)
        assertFalse(ingest.alreadyHave(k))

        val r = ingest.ingest(
            IngestBatch(
                key = k,
                activity = minutes(dev, 30),
                // Zone 2 heart rate (115 bpm; max 180 without a birth year) during all 30 minutes: 30 active minutes.
                metrics = listOf(MetricSample(dev, MetricKind.RESTING_HR, at(day, 8), 52)) +
                    (0 until 30).map { MetricSample(dev, MetricKind.HEART_RATE, at(day, 9) + it * 60L, 115) },
                sleepSessions = listOf(SleepSession(dev, at(day.plusDays(-1), 23), at(day, 7), 80)),
            ),
        )

        assertEquals(Outcome.Ok(Unit), r)
        assertTrue(ingest.alreadyHave(k))
        assertEquals(30L, count("activity_minute"))
        assertEquals(1L, count("sleep_session"))
        val s = db.summaries.day(day)
        assertNotNull("summary of the affected day is rebuilt", s)
        assertEquals(300, s!!.steps)
        assertEquals(30, s.activeMin)
        assertEquals(52, s.restingHr)
        assertEquals(480, s.sleepMin)
    }

    @Test
    fun failedIngestStoresNothingAndLeavesTheFileForRetry() = runBlocking {
        val dev = addDevice()
        val ingest = HealthIngest(db, clock, SummaryBuilder(db, clock))
        // sync_file.device_id must reference a device: the key's unknown device makes the last
        // statement of the transaction fail, after the activity rows were written.
        val badKey = key(deviceId = dev + 99)

        val r = ingest.ingest(IngestBatch(key = badKey, activity = minutes(dev, 10)))

        assertTrue(r is Outcome.Fail)
        assertEquals("no activity row survives the rollback", 0L, count("activity_minute"))
        assertEquals(0L, count("sync_file"))
        assertFalse(ingest.alreadyHave(badKey))
        assertEquals(0L, count("day_summary"))
    }

    @Test
    fun aStoredWorkoutIsReportedWithItsRowId() = runBlocking {
        val dev = addDevice()
        val added = ArrayList<Long>()
        val ingest = HealthIngest(db, clock, null) { added += it }
        ingest.ingest(IngestBatch(key = key(dev), activity = minutes(dev, 1)))
        assertTrue("no workout, no report", added.isEmpty())
        val start = at(day, 7)
        val w = Workout(
            id = 0, deviceId = dev, sport = 1, subSport = null, startSec = start, endSec = start + 1800, durationSec = 1800,
            distanceM = 5000, kcal = 300, avgHr = 140, maxHr = 170, ascentM = null, descentM = null, hasTrack = false,
            fitPath = null, trackPath = null, routePreview = null, source = DataSource.WATCH,
        )
        ingest.ingest(IngestBatch(key = key(dev, index = 2), workout = w))
        assertEquals(1, added.size)
        assertEquals(added.single(), db.workouts.list(1).single().id)
    }

    @Test
    fun changesAreAnnouncedAfterTheCommit() = runBlocking {
        val dev = addDevice()
        val ingest = HealthIngest(db, clock, null)
        val seen = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { db.changes.changes.first { Table.ACTIVITY in it } }
        }
        ingest.ingest(IngestBatch(key = key(dev), activity = minutes(dev, 1)))
        val tables = seen.await()
        assertTrue(Table.SYNC_FILE in tables)
    }

    @Test
    fun affectedDaysCoverNightSamplesAndBothEndsOfSessions() {
        val batch = IngestBatch(
            key = null,
            activity = listOf(ActivityMinute(1, at(day, 10), 1, 0, 0, 0, null)),
            metrics = listOf(MetricSample(1, MetricKind.HRV_RMSSD, at(day, 23), 40)),
            sleepSessions = listOf(SleepSession(1, at(day.plusDays(-2), 22), at(day.plusDays(-1), 6), null)),
        )
        val days = HealthIngest.affectedDays(batch, zone)
        assertEquals(setOf(day, day.plusDays(1), day.plusDays(-2), day.plusDays(-1)), days)
    }

    @Test
    fun fileStoreWritesWorkoutsUnderTheirDevice() = runBlocking {
        val store = AppFileStore(tmp.root)
        val path = store.saveWorkoutFit(7, 1_795_000_000L, byteArrayOf(1, 2, 3))
        assertEquals("workouts/7/1795000000.fit", path)
        assertEquals(listOf<Byte>(1, 2, 3), store.resolve(path).readBytes().toList())
        // Overwrites in place, leaving no temporary file behind.
        store.saveWorkoutFit(7, 1_795_000_000L, byteArrayOf(9))
        assertEquals(listOf<Byte>(9), File(tmp.root, path).readBytes().toList())
        assertEquals(listOf("1795000000.fit"), File(tmp.root, "workouts/7").list()!!.toList())
    }
}
