/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service

import android.Manifest
import android.app.NotificationManager
import android.companion.CompanionDeviceManager
import android.os.Build
import android.os.Looper
import androidx.core.app.NotificationCompat
import app.selfwatch.model.time.Day
import app.selfwatch.service.notify.ServiceNotifications
import app.selfwatch.service.notify.ServiceStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/** Start rules per API level (COMPATIBILITY §2 rows 26, 31, 34, 35). */
@Config(sdk = [26, 31, 34, 35])
class SyncServiceTest : ServiceDbTest() {

    private fun start(action: String): ServiceController<SyncService> =
        Robolectric.buildService(SyncService::class.java, SyncCommands.intent(app, action)).create().startCommand(0, 1)

    private fun grantBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
    }

    /** Runs the main looper until [done] or 5 s pass (database work hops to the IO dispatcher). */
    private fun idleUntil(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!done() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun missingBluetoothPermissionStopsCleanlyFromApi31() {
        val c = start(SyncCommands.ACTION_START)
        val shadow = shadowOf(c.get())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertNull("never foreground without BLUETOOTH_CONNECT or an association", shadow.lastForegroundNotification)
            assertTrue(shadow.isStoppedBySelf)
            assertFalse(ForegroundRules.canStartForeground(app))
        } else {
            // API 26–30: install-time Bluetooth permissions are enough.
            assertNotNull(shadow.lastForegroundNotification)
        }
        c.destroy()
    }

    @Test
    fun withPermissionItGoesForegroundSilentlyThenStopsWithoutAWatch() {
        grantBluetooth()
        val c = start(SyncCommands.ACTION_START)
        val shadow = shadowOf(c.get())
        val n = shadow.lastForegroundNotification
        assertNotNull(n)
        assertEquals(ServiceNotifications.ID_CONNECTION, shadow.lastForegroundNotificationId)
        assertEquals(ServiceNotifications.CHANNEL_CONNECTION, n.channelId)
        assertEquals(1, n.actions.size)
        assertEquals("Sync now", n.actions[0].title.toString())
        assertTrue((n.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0)

        idleUntil { shadow.isStoppedBySelf }
        assertTrue("no paired watch: the service stops", shadow.isStoppedBySelf)
        c.destroy()
    }

    @Test
    fun aCompanionAssociationIsEnoughWithoutThePermission() {
        val cdm = app.getSystemService(CompanionDeviceManager::class.java)
        shadowOf(cdm).addAssociation("AA:BB:CC:DD:EE:01")
        assertTrue(ForegroundRules.hasAssociation(app))
        val c = start(SyncCommands.ACTION_START)
        assertNotNull(shadowOf(c.get()).lastForegroundNotification)
        c.destroy()
    }

    @Test
    fun channelsHaveTheirImportance() {
        ServiceNotifications.createChannels(app)
        val nm = app.getSystemService(NotificationManager::class.java)
        assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel(ServiceNotifications.CHANNEL_CONNECTION).importance)
        assertFalse(nm.getNotificationChannel(ServiceNotifications.CHANNEL_CONNECTION).canShowBadge())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(ServiceNotifications.CHANNEL_FIND_PHONE).importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, nm.getNotificationChannel(ServiceNotifications.CHANNEL_ALERTS).importance)
    }

    @Test
    fun stopCommandStops() {
        grantBluetooth()
        val c = start(SyncCommands.ACTION_STOP)
        val shadow = shadowOf(c.get())
        assertTrue(shadow.isStoppedBySelf)
        assertNull(shadow.lastForegroundNotification)
        c.destroy()
    }

    @Test
    fun refreshDayWorksWithoutGoingForeground() {
        val day = Day(20250101)
        runBlocking { db.water.add(day, 1_735_740_000L, 330) }
        val intent = SyncCommands.intent(app, SyncCommands.ACTION_REFRESH_DAY).putExtra(SyncCommands.EXTRA_DAY, day.yyyymmdd)
        val c = Robolectric.buildService(SyncService::class.java, intent).create().startCommand(0, 1)
        val shadow = shadowOf(c.get())
        idleUntil { shadow.isStoppedBySelf }
        assertNull(shadow.lastForegroundNotification)
        assertTrue(shadow.isStoppedBySelf)
        assertEquals(330, runBlocking { db.summaries.day(day)?.waterMl })
        c.destroy()
    }

    @Test
    fun startHelperRefusesWhenRulesForbid() {
        val started = SyncCommands.start(app)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertFalse(started)
            assertNull(shadowOf(app).nextStartedService)
        } else {
            assertTrue(started)
            assertEquals(SyncCommands.ACTION_START, shadowOf(app).nextStartedService.action)
        }
    }

    @Test
    fun syncNowActionIsAnImmutableServiceIntent() {
        grantBluetooth()
        val n = ServiceNotifications.connection(app, ServiceStatus.Connected(null), 0)
        val pi = n.actions[0].actionIntent
        val saved = shadowOf(pi)
        assertTrue(saved.isForegroundService)
        assertEquals(SyncCommands.ACTION_SYNC_NOW, saved.savedIntent.action)
        assertTrue((saved.flags and android.app.PendingIntent.FLAG_IMMUTABLE) != 0)
        assertEquals(NotificationCompat.CATEGORY_SERVICE, n.category)
    }
}
