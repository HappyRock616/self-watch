/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.SelfWatchApp
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.dao.NetLogEntry
import app.selfwatch.food.install.DataManifest
import app.selfwatch.food.install.UpdateCheck
import app.selfwatch.model.net.NetFeature
import app.selfwatch.model.result.Outcome
import app.selfwatch.net.Firewall
import app.selfwatch.net.NetworkLog
import app.selfwatch.ui.SwTheme
import app.selfwatch.watch.WatchSettings
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.ZoneOffset

/** Privacy center, Network activity, Settings and Food data view models. */
@RunWith(RobolectricTestRunner::class)
class MeViewModelsTest {

    private lateinit var app: SelfWatchApp
    private val store = ViewModelStore()

    @Before
    fun setUp() {
        app = MeTestSupport.freshApp()
    }

    @After
    fun tearDown() {
        store.clear()
        MeTestSupport.resetDb()
    }

    private inline fun <reified T : ViewModel> make(noinline create: () -> T): T =
        ViewModelProvider(store, viewModelFactory { initializer { create() } })[T::class.java]

    private fun log(tSec: Long, feature: NetFeature, result: Int) = runBlocking {
        app.graph.db.netLog.insert(NetLogEntry(0, tSec, feature, "example.org", "GET", 10, 2_000, result))
    }

    @Test
    fun firewallSwitchesAreStoredAndShown() {
        val g = app.graph
        val held = listOf(HeldPermission(PermissionKind.CAMERA, false))
        val vm = make { PrivacyViewModel(Firewall(g.settings), flowOf(emptyList()), { held }) }
        MeTestSupport.waitFor("switches") { vm.state.value.switches.size == NetFeature.entries.size }
        assertFalse(vm.state.value.switches.getValue(NetFeature.WATCH_APP_WEB))
        assertTrue(vm.state.value.switches.getValue(NetFeature.FOOD_DATA))
        assertEquals(held, vm.state.value.permissions)

        vm.setAllowed(NetFeature.WATCH_APP_WEB, true)
        vm.setAllowed(NetFeature.FOOD_DATA, false)
        MeTestSupport.waitFor("toggled", { vm.state.value.toString() }) {
            vm.state.value.switches[NetFeature.WATCH_APP_WEB] == true && vm.state.value.switches[NetFeature.FOOD_DATA] == false
        }
        assertTrue(runBlocking { g.settings.netAllowed(NetFeature.WATCH_APP_WEB) })
        assertFalse(runBlocking { Firewall(g.settings).allowed(NetFeature.FOOD_DATA) })
    }

    @Test
    fun requestsTodayCountBlocked() {
        val now = System.currentTimeMillis() / 1000
        log(now, NetFeature.FOOD_DATA, 200)
        log(now, NetFeature.OPEN_FOOD_FACTS, NetLogEntry.BLOCKED)
        log(now - 3 * 86_400, NetFeature.FOOD_DATA, 200)
        val g = app.graph
        val vm = make { PrivacyViewModel(Firewall(g.settings), NetworkLog(g.db).today(), { emptyList() }) }
        MeTestSupport.waitFor("today", { vm.state.value.requests.toString() }) { vm.state.value.requests.total == 2 }
        assertEquals(RequestsToday(2, 1), vm.state.value.requests)
    }

    @Test
    fun networkLogGroupsByDayClearsAndExports() {
        val day = 86_400L
        val base = 1_780_000_000L - (1_780_000_000L % day) // midnight UTC
        log(base + 100, NetFeature.FOOD_DATA, 200)
        log(base + 200, NetFeature.OPEN_FOOD_FACTS, NetLogEntry.BLOCKED)
        log(base - day + 50, NetFeature.GPS_ASSIST, NetLogEntry.OFFLINE)
        val g = app.graph
        val vm = make { NetworkLogViewModel(NetworkLog(g.db), { g.db.netLog.prune(Long.MAX_VALUE) }, { ZoneOffset.UTC }) }
        MeTestSupport.waitFor("days", { vm.days.value.toString() }) { vm.days.value?.size == 2 }
        val days = requireNotNull(vm.days.value)
        assertEquals(listOf(2, 1), days.map { it.entries.size })
        assertTrue(days[0].date > days[1].date)
        assertEquals(2 * 2_010L, days[0].bytes)
        val csv = vm.csv().lines()
        assertEquals("time,feature,host,method,bytes_up,bytes_down,result", csv[0])
        assertTrue(csv[1].contains("gps_assist") && csv[1].endsWith("offline"))
        assertTrue(csv.any { it.endsWith(",blocked") })

        vm.clear()
        MeTestSupport.waitFor("cleared") { vm.days.value?.isEmpty() == true }
    }

