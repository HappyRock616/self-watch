/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ble.gatt

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.bluetooth.BluetoothManager
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowBluetoothGatt
import java.util.UUID

/**
 * The API branches of [AndroidGattApi] and [AndroidGattCallback], run on Robolectric's SDK 35 with
 * the branch chosen by the injected sdkInt (the deprecated calls still work there).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidGattApiTest {
    private val serviceId = UUID.fromString("6a4e2800-667b-11e3-949a-0800200c9a66")
    private val txId = UUID.fromString("6a4e2820-667b-11e3-949a-0800200c9a66")
    private lateinit var gatt: BluetoothGatt
    private lateinit var tx: BluetoothGattCharacteristic
    private val events = mutableListOf<String>()

    private val listener = object : GattListener {
        override fun onConnectionStateChange(status: Int, newState: Int) { events += "state $status $newState" }
        override fun onServicesDiscovered(status: Int) { events += "discovered $status" }
        override fun onMtuChanged(mtu: Int, status: Int) { events += "mtu $mtu" }
        override fun onDescriptorWrite(service: UUID, characteristic: UUID, descriptor: UUID, status: Int) { events += "desc $status" }
        override fun onCharacteristicWrite(service: UUID, characteristic: UUID, status: Int) { events += "write $status" }
        override fun onCharacteristicRead(service: UUID, characteristic: UUID, value: ByteArray, status: Int) { events += "read" }
        override fun onCharacteristicChanged(service: UUID, characteristic: UUID, value: ByteArray) {
            events += "changed $characteristic ${value.joinToString(",")}"
        }
    }

    @Before
    fun setUp() {
        val device = RuntimeEnvironment.getApplication().getSystemService(BluetoothManager::class.java)
            .adapter.getRemoteDevice("AA:BB:CC:DD:EE:02")
        gatt = ShadowBluetoothGatt.newInstance(device)
        val service = BluetoothGattService(serviceId, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        tx = BluetoothGattCharacteristic(
            txId,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        service.addCharacteristic(tx)
        shadowOf(gatt).setGattCallback(AndroidGattCallback(listener, 35))
        shadowOf(gatt).addDiscoverableService(service)
        gatt.discoverServices()
    }

    @Test
    fun writeTypeConstants() {
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, AndroidGattApi.writeType(noResponse = false))
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, AndroidGattApi.writeType(noResponse = true))
    }

    @Test
    fun api33WritePassesValueAndTypeWithoutTouchingTheCharacteristic() {
        val api = AndroidGattApi(gatt, sdkInt = 33)
        assertEquals(mapOf(serviceId to setOf(txId)), api.services())
        val initialType = tx.writeType // no-response: the constructor picks it when supported
        val status = api.writeCharacteristic(serviceId, txId, byteArrayOf(1, 2, 3), noResponse = false)
        assertEquals(GattApi.STATUS_OK, status)
        assertArrayEquals(byteArrayOf(1, 2, 3), shadowOf(gatt).latestWrittenBytes)
        // the API 33 call carries the type as an argument; the shared object is not touched
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, initialType)
        assertEquals(initialType, tx.writeType)
    }

    @Test
    @Suppress("DEPRECATION")
    fun legacyWriteSetsValueAndTypeOnTheCharacteristic() {
        val api = AndroidGattApi(gatt, sdkInt = 32)
        val status = api.writeCharacteristic(serviceId, txId, byteArrayOf(4, 5), noResponse = false)
        assertEquals(GattApi.STATUS_OK, status)
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, tx.writeType)
        assertArrayEquals(byteArrayOf(4, 5), tx.value)
        assertArrayEquals(byteArrayOf(4, 5), shadowOf(gatt).latestWrittenBytes)
        api.writeCharacteristic(serviceId, txId, byteArrayOf(6), noResponse = true)
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, tx.writeType)
        assertArrayEquals(byteArrayOf(6), tx.value)
    }

    @Test
    fun missingCharacteristicIsReportedNotThrown() {
        val api = AndroidGattApi(gatt, sdkInt = 33)
        assertEquals(GattApi.STATUS_NO_SUCH_ATTRIBUTE, api.writeCharacteristic(serviceId, UUID(1, 2), byteArrayOf(1), false))
        assertEquals(-1, api.properties(serviceId, UUID(1, 2)))
    }

    @Test
    @Suppress("DEPRECATION")
    fun api33NotificationsUseTheValueSignatureOnly() {
        val cb = AndroidGattCallback(listener, sdkInt = 33)
        events.clear()
        val value = byteArrayOf(7, 8)
        cb.onCharacteristicChanged(gatt, tx, value)
        value[0] = 0 // the listener got its own copy
        cb.onCharacteristicChanged(gatt, tx) // the old signature is ignored on 33+ (no duplicate)
        assertEquals(listOf("changed $txId 7,8"), events)
    }

    @Test
    @Suppress("DEPRECATION")
    fun legacyNotificationsCopyTheValueAtOnce() {
        val cb = AndroidGattCallback(listener, sdkInt = 30)
        events.clear()
        tx.value = byteArrayOf(9, 9)
        cb.onCharacteristicChanged(gatt, tx)
        tx.value = byteArrayOf(1) // the stack reuses the object for the next notification
        cb.onCharacteristicChanged(gatt, tx)
        assertEquals(listOf("changed $txId 9,9", "changed $txId 1"), events)
    }
}
