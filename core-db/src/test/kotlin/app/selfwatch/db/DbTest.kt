/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.device.Feature
import app.selfwatch.model.device.SupportTier
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.Day
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Base for Robolectric database tests: a fresh file database per test (Robolectric uses real SQLite). */
@RunWith(RobolectricTestRunner::class)
abstract class DbTest {
    protected lateinit var app: Application
    protected lateinit var db: SelfWatchDb
    protected lateinit var changes: DbChanges

    @Before
    fun openDb() {
        app = RuntimeEnvironment.getApplication()
        app.deleteDatabase(NAME)
        changes = DbChanges(app)
        db = SelfWatchDb.create(app, NAME, changes = changes)
    }

    @After
    fun closeDb() {
        db.close()
        app.deleteDatabase(NAME)
    }

    /** Runs a suspending test body. */
    protected fun test(block: suspend () -> Unit): Unit = runBlocking { block() }

    /** The raw database, for checks. */
    protected val raw: SQLiteDatabase get() = db.writableDatabase

    protected fun count(table: String, where: String = "1"): Long =
        raw.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", null).use { it.moveToFirst(); it.getLong(0) }

    companion object {
        const val NAME: String = "selfwatch-test.db"

        fun device(address: String = "AA:BB:CC:DD:EE:01", name: String = "vivoactive 5"): DeviceInfo = DeviceInfo(
            id = 0, kind = DeviceKind.GARMIN_WATCH, address = address, name = name, model = "vívoactive 5",
            productId = 4426, firmware = "12.34", serial = 3_400_000_001L, tier = SupportTier.UNVERIFIED,
            features = setOf(Feature.NOTIFICATIONS, Feature.HEALTH_HR), batteryPct = null,
        )

        fun workout(startSec: Long, deviceId: Long? = 1, sport: Int = 1): Workout = Workout(
            id = 0, deviceId = deviceId, sport = sport, subSport = null, startSec = startSec, endSec = startSec + 1800,
            durationSec = 1800, distanceM = 5000, kcal = 300, avgHr = 140, maxHr = 170, ascentM = 20, descentM = 18,
            hasTrack = true, fitPath = "/f/$startSec.fit", trackPath = null, routePreview = byteArrayOf(1, 2, 3),
            source = DataSource.WATCH,
        )

        fun nutrients(kcal: Int, protein: Int? = null): Nutrients =
            Nutrients(kcal, protein, null, null, null, null, null, null, null)

        fun entry(
            day: Day,
            slot: Long = 1,
            tSec: Long = 1_795_435_200L,
            food: FoodRef? = null,
            name: String = "Apple",
            kcalX10: Int = 520,
            importId: Long? = null,
        ): DiaryEntry = DiaryEntry(
            id = 0, day = day, mealSlotId = slot, tSec = tSec, food = food, name = name, brand = null, amountX100 = 100,
            servingLabel = "1 medium", gramsX10 = 1820, nutrients = nutrients(kcalX10, 3), importId = importId,
        )
    }
}
