/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.device

import app.selfwatch.db.Table
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.result.SwError
import app.selfwatch.model.time.TimeRange
import app.selfwatch.service.ServiceDbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchStatusTest : ServiceDbTest() {

    @Test
    fun everyStateRoundTrips() {
        val states = listOf(
            ConnectionState.Disconnected,
            ConnectionState.Connecting,
            ConnectionState.Handshaking,
            ConnectionState.Ready,
            ConnectionState.Syncing(3, 12, "monitor"),
            ConnectionState.Failed(SwError.Io("Bluetooth connection failed"), 30),
            ConnectionState.Failed(SwError.Io("no retry"), null),
        )
        for (s in states) assertEquals(s, WatchStatus.decode(WatchStatus.encode(s)))
    }

    @Test
    fun failuresKeepAShortReasonAndSeparatorsAreEscaped() {
        val encoded = WatchStatus.encode(ConnectionState.Failed(SwError.Timeout("Garmin handshake"), 5))
        assertEquals("failed|5|timed out: Garmin handshake", encoded)
        val label = WatchStatus.decode(WatchStatus.encode(ConnectionState.Syncing(1, 2, "a|b"))) as ConnectionState.Syncing
        assertEquals("a/b", label.label)
    }

    @Test
    fun offAndGarbageDecodeToNull() {
        assertNull(WatchStatus.decode(WatchStatus.OFF))
        assertNull(WatchStatus.decode(null))
        assertNull(WatchStatus.decode("syncing|x"))
        assertNull(WatchStatus.decode("what"))
    }

    @Test
    fun publisherWritesSettingsAndAnnouncesThem() = runBlocking {
        val id = addDevice()
        val info = device("AA:BB:CC:DD:EE:01").copy(id = id, batteryPct = 81)
        val p = WatchStatusPublisher(db.settings)
        var notices = 0
        val watcher = launch { db.changes.changes.collect { if (Table.SETTING in it) notices++ } }
        yield()
        p.publish(listOf(DeviceStatus(info, ConnectionState.Ready, null)))
        assertEquals("ready", db.settings.get(WatchStatus.stateKey(id)))
        assertEquals(81, db.settings.getInt(WatchStatus.batteryKey(id)))
        // Nothing changed: no write.
        p.publish(listOf(DeviceStatus(info, ConnectionState.Ready, null)))
        // The device left the list: off, battery kept.
        p.publish(emptyList())
        assertEquals(WatchStatus.OFF, db.settings.get(WatchStatus.stateKey(id)))
        assertEquals(81, db.settings.getInt(WatchStatus.batteryKey(id)))
        yield()
        assertEquals(2, notices)
        watcher.cancel()
    }

    @Test
    fun publisherKeepsTheBatteryHistoryOncePerChange() = runBlocking {
        val id = addDevice()
        val info = device("AA:BB:CC:DD:EE:01").copy(id = id, batteryPct = 81)
        var t = 1_000L
        val p = WatchStatusPublisher(db.settings) { dev, pct -> db.battery.add(dev, t++, pct) }
        p.publish(listOf(DeviceStatus(info, ConnectionState.Ready, null)))
        p.publish(listOf(DeviceStatus(info, ConnectionState.Syncing(1, 2, "x"), null)))
        p.publish(listOf(DeviceStatus(info.copy(batteryPct = 80), ConnectionState.Ready, null)))
        p.publish(listOf(DeviceStatus(info.copy(batteryPct = null), ConnectionState.Ready, null)))
        assertEquals(listOf(81, 80), db.battery.readings(TimeRange(0, 10_000), id).map { it.pct })
    }

    @Test
    fun markAllOffAfterPublishing() = runBlocking {
        val id = addDevice()
        val p = WatchStatusPublisher(db.settings)
        p.publish(listOf(DeviceStatus(device("AA:BB:CC:DD:EE:01").copy(id = id), ConnectionState.Connecting, null)))
        assertEquals("connecting", db.settings.observe(WatchStatus.stateKey(id)).first())
        p.markAllOff()
        assertEquals(WatchStatus.OFF, db.settings.get(WatchStatus.stateKey(id)))
    }
}
