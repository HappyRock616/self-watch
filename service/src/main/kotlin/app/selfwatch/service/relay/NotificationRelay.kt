/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.relay

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.SettingsStore
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.watch.MusicAction
import app.selfwatch.model.watch.PhoneNotification
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.service.notify.ServiceNotifications
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Mirrors phone notifications on the watch (B19) and gives [MusicRelay] its media sessions (B21).
 *
 * Bound by Android once the user grants notification access; runs in process `:watch`. Watch
 * commands go out through [RelayHub] (so they reach the watches owned by
 * [app.selfwatch.service.SyncService]); actions from the watch come back through [RelayHub.listener]
 * ([ListenerAccess]). Settings: `notify.enabled`, `notify.apps_denied`.
 */
public class NotificationRelay : NotificationListenerService(), ListenerAccess {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> SwLog.e(TAG, "notification relay failure", e) },
    )
    private val lock = Mutex()
    private lateinit var settings: SettingsStore
    private lateinit var filter: NotificationFilter
    private val cache = RelayedCache()
    private val labels = HashMap<String, String>()
    private var music: MusicRelay? = null

    override fun onCreate() {
        super.onCreate()
        settings = SelfWatchDb.get(this).settings
        filter = NotificationFilter(packageName, { System.currentTimeMillis() })
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        SwLog.i(TAG, "listener connected")
        RelayHub.attachListener(this)
        NotificationManagerCompat.from(this).cancel(ServiceNotifications.ID_FIX_IT)
        scope.launch { settings.setBool(RelaySettings.LISTENER_SEEN, true) }
        music = MusicRelay(this, ComponentName(this, NotificationRelay::class.java), scope).also { it.start() }
    }

    override fun onListenerDisconnected() {
        SwLog.i(TAG, "listener disconnected")
        stopListening()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        stopListening()
        scope.cancel()
        super.onDestroy()
    }

    private fun stopListening() {
        RelayHub.detachListener(this)
        music?.stop()
        music = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        scope.launch { posted(n) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        scope.launch {
            val id = NotificationMapping.idFor(n.key)
            val known = lock.withLock {
                filter.forget(n.key)
                cache.remove(id) != null
            }
            if (known) RelayHub.sendToAll(WatchCommand.RemoveNotification(id))
        }
    }

    private suspend fun posted(sbn: StatusBarNotification) {
        val input = NotificationReader.input(sbn)
        val enabled = settings.getBool(SettingKeys.NOTIFY_ENABLED, true)
        val denied = settings.getList(SettingKeys.NOTIFY_APPS_DENIED)
        val phone = lock.withLock {
            val skip = filter.check(input, enabled, denied)
            if (skip != null) {
                SwLog.d(TAG) { "skip ${input.packageName}: $skip" }
                return
            }
            val p = NotificationReader.toPhone(sbn, appLabel(sbn.packageName))
            cache.put(p.id, Relayed(sbn.key, sbn.packageName, sbn.notification.actions ?: emptyArray()))
            p
        }
        RelayHub.sendToAll(WatchCommand.Notify(phone))
    }

    private fun appLabel(pkg: String): String = labels.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            SwLog.d(TAG) { "no label for $pkg: $e" }
            pkg
        }
    }

    override suspend fun performAction(id: Int, action: String, replyText: String?): Boolean {
        val r = lock.withLock { cache.get(id) }
        if (r == null) {
            SwLog.i(TAG, "action on a notification that is gone")
            return false
        }
        return when (action) {
            NotificationActions.DISMISS -> {
                cancelSafely(r.key)
                true
            }
            NotificationActions.MUTE -> {
                val denied = settings.getList(SettingKeys.NOTIFY_APPS_DENIED)
                if (r.packageName !in denied) settings.setList(SettingKeys.NOTIFY_APPS_DENIED, denied + r.packageName)
                true
            }
            NotificationActions.REPLY -> {
                val a = NotificationReader.replyAction(r.actions)
                if (a == null || replyText == null) false else NotificationReader.sendReply(this, a, replyText)
            }
            else -> {
                val a = r.actions.firstOrNull { it.title?.toString() == action }
                if (a == null) false else NotificationReader.send(this, a.actionIntent, null)
            }
        }
    }

    private fun cancelSafely(key: String) {
        try {
            cancelNotification(key)
        } catch (e: SecurityException) {
            // The listener was unbound in the meantime.
            SwLog.w(TAG, "cannot dismiss", e)
        }
    }

    override fun musicControl(action: MusicAction): Boolean = music?.control(action) ?: false

    /** What is kept of a relayed notification to act on it later. */
    internal class Relayed(val key: String, val packageName: String, val actions: Array<Notification.Action>)

    /** The last [MAX] relayed notifications by watch id. */
    internal class RelayedCache {
        private val map = LinkedHashMap<Int, Relayed>()

        fun put(id: Int, r: Relayed) {
            map.remove(id)
            map[id] = r
            while (map.size > MAX) map.remove(map.keys.first())
        }

        fun get(id: Int): Relayed? = map[id]

        fun remove(id: Int): Relayed? = map.remove(id)

        private companion object {
            const val MAX = 64
        }
    }

    private companion object {
        const val TAG = "NotificationRelay"
    }
}

