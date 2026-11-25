/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db.dao

import app.selfwatch.db.DbTest
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.health.SleepStageSample
import app.selfwatch.model.time.TimeRange
import app.selfwatch.model.watch.IngestBatch
import app.selfwatch.model.watch.SyncFileKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Devices page data actions (reset keeps workouts, delete removes all) and heart-rate sensor priority. */
class DeviceDataTest : DbTest() {
    private val t0 = 1_795_435_200L

    private suspend fun seed(id: Long) {
        db.healthWrite.ingest(
            IngestBatch(
                key = SyncFileKey(id, 1, 32, 4, t0, 100),
                activity = listOf(ActivityMinute(id, t0, 100, 50, 7000, 1, null)),
                metrics = listOf(MetricSample(id, MetricKind.STRESS, t0 + 60, 30)),
                sleepStages = listOf(SleepStageSample(id, t0 - 3600, SleepStage.DEEP)),
                sleepSessions = listOf(SleepSession(id, t0 - 7200, t0 - 600, 80)),
                workout = workout(t0 + 3600, id),
            ),
            t0,
        )
    }

    @Test
    fun resetKeepsWorkoutsAndSyncFiles() = test {
        val watch = db.devices.upsert(device())
        val other = db.devices.upsert(device("AA:BB:CC:DD:EE:02", "Second"))
        seed(watch)
        seed(other)
        val r = db.devices.deleteData(watch, keepWorkouts = true)
        assertEquals(4, r.rows)
        assertEquals(t0 - 7200 to t0 + 60, r.spanSec)
        assertTrue(r.workoutFiles.isEmpty())
        assertEquals(0L, count("activity_minute", "device_id = $watch"))
        assertEquals(0L, count("metric", "device_id = $watch"))
        assertEquals(0L, count("sleep_session", "device_id = $watch"))
        assertEquals(1L, count("workout", "device_id = $watch"))
        assertEquals(1L, count("sync_file", "device_id = $watch"))
        // The other device is untouched.
        assertEquals(1L, count("activity_minute", "device_id = $other"))
        assertEquals(1L, count("metric", "device_id = $other"))
    }

    @Test
    fun deleteAllRemovesWorkoutsToo() = test {
        val watch = db.devices.upsert(device())
        seed(watch)
        val r = db.devices.deleteData(watch, keepWorkouts = false)
        assertEquals(5, r.rows)
        assertEquals(listOf("/f/${t0 + 3600}.fit"), r.workoutFiles)
        assertEquals(t0 + 3600 + 1800, r.spanSec?.second)
        assertEquals(0L, count("workout", "device_id = $watch"))
        assertEquals(0L, count("sync_file", "device_id = $watch"))
        val empty = db.devices.deleteData(watch, keepWorkouts = false)
        assertEquals(0, empty.rows)
        assertNull(empty.spanSec)
    }

    @Test
    fun strapHeartRateWinsOverTheWatch() = test {
        val watch = db.devices.upsert(device())
        val strap = db.devices.upsert(device("AA:BB:CC:DD:EE:09", "Strap").copy(kind = DeviceKind.HEART_RATE_SENSOR))
        val hr = MetricKind.HEART_RATE
        // The watch logged before and during the strap session (stored first, as from a live sync).
        db.healthWrite.ingest(IngestBatch(null, metrics = listOf(MetricSample(watch, hr, t0, 60), MetricSample(watch, hr, t0 + 120, 61), MetricSample(watch, hr, t0 + 600, 62))), t0)
        // Strap session t0+100..t0+130: the watch sample at t0+120 is replaced; t0 and t0+600 stay.
        db.healthWrite.ingest(IngestBatch(null, metrics = (100L..130L step 10).map { MetricSample(strap, hr, t0 + it, 90) }), t0)
        val all = db.metrics.series(hr, TimeRange(t0 - 1, t0 + 1000))
        assertEquals(listOf(t0, t0 + 600), all.filter { it.deviceId == watch }.map { it.tSec })
        assertEquals(4, all.count { it.deviceId == strap })
        // A later watch sync that covers the strap session drops its samples there; others are kept.
        db.healthWrite.ingest(IngestBatch(null, metrics = listOf(MetricSample(watch, hr, t0 + 115, 70), MetricSample(watch, hr, t0 + 300, 71), MetricSample(watch, MetricKind.STRESS, t0 + 115, 20))), t0)
        val after = db.metrics.series(hr, TimeRange(t0 - 1, t0 + 1000)).filter { it.deviceId == watch }.map { it.tSec }
        assertEquals(listOf(t0, t0 + 300, t0 + 600), after)
        assertEquals(1, db.metrics.series(MetricKind.STRESS, TimeRange(t0, t0 + 1000)).size) // only heart rate has priority
    }

    @Test
    fun sensorPriorityRunsAndCover() {
        assertEquals(emptyList<Pair<Long, Long>>(), SensorPriority.runs(emptyList(), 30))
        assertEquals(listOf(10L to 70L, 200L to 200L), SensorPriority.runs(listOf(70, 10, 40, 200), 30))
        val s = longArrayOf(100, 200)
        assertTrue(SensorPriority.covered(s, 130, 30))
        assertTrue(SensorPriority.covered(s, 170, 30))
        assertFalse(SensorPriority.covered(s, 150, 30))
        assertFalse(SensorPriority.covered(s, 69, 30))
        assertFalse(SensorPriority.covered(longArrayOf(), 5, 30))
    }
}
