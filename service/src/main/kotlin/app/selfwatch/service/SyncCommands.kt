/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Day
import app.selfwatch.service.watchapps.WatchAppOp
import app.selfwatch.service.watchapps.WatchAppRequest
import app.selfwatch.service.watchapps.WatchAppResults
import java.util.UUID

/**
 * The explicit-intent command API of [SyncService], for the app's `ServiceClient`, the boot
 * receiver, the presence service and the sync job. Every intent names the service class, so nothing
 * outside the app can send them (the service is not exported).
 */
public object SyncCommands {
    /** Start (or re-read devices and mode). In sync-only mode this runs one sync cycle, then stops. */
    public const val ACTION_START: String = "app.selfwatch.service.action.START"

    /** Sync every device now (the notification's "Sync now", pull-to-sync). */
    public const val ACTION_SYNC_NOW: String = "app.selfwatch.service.action.SYNC_NOW"

    /** Disconnect everything and stop the service (the user turned the watch off in Devices). */
    public const val ACTION_STOP: String = "app.selfwatch.service.action.STOP"

    /** Rebuild the day summary of [EXTRA_DAY] (sent by the app after diary, water or weight changes). */
    public const val ACTION_REFRESH_DAY: String = "app.selfwatch.service.action.REFRESH_DAY"

    /** The sync-only schedule fired (sent by the JobScheduler job). */
    public const val ACTION_SCHEDULED_SYNC: String = "app.selfwatch.service.action.SCHEDULED_SYNC"

    /** The watch came into range (sent by the presence service). Extra: [EXTRA_ADDRESS]. */
    public const val ACTION_DEVICE_APPEARED: String = "app.selfwatch.service.action.DEVICE_APPEARED"

    /** The watch left range (sent by the presence service). Extra: [EXTRA_ADDRESS]. */
    public const val ACTION_DEVICE_DISAPPEARED: String = "app.selfwatch.service.action.DEVICE_DISAPPEARED"

    /** Extra: a day as `YYYYMMDD` (`Int`). */
    public const val EXTRA_DAY: String = "app.selfwatch.service.extra.DAY"

    /** Extra: a Bluetooth address (`String`). */
    public const val EXTRA_ADDRESS: String = "app.selfwatch.service.extra.ADDRESS"

    /**
     * Broadcast (restricted to this app's package) sent by the service after a new workout is stored.
     * Extra: [EXTRA_WORKOUT_ID]. The app schedules the automatic GPX export from it.
     */
    public const val BROADCAST_WORKOUT_ADDED: String = "app.selfwatch.service.broadcast.WORKOUT_ADDED"

    /** Extra: a workout row id (`Long`). */
    public const val EXTRA_WORKOUT_ID: String = "app.selfwatch.service.extra.WORKOUT_ID"

    /**
     * A watch-app request (Devices screen, C19): extras [EXTRA_OP] (a [WatchAppOp] key), [EXTRA_DEVICE_ID],
     * [EXTRA_APP_UUID] (all but refresh), [EXTRA_REQUEST_ID] and, to write settings, [EXTRA_VALUES_JSON].
     * The result is published as a setting ([WatchAppResults]); use [watchApp] to send one.
     */
    public const val ACTION_WATCH_APP: String = "app.selfwatch.service.action.WATCH_APP"

    /** Extra: the [WatchAppOp] key (`String`). */
    public const val EXTRA_OP: String = "app.selfwatch.service.extra.OP"

    /** Extra: a `device` row id (`Long`). */
    public const val EXTRA_DEVICE_ID: String = "app.selfwatch.service.extra.DEVICE_ID"

    /** Extra: a watch app UUID (`String`). */
    public const val EXTRA_APP_UUID: String = "app.selfwatch.service.extra.APP_UUID"

    /** Extra: the caller's request token, echoed in the result (`String`). */
    public const val EXTRA_REQUEST_ID: String = "app.selfwatch.service.extra.REQUEST_ID"

    /** Extra: settings to write, a JSON object of key → value (`String`). */
    public const val EXTRA_VALUES_JSON: String = "app.selfwatch.service.extra.VALUES_JSON"

    /**
     * Ring (or stop ringing) a watch (Devices → Watch settings, C19). Extras: [EXTRA_DEVICE_ID],
     * [EXTRA_RING]. Ignored when the service is not running (nothing is connected).
     */
    public const val ACTION_FIND_WATCH: String = "app.selfwatch.service.action.FIND_WATCH"

    /**
     * Send watch [EXTRA_DEVICE_ID]'s stored alarms (`device.AlarmSettings`) to it (C19). The result is
     * published as `AlarmSettings.resultKey`. Ignored when the service is not running.
     */
    public const val ACTION_SET_ALARMS: String = "app.selfwatch.service.action.SET_ALARMS"

    /** Show a test notification on every connected watch (C19). Ignored when the service is not running. */
    public const val ACTION_TEST_NOTIFICATION: String = "app.selfwatch.service.action.TEST_NOTIFICATION"

    /**
     * A screen shows live heart rate or steps ([EXTRA_ENABLE] true; repeated while shown) or stopped showing
     * them (false). See `live.LiveRelay`. Ignored when the service is not running (nothing is connected).
     */
    public const val ACTION_LIVE: String = "app.selfwatch.service.action.LIVE"

    /** Extra: live readings wanted (`Boolean`). */
    public const val EXTRA_ENABLE: String = "app.selfwatch.service.extra.ENABLE"

