/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.hc

import android.health.connect.datatypes.ExerciseSessionRecord
import android.health.connect.datatypes.ExerciseSessionType
import android.health.connect.datatypes.HeartRateVariabilityRmssdRecord
import android.health.connect.datatypes.NutritionRecord
import android.health.connect.datatypes.StepsRecord
import android.health.connect.datatypes.WeightRecord
import android.health.connect.datatypes.units.Energy
import android.health.connect.datatypes.units.Mass
import app.selfwatch.interop.DbTestSupport
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.time.Day
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HcSyncTest {
    private val db get() = DbTestSupport.db
    private val now = 1_780_000_000L
    private val clock = TestClock(now)
    private val zone = ZoneId.of("UTC")
    private val noWait = HcRetry(listOf(1L, 1L)) { }
    private lateinit var api: FakeHcApi
    private val own = "app.selfwatch"

    // A watch device id that cannot collide with the health-connect row the reader creates.
    private val WATCH_DEV = 77L

    @Before
    fun setUp() = runBlocking {
        DbTestSupport.fresh()
        api = FakeHcApi()
        db.settings.setBool(HcKeys.ENABLED, true)
    }

    private fun sync() = HcSync(db, { api }, own, zone, clock, noWait)

    private fun count(sql: String): Long = db.readableDatabase.rawQuery(sql, null).use { it.moveToFirst(); it.getLong(0) }

    @Test
    fun rewritesKeepTheSameClientIdAndRaiseTheVersion() = runBlocking {
        db.body.insert(BodyMeasure(0, now - 3600, BodyKind.WEIGHT, null, 7500, DataSource.MANUAL, null, null))
        db.body.insert(BodyMeasure(0, now - 3000, BodyKind.WEIGHT, null, 7400, DataSource.HEALTH_CONNECT, null, null))
        db.diary.add(DiaryEntry(0, Day.of(now, zone), 1, now - 600, null, "Toast", null, 100, "slice", null,
            Nutrients(800, 30, null, null, 150, null, null, null, null), null))
        val r1 = sync().runOnce(HcDirection.WRITE)
        assertTrue(r1 is Outcome.Ok)
        val w1 = api.inserted.filterIsInstance<WeightRecord>()
        assertEquals("HEALTH_CONNECT rows are not written back", 1, w1.size)
        assertEquals(1, api.inserted.filterIsInstance<NutritionRecord>().size)
        clock.now += 600
        sync().runOnce(HcDirection.WRITE)
        val w = api.inserted.filterIsInstance<WeightRecord>()
        assertEquals(2, w.size)
        assertEquals(w[0].metadata.clientRecordId, w[1].metadata.clientRecordId)
        assertTrue(w[1].metadata.clientRecordVersion > w[0].metadata.clientRecordVersion)
        assertEquals((now + 600).toString(), db.settings.get(HcKeys.lastWrite(HcType.WEIGHT)))
    }

    @Test
    fun largeWritesAreSplitIntoBatches() = runBlocking {
        db.metrics.insert((0 until 2500).map { MetricSample(1, MetricKind.HRV_RMSSD, now - 100_000 + it * 10L, 40) })
        api.granted = setOf(HcType.HRV.writePermission)
        val r = sync().runOnce(HcDirection.WRITE)
        assertTrue(r is Outcome.Ok)
        assertEquals(listOf(1000, 1000, 500), api.inserts.map { it.size })
        assertTrue(api.inserted.all { it is HeartRateVariabilityRmssdRecord })
        assertEquals(2500, api.inserted.map { it.metadata.clientRecordId }.toSet().size)
    }

    @Test
    fun activityIsGroupedPerHour() = runBlocking {
        val hour = (now / 3600 - 2) * 3600
        db.activity.insert((0 until 60).map { ActivityMinute(1, hour + it * 60L, 10, 5, 800, 1, null) })
        api.granted = setOf(HcType.STEPS.writePermission, HcType.DISTANCE.writePermission, HcType.ACTIVE_KCAL.writePermission)
        sync().runOnce(HcDirection.WRITE)
        val steps = api.inserted.filterIsInstance<StepsRecord>().single()
        assertEquals(600L, steps.count)
        assertEquals(3, api.inserted.size)
    }

    @Test
    fun ownRecordsAreSkippedAndRereadsDoNotDuplicate() = runBlocking {
        val t = Instant.ofEpochSecond(now - 86_400)
        api.stored += WeightRecord.Builder(origin(own, "sw:body_measure:1"), t, Mass.fromGrams(70.0 * 1000)).build()
        api.stored += WeightRecord.Builder(origin("com.other"), t.plusSeconds(60), Mass.fromGrams(71.0 * 1000)).build()
        api.stored += WeightRecord.Builder(origin("com.other.app", "sw:body_measure:2"), t.plusSeconds(120), Mass.fromGrams(72.0 * 1000)).build()
        api.granted = setOf(HcType.WEIGHT.readPermission)
        val r = sync().runOnce(HcDirection.READ)
        assertTrue(r is Outcome.Ok)
        assertEquals(1L, count("SELECT COUNT(*) FROM body_measure"))
        assertEquals(7100L, count("SELECT value_x100 FROM body_measure"))
        assertEquals("token-1", db.settings.get(HcKeys.changesToken(HcType.WEIGHT)))
        val importId = db.settings.getLong(HcKeys.IMPORT_ID)
        assertNotNull(importId)
        assertEquals(importId, count("SELECT import_id FROM body_measure"))
        // A forced re-read (token expired) of the same data updates instead of adding.
        api.changes["token-1"] = HcChangesPage(emptyList(), emptyList(), "x", hasMore = false, tokenExpired = true)
        sync().runOnce(HcDirection.READ)
        assertEquals(1L, count("SELECT COUNT(*) FROM body_measure"))
        assertEquals("token-2", db.settings.get(HcKeys.changesToken(HcType.WEIGHT)))
    }

    @Test
    fun changesAreAppliedAndTheTokenAdvances() = runBlocking {
        db.settings.set(HcKeys.changesToken(HcType.NUTRITION), "t0")
        val start = Instant.ofEpochSecond(now - 7200)
        val food = NutritionRecord.Builder(origin("com.food"), start, start.plusSeconds(60))
            .setEnergy(Energy.fromCalories(300_000.0)).setMealName("Apple pie").build()
        api.changes["t0"] = HcChangesPage(listOf(food), listOf("deleted-1"), "t1", hasMore = true, tokenExpired = false)
        api.changes["t1"] = HcChangesPage(listOf(food), emptyList(), "t2", hasMore = false, tokenExpired = false)
        api.granted = setOf(HcType.NUTRITION.readPermission)
        val r = sync().runOnce(HcDirection.READ) as Outcome.Ok
        assertEquals(2, r.value.read["nutrition"])
        assertEquals(1L, count("SELECT COUNT(*) FROM diary_entry"))
        assertEquals(3000L, count("SELECT kcal_x10 FROM diary_entry"))
        assertEquals("t2", db.settings.get(HcKeys.changesToken(HcType.NUTRITION)))
        // Diary lines read from Health Connect are not written back.
        api.granted = HcPermissions.build()
        sync().runOnce(HcDirection.WRITE)
        assertTrue(api.inserted.none { it is NutritionRecord })
    }

    @Test
    fun stepsAndExerciseDeferToWatchData() = runBlocking {
        val hour = (now / 3600 - 5) * 3600
        db.activity.insert(listOf(ActivityMinute(WATCH_DEV, hour, 50, 0, 0, 1, null)))
        db.settings.set(HcKeys.changesToken(HcType.STEPS), "s0")
        db.settings.set(HcKeys.changesToken(HcType.EXERCISE), "e0")
        api.changes["s0"] = HcChangesPage(
            listOf(
                StepsRecord.Builder(origin("com.fit"), Instant.ofEpochSecond(hour + 60), Instant.ofEpochSecond(hour + 180), 100).build(),
                StepsRecord.Builder(origin("com.fit"), Instant.ofEpochSecond(hour + 7200), Instant.ofEpochSecond(hour + 7320), 100).build(),
            ),
            emptyList(), "s1", hasMore = false, tokenExpired = false,
        )
        db.workouts.insert(app.selfwatch.model.health.Workout(0, 1, 1, null, now - 5000, now - 3000, 2000, null, null, null, null, null, null,
            false, null, null, null, DataSource.WATCH))
        api.changes["e0"] = HcChangesPage(
            listOf(
                ExerciseSessionRecord.Builder(origin("com.fit"), Instant.ofEpochSecond(now - 4990), Instant.ofEpochSecond(now - 3000),
                    ExerciseSessionType.EXERCISE_SESSION_TYPE_RUNNING).build(),
                ExerciseSessionRecord.Builder(origin("com.fit"), Instant.ofEpochSecond(now - 90_000), Instant.ofEpochSecond(now - 89_000),
                    ExerciseSessionType.EXERCISE_SESSION_TYPE_YOGA).build(),
            ),
            emptyList(), "e1", hasMore = false, tokenExpired = false,
        )
        api.granted = setOf(HcType.STEPS.readPermission, HcType.EXERCISE.readPermission)
        sync().runOnce(HcDirection.READ)
        val hcDev = count("SELECT id FROM device WHERE address = 'health-connect'")
        assertEquals(1L, count("SELECT removed FROM device WHERE id = $hcDev"))
        assertEquals("only the hour without watch data", 100L, count("SELECT SUM(steps) FROM activity_minute WHERE device_id = $hcDev"))
        assertEquals(1L, count("SELECT COUNT(*) FROM workout WHERE source = ${DataSource.HEALTH_CONNECT.code}"))
    }

    @Test
    fun missingOrRevokedPermissionFails() = runBlocking {
        api.granted = emptySet()
        val r = sync().runOnce()
        assertTrue(r is Outcome.Fail && r.error is SwError.PermissionMissing)
        db.body.insert(BodyMeasure(0, now - 60, BodyKind.WEIGHT, null, 7500, DataSource.MANUAL, null, null))
        api.granted = HcPermissions.build()
        api.insertError = SecurityException("revoked")
        val r2 = sync().runOnce(HcDirection.WRITE)
        assertEquals(Outcome.Fail(SwError.PermissionMissing(HcType.WEIGHT.writePermission)), r2)
    }

    @Test
    fun rateLimitBacksOff() = runBlocking {
        db.body.insert(BodyMeasure(0, now - 60, BodyKind.WEIGHT, null, 7500, DataSource.MANUAL, null, null))
        api.insertError = HcRateLimitedException("quota")
        val r = sync().runOnce(HcDirection.WRITE)
        assertEquals(Outcome.Fail(SwError.RateLimited(HcSync.backoffSec(0).toInt())), r)
        assertEquals(now + HcSync.backoffSec(0), db.settings.getLong(HcKeys.BACKOFF_UNTIL))
        api.insertError = null
        assertEquals(HcSkip.BACKING_OFF, (sync().runOnce() as Outcome.Ok).value.skipped)
        clock.now += 3600
        val ok = sync().runOnce(HcDirection.WRITE) as Outcome.Ok
        assertEquals(null, ok.value.skipped)
        assertEquals(null, db.settings.get(HcKeys.BACKOFF_UNTIL))
    }

    @Test
    fun disabledAndBackgroundRuns() = runBlocking {
        db.settings.setBool(HcKeys.ENABLED, false)
        assertEquals(HcSkip.DISABLED, (sync().runOnce() as Outcome.Ok).value.skipped)
        db.settings.setBool(HcKeys.ENABLED, true)
        assertEquals(HcSkip.UNAVAILABLE, (HcSync(db, { null }, own, zone, clock, noWait).runOnce() as Outcome.Ok).value.skipped)
        val bg = sync().runOnce(background = true) as Outcome.Ok
        assertEquals(HcSkip.BACKGROUND_READ_OFF, bg.value.skipped)
        assertTrue(bg.value.read.isEmpty())
        db.settings.setBool(HcKeys.BACKGROUND_READ, true)
        val bg2 = sync().runOnce(background = true) as Outcome.Ok
        assertEquals(null, bg2.value.skipped)
    }

    @Test
    fun historyNeedsItsPermission() = runBlocking {
        api.granted = HcPermissions.build()
        val r = sync().importRange(now - 90 * 86_400L)
        assertEquals(Outcome.Fail(SwError.PermissionMissing(HcPermissions.HISTORY)), r)
        api.granted = HcPermissions.build(history = true)
        assertTrue(sync().importRange(now - 90 * 86_400L) is Outcome.Ok)
    }
}
