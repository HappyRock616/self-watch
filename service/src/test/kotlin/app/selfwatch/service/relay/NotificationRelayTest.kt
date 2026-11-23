/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.relay

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Process
import android.service.notification.StatusBarNotification
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.watch.MusicAction
import app.selfwatch.model.watch.NotificationCategory
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchEvent
import app.selfwatch.service.device.WatchLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** B19: filters, duplicate suppression, mapping and actions. */
@RunWith(RobolectricTestRunner::class)
class NotificationRelayTest {
    private lateinit var app: Application
    private var nowMs = 1_000_000L
    private lateinit var filter: NotificationFilter

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        filter = NotificationFilter("app.selfwatch", { nowMs })
    }

    private fun sbn(
        pkg: String = "com.example.chat",
        id: Int = 7,
        build: Notification.Builder.() -> Unit = {},
    ): StatusBarNotification {
        val b = Notification.Builder(app, "c").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Anna").setContentText("Lunch?")
        val n = b.apply(build).build()
        @Suppress("DEPRECATION") // the only public constructor in the SDK stubs
        return StatusBarNotification(pkg, pkg, id, null, 10_001, 0, 0, n, Process.myUserHandle(), 1_700_000_000_123L)
    }

    private fun input(key: String = "k", pkg: String = "com.example.chat", text: String? = "Lunch?") =
        NotificationInput(key, pkg, ongoing = false, groupSummary = false, title = "Anna", text = text)

    @Test
    fun filtersSkipOngoingSummariesOwnDeniedEmptyAndDisabled() {
        assertNull(filter.check(input(), true, emptyList()))
        assertEquals(SkipReason.DISABLED, filter.check(input("a"), false, emptyList()))
        assertEquals(SkipReason.OWN_PACKAGE, filter.check(input("b", pkg = "app.selfwatch"), true, emptyList()))
        assertEquals(SkipReason.ONGOING, filter.check(input("c").copy(ongoing = true), true, emptyList()))
        assertEquals(SkipReason.GROUP_SUMMARY, filter.check(input("d").copy(groupSummary = true), true, emptyList()))
        assertEquals(SkipReason.DENIED_APP, filter.check(input("e"), true, listOf("com.example.chat")))
        assertEquals(SkipReason.EMPTY, filter.check(input("f").copy(title = null, text = " "), true, emptyList()))
    }

    @Test
    fun duplicatesWithinTwoSecondsAreSuppressed() {
        assertNull(filter.check(input(), true, emptyList()))
        nowMs += 1_500
        assertEquals(SkipReason.DUPLICATE, filter.check(input(), true, emptyList()))
        // Different text is not a duplicate.
        assertNull(filter.check(input(text = "Now?"), true, emptyList()))
        nowMs += 2_000
        assertNull("same text after the window is relayed", filter.check(input(text = "Now?"), true, emptyList()))
        filter.forget("k")
        assertNull("removed then re-posted is relayed", filter.check(input(text = "Now?"), true, emptyList()))
    }

    @Test
    fun readerSeesFlags() {
        val ongoing = sbn { setOngoing(true) }
        assertTrue(NotificationReader.input(ongoing).ongoing)
        val summary = sbn { setGroup("g").setGroupSummary(true) }
        assertTrue(NotificationReader.input(summary).groupSummary)
        val plain = NotificationReader.input(sbn())
        assertFalse(plain.ongoing)
        assertEquals("Anna", plain.title)
        assertEquals("Lunch?", plain.text)
    }

    @Test
    fun mapsToPhoneNotificationWithCategoryReplyAndActions() {
        val pi = PendingIntent.getBroadcast(app, 0, Intent("x"), PendingIntent.FLAG_MUTABLE)
        val reply = Notification.Action.Builder(null, "Reply", pi).addRemoteInput(RemoteInput.Builder("text").build()).build()
        val read = Notification.Action.Builder(null, "Mark read", pi).build()
        val s = sbn {
            setCategory(Notification.CATEGORY_MESSAGE)
            setStyle(Notification.BigTextStyle().bigText("Lunch at noon?"))
            addAction(reply)
            addAction(read)
        }
        val p = NotificationReader.toPhone(s, "Chat")
        assertEquals(NotificationMapping.idFor(s.key), p.id)
        assertTrue(p.id >= 0)
        assertEquals("com.example.chat", p.appPackage)
        assertEquals("Chat", p.appName)
        assertEquals("Anna", p.title)
        assertEquals("Lunch at noon?", p.text)
        assertEquals(1_700_000_000L, p.tSec)
        assertEquals(NotificationCategory.MESSAGE, p.category)
        assertTrue(p.canReply)
        assertEquals(listOf("Mark read"), p.actions)
    }

    @Test
    fun categoryMapping() {
        assertEquals(NotificationCategory.CALL, NotificationMapping.category(Notification.CATEGORY_CALL))
        assertEquals(NotificationCategory.CALL, NotificationMapping.category(Notification.CATEGORY_MISSED_CALL))
        assertEquals(NotificationCategory.EMAIL, NotificationMapping.category(Notification.CATEGORY_EMAIL))
        assertEquals(NotificationCategory.CALENDAR, NotificationMapping.category(Notification.CATEGORY_EVENT))
        assertEquals(NotificationCategory.SOCIAL, NotificationMapping.category(Notification.CATEGORY_SOCIAL))
        assertEquals(NotificationCategory.OTHER, NotificationMapping.category(null))
        assertEquals(NotificationCategory.OTHER, NotificationMapping.category(Notification.CATEGORY_PROMO))
    }

    @Test
    fun replyFillsRemoteInputAndFiresTheIntent() {
        val pi = PendingIntent.getBroadcast(app, 0, Intent("app.test.REPLY"), PendingIntent.FLAG_MUTABLE)
        val reply = Notification.Action.Builder(null, "Reply", pi).addRemoteInput(RemoteInput.Builder("text").build()).build()
        assertEquals(reply, NotificationReader.replyAction(arrayOf(reply)))
        assertTrue(NotificationReader.sendReply(app, reply, "On my way"))
        val sent = shadowOf(app).broadcastIntents.last()
        assertEquals("app.test.REPLY", sent.action)
        assertEquals("On my way", RemoteInput.getResultsFromIntent(sent)?.getCharSequence("text")?.toString())
    }

    @Test
    fun cancelledActionReportsFalse() {
        val pi = PendingIntent.getBroadcast(app, 1, Intent("app.test.GONE"), PendingIntent.FLAG_IMMUTABLE)
        pi.cancel()
        assertFalse(NotificationReader.send(app, pi, null))
        assertFalse(NotificationReader.send(app, null, null))
    }

    @Test
    fun actionRouterUsesTheBoundListener() = runBlocking {
        val calls = ArrayList<String>()
        val access = object : ListenerAccess {
            override suspend fun performAction(id: Int, action: String, replyText: String?): Boolean {
                calls.add("$id/$action/$replyText")
                return true
            }

            override fun musicControl(action: MusicAction): Boolean {
                calls.add("music/$action")
                return true
            }
        }
        val link = object : WatchLink {
            override val deviceId: Long = 1
            override suspend fun send(cmd: WatchCommand): Outcome<Unit> = Outcome.Ok(Unit)
        }
        NotificationActionRouter { access }.onNotificationAction(link, WatchEvent.NotificationAction(5, NotificationActions.REPLY, "ok"))
        MusicControlRouter { access }.onMusicControl(link, WatchEvent.MusicControl(MusicAction.NEXT))
        // Without a listener nothing happens and nothing throws.
        NotificationActionRouter { null }.onNotificationAction(link, WatchEvent.NotificationAction(5, NotificationActions.DISMISS, null))
        assertEquals(listOf("5/reply/ok", "music/NEXT"), calls)
    }

    @Test
    fun hubDropsCommandsWithoutAServiceAndForwardsWithOne() = runBlocking {
        assertFalse(RelayHub.sendToAll(WatchCommand.SyncTime))
        val got = ArrayList<WatchCommand>()
        val b = WatchBroadcaster { got.add(it) }
        RelayHub.attach(b)
        try {
            assertTrue(RelayHub.sendToAll(WatchCommand.RemoveNotification(3)))
        } finally {
            RelayHub.detach(b)
        }
        assertEquals(listOf<WatchCommand>(WatchCommand.RemoveNotification(3)), got)
        assertFalse(RelayHub.hasWatches)
    }
}
