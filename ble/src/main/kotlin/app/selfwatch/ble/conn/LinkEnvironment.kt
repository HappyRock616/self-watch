/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ble.conn

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.selfwatch.ble.pair.Bonding
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What a connection needs to know about the phone outside GATT: whether Bluetooth is on, whether the
 * watch is bonded, and when the bond is lost. A fake replaces it in tests.
 */
internal interface LinkEnvironment {
    /** True while the Bluetooth adapter is on. */
    val adapterOn: StateFlow<Boolean>

    /** Emits when the bond with this watch is lost. */
    val bondLost: SharedFlow<Unit>

    /** True if the watch is bonded. May throw [SecurityException]. */
    fun isBonded(): Boolean

    /** Bonds with the watch, waiting for the user to confirm. */
    suspend fun bond(): Outcome<Unit>

    /** Starts listening for system broadcasts. */
    fun start()

    /** Stops listening. */
    fun stop()
}

/**
 * [LinkEnvironment] from system broadcasts.
 *
 * Receivers are registered with `RECEIVER_EXPORTED` on purpose: `ACTION_BOND_STATE_CHANGED` and
 * `ACTION_KEY_MISSING` are sent by the Bluetooth stack's own app (the `bluetooth` uid, not `system`),
 * and a `RECEIVER_NOT_EXPORTED` receiver only accepts broadcasts from our own uid and `system`, so it
 * would never hear them. All three actions are protected broadcasts that only the platform may send,
 * so exporting does not let other apps spoof them; the device address is checked as well.
 */
internal class AndroidLinkEnvironment(
    context: Context,
    private val address: String,
) : LinkEnvironment {
    private val appContext: Context = context.applicationContext
    private val adapter: BluetoothAdapter? = appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val bonding = Bonding(appContext)

    private val _adapterOn = MutableStateFlow(adapter?.isEnabled == true)
    override val adapterOn: StateFlow<Boolean> get() = _adapterOn

    private val _bondLost = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val bondLost: SharedFlow<Unit> get() = _bondLost

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    _adapterOn.value = state == BluetoothAdapter.STATE_ON
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    if (!isOurDevice(intent)) return
                    val now = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    val before = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                    if (before == BluetoothDevice.BOND_BONDED && now == BluetoothDevice.BOND_NONE) {
                        SwLog.w(TAG, "bond removed")
                        _bondLost.tryEmit(Unit)
                    }
                }
                ACTION_KEY_MISSING -> {
                    if (!isOurDevice(intent)) return
                    SwLog.w(TAG, "link key missing")
                    _bondLost.tryEmit(Unit)
                }
            }
        }
    }

    private fun isOurDevice(intent: Intent): Boolean {
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        return device?.address.equals(address, ignoreCase = true)
    }

    @SuppressLint("MissingPermission") // callers catch SecurityException
    override fun isBonded(): Boolean =
        adapter?.getRemoteDevice(address)?.bondState == BluetoothDevice.BOND_BONDED

    override suspend fun bond(): Outcome<Unit> = bonding.bond(address)

    override fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 36) addAction(ACTION_KEY_MISSING)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
        _adapterOn.value = adapter?.isEnabled == true
    }

    override fun stop() {
        if (!registered) return
        registered = false
        try {
            appContext.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            SwLog.w(TAG, "receiver already gone", e)
        }
    }

    private companion object {
        const val TAG = "BleEnv"

        /** `BluetoothDevice.ACTION_KEY_MISSING` (API 36), spelled out so it compiles against any SDK. */
        const val ACTION_KEY_MISSING = "android.bluetooth.device.action.KEY_MISSING"
    }
}
