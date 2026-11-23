/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.selfwatch.db.dao.CustomFood
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.DaySummary
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.health.SleepStageSample
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.TimeRange
import app.selfwatch.model.watch.IngestBatch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import kotlin.random.Random

/**
 * B07 performance measurement on a real phone (results in logcat, tag `SelfWatchPerf`, and in
 * WP-03's performance table). Also proves the device SQLite has FTS4 with unicode61.
 */
@RunWith(AndroidJUnit4::class)
class PerformanceTest {
    private lateinit var ctx: Context
    private lateinit var db: SelfWatchDb
    private val zone: ZoneId = ZoneId.of("America/Detroit")
    private val firstDay = Day(20251124)

    @Before
    fun open() {
        ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.deleteDatabase(NAME)
        db = SelfWatchDb.create(ctx, NAME)
    }

    @After
    fun close() {
        db.close()
        ctx.deleteDatabase(NAME)
    }

    /** One day: 1,440 activity minutes and 2,000 metric rows (HR every minute, stress, respiration, SpO2, HRV). */
    private fun dayBatch(day: Day, rnd: Random): Pair<List<ActivityMinute>, List<MetricSample>> {
        val start = day.startSec(zone)
        val act = (0 until 1440).map { m ->
            val active = m in 420..1320
            ActivityMinute(1, start + m * 60L, if (active) rnd.nextInt(0, 120) else 0, rnd.nextInt(0, 60), rnd.nextInt(0, 9000), rnd.nextInt(0, 4), null)
        }
        val met = ArrayList<MetricSample>(2000)
        for (m in 0 until 1440) met += MetricSample(1, MetricKind.HEART_RATE, start + m * 60L, 50 + rnd.nextInt(0, 100))
        for (k in 0 until 360) met += MetricSample(1, MetricKind.STRESS, start + k * 240L, rnd.nextInt(0, 100))
        for (k in 0 until 144) met += MetricSample(1, MetricKind.RESPIRATION, start + k * 600L, 120 + rnd.nextInt(0, 80))
        for (k in 0 until 48) met += MetricSample(1, MetricKind.SPO2, start + k * 1800L, 90 + rnd.nextInt(0, 10))
        for (k in 0 until 8) met += MetricSample(1, MetricKind.HRV_RMSSD, start + k * 900L, 30 + rnd.nextInt(0, 40))
        return act to met
    }

    @Test
    fun deviceSqliteHasFts4Unicode61(): Unit = runBlocking {
        db.foods.insert(
            CustomFood(
                Food(FoodRef(FoodOrigin.MY_FOOD, 0), "Crème brûlée", "Pâtisserie", 0, Basis.PER_100G, Nutrients.ZERO, emptyList(), null, "", null),
                CustomFoodOrigin.TYPED, null, false, 1, 1, null,
            ),
        )
        assertEquals(1, db.foods.searchMine("creme bru").size)
        assertEquals(1, db.foods.searchMine("patis").size)
        val version = db.readableDatabase.rawQuery("SELECT sqlite_version()", null).use { it.moveToFirst(); it.getString(0) }
        Log.i(TAG, "device SQLite $version, API ${android.os.Build.VERSION.SDK_INT}")
    }

    @Test
    fun insertOneDayOfMinuteData(): Unit = runBlocking {
        val rnd = Random(1)
        db.devices.upsert(DbTestDevices.device())
        // Warm up (compile statements, grow the file).
        repeat(2) { i -> dayBatch(firstDay.plusDays(i), rnd).let { (a, m) -> db.activity.insert(a); db.metrics.insert(m) } }
        val separate = ArrayList<Long>()
        val ingest = ArrayList<Long>()
        for (i in 0 until 5) {
            val (a, m) = dayBatch(firstDay.plusDays(10 + i), rnd)
            val t0 = System.nanoTime()
            db.activity.insert(a)
            db.metrics.insert(m)
            separate += (System.nanoTime() - t0) / 1_000_000
            val (a2, m2) = dayBatch(firstDay.plusDays(20 + i), rnd)
            val t1 = System.nanoTime()
            db.healthWrite.ingest(IngestBatch(key = null, activity = a2, metrics = m2), nowSec = 1)
            ingest += (System.nanoTime() - t1) / 1_000_000
        }
        val medSeparate = separate.sorted()[2]
        val medIngest = ingest.sorted()[2]
        Log.i(TAG, "day insert (1440 activity + 2000 metric): two transactions $separate ms (median $medSeparate); one ingest transaction $ingest ms (median $medIngest)")
        assertTrue("median day insert $medIngest ms", medIngest < 1000)
    }

