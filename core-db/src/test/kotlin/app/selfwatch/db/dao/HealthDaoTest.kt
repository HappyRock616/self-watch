/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db.dao

import android.database.sqlite.SQLiteConstraintException
import app.selfwatch.db.DbTest
import app.selfwatch.db.SyncFileState
import app.selfwatch.model.device.Feature
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.health.SleepStageSample
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.TimeRange
import app.selfwatch.model.watch.IngestBatch
import app.selfwatch.model.watch.SyncFileKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class HealthDaoTest : DbTest() {
    private val utc: ZoneId = ZoneOffset.UTC
    private val london: ZoneId = ZoneId.of("Europe/London")
    private val d23 = Day(20261123)
    private val t23 = d23.startSec(utc)

    // ---- device ----

    @Test
    fun deviceUpsertAndQueries() = test {
        val id = db.devices.upsert(device(), nowSec = 100)
        val again = db.devices.upsert(device().copy(firmware = "13.00", features = setOf(Feature.CALLS)), nowSec = 200)
        assertEquals(id, again)
        val d = db.devices.get(id)!!
        assertEquals("13.00", d.firmware)
        assertEquals(setOf(Feature.CALLS), d.features)
        assertEquals(4426, d.productId)
        assertEquals(3_400_000_001L, d.serial)
        assertEquals(d, db.devices.byAddress("AA:BB:CC:DD:EE:01"))
        assertNull(db.devices.byAddress("nope"))
        val id2 = db.devices.upsert(device("AA:BB:CC:DD:EE:02", "Forerunner"), nowSec = 300)
        db.devices.setPriority(id2, 5)
        assertEquals(listOf(id2, id), db.devices.active().map { it.id })
        db.devices.markRemoved(id2)
        assertEquals(listOf(id), db.devices.active().map { it.id })
        assertEquals(2, db.devices.all().size)
        db.devices.setLastSync(id, 999)
        assertEquals(999L, db.devices.lastSync(id))
        db.devices.setAssociationId(id, 17)
        assertEquals(17L, db.devices.associationId(id))
        db.devices.setAssociationId(id, null)
        assertNull(db.devices.associationId(id))
        // Re-pairing a removed device brings it back.
        db.devices.upsert(device("AA:BB:CC:DD:EE:02", "Forerunner"))
        assertEquals(2, db.devices.active().size)
        assertEquals(100L, raw.rawQuery("SELECT added_sec FROM device WHERE id = $id", null).use { it.moveToFirst(); it.getLong(0) })
    }

    // ---- sync_file ----

    @Test
    fun syncFileMarks() = test {
        val dev = db.devices.upsert(device())
        val key = SyncFileKey(dev, 3, 128, 4, 1_000, 2048)
        assertFalse(db.syncFiles.isDone(key))
        assertNull(db.syncFiles.state(key))
        db.syncFiles.mark(key, SyncFileState.FAILED, null)
        assertFalse(db.syncFiles.isDone(key))
        db.syncFiles.mark(key.copy(fileIndex = 9), SyncFileState.DONE, 5)
        assertTrue(db.syncFiles.isDone(key)) // file index is not part of the identity
        assertEquals(SyncFileState.DONE, db.syncFiles.state(key))
        db.syncFiles.mark(key.copy(size = 1), SyncFileState.SKIPPED, 5)
        assertTrue(db.syncFiles.isDone(key.copy(size = 1)))
        assertEquals(2L, count("sync_file"))
    }

    @Test
    fun statsCountTheDayTheNightAndStoredFiles() = test {
        val dev = db.devices.upsert(device())
        val day = d23.toTimeRange(utc)
        val night = TimeRange(t23 - 6 * 3_600, t23 + 7 * 3_600)
        val empty = db.stats.counts(day, night)
        assertEquals(DataCounts(0, 0, 0, 0, 0, 0, null), empty)
        db.activity.insert(listOf(minute(dev, t23 + 60, 10), minute(dev, t23 + 120, 15), minute(dev, t23 - 60, 99)))
        db.metrics.insert(listOf(MetricSample(dev, MetricKind.HEART_RATE, t23 + 60, 70)))
        db.sleep.insertStages(listOf(SleepStageSample(dev, t23 - 3_600, SleepStage.DEEP), SleepStageSample(dev, t23 + 3_600, SleepStage.REM)))
        db.syncFiles.mark(SyncFileKey(dev, 3, 128, 4, 1_000, 2048), SyncFileState.DONE, 500)
        db.syncFiles.mark(SyncFileKey(dev, 4, 128, 4, 2_000, 2048), SyncFileState.DONE, 700)
        db.syncFiles.mark(SyncFileKey(dev, 5, 128, 4, 3_000, 2048), SyncFileState.FAILED, null)
        val c = db.stats.counts(day, night)
        assertEquals(2L, c.activityMinutes)
        assertEquals(25L, c.steps)
        assertEquals(1L, c.metrics)
        assertEquals(2L, c.sleepStages)
        assertEquals(0L, c.workouts)
        assertEquals(2L, c.filesStored)
        assertEquals(700L, c.lastIngestSec)
    }

    // ---- activity ----

    private fun minute(dev: Long, t: Long, steps: Int, intensity: Int = 0) = ActivityMinute(dev, t, steps, 10, 100, intensity, null)

    @Test
    fun activityInsertReplaceAndRead() = test {
        val list = (0 until 10).map { minute(1, t23 + it * 60L, it, intensity = it % 4) }
        assertEquals(10, db.activity.insert(list))
        assertEquals(0, db.activity.insert(emptyList()))
        db.activity.insert(listOf(minute(1, t23, 500).copy(activityType = 6)))
        val got = db.activity.minutes(TimeRange(t23, t23 + 600))
        assertEquals(10, got.size)
        assertEquals(500, got[0].steps)
        assertEquals(6, got[0].activityType)
        db.activity.insert(listOf(minute(2, t23, 7)))
        assertEquals(11, db.activity.minutes(TimeRange(t23, t23 + 600)).size)
        assertEquals(1, db.activity.minutes(TimeRange(t23, t23 + 600), deviceId = 2).size)
        assertEquals(0, db.activity.minutes(TimeRange(t23 + 600, t23 + 700)).size)
    }

    @Test
    fun activityTotalsByDayUtc() = test {
        val rows = ArrayList<ActivityMinute>()
        for (day in 0 until 3) for (m in 0 until 1440) rows += minute(1, t23 + day * 86_400L + m * 60, 1, if (m < 30) 2 else 0)
        db.activity.insert(rows)
        db.activity.insert(listOf(minute(2, t23 + 5, 100)))
        val totals = db.activity.totalsByDay(DayRange(d23, d23.plusDays(4)), utc)
        assertEquals(3, totals.size)
        assertEquals(ActivityDayTotals(d23, 1540, 14410, 144100, 30, 1441), totals[0])
        assertEquals(1440L, totals[1].steps)
        assertEquals(1440L, db.activity.totalsByDay(DayRange(d23, d23), utc, deviceId = 1)[0].steps)
        assertEquals(emptyList<ActivityDayTotals>(), db.activity.totalsByDay(DayRange(d23, d23), utc, deviceId = 9))
    }

    @Test
    fun activityTotalsAcrossDstChange() = test {
        // 2026-10-25: Europe/London falls back from UTC+1 to UTC+0, so the day has 25 hours.
        val day = Day(20261025)
        val range = day.toTimeRange(london)
        assertEquals(25 * 3600L, range.durationSec)
        val rows = (0 until 25 * 60).map { minute(1, range.startSec + it * 60L, 1) }
        db.activity.insert(rows)
        db.activity.insert(listOf(minute(1, range.startSec - 60, 1000), minute(1, range.endSec, 1000)))
        val totals = db.activity.totalsByDay(DayRange(day, day), london)
        assertEquals(1, totals.size)
        assertEquals(day, totals[0].day)
        assertEquals(1500L, totals[0].steps)
        val three = db.activity.totalsByDay(DayRange(day.plusDays(-1), day.plusDays(1)), london)
        assertEquals(listOf(1000L, 1500L, 1000L), three.map { it.steps })
    }

    // ---- metric ----

    @Test
    fun metricSeriesStatsLatest() = test {
        val hr = (0 until 120).map { MetricSample(1, MetricKind.HEART_RATE, t23 + it * 60L, 60 + it % 10) }
        db.metrics.insert(hr)
        db.metrics.insert(listOf(MetricSample(1, MetricKind.HRV_RMSSD, t23, 45)))
        db.metrics.insert(listOf(MetricSample(2, MetricKind.HEART_RATE, t23 + 86_400, 99)))
        db.metrics.insert(listOf(MetricSample(1, MetricKind.HEART_RATE, t23, 61))) // replace
        val s = db.metrics.series(MetricKind.HEART_RATE, TimeRange(t23, t23 + 3600))
        assertEquals(60, s.size)
        assertEquals(61, s[0].value)
        assertEquals(1, db.metrics.series(MetricKind.HEART_RATE, TimeRange(t23, t23 + 2 * 86_400), deviceId = 2).size)
        assertEquals(1, db.metrics.series(MetricKind.HRV_RMSSD, TimeRange(t23, t23 + 3600)).size)
        val stats = db.metrics.dailyStats(MetricKind.HEART_RATE, DayRange(d23, d23.plusDays(1)), utc)
        assertEquals(2, stats.size)
        assertEquals(60, stats[0].min)
        assertEquals(69, stats[0].max)
        assertEquals(120, stats[0].count)
        // values 61,61..69: sum = 12*(60+...+69) - 60 + 61 = 7741 -> 64.508 -> 65
        assertEquals(65, stats[0].avg)
        assertEquals(MetricDayStats(d23.plusDays(1), 99, 99, 99, 1), stats[1])
        assertEquals(99, db.metrics.latest(MetricKind.HEART_RATE)!!.value)
        assertNull(db.metrics.latest(MetricKind.SPO2))
        assertEquals(emptyList<MetricSample>(), db.metrics.series(MetricKind.SPO2, TimeRange(0, Long.MAX_VALUE)))
    }

    // ---- sleep ----

    @Test
    fun sleepNightAndSessions() = test {
        val start = t23 - 3 * 3600L // 21:00 the day before (UTC)
        val end = t23 + 5 * 3600L
        val id = db.sleep.insertSession(SleepSession(1, start, end, 80))
        val nap = db.sleep.insertSession(SleepSession(1, t23 + 13 * 3600L, t23 + 14 * 3600L, null))
        assertEquals(id, db.sleep.insertSession(SleepSession(1, start, end, 85))) // update keeps id
        val stages = (0 until 16).map { SleepStageSample(1, start + it * 1800L, SleepStage.entries[it % 4]) }
        assertEquals(16, db.sleep.insertStages(stages))
        db.sleep.insertStages(listOf(SleepStageSample(1, end + 60, SleepStage.AWAKE)))
        val night = db.sleep.night(d23, utc)
        assertNotNull(night)
        assertEquals(id, night!!.id)
        assertEquals(SleepSession(1, start, end, 85), night.session)
        assertEquals(stages, night.stages)
        assertNull(db.sleep.night(d23.plusDays(1), utc))
        assertEquals(2, db.sleep.sessions(DayRange(d23, d23), utc).size)
        assertEquals(17, db.sleep.stages(TimeRange(start, end + 120), 1).size)
        assertTrue(db.sleep.deleteSession(id))
        assertFalse(db.sleep.deleteSession(id))
        assertEquals(1L, count("sleep_stage"))
        assertEquals(nap, db.sleep.night(d23, utc)!!.id)
    }

    // ---- workout ----

    @Test
    fun workoutCrud() = test {
        val a = db.workouts.insert(workout(t23))
        val b = db.workouts.insert(workout(t23 + 86_400))
        val c = db.workouts.insert(workout(t23 + 2 * 86_400, deviceId = null).copy(routePreview = null), importId = 7)
        assertEquals(a, db.workouts.insert(workout(t23).copy(kcal = 999))) // same device + start
        assertEquals(999, db.workouts.get(a)!!.kcal)
        assertEquals(workout(t23 + 86_400).copy(id = b), db.workouts.get(b))
        assertNull(db.workouts.get(c)!!.routePreview)
        assertEquals(listOf(c, b, a), db.workouts.list(10).map { it.id })
        assertEquals(listOf(b), db.workouts.list(1, beforeStartSec = t23 + 2 * 86_400).map { it.id })
        assertEquals(listOf(a, b), db.workouts.forExport(DayRange(d23, d23.plusDays(1)), utc).map { it.id })
        assertTrue(db.workouts.update(db.workouts.get(b)!!.copy(sport = 2, hasTrack = false)))
        assertEquals(2, db.workouts.get(b)!!.sport)
        assertFalse(db.workouts.get(b)!!.hasTrack)
        assertTrue(db.workouts.delete(a))
        assertFalse(db.workouts.delete(a))
        assertFalse(db.workouts.update(workout(0).copy(id = 12345)))
        assertEquals(1L, count("workout", "import_id = 7"))
    }

    @Test
    fun workoutTrimKeepsTheOriginalAndUndoes() = test {
        val id = db.workouts.insert(workout(t23))
        val original = db.workouts.get(id)!!
        assertNull(db.workouts.trim(id))
        val cut = original.copy(startSec = t23 + 60, endSec = t23 + 1200, durationSec = 1140, distanceM = 3200, kcal = 190, avgHr = 150, routePreview = byteArrayOf(9))
        assertTrue(db.workouts.saveTrim(cut, t23 + 60, t23 + 1200))
        val stored = db.workouts.get(id)!!
        assertEquals(cut, stored)
        assertEquals("/f/$t23.fit", stored.fitPath) // the file is kept
        val trim = db.workouts.trim(id)!!
        assertEquals(t23 + 60 to t23 + 1200, trim.startSec to trim.endSec)
        assertEquals(original, trim.original)
        assertEquals(setOf(id), db.workouts.trimmedIds())
        // Trimming again keeps the first originals.
        assertTrue(db.workouts.saveTrim(cut.copy(endSec = t23 + 900, durationSec = 840), t23 + 60, t23 + 900))
        assertEquals(original, db.workouts.trim(id)!!.original)
        assertEquals(t23 + 900, db.workouts.trim(id)!!.endSec)
        // A re-sync of the same workout (original start) leaves the trim alone.
        assertEquals(id, db.workouts.insert(workout(t23).copy(kcal = 999)))
        assertEquals(190, db.workouts.get(id)!!.kcal)
        assertEquals(1L, count("workout"))
        // Undo puts everything back.
        assertTrue(db.workouts.undoTrim(id))
        assertEquals(original, db.workouts.get(id))
        assertNull(db.workouts.trim(id))
        assertFalse(db.workouts.undoTrim(id))
        // An empty window is refused; deleting a trimmed workout removes its trim.
        assertFalse(db.workouts.saveTrim(cut, t23 + 100, t23 + 100))
        assertTrue(db.workouts.saveTrim(cut, t23 + 60, t23 + 1200))
        assertTrue(db.workouts.delete(id))
        assertEquals(0L, count("workout_trim"))
    }

    // ---- ingest ----

    @Test
    fun ingestIsAtomic() = test {
        val dev = db.devices.upsert(device())
        val key = SyncFileKey(dev, 1, 32, 0, t23, 100)
        val batch = IngestBatch(
            key = key,
            activity = listOf(minute(dev, t23, 5)),
            metrics = listOf(MetricSample(dev, MetricKind.STRESS, t23, 30)),
            sleepStages = listOf(SleepStageSample(dev, t23, SleepStage.DEEP)),
            sleepSessions = listOf(SleepSession(dev, t23, t23 + 60, null)),
            workout = workout(t23, dev),
            body = listOf(BodyMeasure(0, t23, BodyKind.WEIGHT, null, 8000, DataSource.SCALE, null, null)),
        )
        val r = db.healthWrite.ingest(batch, nowSec = 5)
        assertNotNull(r.workoutId)
        assertTrue(db.syncFiles.isDone(key))
        for (t in listOf("activity_minute", "metric", "sleep_stage", "sleep_session", "workout", "body_measure")) assertEquals(t, 1L, count(t))

        // A batch whose sync key points at an unknown device breaks a foreign key: nothing is stored.
        val bad = IngestBatch(key = key.copy(deviceId = 999), activity = listOf(minute(dev, t23 + 60, 5)))
        try {
            db.healthWrite.ingest(bad, nowSec = 6)
            fail("expected a constraint failure")
        } catch (e: SQLiteConstraintException) {
            // expected
        }
        assertEquals(1L, count("activity_minute"))
    }

    // ---- body ----

    @Test
    fun bodyMeasures() = test {
        fun w(t: Long, v: Int) = BodyMeasure(0, t, BodyKind.WEIGHT, null, v, DataSource.MANUAL, null, null)
        val a = db.body.insert(w(t23, 8000))
        val b = db.body.insert(w(t23 + 86_400, 7950))
        db.body.insert(BodyMeasure(0, t23, BodyKind.WAIST, null, 9000, DataSource.MANUAL, null, null))
        assertEquals(7950, db.body.latest(BodyKind.WEIGHT)!!.valueX100)
        assertEquals(listOf(a, b), db.body.series(BodyKind.WEIGHT, DayRange(d23, d23.plusDays(1)), utc).map { it.id })
        assertEquals(listOf(a), db.body.series(BodyKind.WEIGHT, DayRange(d23, d23), utc).map { it.id })
        assertTrue(db.body.update(db.body.get(b)!!.copy(valueX100 = 7900, source = DataSource.SCALE)))
        assertEquals(DataSource.SCALE, db.body.get(b)!!.source)
        assertTrue(db.body.delete(b))
        assertFalse(db.body.delete(b))
        assertEquals(8000, db.body.latest(BodyKind.WEIGHT)!!.valueX100)
        assertNull(db.body.latest(BodyKind.NECK))

        val kind = db.body.addCustomKind("Calf", "cm")
        db.body.addCustomKind("Bicep", "cm")
        assertEquals(listOf("Bicep", "Calf"), db.body.customKinds().map { it.name })
        assertTrue(db.body.updateCustomKind(MeasureKind(kind, "Calf L", "in", true)))
        assertEquals(MeasureKind(kind, "Calf L", "in", true), db.body.customKinds()[1])
        db.body.insert(BodyMeasure(0, t23, BodyKind.CUSTOM, kind, 3800, DataSource.MANUAL, null, null))
        assertEquals(3800, db.body.latest(BodyKind.CUSTOM, kind)!!.valueX100)
        assertNull(db.body.latest(BodyKind.CUSTOM, kind + 1))
        assertEquals(1, db.body.series(BodyKind.CUSTOM, DayRange(d23, d23), utc, kind).size)
    }
}
