/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.notify

import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.device.SupportTier
import app.selfwatch.model.result.SwError
import app.selfwatch.service.device.DeviceStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ServiceStatusTest {
    private val ctx = RuntimeEnvironment.getApplication()

    private fun status(id: Long, state: ConnectionState, last: Long? = null) = DeviceStatus(
        DeviceInfo(id, DeviceKind.GARMIN_WATCH, "AA:00:00:00:00:0$id", "w$id", "m", null, null, null, SupportTier.UNVERIFIED, emptySet(), null),
        state,
        last,
    )

    @Test
    fun theMostRelevantStateWins() {
        assertEquals(ServiceStatus.NoWatch, ServiceStatus.of(emptyList()))
        val failed = ConnectionState.Failed(SwError.NotConnected(1), 30)
        assertEquals(ServiceStatus.OutOfRange, ServiceStatus.of(listOf(status(1, failed))))
        assertEquals(ServiceStatus.Connecting, ServiceStatus.of(listOf(status(1, failed), status(2, ConnectionState.Handshaking))))
        assertEquals(ServiceStatus.Connected(500), ServiceStatus.of(listOf(status(1, ConnectionState.Ready, 500), status(2, ConnectionState.Connecting))))
        assertEquals(
            ServiceStatus.Syncing(3, 7),
            ServiceStatus.of(listOf(status(1, ConnectionState.Ready, 500), status(2, ConnectionState.Syncing(3, 7, "sleep")))),
        )
    }

    @Test
    fun texts() {
        val now = 100_000L
        assertEquals("Connected · synced 2 min ago", ServiceNotifications.text(ctx, ServiceStatus.Connected(now - 130), now))
        assertEquals("Connected · synced just now", ServiceNotifications.text(ctx, ServiceStatus.Connected(now - 20), now))
        assertEquals("Connected · synced 3 hours ago", ServiceNotifications.text(ctx, ServiceStatus.Connected(now - 3 * 3600), now))
        assertEquals("Connected · synced 1 day ago", ServiceNotifications.text(ctx, ServiceStatus.Connected(now - 90_000), now))
        assertEquals("Connected", ServiceNotifications.text(ctx, ServiceStatus.Connected(null), now))
        assertEquals("Syncing 3 of 7", ServiceNotifications.text(ctx, ServiceStatus.Syncing(3, 7), now))
        assertEquals("Watch out of range", ServiceNotifications.text(ctx, ServiceStatus.OutOfRange, now))
    }
}