    @Test
    fun yearOfDataSizeAndQueries(): Unit = runBlocking {
        val rnd = Random(2)
        db.devices.upsert(DbTestDevices.device())
        val t0 = System.nanoTime()
        var d = firstDay
        for (i in 0 until 365) {
            val (act, met) = dayBatch(d, rnd)
            val start = d.startSec(zone)
            val sleepStart = start - 2 * 3600L
            val sleepEnd = start + 6 * 3600L + rnd.nextInt(0, 3600)
            val stages = (0 until ((sleepEnd - sleepStart) / 480).toInt()).map {
                SleepStageSample(1, sleepStart + it * 480L, SleepStage.entries[rnd.nextInt(0, 4)])
            }
            val workout = if (i % 2 == 0) {
                Workout(0, 1, 1, 0, start + 18 * 3600L, start + 18 * 3600L + 2700, 2700, 7000, 450, 145, 172, 40, 38, true,
                    "/data/fit/$i.fit", null, ByteArray(64) { it.toByte() }, DataSource.WATCH)
            } else {
                null
            }
            val body = if (i % 3 == 0) listOf(BodyMeasure(0, start + 7 * 3600L, BodyKind.WEIGHT, null, 8000 + rnd.nextInt(-200, 200), DataSource.SCALE, null, null)) else emptyList()
            db.healthWrite.ingest(
                IngestBatch(key = null, activity = act, metrics = met, sleepStages = stages,
                    sleepSessions = listOf(SleepSession(1, sleepStart, sleepEnd, 70 + rnd.nextInt(0, 30))), workout = workout, body = body),
                nowSec = start,
            )
            db.diary.addAll((0 until 8).map { k ->
                DiaryEntry(0, d, (k % 4 + 1).toLong(), start + 8 * 3600L + k * 3600L, FoodRef(FoodOrigin.FOODS_DB, rnd.nextLong(1, 5000)),
                    "Food item number $k", "Brand", 100, "1 serving", 1500, Nutrients(2500, 120, 80, 30, 300, 120, 40, 400, 20), null)
            })
            repeat(4) { k -> db.water.add(d, start + 9 * 3600L + k * 7200L, 250) }
            db.summaries.upsertDay(DaySummary(d, -300, 9000, 450, 1650, 7000, 45, 55, 42, 430, 2100, 95, 70, 250, 8000, 1000), start)
            d = d.plusDays(1)
        }
        val genMs = (System.nanoTime() - t0) / 1_000_000
        db.writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        val file = ctx.getDatabasePath(NAME)
        val wal = java.io.File(file.path + "-wal")
        val mb = file.length() / 1_048_576.0
        val counts = listOf("activity_minute", "metric", "sleep_stage", "workout", "diary_entry", "day_summary").joinToString { t ->
            val n = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $t", null).use { it.moveToFirst(); it.getLong(0) }
            "$t=$n"
        }
        Log.i(TAG, "year of data: %.1f MB (wal %d bytes) after %d ms to generate; rows: %s".format(mb, wal.length(), genMs, counts))

        // Activity tab: 365 day-summary rows.
        val last = firstDay.plusDays(364)
        val sumTimes = (0 until 7).map {
            val t = System.nanoTime()
            val rows = db.summaries.days(DayRange(firstDay, last))
            assertEquals(365, rows.size)
            (System.nanoTime() - t) / 1000
        }.drop(2).sorted()
        // Sleep detail: one night of stages + heart rate + HRV.
        val night = firstDay.plusDays(200)
        val sleepTimes = (0 until 7).map {
            val t = System.nanoTime()
            val n = db.sleep.night(night, zone)!!
            val r = TimeRange(n.session.startSec, n.session.endSec)
            db.metrics.series(MetricKind.HEART_RATE, r)
            db.metrics.series(MetricKind.HRV_RMSSD, r)
            (System.nanoTime() - t) / 1000
        }.drop(2).sorted()
        val weekTimes = (0 until 5).map {
            val t = System.nanoTime()
            db.summaries.weeks(DayRange(firstDay, last))
            db.summaries.months(DayRange(firstDay, last))
            (System.nanoTime() - t) / 1000
        }.drop(1).sorted()
        val totalsTimes = (0 until 3).map {
            val t = System.nanoTime()
            db.activity.totalsByDay(DayRange(last.plusDays(-29), last), zone)
            (System.nanoTime() - t) / 1000
        }.sorted()
        Log.i(TAG, "365 day summaries: median ${sumTimes[2] / 1000.0} ms; sleep night+HR+HRV: median ${sleepTimes[2] / 1000.0} ms; " +
            "weeks+months over a year: median ${weekTimes[2] / 1000.0} ms; activity totalsByDay 30 days: median ${totalsTimes[1] / 1000.0} ms")
    }

    private companion object {
        const val NAME = "perf-test.db"
        const val TAG = "SelfWatchPerf"
    }
}

/** Test device row. */
internal object DbTestDevices {
    fun device(): app.selfwatch.model.device.DeviceInfo = app.selfwatch.model.device.DeviceInfo(
        0, app.selfwatch.model.device.DeviceKind.GARMIN_WATCH, "AA:BB:CC:DD:EE:01", "vivoactive 5", "vívoactive 5",
        4426, "12.34", 3_400_000_001L, app.selfwatch.model.device.SupportTier.UNVERIFIED, emptySet(), null,
    )
}
