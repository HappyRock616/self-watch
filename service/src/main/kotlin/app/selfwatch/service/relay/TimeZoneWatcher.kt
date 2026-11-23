/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.watch.WatchCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Sets the watch clock again when the phone's time zone or time changes (B24): runtime receiver for
 * `ACTION_TIMEZONE_CHANGED` and `ACTION_TIME_CHANGED`, registered by
 * [app.selfwatch.service.SyncService] while it runs (system broadcasts reach not-exported receivers).
 */
public class TimeZoneWatcher(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val send: suspend (WatchCommand) -> Unit = { RelayHub.sendToAll(it) },
) {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onTimeChanged(intent.action)
        }
    }

    /** Starts listening. */
    public fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
        }
        SystemReceivers.register(ctx, receiver, filter)
        registered = true
    }

    /** Stops listening. */
    public fun unregister() {
        if (!registered) return
        registered = false
        try {
            ctx.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            SwLog.w(TAG, "time receiver was not registered", e)
        }
    }

    /** Handles one broadcast [action]; sends [WatchCommand.SyncTime] for the time and zone actions. */
    internal fun onTimeChanged(action: String?) {
        if (action != Intent.ACTION_TIMEZONE_CHANGED && action != Intent.ACTION_TIME_CHANGED) return
        SwLog.i(TAG, "phone time changed ($action); setting the watch clock")
        scope.launch { send(WatchCommand.SyncTime) }
    }

    private companion object {
        const val TAG = "TimeZoneWatcher"
    }
}

/** Registers receivers for protected system broadcasts (only the system can send them). */
internal object SystemReceivers {
    /**
     * Registers [r] for [filter]: not exported on API 33+; below 33 there is no flag, and
     * `ContextCompat`'s fallback would guard the receiver with a dynamic permission, which is not
     * needed for broadcasts that only the system may send.
     */
    fun register(ctx: Context, r: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(r, filter)
        }
    }
}
