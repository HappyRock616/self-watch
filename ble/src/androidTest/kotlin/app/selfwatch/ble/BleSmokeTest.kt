/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ble

import android.bluetooth.BluetoothManager
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.selfwatch.ble.log.BleLog
import app.selfwatch.ble.log.BleLogFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Smoke test on a real phone: the platform pieces the module relies on exist. It pairs nothing,
 * connects to nothing and changes no settings.
 */
@RunWith(AndroidJUnit4::class)
class BleSmokeTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun phoneHasBluetoothLeAndCompanionDeviceSetup() {
        val pm = context.packageManager
        assertTrue(pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
        assertTrue(pm.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP))
    }

    @Test
    fun companionDeviceManagerIsAvailable() {
        assertNotNull(context.getSystemService(CompanionDeviceManager::class.java))
    }

    @Test
    fun bluetoothManagerAndAdapterAreObtainable() {
        val manager = context.getSystemService(BluetoothManager::class.java)
        assertNotNull(manager)
        assertNotNull(manager.adapter)
    }

    @Test
    fun captureWritesAndReadsBackOnDevice() {
        val dir = File(context.cacheDir, "blelog-smoke").apply { deleteRecursively() }
        val log = BleLog(dir).apply { enabled = true }
        log.record(BleLogFormat.DIR_IN, 0, byteArrayOf(1, 2, 3))
        log.close()
        val records = BleLogFormat.decode(log.files().single().readBytes()).records
        assertEquals(listOf(listOf<Byte>(1, 2, 3)), records.map { it.bytes.toList() })
        dir.deleteRecursively()
    }
}
