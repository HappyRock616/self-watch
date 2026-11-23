/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.device

import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.device.SupportTier
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.MusicState
import app.selfwatch.model.watch.NotificationCategory
import app.selfwatch.model.watch.PhoneNotification
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.service.notify.ServiceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Several devices: the primary watch gets notifications, sensors get nothing, status counts watches. */
class RelayTargetsTest {
    private fun status(id: Long, kind: DeviceKind, state: ConnectionState) = DeviceStatus(
        DeviceInfo(id, kind, "AA:00:00:00:00:0$id", "d$id", "m", null, null, null, SupportTier.UNVERIFIED, emptySet(), null),
        state,
        null,
    )

    private val notify = WatchCommand.Notify(
        PhoneNotification(1, "p", "App", "t", "x", 0, NotificationCategory.MESSAGE, false, emptyList()),
    )

    @Test
    fun primaryConnectedWatchGetsNotificationsCallsAndMusic() {
        val list = listOf(
            status(3, DeviceKind.HEART_RATE_SENSOR, ConnectionState.Ready),
            status(1, DeviceKind.GARMIN_WATCH, ConnectionState.Ready),
            status(2, DeviceKind.GARMIN_WATCH, ConnectionState.Syncing(1, 2, "x")),
        )
        assertEquals(listOf(1L), RelayTargets.of(list, notify))
        assertEquals(listOf(1L), RelayTargets.of(list, WatchCommand.IncomingCall("1", null)))
        assertEquals(listOf(1L), RelayTargets.of(list, WatchCommand.Music(MusicState(true, null, null, null, null, null, null))))
        // Time goes to every connected watch, never to a sensor.
        assertEquals(listOf(1L, 2L), RelayTargets.of(list, WatchCommand.SyncTime))
    }

    @Test
    fun secondWatchTakesOverWhileThePrimaryIsAway() {
        val list = listOf(
            status(1, DeviceKind.GARMIN_WATCH, ConnectionState.Failed(SwError.NotConnected(1), 30)),
            status(2, DeviceKind.GARMIN_WATCH, ConnectionState.Ready),
        )
        assertEquals(listOf(2L), RelayTargets.of(list, notify))
        assertTrue(RelayTargets.of(listOf(status(5, DeviceKind.WEIGHT_SCALE, ConnectionState.Ready)), notify).isEmpty())
        assertTrue(DeviceKinds.isWatch(DeviceKind.GARMIN_WATCH))
        DeviceKind.entries.filter { it != DeviceKind.GARMIN_WATCH }.forEach { assertTrue(!DeviceKinds.isWatch(it)) }
    }

    @Test
    fun serviceStatusFollowsWatchesNotSleepingSensors() {
        val list = listOf(
            status(1, DeviceKind.GARMIN_WATCH, ConnectionState.Ready),
            status(2, DeviceKind.WEIGHT_SCALE, ConnectionState.Failed(SwError.NotConnected(2), 300)),
        )
        assertEquals(ServiceStatus.Connected(null), ServiceStatus.of(list))
        // Only sensors: they count.
        assertEquals(ServiceStatus.OutOfRange, ServiceStatus.of(list.drop(1)))
    }
}
