/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.watch

import android.app.Application
import app.selfwatch.SelfWatchApp
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.garmin.GarminDriver
import app.selfwatch.model.ble.BlePort
import app.selfwatch.model.ble.BytePipe
import app.selfwatch.model.ble.PortState
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.device.SupportTier
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.service.relay.CallControl
import app.selfwatch.service.relay.FindPhoneAlert
import app.selfwatch.service.relay.MusicControlRouter
import app.selfwatch.service.relay.NotificationActionRouter
import app.selfwatch.service.web.WebRequestRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

private val WATCH = DeviceInfo(
    id = 3, kind = DeviceKind.GARMIN_WATCH, address = "AA:BB:CC:DD:EE:01", name = "vívoactive 5", model = "vívoactive 5",
    productId = null, firmware = null, serial = null, tier = SupportTier.VERIFIED, features = emptySet(), batteryPct = null,
)

/** The real app: `watchServiceDeps` gives the ble and garmin factories and leaves food and interop alone. */
@RunWith(RobolectricTestRunner::class)
class WatchServiceHostTest {

    @Test
    fun hostReturnsRealFactoriesWithoutBuildingFoodOrInterop() {
        val app = RuntimeEnvironment.getApplication() as SelfWatchApp
        assertFalse(app.isWatchWiringBuilt)
        val deps = app.watchServiceDeps()
        assertTrue(app.isWatchWiringBuilt)
        assertTrue(deps.portFactory is BlePortFactory)
        assertTrue(deps.driverFactory is GarminDriverFactory)
        assertTrue(deps.driverFactory.create(WATCH) is GarminDriver)
        // The relays (B19–B25) are wired.
        assertTrue(deps.handlers.findPhone is FindPhoneAlert)
        assertTrue(deps.handlers.music is MusicControlRouter)
        assertTrue(deps.handlers.call is CallControl)
        assertTrue(deps.handlers.notificationAction is NotificationActionRouter)
        assertTrue(deps.handlers.webRequest is WebRequestRouter)
        // The service's half never builds the food or interop graphs.
        if (app.isGraphBuilt) {
            assertFalse(app.graph.isFoodBuilt)
            assertFalse(app.graph.isInteropBuilt)
        }
    }
}

/** The `:watch` wiring on its own (a plain Application, so no app graph exists at all). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WatchWiringTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun wiringNeedsNoAppGraph() {
        val deps = WatchWiring(ctx).deps()
        assertTrue(deps.portFactory is BlePortFactory)
        assertTrue(deps.driverFactory.create(WATCH) is GarminDriver)
    }

    @Test
    fun bleCaptureDefaultsFollowTheBuildType() {
        assertTrue(WatchSettings.bleCaptureOn(null, debuggable = true))
        assertFalse(WatchSettings.bleCaptureOn(null, debuggable = false))
        assertFalse(WatchSettings.bleCaptureOn("false", debuggable = true))
        assertTrue(WatchSettings.bleCaptureOn("true", debuggable = false))
        assertEquals("garmin.first_connect.AA:BB:CC:DD:EE:01", WatchSettings.firstConnectKey("aa:bb:cc:dd:ee:01"))
    }

    @Test
    fun firstConnectFlagGoesIntoTheConnectionConfig() = runBlocking {
        val settings = SelfWatchDb.get(ctx).settings
        val key = WatchSettings.firstConnectKey(WATCH.address)
        val f = GarminDriverFactory(settings, scope, { null })
        settings.set(key, null)
        assertFalse(f.configFor(key).firstConnect)
        settings.setBool(key, true)
        assertTrue(f.configFor(key).firstConnect)
        settings.set(key, null)
    }

    private class FakeLink(address: String) : LinkHandle {
        val autos = ArrayList<Boolean>()
        var closed = false
        val stateFlow = MutableStateFlow(PortState.DISCONNECTED)
        override val port: BlePort = object : BlePort {
            override val address: String = address
            override val state: StateFlow<PortState> = stateFlow
            override suspend fun serviceUuids(): Set<UUID> = emptySet()
            override suspend fun openPipe(service: UUID, rx: UUID, tx: UUID): Outcome<BytePipe> = Outcome.Fail(SwError.Io("fake"))
            override suspend fun setHighSpeed(on: Boolean) {}
        }

        override suspend fun connect(auto: Boolean): Outcome<Unit> {
            autos += auto
            return Outcome.Ok(Unit)
        }

        override suspend fun close() {
            closed = true
        }
    }

    @Test
    fun directConnectFirstThenAutoAfterAFailedDirectTry() = runBlocking {
        val links = ArrayList<FakeLink>()
        val f = BlePortFactory(scope) { a -> FakeLink(a).also { links += it } }

        val p1 = f.create(WATCH.address, autoReconnect = true)
        yield()
        assertEquals(listOf(false), links[0].autos)
        f.release(p1) // never connected
        assertTrue(links[0].closed)
        assertTrue(f.usesAutoConnect(WATCH.address))

        val p2 = f.create(WATCH.address, autoReconnect = true)
        yield()
        assertEquals(listOf(true), links[1].autos)
        links[1].stateFlow.value = PortState.CONNECTED
        yield()
        assertFalse(f.usesAutoConnect(WATCH.address))
        f.release(p2)

        // After a link that worked, the next try is direct again; sync-only links are always direct.
        f.create(WATCH.address, autoReconnect = true)
        yield()
        assertEquals(listOf(false), links[2].autos)
        f.release(links[2].port)
        f.create(WATCH.address, autoReconnect = false)
        yield()
        assertEquals(listOf(false), links[3].autos)
    }
}
