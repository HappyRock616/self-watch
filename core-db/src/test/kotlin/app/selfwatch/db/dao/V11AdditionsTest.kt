/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db.dao

import app.selfwatch.db.DbTest
import app.selfwatch.db.Table
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections

/** WP-03 schema and DAO additions for core-model v1.1 (import ids on watch data, transactions, range reads). */
class V11AdditionsTest : DbTest() {
    private val dev = 5L

    // ---- inTransaction ----

    @Test
    fun inTransactionCommitsTogetherAndNotifiesOnceAfterwards() = test {
        val seen = Collections.synchronizedList(ArrayList<Set<Table>>())
        coroutineScope {
            val job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { changes.changes.collect { seen.add(it) } }
            val id = db.inTransaction(setOf(Table.DEVICE)) { d ->
                db.water.add(Day(20261123), 1, 250)
                // A suspension inside the block keeps the transaction's thread.
                delay(10)
                db.diary.add(entry(Day(20261123)))
                // Reads inside see the uncommitted rows (same connection).
                assertEquals(250, db.water.dayTotal(Day(20261123)))
                assertTrue(seen.isEmpty())
                d.rawQuery("SELECT COUNT(*) FROM diary_entry", null).use { it.moveToFirst(); it.getLong(0) }
            }
            assertEquals(1L, id)
            job.cancel()
        }
        assertEquals(listOf(setOf(Table.DEVICE, Table.WATER, Table.DIARY)), seen.toList())
        assertEquals(1L, count("water"))
        assertEquals(1L, count("diary_entry"))
    }

    @Test
    fun inTransactionRollsEverythingBackOnFailure() = test {
        try {
            db.inTransaction { _ ->
                db.water.add(Day(20261123), 1, 250)
                db.imports.begin(1, "csv", null)
                error("boom")
            }
            fail("expected the failure to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertEquals(0L, count("water"))
        assertEquals(0L, count("import_batch"))
        // The database is usable afterwards.
        db.water.add(Day(20261123), 1, 100)
        assertEquals(1L, count("water"))
    }

    @Test
    fun daoCallFromAnotherThreadInsideTheBlockFails() = test {
        try {
            db.inTransaction { _ -> withContext(Dispatchers.IO) { db.water.add(Day(20261123), 1, 250) } }
            fail("expected a thread check failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("another thread"))
        }
        assertEquals(0L, count("water"))
    }

    @Test
    fun nestedInTransactionJoins() = test {
        db.inTransaction { _ ->
            db.water.add(Day(20261123), 1, 250)
            db.inTransaction { _ -> db.water.add(Day(20261123), 2, 100) }
        }
        assertEquals(2L, count("water"))
    }

    // ---- import_id on watch data ----

    @Test
    fun watchSyncWritesLeaveImportIdNull() = test {
        db.healthWrite.ingest(
            IngestBatch(
                key = null,
                activity = listOf(ActivityMinute(dev, 60, 10, 5, 100, 1, null)),
                metrics = listOf(MetricSample(dev, MetricKind.RESTING_KCAL, 0, 1650)),
                sleepStages = listOf(SleepStageSample(dev, 0, SleepStage.LIGHT)),
                sleepSessions = listOf(SleepSession(dev, 0, 3600, null)),
            ),
            nowSec = 100,
        )
        for (t in listOf("activity_minute", "metric", "sleep_session", "sleep_stage")) {
            assertEquals(t, 1L, count(t, "import_id IS NULL"))
        }
        assertEquals(1650, db.metrics.latest(MetricKind.RESTING_KCAL)?.value)
    }

    @Test
    fun sleepSessionUpdateKeepsItsImportId() = test {
        db.sleep.insertSession(SleepSession(dev, 0, 3600, null), importId = 7)
        db.sleep.insertSession(SleepSession(dev, 0, 4000, 80), importId = 7)
        assertEquals(1L, count("sleep_session", "import_id = 7 AND end_sec = 4000"))
    }

    // ---- range reads ----

    @Test
    fun diaryRangeAndLastEntry() = test {
        val ref = FoodRef(FoodOrigin.FOODS_DB, 42)
        db.diary.add(entry(Day(20261121), tSec = 300, food = ref, name = "old"))
        db.diary.add(entry(Day(20261122), slot = 2, tSec = 200, name = "b"))
        db.diary.add(entry(Day(20261122), slot = 1, tSec = 500, food = ref, name = "newest"))
        db.diary.add(entry(Day(20261123), tSec = 900, food = FoodRef(FoodOrigin.FOODS_DB, 43), name = "other"))

        assertEquals(listOf("old", "newest", "b", "other"), db.diary.range(null).map { it.name })
        assertEquals(listOf("newest", "b"), db.diary.range(DayRange(Day(20261122), Day(20261122))).map { it.name })
        assertEquals("newest", db.diary.lastEntryFor(ref)?.name)
        assertNull(db.diary.lastEntryFor(FoodRef(FoodOrigin.MY_FOOD, 42)))
    }

    @Test
    fun waterBodyAndWorkoutReads() = test {
        db.water.add(Day(20261122), 20, 100)
        db.water.add(Day(20261123), 30, 200)
        db.water.add(Day(20261123), 25, 300)
        assertEquals(listOf(100, 300, 200), db.water.range(null).map { it.ml })
        assertEquals(listOf(300, 200), db.water.range(DayRange(Day(20261123), Day(20261124))).map { it.ml })

        db.body.insert(BodyMeasure(0, 50, BodyKind.WEIGHT, null, 8000, DataSource.MANUAL, null, null))
        db.body.insert(BodyMeasure(0, 10, BodyKind.WAIST, null, 9000, DataSource.MANUAL, null, null))
        db.body.insert(BodyMeasure(0, 99, BodyKind.WEIGHT, null, 7900, DataSource.MANUAL, null, null))
        assertEquals(listOf(10L, 50L, 99L), db.body.all().map { it.tSec })
        assertEquals(listOf(50L), db.body.all(TimeRange(11, 99)).map { it.tSec })

        db.workouts.insert(workout(2000))
        db.workouts.insert(workout(1000))
        assertEquals(listOf(1000L, 2000L), db.workouts.all().map { it.startSec })
    }
}
