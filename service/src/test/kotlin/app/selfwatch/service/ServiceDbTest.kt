/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service

import android.app.Application
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.device.Feature
import app.selfwatch.model.device.SupportTier
import app.selfwatch.model.fake.FakeClock
import app.selfwatch.model.time.Day
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.ZoneId

/**
 * Base for tests that need the database. Uses the process's shared [SelfWatchDb] (the only public
 * way to open it from another module) and empties the tables these tests touch before each test.
 */
@RunWith(RobolectricTestRunner::class)
abstract class ServiceDbTest {
    protected lateinit var app: Application
    protected lateinit var db: SelfWatchDb
    protected val zone: ZoneId = ZoneId.of("America/Detroit")

    /** Noon on 2026-11-23 in Detroit. */
    protected val clock: FakeClock = FakeClock(noon(Day(20261123)), zone)

    @Before
    fun openDb() {
        app = RuntimeEnvironment.getApplication()
        db = SelfWatchDb.get(app)
        val raw = db.writableDatabase
        for (t in WIPE) raw.execSQL("DELETE FROM $t")
    }

    protected fun noon(day: Day): Long = day.toLocalDate().atTime(12, 0).atZone(zone).toEpochSecond()

    protected fun at(day: Day, hour: Int, minute: Int = 0): Long =
        day.toLocalDate().atTime(hour, minute).atZone(zone).toEpochSecond()

    protected fun addDevice(address: String = "AA:BB:CC:DD:EE:01", priority: Int = 0): Long = runBlocking {
        val id = db.devices.upsert(device(address), clock.nowSec())
        db.devices.setPriority(id, priority)
        id
    }

    protected fun count(table: String): Long =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }

    companion object {
        private val WIPE = listOf(
            "sync_file", "activity_minute", "metric", "sleep_stage", "sleep_session", "workout", "body_measure",
            "diary_entry", "water", "day_summary", "setting", "net_log", "watch_app_settings", "watch_app", "device",
            "link_permission", "goal",
        )

        fun device(address: String, name: String = "vivoactive 5"): DeviceInfo = DeviceInfo(
            id = 0, kind = DeviceKind.GARMIN_WATCH, address = address, name = name, model = "vívoactive 5",
            productId = 4426, firmware = "12.34", serial = null, tier = SupportTier.UNVERIFIED,
            features = setOf(Feature.HEALTH_ACTIVITY), batteryPct = null,
        )
    }
}
