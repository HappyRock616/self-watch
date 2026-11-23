/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.relay

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import app.selfwatch.db.DbChanges
import app.selfwatch.db.SettingKeys
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.time.Day
import app.selfwatch.model.watch.MusicAction
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchEvent
import app.selfwatch.service.ServiceDbTest
import app.selfwatch.service.device.WatchLink
import app.selfwatch.service.health.DayRefreshReceiver
import app.selfwatch.service.health.SummaryBuilder
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.service.notify.ServiceNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.util.Collections

/** B23 find my phone, B24 time zone, the REFRESH_DAY receiver and the notification-access "fix it" check. */
class SmallRelaysTest : ServiceDbTest() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sent: MutableList<WatchCommand> = Collections.synchronizedList(ArrayList())
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    private val link = object : WatchLink {
        override val deviceId: Long = 1
        override suspend fun send(cmd: WatchCommand): Outcome<Unit> = Outcome.Ok(Unit)
    }

    private class FakePlayer : AlertPlayer {
        var playing = false
        var starts = 0

        override fun start() {
            playing = true
            starts++
        }

        override fun stop() {
            playing = false
        }
    }

    @Before
    fun grant() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ServiceNotifications.createChannels(app)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun idleUntil(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 3_000
        while (!done() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun findPhoneNotification() = shadowOf(nm).getNotification(ServiceNotifications.ID_FIND_PHONE)

    @Test
    fun findPhoneRingsAndStopsFromTheWatch() {
        val player = FakePlayer()
        val alert = FindPhoneAlert(app, player)
        runBlocking { alert.onFindPhone(link, WatchEvent.FindPhone(true)) }
        idleUntil { alert.ringing }
        assertTrue(player.playing)
        val n = findPhoneNotification()
        assertNotNull(n)
        assertEquals(ServiceNotifications.CHANNEL_FIND_PHONE, n.channelId)
        assertEquals("Stop", n.actions.single().title.toString())
        assertNull("no full-screen intent", n.fullScreenIntent)
        // A second start keeps one sound.
        runBlocking { alert.onFindPhone(link, WatchEvent.FindPhone(true)) }
        idleUntil { false }
        assertEquals(1, player.starts)
        runBlocking { alert.onFindPhone(link, WatchEvent.FindPhone(false)) }
        idleUntil { !alert.ringing }
        assertFalse(player.playing)
        assertNull(findPhoneNotification())
    }

    @Test
    fun findPhoneStopsFromTheStopActionAndTimesOut() {
        val player = FakePlayer()
        val alert = FindPhoneAlert(app, player, timeoutMs = 60_000)
        alert.start()
        assertTrue(alert.ringing)
        FindPhoneStopReceiver().onReceive(app, Intent(FindPhoneAlert.ACTION_STOP))
        assertFalse(alert.ringing)
        assertFalse(player.playing)
        assertNull(findPhoneNotification())

        alert.start()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(61))
        assertFalse("stops by itself", alert.ringing)
    }

    @Test
    fun timeAndZoneChangesSyncTheWatchClock() {
        val w = TimeZoneWatcher(app, scope, { sent.add(it) })
        w.register()
        try {
            app.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
            idleUntil { sent.isNotEmpty() }
            assertEquals(listOf<WatchCommand>(WatchCommand.SyncTime), sent.toList())
            w.onTimeChanged(Intent.ACTION_TIME_CHANGED)
            w.onTimeChanged(Intent.ACTION_BATTERY_LOW)
            idleUntil { sent.size >= 2 }
            assertEquals(2, sent.size)
        } finally {
            w.unregister()
        }
    }

    @Test
    fun refreshDayBroadcastRefreshesThoseDays() {
        val got: MutableList<Set<Day>> = Collections.synchronizedList(ArrayList())
        val r = DayRefreshReceiver(app, scope) { got.add(it) }
        r.register()
        try {
            val intent = Intent(DayRefreshReceiver.ACTION)
                .setPackage(app.packageName)
                .putExtra(DayRefreshReceiver.EXTRA_DAYS, intArrayOf(20261123, 20261122, 20261399))
            app.sendBroadcast(intent, DbChanges.permissionFor(app.packageName))
            idleUntil { got.isNotEmpty() }
            assertEquals(listOf(setOf(Day(20261123), Day(20261122))), got.toList())
        } finally {
            r.unregister()
        }
        assertTrue(DayRefreshReceiver.valid(null).isEmpty())
        assertTrue(DayRefreshReceiver.valid(intArrayOf(0, 20260230)).isEmpty())
    }

    @Test
    fun refreshDayReachesTheSummaryBuilder() {
        val summaries = SummaryBuilder(db, clock)
        val r = DayRefreshReceiver(app, scope) { summaries.refresh(it) }
        val day = Day(20261120)
        val dev = addDevice()
        runBlocking { db.activity.insert(listOf(ActivityMinute(dev, noon(day), 1234, 0, 0, 0, null))) }
        r.onBroadcast(Intent(DayRefreshReceiver.ACTION).putExtra(DayRefreshReceiver.EXTRA_DAYS, intArrayOf(day.yyyymmdd)))
        idleUntil { count("day_summary") > 0 }
        assertEquals(1234, runBlocking { db.summaries.day(day)?.steps })
    }

    @Test
    fun fixItOnlyAfterAccessWasSeenAndLost() = runBlocking {
        var granted = false
        var rebinds = 0
        val h = ListenerHealth(app, db.settings, bindWaitMs = 50, accessGranted = { granted }, rebind = { rebinds++ })
        assertEquals("never granted: no nagging", ListenerCheck.NOT_WANTED, h.check())
        assertNull(shadowOf(nm).getNotification(ServiceNotifications.ID_FIX_IT))

        db.settings.setBool(RelaySettings.LISTENER_SEEN, true)
        assertEquals(ListenerCheck.ACCESS_LOST, h.check())
        val n = shadowOf(nm).getNotification(ServiceNotifications.ID_FIX_IT)
        assertNotNull(n)
        assertEquals(ServiceNotifications.CHANNEL_ALERTS, n.channelId)
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, shadowOf(n.contentIntent).savedIntent.action)

        granted = true
        assertEquals(ListenerCheck.NOT_BOUND, h.check())
        assertEquals(1, rebinds)

        val access = object : ListenerAccess {
            override suspend fun performAction(id: Int, action: String, replyText: String?): Boolean = false
            override fun musicControl(action: MusicAction): Boolean = false
        }
        RelayHub.attachListener(access)
        try {
            assertEquals(ListenerCheck.OK, h.check())
            assertNull("fixed: the notice goes away", shadowOf(nm).getNotification(ServiceNotifications.ID_FIX_IT))
        } finally {
            RelayHub.detachListener(access)
        }
        db.settings.setBool(SettingKeys.NOTIFY_ENABLED, false)
        assertEquals(ListenerCheck.NOT_WANTED, h.check())
    }
}
