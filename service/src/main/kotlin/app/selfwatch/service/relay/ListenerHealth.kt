/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.relay

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.SettingsStore
import app.selfwatch.model.log.SwLog
import app.selfwatch.service.R
import app.selfwatch.service.notify.ServiceNotifications
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** The outcome of one [ListenerHealth] check. */
public enum class ListenerCheck {
    /** Notifications are off, or access was never granted: nothing to fix. */
    NOT_WANTED,

    /** The listener is bound. */
    OK,

    /** Access is granted but the listener did not bind, even after asking Android to rebind. */
    NOT_BOUND,

    /** Access was granted before but is gone (Xiaomi resets it after updates). */
    ACCESS_LOST,
}

/**
 * The "fix it" check for notification access (COMPATIBILITY §4, Xiaomi). Run by
 * [app.selfwatch.service.SyncService] on every start: when `notify.enabled` is on and the listener
 * was bound before ([RelaySettings.LISTENER_SEEN]) but is not bound now, it asks Android to rebind
 * and, if that does not help within [bindWaitMs], posts a notification on channel `alerts` that opens
 * the notification-access settings screen. It never nags a user who never granted access.
 */
public class ListenerHealth(
    private val ctx: Context,
    private val settings: SettingsStore,
    private val bindWaitMs: Long = BIND_WAIT_MS,
    private val accessGranted: () -> Boolean = {
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    },
    private val rebind: () -> Unit = {
        try {
            NotificationListenerService.requestRebind(ComponentName(ctx, NotificationRelay::class.java))
        } catch (e: SecurityException) {
            SwLog.w("ListenerHealth", "rebind refused", e)
        }
    },
) {
    /** Checks once and posts or clears the notification. */
    public suspend fun check(): ListenerCheck {
        val result = evaluate()
        when (result) {
            ListenerCheck.ACCESS_LOST, ListenerCheck.NOT_BOUND -> post()
            ListenerCheck.OK, ListenerCheck.NOT_WANTED -> NotificationManagerCompat.from(ctx).cancel(ServiceNotifications.ID_FIX_IT)
        }
        return result
    }

    private suspend fun evaluate(): ListenerCheck {
        if (!settings.getBool(SettingKeys.NOTIFY_ENABLED, true)) return ListenerCheck.NOT_WANTED
        if (RelayHub.listenerConnected.value) return ListenerCheck.OK
        if (!settings.getBool(RelaySettings.LISTENER_SEEN)) return ListenerCheck.NOT_WANTED
        if (!accessGranted()) return ListenerCheck.ACCESS_LOST
        // Granted but not bound yet: give Android a moment, then ask it to rebind.
        if (waitBound(bindWaitMs)) return ListenerCheck.OK
        rebind()
        return if (waitBound(bindWaitMs)) ListenerCheck.OK else ListenerCheck.NOT_BOUND
    }

    private suspend fun waitBound(ms: Long): Boolean =
        withTimeoutOrNull(ms) { RelayHub.listenerConnected.first { it } } ?: false

    private fun post() {
        SwLog.i(TAG, "notification access needs fixing")
        if (!ServiceNotifications.canPost(ctx)) return
        val open = PendingIntent.getActivity(
            ctx,
            REQUEST_FIX,
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, ServiceNotifications.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.svc_ic_watch)
            .setContentTitle(ctx.getString(R.string.svc_fix_notify_title))
            .setContentText(ctx.getString(R.string.svc_fix_notify_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ctx.getString(R.string.svc_fix_notify_text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(ServiceNotifications.ID_FIX_IT, n)
        } catch (e: SecurityException) {
            SwLog.w(TAG, "fix-it notification refused", e)
        }
    }

    private companion object {
        const val TAG = "ListenerHealth"
        const val BIND_WAIT_MS = 10_000L
        const val REQUEST_FIX = 4
    }
}
