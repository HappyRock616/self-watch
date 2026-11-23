/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.health

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import app.selfwatch.db.DbChanges
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Day
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.DateTimeException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Hears the food module's `app.selfwatch.REFRESH_DAY` broadcast (int-array extra `days`, `YYYYMMDD`)
 * and rebuilds those day summaries. Registered at run time by [app.selfwatch.service.SyncService],
 * not exported and guarded by `<applicationId>.permission.INTERNAL`, so only the app's own processes
 * can trigger it. Invalid days are dropped.
 */
public class DayRefreshReceiver(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val refresh: suspend (Set<Day>) -> Unit,
) {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onBroadcast(intent)
        }
    }

    /** Starts listening. */
    public fun register() {
        if (registered) return
        ContextCompat.registerReceiver(
            ctx,
            receiver,
            IntentFilter(ACTION),
            DbChanges.permissionFor(ctx.packageName),
            null,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
    }

    /** Stops listening. */
    public fun unregister() {
        if (!registered) return
        registered = false
        try {
            ctx.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            SwLog.w(TAG, "refresh receiver was not registered", e)
        }
    }

    /** Handles one broadcast. */
    internal fun onBroadcast(intent: Intent) {
        if (intent.action != ACTION) return
        val days = valid(intent.getIntArrayExtra(EXTRA_DAYS))
        if (days.isEmpty()) return
        scope.launch {
            try {
                refresh(days)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "summary refresh failed", e)
            }
        }
    }

    /** Names and parsing. */
    public companion object {
        /** The broadcast action (sent by `food.diary.BroadcastDayRefresher`). */
        public const val ACTION: String = "app.selfwatch.REFRESH_DAY"

        /** Extra: days as `int[]` of `YYYYMMDD`. */
        public const val EXTRA_DAYS: String = "days"

        private const val TAG = "DayRefreshReceiver"
        private const val MAX_DAYS = 400

        /** The real calendar days in [raw] (at most 400). */
        public fun valid(raw: IntArray?): Set<Day> {
            if (raw == null) return emptySet()
            val out = HashSet<Day>()
            for (v in raw.take(MAX_DAYS)) {
                val d = Day(v)
                try {
                    d.toLocalDate()
                    out.add(d)
                } catch (e: DateTimeException) {
                    SwLog.d(TAG) { "dropped day $v: $e" }
                }
            }
            return out
        }
    }
}
