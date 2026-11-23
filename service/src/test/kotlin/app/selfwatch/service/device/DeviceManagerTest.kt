/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.device

import app.selfwatch.db.SettingKeys
import app.selfwatch.model.ble.BlePort
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.SyncReason
import app.selfwatch.model.fake.FakeBlePort
import app.selfwatch.model.fake.FakeFileStore
import app.selfwatch.model.fake.FakeWatchDriver
import app.selfwatch.model.fake.RecordingHealthSink
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.CiqValue
import app.selfwatch.model.watch.FileStore
import app.selfwatch.model.watch.HealthSink
import app.selfwatch.model.watch.MusicAction
import app.selfwatch.model.watch.SyncReport
import app.selfwatch.model.watch.WatchApp
import app.selfwatch.model.watch.WatchAppKind
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchDriver
import app.selfwatch.model.watch.WatchEvent
import app.selfwatch.service.ServiceDbTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceManagerTest : ServiceDbTest() {

    private class RecordingPorts : PortFactory {
        val created: MutableList<Pair<String, Boolean>> = Collections.synchronizedList(ArrayList())
        val released: MutableList<BlePort> = Collections.synchronizedList(ArrayList())

        override fun create(address: String, autoReconnect: Boolean): BlePort {
            created += address to autoReconnect
            return FakeBlePort(address)
        }

        override suspend fun release(port: BlePort) {
            released += port
        }
    }

    private class RecordingScheduler : SyncScheduler {
        val scheduled = ArrayList<Int>()
        var cancels = 0

        override fun schedule(hours: Int) {
            scheduled += hours
        }

        override fun cancel() {
            cancels++
        }
    }

    /** A driver whose connection attempts fail at once (never reaching Ready); records when each started. */
    private class FailingDriver(private val now: () -> Long, private val hang: Boolean = false) : WatchDriver {
        val runTimes: MutableList<Long> = Collections.synchronizedList(ArrayList())
        override val info: StateFlow<DeviceInfo?> = MutableStateFlow(null)
        override val state: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected)
        override val events: Flow<WatchEvent> = emptyFlow()

        override suspend fun run(port: BlePort, sink: HealthSink, files: FileStore) {
            runTimes += now()
            if (hang) awaitCancellation()
        }

        override suspend fun sync(reason: SyncReason): Outcome<SyncReport> = Outcome.Fail(SwError.NotConnected(0))

        override suspend fun send(cmd: WatchCommand): Outcome<Unit> = Outcome.Fail(SwError.NotConnected(0))
    }

    private fun TestScope.manager(
        driver: WatchDriver,
        ports: PortFactory = RecordingPorts(),
        handlers: EventHandlers = EventHandlers(),
        scheduler: SyncScheduler = SyncScheduler.NONE,
        scope: CoroutineScope = backgroundScope,
    ) = DeviceManager(db, clock, ports, DriverFactory { driver }, RecordingHealthSink(), FakeFileStore(), scope, handlers, scheduler)

    @Test
    fun noDevicesMeansStopAndNoSchedule() = runTest {
        val scheduler = RecordingScheduler()
        val dm = manager(FakeWatchDriver(), scheduler = scheduler)
        assertEquals(StartResult.NO_DEVICES, dm.start())
        assertEquals(1, scheduler.cancels)
        assertTrue(dm.devices.value.isEmpty())
    }

    @Test
    fun failedConnectionsBackOffOneTwoFiveTenThirtySixtyThenFiveMinutes() = runTest {
        addDevice()
        val driver = FailingDriver({ testScheduler.currentTime })
        val ports = RecordingPorts()
        val dm = manager(driver, ports)
        assertEquals(StartResult.ALWAYS, dm.start())

        advanceTimeBy(708_001)
        runCurrent()

        // Gaps: 1, 2, 5, 10, 30, 60, 300, 300 s.
        assertEquals(listOf(0L, 1_000, 3_000, 8_000, 18_000, 48_000, 108_000, 408_000, 708_000), driver.runTimes.toList())
        assertTrue("background reconnect", ports.created.all { it.second })
        assertEquals("every link released", ports.created.size, ports.released.size)
        val st = dm.devices.value.single().state
        assertEquals(ConnectionState.Failed(SwError.NotConnected(dm.devices.value.single().info.id), 300), st)
        dm.stop()
    }

    @Test
    fun aConnectionThatReachedReadyResetsTheBackOff() = runTest {
        addDevice()
        val driver = FakeWatchDriver()
        val ports = RecordingPorts()
        val dm = manager(driver, ports)
        dm.start()
        runCurrent()
        assertEquals(1, driver.runCount)
        assertEquals(ConnectionState.Ready, dm.devices.value.single().state)

        repeat(3) {
            advanceTimeBy(60_000)
            driver.disconnect()
            runCurrent()
            assertTrue(dm.devices.value.single().state is ConnectionState.Failed)
            advanceTimeBy(1_001) // always the first back-off step, since each connection was Ready
            runCurrent()
            assertEquals(it + 2, driver.runCount)
        }
        assertEquals(3, ports.released.size)
        dm.stop()
        assertEquals(4, ports.released.size)
    }

    @Test
    fun syncOnlyCycleConnectsSyncsAndDisconnects() = runTest {
        val id = addDevice()
        runBlocking {
            db.settings.set(SettingKeys.CONNECTION_MODE, ConnectionMode.SYNC_ONLY.key)
            db.settings.setInt(SettingKeys.CONNECTION_SYNC_ONLY_HOURS, 6)
        }
        val driver = FakeWatchDriver()
        driver.syncResult = Outcome.Ok(SyncReport(filesNew = 3, filesFailed = 0, durationMs = 900))
        val ports = RecordingPorts()
        val scheduler = RecordingScheduler()
        val dm = manager(driver, ports, scheduler = scheduler)

        assertEquals(StartResult.SYNC_ONLY, dm.start())
        runCurrent()
        assertEquals(listOf(6), scheduler.scheduled)
        assertEquals("nothing stays connected", 0, driver.runCount)

        val result = dm.syncCycle(SyncReason.SCHEDULE)

        assertEquals(mapOf(id to driver.syncResult), result)
        assertEquals(listOf(SyncReason.SCHEDULE), driver.syncCalls.toList())
        assertEquals(1, driver.runCount)
        assertEquals(ConnectionState.Disconnected, driver.state.value)
        assertEquals(listOf("AA:BB:CC:DD:EE:01" to false), ports.created.toList())
        assertEquals(1, ports.released.size)
        assertEquals(clock.nowSec(), runBlocking { db.devices.lastSync(id) })
        assertEquals(clock.nowSec(), dm.devices.value.single().lastSyncSec)
    }

    @Test
    fun syncOnlyCycleGivesUpWhenTheWatchNeverAnswers() = runTest {
        val id = addDevice()
        runBlocking { db.settings.set(SettingKeys.CONNECTION_MODE, ConnectionMode.SYNC_ONLY.key) }
        val driver = FailingDriver({ testScheduler.currentTime }, hang = true)
        val ports = RecordingPorts()
        val dm = manager(driver, ports)
        dm.start()

        val result = dm.syncCycle(SyncReason.USER)

        assertEquals(mapOf(id to Outcome.Fail(SwError.Timeout("connect"))), result)
        assertEquals(DeviceManager.CONNECT_TIMEOUT_MS, testScheduler.currentTime)
        assertEquals(1, ports.released.size)
    }

    @Test
    fun eventsGoToTheirHandlersAndBatteryIsKept() = runTest {
        val id = addDevice()
        val seen = Collections.synchronizedList(ArrayList<Pair<Long, WatchEvent>>())
        val handlers = EventHandlers(
            findPhone = { from, e -> seen += from.deviceId to e },
            music = { from, e -> seen += from.deviceId to e },
            call = { from, e -> seen += from.deviceId to e },
            notificationAction = { _, _ -> error("a broken relay must not stop routing") },
            appMessage = { from, e -> seen += from.deviceId to e },
            webRequest = { from, e ->
                seen += from.deviceId to e
                from.send(WatchCommand.WebResponse(e.requestId, 200, byteArrayOf(1), "text/plain"))
            },
            watchApps = object : WatchAppsHandler {
                override suspend fun onInstalledApps(from: WatchLink, event: WatchEvent.InstalledApps) {
                    seen += from.deviceId to event
                }

                override suspend fun onAppSettings(from: WatchLink, event: WatchEvent.AppSettings) {
                    seen += from.deviceId to event
                }
            },
        )
        val driver = FakeWatchDriver()
        val dm = manager(driver, handlers = handlers)
        dm.start()
        runCurrent()

        val app = UUID.fromString("00000000-0000-0000-0000-000000000042")
        val events = listOf(
            WatchEvent.FindPhone(start = true),
            WatchEvent.MusicControl(MusicAction.NEXT),
            WatchEvent.CallAction(accept = false),
            WatchEvent.NotificationAction(7, "reply", "On my way"),
            WatchEvent.AppMessageReceived(app, CiqValue.Num(5)),
            WatchEvent.WebRequest(9, app, "https://example.org/", "GET", emptyMap(), null),
            WatchEvent.InstalledApps(listOf(WatchApp(app, "Just Go 5K", 3, WatchAppKind.APP))),
            WatchEvent.AppSettings(app, mapOf("goal" to CiqValue.Num(5))),
            WatchEvent.Battery(64),
            WatchEvent.SecurePairingRequired,
        )
        for (e in events) assertTrue(driver.emit(e))
        runCurrent()

        val expected = events.filter {
            it !is WatchEvent.NotificationAction && it !is WatchEvent.Battery && it != WatchEvent.SecurePairingRequired
        }.map { id to it }
        assertEquals(expected, seen.toList())
        assertEquals(listOf<WatchCommand>(WatchCommand.WebResponse(9, 200, byteArrayOf(1), "text/plain")), driver.sent.toList())
        assertEquals(64, dm.devices.value.single().info.batteryPct)
        dm.stop()
    }

    @Test
    fun syncNowAndSendUseTheConnectedDriver() = runTest {
        val id = addDevice()
        val driver = FakeWatchDriver()
        val dm = manager(driver)
        dm.start()
        runCurrent()

        assertEquals(driver.syncResult, dm.syncNow(id))
        assertEquals(listOf(SyncReason.USER), driver.syncCalls.toList())
        assertEquals(Outcome.Ok(Unit), dm.send(id, WatchCommand.SyncTime))
        assertEquals(Outcome.Fail(SwError.NotConnected(id + 1)), dm.send(id + 1, WatchCommand.SyncTime))
        dm.stop()
    }

    @Test
    fun driverSyncsAreRecordedWhenTheyFinish() = runTest {
        val id = addDevice()
        val driver = FakeWatchDriver()
        val dm = manager(driver)
        dm.start()
        runCurrent()
        driver.mutableState.value = ConnectionState.Syncing(1, 4, "activity")
        runCurrent()
        assertEquals(ConnectionState.Syncing(1, 4, "activity"), dm.devices.value.single().state)
        driver.mutableState.value = ConnectionState.Ready
        runCurrent()
        assertEquals(clock.nowSec(), dm.devices.value.single().lastSyncSec)
        // The database write runs on the IO dispatcher; wait for it in real time.
        var stored: Long? = null
        for (i in 0 until 100) {
            runCurrent()
            stored = runBlocking { db.devices.lastSync(id) }
            if (stored != null) break
            Thread.sleep(20)
        }
        assertEquals(clock.nowSec(), stored)
        dm.stop()
    }
}
