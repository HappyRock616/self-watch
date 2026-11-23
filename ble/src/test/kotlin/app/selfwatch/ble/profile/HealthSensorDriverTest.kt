/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.selfwatch.ble.profile

import android.bluetooth.BluetoothGattCharacteristic
import app.selfwatch.ble.conn.BleConnection
import app.selfwatch.ble.conn.BleTiming
import app.selfwatch.ble.conn.FakeEnv
import app.selfwatch.ble.conn.FakeGatt
import app.selfwatch.model.ble.PortState
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.device.Feature
import app.selfwatch.model.device.SupportTier
import app.selfwatch.model.fake.FakeBlePort
import app.selfwatch.model.fake.FakeClock
import app.selfwatch.model.fake.FakeFileStore
import app.selfwatch.model.fake.RecordingHealthSink
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Fake devices through the real connection: [FakeGatt] plays a scale, a strap and a cuff behind a
 * [BleConnection]; [HealthSensorDriver] connects, subscribes (indications or notifications), reads
 * the battery and stores readings in a [RecordingHealthSink].
 */
class HealthSensorDriverTest {
    private val zone: ZoneId = ZoneId.of("America/Detroit")
    private val clock = FakeClock(startSec = 1_795_435_200L, zone = zone) // 2026-11-23 09:00 local

    private fun info(kind: DeviceKind) =
        DeviceInfo(7, kind, "AA:BB:CC:DD:EE:07", "Sensor", "Sensor", null, null, null, SupportTier.UNVERIFIED, emptySet(), null)

