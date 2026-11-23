/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.selfwatch.ble.conn

import android.bluetooth.BluetoothGattCharacteristic
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** One-off characteristic reads (battery level) and indications (scales, cuffs) on a [BleConnection]. */
class BleReadTest {
    private val battery: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val level: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    private fun TestScope.connection(gatt: FakeGatt): BleConnection =
        BleConnection("AA:BB:CC:DD:EE:01", null, gatt, FakeEnv(), StandardTestDispatcher(testScheduler), BleTiming())

    @Test
    fun readsACharacteristicThroughTheQueue() = runTest {
        val gatt = FakeGatt(now = { testScheduler.currentTime }).apply {
            services = mapOf(battery to setOf(level))
            readValues = mapOf(level to byteArrayOf(87))
        }
        val conn = connection(gatt)
        assertEquals(Outcome.Ok(Unit), conn.connect(false))
        assertTrue(conn.hasCharacteristic(battery, level))
        assertFalse(conn.hasCharacteristic(battery, UUID.randomUUID()))
        val r = conn.read(battery, level)
        assertArrayEquals(byteArrayOf(87), (r as Outcome.Ok).value)
        assertTrue(gatt.ops.contains("read $level"))
        conn.close()
    }

    @Test
    fun readFailsCleanly() = runTest {
        val gatt = FakeGatt(now = { testScheduler.currentTime }).apply {
            services = mapOf(battery to setOf(level))
            readValues = mapOf(level to byteArrayOf(1))
            readStatus = 3 // GATT_READ_NOT_PERMITTED
        }
        val conn = connection(gatt)
        assertTrue(conn.read(battery, level) is Outcome.Fail) // not connected yet
        conn.connect(false)
        assertEquals(Outcome.Fail(SwError.Protocol("read failed (GATT status 3)")), conn.read(battery, level))
        assertTrue(conn.read(battery, UUID.randomUUID()) is Outcome.Fail)
        conn.close()
    }

    @Test
    fun indicateOnlyCharacteristicsGetTheIndicationValue() = runTest {
        val gatt = FakeGatt(now = { testScheduler.currentTime }).apply {
            rxProperties = BluetoothGattCharacteristic.PROPERTY_INDICATE
        }
        val conn = connection(gatt)
        conn.connect(false)
        // A read-only measurement: the same characteristic is passed as rx and tx.
        assertTrue(conn.openPipe(Ids.SERVICE, Ids.RX, Ids.RX) is Outcome.Ok)
        assertTrue(gatt.ops.contains("desc ${Ids.RX} 0200"))
        conn.close()
    }
}
