/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.diary

import android.content.Context
import android.content.Intent
import app.selfwatch.db.DbChanges
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Day

/**
 * Asks the always-on service to rebuild the day summaries of the given days (the energy card and the
 * watch's calories left read them). Food services call it after every change.
 */
fun interface DayRefresher {
    /** Requests a summary refresh for [days]. Must not block. */
    fun refresh(days: Set<Day>)

    /** Constants and the no-op refresher. */
    companion object {
        /** Does nothing (tests, or when no service runs). */
        val NONE: DayRefresher = DayRefresher { }
    }
}

/**
 * The app's [DayRefresher]: an explicit broadcast [ACTION] to this package, guarded by the app's
 * internal signature permission (the same one `DbChanges` uses), carrying the days as `YYYYMMDD`
 * ints in [EXTRA_DAYS]. The service registers the receiver and calls `SummaryBuilder.refresh(days)`.
 */
class BroadcastDayRefresher(ctx: Context) : DayRefresher {
    private val app: Context = ctx.applicationContext ?: ctx

    override fun refresh(days: Set<Day>) {
        if (days.isEmpty()) return
        val intent = Intent(ACTION)
            .setPackage(app.packageName)
            .putExtra(EXTRA_DAYS, days.map { it.yyyymmdd }.toIntArray())
        try {
            app.sendBroadcast(intent, DbChanges.permissionFor(app.packageName))
        } catch (e: RuntimeException) {
            SwLog.w(TAG, "REFRESH_DAY broadcast failed", e)
        }
    }

    /** Names. */
    companion object {
        /** The broadcast action. */
        const val ACTION: String = "app.selfwatch.REFRESH_DAY"

        /** Extra: the days to refresh (`int[]` of `YYYYMMDD`). */
        const val EXTRA_DAYS: String = "days"

        private const val TAG = "DayRefresher"
    }
}
