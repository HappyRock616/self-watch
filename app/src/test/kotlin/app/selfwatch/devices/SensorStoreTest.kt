/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.devices

import app.selfwatch.SelfWatchApp
import app.selfwatch.ble.pair.PairedDevice
import app.selfwatch.ble.profile.GattUuids
import app.selfwatch.ble.profile.HealthProfile
import app.selfwatch.ble.profile.HealthSensorDriver
import app.selfwatch.me.MeTestSupport
import app.selfwatch.model.ble.BlePort
import app.selfwatch.model.ble.PortState
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.fake.FakeBlePort
import app.selfwatch.model.fake.FakeFileStore
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.time.SystemClock
import app.selfwatch.model.time.TimeRange
import app.selfwatch.service.device.DeviceManager
import app.selfwatch.service.device.DriverFactory
import app.selfwatch.service.device.PortFactory
import app.selfwatch.service.health.HealthIngest
import app.selfwatch.service.health.SummaryBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

/**
 * Fake devices end to end: the service's DeviceManager runs [HealthSensorDriver] on fake ports, and
 * readings land in the real database through HealthIngest (body measures with source SCALE and the
 * scale's device id, blood pressure metrics with the cuff's id, the day summary's weight).
 */
@RunWith(RobolectricTestRunner::class)
class SensorStoreTest {
    private lateinit var app: SelfWatchApp

    @Before
    fun setUp() {
        app = MeTestSupport.freshApp()
    }

    @After
    fun tearDown() = MeTestSupport.resetDb()

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), (v shr 8).toByte())

    @Test
    fun scaleAndCuffReadingsAreStoredWithTheirDevice() {
        val db = app.graph.db
        val scale = runBlocking { db.devices.upsert(DbDeviceList.sensorInfo(PairedDevice("AA:00:00:00:00:01", "Scale", null), DeviceKind.WEIGHT_SCALE, "Weight scale")) }
        val cuff = runBlocking {
            db.devices.upsert(DbDeviceList.sensorInfo(PairedDevice("AA:00:00:00:00:02", "Cuff", null), DeviceKind.BLOOD_PRESSURE_MONITOR, "Cuff"))
        }
        val ws = HealthProfile.WEIGHT_SCALE
        val bp = HealthProfile.BLOOD_PRESSURE
        val created: MutableList<FakeBlePort> = Collections.synchronizedList(ArrayList())
        val ports = object : PortFactory {
            override fun create(address: String, autoReconnect: Boolean): BlePort {
                val first = created.none { it.address == address }
                val port = if (address.endsWith("01")) {
                    FakeBlePort(address, PortState.CONNECTED, setOf(ws.service, GattUuids.BATTERY_SERVICE)).also {
                        // 72.50 kg, no time stamp: stored at arrival.
                        if (first) it.pipe(ws.service, ws.measurement, ws.measurement).queueIncoming(byteArrayOf(0) + le16(14500))
                    }
                } else {
                    FakeBlePort(address, PortState.CONNECTED, setOf(bp.service)).also {
                        // 124/81 mmHg, MAP 95, pulse 66.
                        if (first) it.pipe(bp.service, bp.measurement, bp.measurement).queueIncoming(byteArrayOf(0x04) + le16(124) + le16(81) + le16(95) + le16(66))
                    }
                }
                created += port
                return port
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val summaries = SummaryBuilder(db, SystemClock)
        val dm = DeviceManager(
            db, SystemClock, ports, DriverFactory { HealthSensorDriver(it, SystemClock) },
            HealthIngest(db, SystemClock, summaries), FakeFileStore(), scope,
        )
        try {
            runBlocking { dm.start() }
            MeTestSupport.waitFor("weight stored") { runBlocking { db.body.latest(BodyKind.WEIGHT) } != null }
            val w = runBlocking { db.body.latest(BodyKind.WEIGHT) }!!
            assertEquals(7250, w.valueX100)
            assertEquals(DataSource.SCALE, w.source)
            assertEquals(scale, w.deviceId)
            val range = TimeRange(SystemClock.nowSec() - 3600, SystemClock.nowSec() + 60)
            MeTestSupport.waitFor("blood pressure stored") { runBlocking { db.metrics.series(MetricKind.BP_DIASTOLIC, range) }.isNotEmpty() }
            assertEquals(listOf(124), runBlocking { db.metrics.series(MetricKind.BP_SYSTOLIC, range, cuff) }.map { it.value })
            assertEquals(listOf(81), runBlocking { db.metrics.series(MetricKind.BP_DIASTOLIC, range, cuff) }.map { it.value })
            assertEquals(listOf(66), runBlocking { db.metrics.series(MetricKind.HEART_RATE, range, cuff) }.map { it.value })
            MeTestSupport.waitFor("summary weight") { runBlocking { db.summaries.day(SystemClock.today()) }?.weightKgX100 == 7250 }
        } finally {
            runBlocking { dm.stop() }
            scope.cancel()
        }
    }
}