/** Reads `StatusBarNotification`s and performs their actions. */
public object NotificationReader {
    private const val TAG = "NotificationReader"

    /** The filter's view of [sbn]. */
    public fun input(sbn: StatusBarNotification): NotificationInput {
        val n = sbn.notification
        val flags = n.flags
        return NotificationInput(
            key = sbn.key,
            packageName = sbn.packageName,
            ongoing = (flags and Notification.FLAG_ONGOING_EVENT) != 0 || (flags and Notification.FLAG_FOREGROUND_SERVICE) != 0,
            groupSummary = (flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            title = title(n),
            text = text(n),
        )
    }

    /** [sbn] as a watch notification; [appName] is the app's label. */
    public fun toPhone(sbn: StatusBarNotification, appName: String): PhoneNotification {
        val n = sbn.notification
        val actions = n.actions ?: emptyArray()
        return PhoneNotification(
            id = NotificationMapping.idFor(sbn.key),
            appPackage = sbn.packageName,
            appName = appName,
            title = title(n),
            text = text(n),
            tSec = sbn.postTime / 1000,
            category = NotificationMapping.category(n.category),
            canReply = replyAction(actions) != null,
            actions = actions.filter { !isReply(it) }.mapNotNull { it.title?.toString()?.takeIf { t -> t.isNotBlank() } },
        )
    }

    private fun title(n: Notification): String? =
        n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }

    private fun text(n: Notification): String? {
        val e = n.extras
        val big = e?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val plain = e?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        return (big?.takeIf { it.isNotBlank() } ?: plain?.takeIf { it.isNotBlank() } ?: n.tickerText?.toString())
            ?.takeIf { it.isNotBlank() }
    }

    private fun isReply(a: Notification.Action): Boolean = a.remoteInputs?.any { it.allowFreeFormInput } == true

    /** The first action that takes free-form text, or null. */
    public fun replyAction(actions: Array<Notification.Action>): Notification.Action? = actions.firstOrNull { isReply(it) }

    /** Fills [a]'s free-form [RemoteInput]s with [text] and fires its intent. */
    public fun sendReply(ctx: Context, a: Notification.Action, text: String): Boolean {
        val inputs = a.remoteInputs ?: return false
        val results = Bundle()
        for (ri in inputs) if (ri.allowFreeFormInput) results.putCharSequence(ri.resultKey, text)
        val fill = Intent()
        RemoteInput.addResultsToIntent(inputs, fill, results)
        return send(ctx, a.actionIntent, fill)
    }

    /** Fires [pi] (with [fill] merged in); false when it was cancelled or is missing. */
    public fun send(ctx: Context, pi: PendingIntent?, fill: Intent?): Boolean {
        if (pi == null) return false
        return try {
            pi.send(ctx, 0, fill)
            true
        } catch (e: PendingIntent.CanceledException) {
            SwLog.w(TAG, "notification action no longer valid", e)
            false
        }
    }
}
