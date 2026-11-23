/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ble.gatt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import java.util.UUID

/**
 * Opens real GATT clients over LE transport. Throws [SecurityException] without `BLUETOOTH_CONNECT`
 * (API 31+); the connection catches it.
 */
internal class AndroidGattConnector(
    context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : GattConnector {
    private val appContext: Context = context.applicationContext

    @SuppressLint("MissingPermission") // caller catches SecurityException
    override fun connect(address: String, auto: Boolean, listener: GattListener): GattApi? {
        val manager = appContext.getSystemService(BluetoothManager::class.java) ?: return null
        val adapter = manager.adapter ?: return null
        val device: BluetoothDevice = adapter.getRemoteDevice(address)
        val callback = AndroidGattCallback(listener, sdkInt)
        val gatt = if (atLeast(37)) connect37(device, auto, callback) else connectLegacy(device, auto, callback)
        return gatt?.let { AndroidGattApi(it, sdkInt) }
    }

    @ChecksSdkIntAtLeast(parameter = 0)
    private fun atLeast(api: Int): Boolean = sdkInt >= api

    // API 37+: settings object. Callbacks run on the Binder thread (direct executor); the connection
    // hops onto its own dispatcher anyway. Automatic MTU stays off: the connection asks for 517 itself.
    @SuppressLint("MissingPermission", "NewApi") // caller catches SecurityException; called only on 37+
    private fun connect37(device: BluetoothDevice, auto: Boolean, callback: BluetoothGattCallback): BluetoothGatt? {
        val settings = BluetoothGattConnectionSettings.Builder()
            .setTransport(BluetoothDevice.TRANSPORT_LE)
            .setAutoConnectEnabled(auto)
            .build()
        return device.connectGatt(settings, { it.run() }, callback)
    }

    // API 26–36.
    @SuppressLint("MissingPermission") // caller catches SecurityException
    @Suppress("DEPRECATION")
    private fun connectLegacy(device: BluetoothDevice, auto: Boolean, callback: BluetoothGattCallback): BluetoothGatt? =
        device.connectGatt(appContext, auto, callback, BluetoothDevice.TRANSPORT_LE)
}

/**
 * [GattApi] over a real [BluetoothGatt]. Uses the API 33 calls that return a status code where they
 * exist and the older value-on-the-object calls below 33. [sdkInt] is injectable for tests.
 */
@SuppressLint("MissingPermission") // every caller catches SecurityException
internal class AndroidGattApi(
    private val gatt: BluetoothGatt,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : GattApi {

    @ChecksSdkIntAtLeast(parameter = 0)
    private fun atLeast(api: Int): Boolean = sdkInt >= api

    private fun characteristic(service: UUID, characteristic: UUID): BluetoothGattCharacteristic? =
        gatt.getService(service)?.getCharacteristic(characteristic)

    override fun discoverServices(): Boolean = gatt.discoverServices()

    override fun services(): Map<UUID, Set<UUID>> =
        gatt.services.associate { s -> s.uuid to s.characteristics.map { it.uuid }.toSet() }

    override fun properties(service: UUID, characteristic: UUID): Int =
        characteristic(service, characteristic)?.properties ?: -1

    override fun hasDescriptor(service: UUID, characteristic: UUID, descriptor: UUID): Boolean =
        characteristic(service, characteristic)?.getDescriptor(descriptor) != null

    override fun requestMtu(mtu: Int): Boolean = gatt.requestMtu(mtu)

    override fun setNotification(service: UUID, characteristic: UUID, enable: Boolean): Boolean {
        val ch = characteristic(service, characteristic) ?: return false
        return gatt.setCharacteristicNotification(ch, enable)
    }

    override fun writeDescriptor(service: UUID, characteristic: UUID, descriptor: UUID, value: ByteArray): Int {
        val d = characteristic(service, characteristic)?.getDescriptor(descriptor)
            ?: return GattApi.STATUS_NO_SUCH_ATTRIBUTE
        return if (atLeast(Build.VERSION_CODES.TIRAMISU)) {
            gatt.writeDescriptor(d, value)
        } else {
            legacyWriteDescriptor(d, value)
        }
    }

    @Suppress("DEPRECATION") // API 26–32 path
    private fun legacyWriteDescriptor(d: BluetoothGattDescriptor, value: ByteArray): Int {
        d.value = value
        return if (gatt.writeDescriptor(d)) GattApi.STATUS_OK else GattApi.STATUS_NOT_STARTED
    }

    override fun writeCharacteristic(service: UUID, characteristic: UUID, value: ByteArray, noResponse: Boolean): Int {
        val ch = characteristic(service, characteristic) ?: return GattApi.STATUS_NO_SUCH_ATTRIBUTE
        val type = writeType(noResponse)
        return if (atLeast(Build.VERSION_CODES.TIRAMISU)) {
            gatt.writeCharacteristic(ch, value, type)
        } else {
            legacyWriteCharacteristic(ch, value, type)
        }
    }

    @Suppress("DEPRECATION") // API 26–32 path
    private fun legacyWriteCharacteristic(ch: BluetoothGattCharacteristic, value: ByteArray, type: Int): Int {
        ch.writeType = type
        ch.value = value
        return if (gatt.writeCharacteristic(ch)) GattApi.STATUS_OK else GattApi.STATUS_NOT_STARTED
    }

    override fun readCharacteristic(service: UUID, characteristic: UUID): Boolean {
        val ch = characteristic(service, characteristic) ?: return false
        return gatt.readCharacteristic(ch)
    }

    override fun requestConnectionPriority(high: Boolean): Boolean = gatt.requestConnectionPriority(
        if (high) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED,
    )

    override fun disconnect() {
        gatt.disconnect()
    }

    override fun close() {
        gatt.close()
    }

    internal companion object {
        /** `WRITE_TYPE_DEFAULT` (with response) unless the pipe asked for no-response writes. */
        fun writeType(noResponse: Boolean): Int =
            if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    }
}

/**
 * Forwards framework callbacks to a [GattListener] as plain values. Both notification signatures
 * are overridden: API 33+ calls the one with a value (and, because it is overridden, never the old
 * one); below 33 only the old one is called and the value must be copied at once because the stack
 * reuses the characteristic object for the next notification.
 */
internal class AndroidGattCallback(
    private val listener: GattListener,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : BluetoothGattCallback() {

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        listener.onConnectionStateChange(status, newState)
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        listener.onServicesDiscovered(status)
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        listener.onMtuChanged(mtu, status)
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        val ch = descriptor.characteristic
        listener.onDescriptorWrite(ch.service.uuid, ch.uuid, descriptor.uuid, status)
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        listener.onCharacteristicWrite(characteristic.service.uuid, characteristic.uuid, status)
    }

    // API 33+
    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        listener.onCharacteristicRead(characteristic.service.uuid, characteristic.uuid, value.copyOf(), status)
    }

    // API 26–32
    @Deprecated("Called only below API 33")
    @Suppress("DEPRECATION")
    override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) return
        val value = characteristic.value?.copyOf() ?: ByteArray(0)
        listener.onCharacteristicRead(characteristic.service.uuid, characteristic.uuid, value, status)
    }

    // API 33+
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        listener.onCharacteristicChanged(characteristic.service.uuid, characteristic.uuid, value.copyOf())
    }

    // API 26–32
    @Deprecated("Called only below API 33")
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) return
        val value = characteristic.value?.copyOf() ?: ByteArray(0)
        listener.onCharacteristicChanged(characteristic.service.uuid, characteristic.uuid, value)
    }
}