    @Test
    fun settingsChangesAreStored() {
        val g = app.graph
        var reconnects = 0
        val vm = make { SettingsViewModel(g.settings, debuggable = false) { reconnects++ } }
        MeTestSupport.waitFor("loaded") { !vm.state.value.loading }
        val s0 = vm.state.value
        assertTrue(s0.alwaysConnected)
        assertFalse(s0.fakeAuth)
        assertFalse(s0.bleCapture)

        vm.setAlwaysConnected(false)
        vm.setSyncHours(6)
        vm.setFoodEnabled(false)
        vm.setUnits(SettingsViewModel.UNITS_IMPERIAL)
        vm.setTheme(SwTheme.NightMode.LIGHT)
        vm.setColorTheme(SwTheme.Theme.TURQUOISE)
        vm.setFakeAuth(true)
        vm.setBleCapture(true) // ignored: not a debug build
        MeTestSupport.waitFor("stored", { vm.state.value.toString() }) {
            val s = vm.state.value
            !s.alwaysConnected && s.syncHours == 6 && !s.foodEnabled && s.units == "imperial" &&
                s.appearance.night == SwTheme.NightMode.LIGHT && s.appearance.theme == SwTheme.Theme.TURQUOISE && s.fakeAuth
        }
        val st = g.settings
        assertEquals("sync_only", runBlocking { st.get(SettingKeys.CONNECTION_MODE) })
        assertEquals("#19D3C5", runBlocking { st.get(SettingKeys.ACCENT) })
        assertEquals("light", runBlocking { st.get(SettingKeys.THEME) })
        assertTrue(runBlocking { st.getBool(SettingKeys.GARMIN_FAKE_AUTH) })
        assertNull(runBlocking { st.get(WatchSettings.BLE_CAPTURE) })
        assertEquals(2, reconnects)
    }

    @Test
    fun foodDataCheckDownloadAndRemove() {
        val ops = FakeFoodData()
        val vm = make { FoodDataViewModel(ops) }
        MeTestSupport.waitFor("loaded") { !vm.state.value.loading }
        assertFalse(vm.state.value.installed)

        vm.check()
        MeTestSupport.waitFor("available") { vm.state.value.available != null }
        vm.download()
        MeTestSupport.waitFor("installed", { vm.state.value.toString() }) { vm.state.value.message is FoodDataMessage.Installed }
        assertEquals(20261122, vm.state.value.version)
        assertNull(vm.state.value.available)
        vm.messageShown()

        vm.check()
        MeTestSupport.waitFor("up to date") { vm.state.value.message == FoodDataMessage.UpToDate }
        vm.messageShown()

        vm.loadFrom { ByteArrayInputStream(byteArrayOf(1)) }
        MeTestSupport.waitFor("loaded from file") { vm.state.value.message is FoodDataMessage.Installed }
        assertEquals(1, ops.fromFile)
        vm.messageShown()

        vm.remove()
        MeTestSupport.waitFor("removed") { vm.state.value.message == FoodDataMessage.Removed }
        assertFalse(vm.state.value.installed)
    }

    private class FakeFoodData : FoodDataOps {
        var version: Int? = null
        var fromFile = 0
        private val manifest = DataManifest(1, 20261122, 1, "foods.db.gz", "https://example.org/foods.db.gz", "00", 1_000L, 4_000L, 10L, null)

        override suspend fun installedVersion(): Int? = version
        override suspend fun sizeBytes(): Long = if (version == null) 0 else 4_000
        override suspend fun check(): Outcome<UpdateCheck> =
            Outcome.Ok(if (version == manifest.dataVersion) UpdateCheck.UpToDate else UpdateCheck.Available(manifest))
        override suspend fun install(m: DataManifest, onProgress: (Long, Long?) -> Unit): Outcome<Int> {
            onProgress(500, 1_000)
            version = m.dataVersion
            return Outcome.Ok(m.dataVersion)
        }
        override suspend fun installFrom(input: InputStream): Outcome<Int> {
            fromFile++
            version = 20261201
            return Outcome.Ok(20261201)
        }
        override suspend fun remove() {
            version = null
        }
    }
}