    private fun TestScope.connection(gatt: FakeGatt): BleConnection =
        BleConnection("AA:BB:CC:DD:EE:07", null, gatt, FakeEnv(), StandardTestDispatcher(testScheduler), BleTiming())

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), (v shr 8).toByte())

    private fun time(t: LocalDateTime) =
        le16(t.year) + byteArrayOf(t.monthValue.toByte(), t.dayOfMonth.toByte(), t.hour.toByte(), t.minute.toByte(), t.second.toByte())

    @Test
    fun scaleConnectSubscribeAndStore() = runTest {
        val ws = HealthProfile.WEIGHT_SCALE
        val bc = HealthProfile.BODY_COMPOSITION
        val gatt = FakeGatt(now = { testScheduler.currentTime }).apply {
            services = mapOf(
                ws.service to setOf(ws.measurement),
                bc.service to setOf(bc.measurement),
                GattUuids.BATTERY_SERVICE to setOf(GattUuids.BATTERY_LEVEL),
            )
            rxProperties = BluetoothGattCharacteristic.PROPERTY_INDICATE
            readValues = mapOf(GattUuids.BATTERY_LEVEL to byteArrayOf(64))
        }
        val conn = connection(gatt)
        val sink = RecordingHealthSink()
        val driver = HealthSensorDriver(info(DeviceKind.WEIGHT_SCALE), clock)
        val events = ArrayList<WatchEvent>()
        backgroundScope.launch { driver.events.collect { events += it } }
        val run = launch { driver.run(conn, sink, FakeFileStore()) }
        runCurrent()
        assertEquals(Outcome.Ok(Unit), conn.connect(false))
        driver.state.first { it == ConnectionState.Ready }
        runCurrent()

        assertTrue(gatt.ops.contains("desc ${ws.measurement} 0200"))
        assertTrue(gatt.ops.contains("desc ${bc.measurement} 0200"))
        assertEquals(listOf<WatchEvent>(WatchEvent.Battery(64)), events)
        assertEquals(
            setOf(Feature.HEALTH_WEIGHT, Feature.HEALTH_BODY_COMPOSITION, Feature.BATTERY),
            driver.info.value!!.features,
        )

        // A stored reading with the scale's time stamp: 80.00 kg, BMI 24.7.
        val at = LocalDateTime.of(2026, 11, 23, 7, 45, 0)
        val weight = byteArrayOf(0x0A) + le16(16000) + time(at) + le16(247) + le16(1800)
        gatt.current.notify(ws.measurement, weight, ws.service)
        runCurrent()
        // Body composition right after: 20.0 % fat, 34.00 kg muscle, 44.00 kg water, no weight of its own.
        val body = le16(0x0020 or 0x0100) + le16(200) + le16(6800) + le16(8800)
        gatt.current.notify(bc.measurement, body, bc.service)
        runCurrent()
        // The scale sends the stored reading again: not stored twice.
        gatt.current.notify(ws.measurement, weight, ws.service)
        // Garbage never stops the connection.
        gatt.current.notify(ws.measurement, byteArrayOf(0x02, 1), ws.service)
        runCurrent()

        val batches = sink.batches()
        assertEquals(2, batches.size)
        val atSec = at.atZone(zone).toEpochSecond()
        val w = batches[0].body
        assertEquals(listOf(BodyKind.WEIGHT to 8000, BodyKind.BMI to 2470), w.map { it.kind to it.valueX100 })
        assertTrue(w.all { it.tSec == atSec && it.deviceId == 7L && it.source == DataSource.SCALE })
        assertEquals(HealthSensorDriver.KEY_TYPE_BASE + 0x2A9D, batches[0].key!!.fileType)
        val b = batches[1].body
        assertEquals(
            listOf(BodyKind.BODY_FAT to 2000, BodyKind.MUSCLE_MASS to 3400, BodyKind.BODY_WATER to 5500),
            b.map { it.kind to it.valueX100 },
        )
        assertTrue(b.all { it.tSec == clock.nowSec() && it.source == DataSource.SCALE })
        assertEquals(null, batches[1].key)

        gatt.current.drop(19)
        runCurrent()
        assertTrue(run.isCompleted)
        assertEquals(ConnectionState.Disconnected, driver.state.value)
        conn.close()
    }

    @Test
    fun heartRateStrapStoresSamplesInBatches() = runTest {
        val hr = HealthProfile.HEART_RATE
        val gatt = FakeGatt(now = { testScheduler.currentTime }).apply {
            services = mapOf(hr.service to setOf(hr.measurement))
            rxProperties = BluetoothGattCharacteristic.PROPERTY_NOTIFY
        }
        val conn = connection(gatt)
        val sink = RecordingHealthSink()
        val driver = HealthSensorDriver(info(DeviceKind.HEART_RATE_SENSOR), clock, hrFlushMs = 10_000)
        val run = launch { driver.run(conn, sink, FakeFileStore()) }
        runCurrent()
        conn.connect(false)
        driver.state.first { it == ConnectionState.Ready }
        runCurrent()
        assertTrue(gatt.ops.contains("desc ${hr.measurement} 0100"))
        assertEquals(setOf(Feature.HEALTH_HR), driver.info.value!!.features)

        for (bpm in listOf(70, 72, 0, 74)) { // 0 = no reading
            gatt.current.notify(hr.measurement, byteArrayOf(0x00, bpm.toByte()), hr.service)
            runCurrent()
            clock.advance(1)
        }
        gatt.current.notify(hr.measurement, byteArrayOf(0x04, 99), hr.service) // contact lost
        runCurrent()
        assertTrue(sink.batches().isEmpty())
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(listOf(70, 72, 74), sink.batches().single().metrics.map { it.value })
        assertTrue(sink.batches().single().metrics.all { it.kind == MetricKind.HEART_RATE && it.deviceId == 7L })

        gatt.current.notify(hr.measurement, byteArrayOf(0x00, 80), hr.service)
        runCurrent()
        gatt.current.drop(8)
        runCurrent()
        assertTrue(run.isCompleted)
        assertEquals(listOf(80), sink.batches().last().metrics.map { it.value }) // flushed on disconnect
        conn.close()
    }

    @Test
    fun bloodPressureCuffWithFakePort() = runTest {
        val bp = HealthProfile.BLOOD_PRESSURE
        val port = FakeBlePort(initialState = PortState.CONNECTED, services = setOf(bp.service))
        val sink = RecordingHealthSink()
        val driver = HealthSensorDriver(info(DeviceKind.BLOOD_PRESSURE_MONITOR), clock)
        val pipe = port.pipe(bp.service, bp.measurement, bp.measurement)
        // 118/76 mmHg, MAP 90, pulse 61, time stamp, then the same reading again.
        val at = LocalDateTime.of(2026, 11, 23, 8, 0, 0)
        val reading = byteArrayOf(0x06) + le16(118) + le16(76) + le16(90) + time(at) + le16(61)
        pipe.queueIncoming(reading)
        pipe.queueIncoming(reading)
        pipe.endIncoming()
        driver.run(port, sink, FakeFileStore())
        val m = sink.batches().single().metrics
        assertEquals(
            listOf(MetricKind.BP_SYSTOLIC to 118, MetricKind.BP_DIASTOLIC to 76, MetricKind.HEART_RATE to 61),
            m.map { it.kind to it.value },
        )
        assertTrue(m.all { it.tSec == at.atZone(zone).toEpochSecond() })
        assertEquals(Outcome.Fail(SwError.Unsupported(Feature.NOTIFICATIONS)), driver.send(WatchCommand.RemoveNotification(1)))
        assertTrue(driver.sync(app.selfwatch.model.device.SyncReason.USER) is Outcome.Fail)
    }

    @Test
    fun deviceWithoutAHealthServiceFails() = runTest {
        val port = FakeBlePort(initialState = PortState.CONNECTED, services = setOf(GattUuids.BATTERY_SERVICE))
        val driver = HealthSensorDriver(info(DeviceKind.WEIGHT_SCALE), clock)
        driver.run(port, RecordingHealthSink(), FakeFileStore())
        assertTrue(driver.state.value is ConnectionState.Failed)
    }
}
