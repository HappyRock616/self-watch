/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.notify

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.log.SwLog
import app.selfwatch.service.R
import app.selfwatch.service.SyncCommands
import app.selfwatch.service.device.DeviceKinds
import app.selfwatch.service.device.DeviceStatus

/** What the connection notification says. */
public sealed interface ServiceStatus {
    /** No device is managed. */
    public data object NoWatch : ServiceStatus

    /** A sync is running: step [done] of [total]. */
    public data class Syncing(val done: Int, val total: Int) : ServiceStatus

    /** Connected; last synced at [lastSyncSec] (null if never). */
    public data class Connected(val lastSyncSec: Long?) : ServiceStatus

    /** Opening the link or handshaking. */
    public data object Connecting : ServiceStatus

    /** Not connected (out of range, or waiting to retry). */
    public data object OutOfRange : ServiceStatus

    /** The rule that sums up several devices. */
    public companion object {
        /**
         * The most relevant state across [all]: syncing, then connected, connecting, out of range.
         * Only watches count when there is one (a scale that sleeps between weigh-ins is not "out of range").
         */
        public fun of(all: List<DeviceStatus>): ServiceStatus {
            val watches = all.filter { DeviceKinds.isWatch(it.info.kind) }
            val devices = watches.ifEmpty { all }
            if (devices.isEmpty()) return NoWatch
            devices.firstNotNullOfOrNull { it.state as? ConnectionState.Syncing }?.let { return Syncing(it.done, it.total) }
            val ready = devices.filter { it.state is ConnectionState.Ready }
            if (ready.isNotEmpty()) return Connected(ready.mapNotNull { it.lastSyncSec }.maxOrNull())
            if (devices.any { it.state is ConnectionState.Connecting || it.state is ConnectionState.Handshaking }) return Connecting
            return OutOfRange
        }
    }
}

/** Channels, ids and the connection notification of the service. */
public object ServiceNotifications {
    /** Channel for the always-there connection notification (low importance: silent, no badge). */
    public const val CHANNEL_CONNECTION: String = "connection"

    /** Channel for find-my-phone (high importance, heads-up). */
    public const val CHANNEL_FIND_PHONE: String = "find_phone"

    /** Channel for alerts such as "fix it" notices (default importance). */
    public const val CHANNEL_ALERTS: String = "alerts"

    /** Notification id of the connection notification. */
    public const val ID_CONNECTION: Int = 1

    /** Notification id of the find-my-phone alert. */
    public const val ID_FIND_PHONE: Int = 2

    /** Notification id of the "fix it" alert. */
    public const val ID_FIX_IT: Int = 3

    private const val REQUEST_OPEN = 1
    private const val REQUEST_SYNC = 2
    private const val TAG = "ServiceNotifications"

    /** Creates (or updates the names of) the three channels. Safe to call often. */
    public fun createChannels(ctx: Context) {
        val nm = NotificationManagerCompat.from(ctx)
        nm.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_CONNECTION, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(ctx.getString(R.string.svc_channel_connection))
                    .setDescription(ctx.getString(R.string.svc_channel_connection_desc))
                    .setShowBadge(false)
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_FIND_PHONE, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(ctx.getString(R.string.svc_channel_find_phone))
                    .setDescription(ctx.getString(R.string.svc_channel_find_phone_desc))
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_ALERTS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(ctx.getString(R.string.svc_channel_alerts))
                    .setDescription(ctx.getString(R.string.svc_channel_alerts_desc))
                    .build(),
            ),
        )
    }

    /** The text for [status] at [nowSec]. */
    public fun text(ctx: Context, status: ServiceStatus, nowSec: Long): String = when (status) {
        ServiceStatus.NoWatch -> ctx.getString(R.string.svc_status_no_watch)
        is ServiceStatus.Syncing -> ctx.getString(R.string.svc_status_syncing, status.done, status.total)
        ServiceStatus.Connecting -> ctx.getString(R.string.svc_status_connecting)
        ServiceStatus.OutOfRange -> ctx.getString(R.string.svc_status_out_of_range)
        is ServiceStatus.Connected -> {
            val last = status.lastSyncSec
            if (last == null) {
                ctx.getString(R.string.svc_status_connected)
            } else {
                val ageMin = ((nowSec - last).coerceAtLeast(0) / 60).toInt()
                val res = ctx.resources
                when {
                    ageMin < 1 -> ctx.getString(R.string.svc_status_synced_now)
                    ageMin < 60 -> res.getQuantityString(R.plurals.svc_status_synced_min, ageMin, ageMin)
                    ageMin < 24 * 60 -> (ageMin / 60).let { res.getQuantityString(R.plurals.svc_status_synced_hours, it, it) }
                    else -> (ageMin / (24 * 60)).let { res.getQuantityString(R.plurals.svc_status_synced_days, it, it) }
                }
            }
        }
    }

    /**
     * The connection notification: silent and ongoing, tap opens the app, with a "Sync now" action.
     * Every PendingIntent is immutable.
     */
    public fun connection(ctx: Context, status: ServiceStatus, nowSec: Long): Notification {
        val syncNow = PendingIntent.getForegroundService(
            ctx,
            REQUEST_SYNC,
            SyncCommands.intent(ctx, SyncCommands.ACTION_SYNC_NOW),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(ctx, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.svc_ic_watch)
            .setContentTitle(ctx.getString(R.string.svc_notification_title))
            .setContentText(text(ctx, status, nowSec))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, ctx.getString(R.string.svc_action_sync_now), syncNow)
        openApp(ctx)?.let { b.setContentIntent(it) }
        return b.build()
    }

    /** Replaces the shown connection notification. Does nothing without notification permission. */
    public fun update(ctx: Context, notification: Notification) {
        if (!canPost(ctx)) return
        try {
            NotificationManagerCompat.from(ctx).notify(ID_CONNECTION, notification)
        } catch (e: SecurityException) {
            SwLog.w(TAG, "notification update refused", e)
        }
    }

    /** True when notifications may be posted (`POST_NOTIFICATIONS` on API 33+). */
    public fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Opens the app's launcher activity directly (no trampoline), or null if it has none. */
    private fun openApp(ctx: Context): PendingIntent? {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(ctx, REQUEST_OPEN, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
