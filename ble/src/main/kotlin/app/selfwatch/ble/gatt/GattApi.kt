/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ble.gatt

import java.util.UUID

/**
 * The few `BluetoothGatt` calls the connection needs, addressed by UUID so a fake can stand in for
 * the real stack in tests. Every call may throw [SecurityException] when `BLUETOOTH_CONNECT` is
 * missing; callers catch it. Calls returning an Int return [STATUS_OK] when the operation started.
 */
internal interface GattApi {
    /** Starts service discovery; the result arrives in [GattListener.onServicesDiscovered]. */
    fun discoverServices(): Boolean

    /** Discovered services and their characteristics. Empty before discovery. */
    fun services(): Map<UUID, Set<UUID>>

    /** Characteristic property bits (`BluetoothGattCharacteristic.PROPERTY_*`), or -1 if missing. */
    fun properties(service: UUID, characteristic: UUID): Int

    /** True if [characteristic] has a descriptor [descriptor]. */
    fun hasDescriptor(service: UUID, characteristic: UUID, descriptor: UUID): Boolean

    /** Asks for a larger MTU; the result arrives in [GattListener.onMtuChanged]. */
    fun requestMtu(mtu: Int): Boolean

    /** Turns local delivery of notifications for [characteristic] on or off (no radio traffic). */
    fun setNotification(service: UUID, characteristic: UUID, enable: Boolean): Boolean

    /** Writes a descriptor; completes in [GattListener.onDescriptorWrite]. */
    fun writeDescriptor(service: UUID, characteristic: UUID, descriptor: UUID, value: ByteArray): Int

    /** Writes a characteristic, with or without response; completes in [GattListener.onCharacteristicWrite]. */
    fun writeCharacteristic(service: UUID, characteristic: UUID, value: ByteArray, noResponse: Boolean): Int

    /** Reads a characteristic; completes in [GattListener.onCharacteristicRead]. */
    fun readCharacteristic(service: UUID, characteristic: UUID): Boolean

    /** High (fast, more power) or balanced connection priority. Has no callback. */
    fun requestConnectionPriority(high: Boolean): Boolean

    /** Starts a disconnect. */
    fun disconnect()

    /** Releases the GATT client. The object must not be used afterwards. */
    fun close()

    companion object {
        /** The operation started (same value as `BluetoothStatusCodes.SUCCESS` and `GATT_SUCCESS`). */
        const val STATUS_OK: Int = 0

        /** A legacy call returned false: the stack did not start the operation. */
        const val STATUS_NOT_STARTED: Int = -1

        /** The service or characteristic does not exist on this connection. */
        const val STATUS_NO_SUCH_ATTRIBUTE: Int = -2
    }
}

/**
 * GATT callbacks, already reduced to plain values. Called on Binder threads; the receiver must hop
 * onto its own dispatcher. Byte arrays are private copies the receiver may keep.
 */
internal interface GattListener {
    fun onConnectionStateChange(status: Int, newState: Int)
    fun onServicesDiscovered(status: Int)
    fun onMtuChanged(mtu: Int, status: Int)
    fun onDescriptorWrite(service: UUID, characteristic: UUID, descriptor: UUID, status: Int)
    fun onCharacteristicWrite(service: UUID, characteristic: UUID, status: Int)
    fun onCharacteristicRead(service: UUID, characteristic: UUID, value: ByteArray, status: Int)
    fun onCharacteristicChanged(service: UUID, characteristic: UUID, value: ByteArray)
}

/** Opens GATT clients. Returns null when the stack refused to start a connection. */
internal fun interface GattConnector {
    fun connect(address: String, auto: Boolean, listener: GattListener): GattApi?
}

/** GATT status and state numbers used by the connection (values from the Android SDK). */
internal object GattCodes {
    const val STATE_DISCONNECTED: Int = 0
    const val STATE_CONNECTED: Int = 2
    const val GATT_SUCCESS: Int = 0
    const val GATT_INSUFFICIENT_AUTHENTICATION: Int = 5
    const val GATT_INSUFFICIENT_ENCRYPTION: Int = 15
    /** Generic stack error, common on connect. */
    const val GATT_ERROR: Int = 133
    /** Link supervision timeout (out of range). */
    const val CONN_TIMEOUT: Int = 8
    /** The watch ended the link. */
    const val CONN_TERMINATE_PEER_USER: Int = 19
    /** The phone ended the link. */
    const val CONN_TERMINATE_LOCAL_HOST: Int = 22

    /** True for statuses that mean "the link needs a bond (or a new one)". */
    fun isAuthFailure(status: Int): Boolean =
        status == GATT_INSUFFICIENT_AUTHENTICATION || status == GATT_INSUFFICIENT_ENCRYPTION
}
