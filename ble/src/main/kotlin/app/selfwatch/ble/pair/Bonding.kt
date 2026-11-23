/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ble.pair

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.selfwatch.ble.BOND_TIMEOUT_MS
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bluetooth bonding with a watch. Garmin watches may connect without a bond and ask for one only
 * when needed (an insufficient-authentication GATT error); the connection calls [bond] then.
 *
 * The bond-state receiver is registered with `RECEIVER_EXPORTED`: `ACTION_BOND_STATE_CHANGED` is
 * sent by the Bluetooth stack's app (the `bluetooth` uid), which a `RECEIVER_NOT_EXPORTED` receiver
 * does not hear on API 34+. It is a protected broadcast, so other apps cannot spoof it, and the
 * device address is checked.
 */
public class Bonding(context: Context) {
    private val appContext: Context = context.applicationContext

    private fun adapter(): BluetoothAdapter? = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    /** True if the device at [address] is bonded. False when unknown or without permission. */
    @SuppressLint("MissingPermission") // SecurityException caught
    public fun isBonded(address: String): Boolean = try {
        adapter()?.getRemoteDevice(address)?.bondState == BluetoothDevice.BOND_BONDED
    } catch (e: SecurityException) {
        SwLog.w(TAG, "bond state: permission missing", e)
        false
    }

    /**
     * Bonds with the device at [address] and waits until the bond exists, the user refuses, or
     * [timeoutMs] passes (the watch may show a passkey to confirm). Returns at once if already bonded.
     * Call from the main thread or any coroutine; the broadcast arrives on the main thread.
     */
    @SuppressLint("MissingPermission") // SecurityException caught
    public suspend fun bond(address: String, timeoutMs: Long = BOND_TIMEOUT_MS): Outcome<Unit> {
        val adapter = adapter() ?: return Outcome.Fail(SwError.Io("no Bluetooth adapter"))
        val result = CompletableDeferred<Outcome<Unit>>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (!device?.address.equals(address, ignoreCase = true)) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> result.complete(Outcome.Ok(Unit))
                    BluetoothDevice.BOND_NONE -> result.complete(Outcome.Fail(SwError.Protocol("bond refused or failed")))
                }
            }
        }
        return try {
            val device = adapter.getRemoteDevice(address)
            if (device.bondState == BluetoothDevice.BOND_BONDED) return Outcome.Ok(Unit)
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            try {
                when (device.bondState) {
                    BluetoothDevice.BOND_BONDED -> return Outcome.Ok(Unit) // bonded while registering
                    BluetoothDevice.BOND_BONDING -> Unit // already in progress: just wait
                    else -> if (!device.createBond()) return Outcome.Fail(SwError.Io("createBond refused"))
                }
                SwLog.i(TAG, "waiting for bond")
                withTimeoutOrNull(timeoutMs) { result.await() } ?: Outcome.Fail(SwError.Timeout("bond"))
            } finally {
                appContext.unregisterReceiver(receiver)
            }
        } catch (e: SecurityException) {
            SwLog.w(TAG, "bond: permission missing", e)
            Outcome.Fail(SwError.PermissionMissing(BLUETOOTH_CONNECT))
        } catch (e: IllegalArgumentException) {
            SwLog.w(TAG, "bond: bad address", e)
            Outcome.Fail(SwError.BadInput("bad Bluetooth address"))
        }
    }

    private companion object {
        const val TAG = "Bonding"
        const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    }
}
