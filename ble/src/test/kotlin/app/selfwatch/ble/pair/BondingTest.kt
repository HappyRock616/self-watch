/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.selfwatch.ble.pair

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Looper
import org.robolectric.RuntimeEnvironment
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BondingTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val address = "AA:BB:CC:DD:EE:10"
    private val device: BluetoothDevice =
        app.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(device).setBondState(BluetoothDevice.BOND_NONE)
        shadowOf(device).setCreatedBond(true)
    }

    private fun broadcast(state: Int, who: BluetoothDevice = device) {
        app.sendBroadcast(
            Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, who)
                .putExtra(BluetoothDevice.EXTRA_BOND_STATE, state),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun waitsForBonded() = runTest {
        val result = async { Bonding(app).bond(address) }
        runCurrent()
        broadcast(BluetoothDevice.BOND_BONDING)
        // another device's bond does not count
        val other = app.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice("AA:BB:CC:DD:EE:11")
        broadcast(BluetoothDevice.BOND_BONDED, other)
        runCurrent()
        assertTrue(!result.isCompleted)
        broadcast(BluetoothDevice.BOND_BONDED)
        runCurrent()
        assertEquals(Outcome.Ok(Unit), result.await())
    }

    @Test
    fun refusedBond() = runTest {
        val result = async { Bonding(app).bond(address) }
        runCurrent()
        broadcast(BluetoothDevice.BOND_NONE)
        runCurrent()
        assertTrue((result.await() as Outcome.Fail).error is SwError.Protocol)
    }

    @Test
    fun timesOut() = runTest {
        val result = async { Bonding(app).bond(address, timeoutMs = 60_000) }
        runCurrent()
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(Outcome.Fail(SwError.Timeout("bond")), result.await())
    }

    @Test
    fun alreadyBondedReturnsAtOnce() = runTest {
        shadowOf(device).setBondState(BluetoothDevice.BOND_BONDED)
        assertEquals(Outcome.Ok(Unit), Bonding(app).bond(address))
        assertTrue(Bonding(app).isBonded(address))
    }

    @Test
    fun createBondRefused() = runTest {
        shadowOf(device).setCreatedBond(false)
        assertTrue((Bonding(app).bond(address) as Outcome.Fail).error is SwError.Io)
    }
}