    /** Extra: start (true) or stop (false) ringing (`Boolean`). */
    public const val EXTRA_RING: String = "app.selfwatch.service.extra.RING"

    private const val TAG = "SyncCommands"

    /** Asks the service to ring ([ring]) or stop ringing watch [deviceId]. Returns false when Android refuses. */
    public fun findWatch(ctx: Context, deviceId: Long, ring: Boolean): Boolean =
        sendPlain(ctx, intent(ctx, ACTION_FIND_WATCH).putExtra(EXTRA_DEVICE_ID, deviceId).putExtra(EXTRA_RING, ring))

    /** Asks the service to send watch [deviceId]'s stored alarms. Returns false when Android refuses. */
    public fun setAlarms(ctx: Context, deviceId: Long): Boolean =
        sendPlain(ctx, intent(ctx, ACTION_SET_ALARMS).putExtra(EXTRA_DEVICE_ID, deviceId))

    /** Asks the service for live readings ([enable]) or tells it they are no longer shown. False when Android refuses. */
    public fun live(ctx: Context, enable: Boolean): Boolean = sendPlain(ctx, intent(ctx, ACTION_LIVE).putExtra(EXTRA_ENABLE, enable))

    /** Asks the service to show a test notification on the connected watches. Returns false when Android refuses. */
    public fun testNotification(ctx: Context): Boolean = sendPlain(ctx, intent(ctx, ACTION_TEST_NOTIFICATION))

    /**
     * Sends watch-app request [req] to the service (plain start: it never forces the service into the
     * foreground; when no watch is connected the result says so). Returns false when Android refuses.
     */
    public fun watchApp(ctx: Context, req: WatchAppRequest): Boolean = sendPlain(ctx, watchAppIntent(ctx, req))

    /** The intent for [req] (see [ACTION_WATCH_APP]). */
    public fun watchAppIntent(ctx: Context, req: WatchAppRequest): Intent {
        val i = intent(ctx, ACTION_WATCH_APP)
            .putExtra(EXTRA_OP, req.op.key)
            .putExtra(EXTRA_DEVICE_ID, req.deviceId)
            .putExtra(EXTRA_REQUEST_ID, req.requestId)
        req.appUuid?.let { i.putExtra(EXTRA_APP_UUID, it.toString()) }
        req.valuesJson?.let { i.putExtra(EXTRA_VALUES_JSON, it) }
        return i
    }

    /** The request carried by [i], or null when it is malformed. */
    public fun parseWatchApp(i: Intent): WatchAppRequest? {
        if (i.action != ACTION_WATCH_APP) return null
        val op = WatchAppOp.of(i.getStringExtra(EXTRA_OP)) ?: return null
        val deviceId = i.getLongExtra(EXTRA_DEVICE_ID, -1L).takeIf { it >= 0 } ?: return null
        val uuid = i.getStringExtra(EXTRA_APP_UUID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        return WatchAppRequest(op, deviceId, uuid, i.getStringExtra(EXTRA_REQUEST_ID) ?: "", i.getStringExtra(EXTRA_VALUES_JSON))
    }

    /** An explicit intent for [action] addressed to [SyncService]. */
    public fun intent(ctx: Context, action: String): Intent = Intent(ctx, SyncService::class.java).setAction(action)

    /**
     * Starts [SyncService] as a foreground service with [action]. Returns false (and starts nothing)
     * when [ForegroundRules] forbid it or Android refuses the start, so the caller can show why.
     */
    public fun start(ctx: Context, action: String = ACTION_START, address: String? = null): Boolean {
        if (!ForegroundRules.canStartForeground(ctx)) {
            SwLog.i(TAG, "not starting: no Bluetooth permission and no companion association")
            return false
        }
        val i = intent(ctx, action)
        if (address != null) i.putExtra(EXTRA_ADDRESS, address)
        return try {
            ContextCompat.startForegroundService(ctx, i)
            true
        } catch (e: IllegalStateException) {
            // API 31+: ForegroundServiceStartNotAllowedException (background start not allowed).
            SwLog.w(TAG, "foreground start refused", e)
            false
        } catch (e: SecurityException) {
            SwLog.w(TAG, "foreground start refused", e)
            false
        }
    }

    /** Stops the service (disconnects every watch). */
    public fun stop(ctx: Context) {
        sendPlain(ctx, intent(ctx, ACTION_STOP))
    }

    /**
     * Asks the service to rebuild [day]'s summary. Uses a plain start, so it never forces the service
     * into the foreground; if the service isn't running it does the refresh and stops again. Returns
     * false when Android refuses (the app is in the background).
     */
    public fun refreshDay(ctx: Context, day: Day): Boolean =
        sendPlain(ctx, intent(ctx, ACTION_REFRESH_DAY).putExtra(EXTRA_DAY, day.yyyymmdd))

    /** Tells the app (package-restricted broadcast) that workout [workoutId] was stored. */
    public fun workoutAdded(ctx: Context, workoutId: Long) {
        ctx.sendBroadcast(Intent(BROADCAST_WORKOUT_ADDED).setPackage(ctx.packageName).putExtra(EXTRA_WORKOUT_ID, workoutId))
    }

    /** Delivers a command with a plain `startService`. Returns false when Android refuses. */
    internal fun sendPlain(ctx: Context, i: Intent): Boolean = try {
        ctx.startService(i) != null
    } catch (e: IllegalStateException) {
        SwLog.w(TAG, "service command refused", e)
        false
    } catch (e: SecurityException) {
        SwLog.w(TAG, "service command refused", e)
        false
    }
}
