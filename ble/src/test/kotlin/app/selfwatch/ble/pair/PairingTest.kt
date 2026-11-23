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
import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.os.Looper
import androidx.activity.result.ActivityResult
import org.robolectric.RuntimeEnvironment
import app.selfwatch.ble.GARMIN_MANUFACTURER_ID
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import kotlinx.coroutines.async
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
import org.robolectric.shadows.AssociationInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PairingTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val cdm: CompanionDeviceManager = app.getSystemService(CompanionDeviceManager::class.java)
    private val launched = mutableListOf<IntentSender>()
    private var nextResult = ActivityResult(Activity.RESULT_OK, null)
    private val launcher = IntentSenderLauncher { sender -> launched += sender; nextResult }
    private val sender: IntentSender =
        PendingIntent.getActivity(app, 0, Intent("x"), PendingIntent.FLAG_IMMUTABLE).intentSender

    private fun pairing(sdkInt: Int) = Pairing(app, launcher, cdm, Bonding(app), sdkInt)

    @Before
    fun grant() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
    }

    @Test
    fun garminPresetUsesTheManufacturerId() {
        assertEquals(0x0087, PairFilter.GARMIN.manufacturerId)
        assertEquals(GARMIN_MANUFACTURER_ID, PairFilter.GARMIN.manufacturerId)
        // every filter combination builds a valid request
        Pairing.buildRequest(PairFilter.GARMIN)
        Pairing.buildRequest(PairFilter(Regex("vívoactive.*|Forerunner.*"), GARMIN_MANUFACTURER_ID))
        Pairing.buildRequest(PairFilter(null, null))
        Pairing.buildRequest(PairFilter(null, null, listOf(java.util.UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"))))
    }

    @Test
    fun api33PendingThenCreated() = runTest {
        val p = pairing(35)
        val result = async { p.pair(PairFilter.GARMIN) }
        runCurrent()
        val cb = shadowOf(cdm).lastAssociationCallback
        cb.onAssociationPending(sender)
        runCurrent()
        assertEquals(listOf(sender), launched)
        assertTrue(!result.isCompleted) // waits for onAssociationCreated
        cb.onAssociationCreated(
            AssociationInfoBuilder.newBuilder().setId(7).setPackageName(app.packageName)
                .setDeviceMacAddress("aa:bb:cc:dd:ee:03").setDisplayName("vívoactive 5").build(),
        )
        runCurrent()
        assertEquals(Outcome.Ok(PairedDevice("AA:BB:CC:DD:EE:03", "vívoactive 5", 7)), result.await())
    }

    @Test
    fun api33CancelledPicker() = runTest {
        nextResult = ActivityResult(Activity.RESULT_CANCELED, null)
        val p = pairing(35)
        val result = async { p.pair(PairFilter.GARMIN) }
        runCurrent()
        shadowOf(cdm).lastAssociationCallback.onAssociationPending(sender)
        runCurrent()
        assertEquals(Outcome.Fail(SwError.Io("pairing cancelled")), result.await())
    }

    @Test
    fun failureCallback() = runTest {
        val p = pairing(35)
        val result = async { p.pair(PairFilter.GARMIN) }
        runCurrent()
        shadowOf(cdm).lastAssociationCallback.onFailure("discovery timeout")
        runCurrent()
        assertTrue((result.await() as Outcome.Fail).error is SwError.Io)
        assertTrue(launched.isEmpty())
    }

    @Test
    @Suppress("DEPRECATION")
    fun legacyDeviceFoundThenResultIntent() = runTest {
        val device = app.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice("AA:BB:CC:DD:EE:04")
        nextResult = ActivityResult(Activity.RESULT_OK, Intent().putExtra(CompanionDeviceManager.EXTRA_DEVICE, device))
        val p = pairing(30)
        val result = async { p.pair(PairFilter.GARMIN) }
        runCurrent()
        shadowOf(cdm).lastAssociationCallback.onDeviceFound(sender)
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("AA:BB:CC:DD:EE:04", (result.await() as Outcome.Ok).value.address)
        assertEquals(null, (result.await() as Outcome.Ok).value.associationId)
    }

    @Test
    fun legacyResultWithoutDeviceFails() {
        assertTrue(pairing(30).legacyDevice(Intent()) is Outcome.Fail)
        assertTrue(pairing(30).legacyDevice(null) is Outcome.Fail)
    }

    @Test
    fun missingConnectPermissionFailsBeforeThePicker() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertEquals(
            Outcome.Fail(SwError.PermissionMissing(Manifest.permission.BLUETOOTH_CONNECT)),
            pairing(35).pair(PairFilter.GARMIN),
        )
        // below API 31 there is no runtime permission to check
        val legacy = async { pairing(30).pair(PairFilter.GARMIN) }
        runCurrent()
        assertTrue(!legacy.isCompleted)
        legacy.cancel()
    }

    @Test
    fun associationsAndUnpairOnApi33Plus() {
        val info = AssociationInfoBuilder.newBuilder().setId(9).setPackageName(app.packageName)
            .setDeviceMacAddress("aa:bb:cc:dd:ee:05").setDisplayName("watch").build()
        shadowOf(cdm).addAssociation(info)
        val p = pairing(35)
        assertEquals(listOf(PairedDevice("AA:BB:CC:DD:EE:05", "watch", 9)), p.associations())
        val r = p.unpair(PairedDevice("AA:BB:CC:DD:EE:05", "watch", 9), removeBond = true)
        // removeBond is public only from API 36, so on 35 the bond stays and the user is told
        assertEquals(Outcome.Ok(UnpairResult(bondRemoved = false)), r)
        assertEquals(-1, shadowOf(cdm).lastRemoveBondAssociationId)
        assertEquals(emptyList<PairedDevice>(), p.associations())
    }

    @Test
    @Suppress("DEPRECATION")
    fun legacyDeviceNamePrefersTheScanRecordAndSurvivesNoPermission() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val device: BluetoothDevice =
            app.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice("AA:BB:CC:DD:EE:06")
        val r = pairing(30).legacyDevice(Intent().putExtra(CompanionDeviceManager.EXTRA_DEVICE, device))
        assertEquals("AA:BB:CC:DD:EE:06", (r as Outcome.Ok).value.address)
    }
}
