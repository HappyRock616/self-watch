/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.selfwatch.devices

import android.app.Application
import app.selfwatch.ServiceClient
import app.selfwatch.ble.pair.PairedDevice
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.dao.DataCounts
import app.selfwatch.garmin.models.GarminModels
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.device.SupportTier
import app.selfwatch.model.result.SwError
import app.selfwatch.model.result.valueOrNull
import app.selfwatch.service.device.WatchStatus
import app.selfwatch.watch.WatchSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DevicesViewModelTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()

    private class FakeSource : DevicesSource {
        override val watch = MutableStateFlow<WatchCard?>(null)
        override val counts = MutableStateFlow(DataCounts(0, 0, 0, 0, 0, 0, null))
        override val bleCapture = MutableStateFlow(true)
    }

    private class FakeActions : DeviceActions {
        var starts = 0
        var syncs = 0
        val forgotten = ArrayList<WatchCard>()
        override suspend fun savePaired(paired: PairedDevice): Long = 1
        override fun startService(): Boolean {
            starts++
            return true
        }
        override fun syncNow(): Boolean {
            syncs++
            return true
        }
        override suspend fun forget(watch: WatchCard) {
            forgotten += watch
        }
        override suspend fun setBleCapture(on: Boolean) {}
    }

    private val card = WatchCard(
        id = 1, address = "AA:BB:CC:DD:EE:01", name = "vívoactive 5", model = "vívoactive 5", tier = SupportTier.VERIFIED,
        associationId = 7, state = ConnectionState.Ready, lastSyncSec = 1_795_435_200, batteryPct = 81,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun unpairedShowsNoWatchAndDoesNotStartTheService() = runTest {
        val source = FakeSource()
        val actions = FakeActions()
        val vm = DevicesViewModel(source, actions)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        val s = vm.state.value
        assertFalse(s.loading)
        assertNull(s.watch)
        assertTrue(s.bleCapture)
        assertEquals(0, actions.starts)
        assertEquals("Not connected", DevicesFragment.statusText(ctx, null))
    }

    @Test
    fun pairedAndConnectedStartsTheServiceAndShowsReady() = runTest {
        val source = FakeSource().apply { watch.value = card }
        val actions = FakeActions()
        val vm = DevicesViewModel(source, actions)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertEquals(1, actions.starts)
        assertEquals(ConnectionState.Ready, vm.state.value.watch?.state)
        assertEquals(81, vm.state.value.watch?.batteryPct)
        assertEquals("Connected", DevicesFragment.statusText(ctx, vm.state.value.watch?.state))
        source.watch.value = card.copy(state = ConnectionState.Syncing(2, 9, "monitor"))
        assertEquals("Syncing 2 of 9 (monitor)", DevicesFragment.statusText(ctx, vm.state.value.watch?.state))
        source.counts.value = DataCounts(600, 4_200, 900, 380, 1, 12, 1_795_435_300)
        assertEquals(600L, vm.state.value.counts?.activityMinutes)
        assertTrue(vm.syncNow())
        assertEquals(1, actions.syncs)
    }

    @Test
    fun failedConnectionShowsTheReasonAndRetry() = runTest {
        val failed = ConnectionState.Failed(SwError.Timeout("Garmin handshake"), 30)
        val source = FakeSource().apply { watch.value = card.copy(state = failed) }
        val vm = DevicesViewModel(source, FakeActions())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertEquals(failed, vm.state.value.watch?.state)
        assertEquals(
            "Connection failed: timed out: Garmin handshake. Trying again in 30 s",
            DevicesFragment.statusText(ctx, vm.state.value.watch?.state),
        )
        // As it arrives from the service process (through the settings text).
        val published = WatchStatus.decode(WatchStatus.encode(failed))
        assertEquals(
            "Connection failed: timed out: Garmin handshake. Trying again in 30 s",
            DevicesFragment.statusText(ctx, published),
        )
    }

    @Test
    fun busyAndForget() = runTest {
        val source = FakeSource().apply { watch.value = card }
        val actions = FakeActions()
        val vm = DevicesViewModel(source, actions)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.setBusy(true)
        assertTrue(vm.state.value.busy)
        vm.forget(card)
        assertEquals(listOf(card), actions.forgotten)
    }

    @Test
    fun databaseSourceFollowsWhatTheServicePublishes() = runBlocking {
        val db = SelfWatchDb.get(ctx)
        val raw = db.writableDatabase
        for (t in listOf("setting", "sync_file", "device")) raw.execSQL("DELETE FROM $t")
        val models = GarminModels.load().valueOrNull()
        val actions = AppDeviceActions(ctx, db, ServiceClient(ctx), { models })
        val source = DbDevicesSource(db, { models }, debuggable = true)
        assertNull(source.watch.first())

        val id = actions.savePaired(PairedDevice("aa:bb:cc:dd:ee:01", "vívoactive 5", 12))
        assertTrue(db.settings.getBool(WatchSettings.firstConnectKey("AA:BB:CC:DD:EE:01")))
        assertEquals(12L, db.devices.associationId(id))
        val fresh = source.watch.first()
        assertEquals("AA:BB:CC:DD:EE:01", fresh?.address)
        assertEquals(SupportTier.VERIFIED, fresh?.tier)
        assertNull("service not running yet", fresh?.state)

        // What SyncService's WatchStatusPublisher writes in the :watch process.
        db.settings.setAll(mapOf(WatchStatus.stateKey(id) to "ready", WatchStatus.batteryKey(id) to "64"))
        db.devices.setLastSync(id, 1_795_435_200)
        val live = withTimeout(5_000) { source.watch.first { it?.state == ConnectionState.Ready } }
        assertEquals(64, live?.batteryPct)
        assertEquals(1_795_435_200L, live?.lastSyncSec)
        assertTrue(source.bleCapture.first())

        actions.forget(live ?: error("no watch"))
        assertNull(source.watch.first())
        assertFalse(db.settings.getBool(WatchSettings.firstConnectKey("AA:BB:CC:DD:EE:01")))
    }

    @Test
    fun pairedDeviceGetsModelAndTierByName() {
        val models = GarminModels.load().valueOrNull()
        val known = AppDeviceActions.deviceInfoFor(PairedDevice("aa:bb:cc:dd:ee:01", "vívoactive 5", null), models)
        assertEquals(SupportTier.VERIFIED, known.tier)
        assertEquals("AA:BB:CC:DD:EE:01", known.address)
        val unknown = AppDeviceActions.deviceInfoFor(PairedDevice("AA:BB:CC:DD:EE:02", null, null), models)
        assertEquals(AppDeviceActions.UNNAMED, unknown.name)
        assertEquals(SupportTier.UNVERIFIED, unknown.tier)
    }
}
