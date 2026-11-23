/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.diary

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.db.dao.Fast
import app.selfwatch.food.R
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * A running or finished [fast] seen at time `nowSec`: [elapsedSec] so far (or in total when ended),
 * [remainingSec] to the goal (0 once reached) and whether the goal is [reached].
 */
data class FastStatus(val fast: Fast, val elapsedSec: Long, val remainingSec: Long, val reached: Boolean) {
    /** Progress towards the goal, 0..1000 (per mille). */
    val progressPermille: Int
        get() {
            val goal = fast.goalMin * 60L
            if (goal <= 0) return 1000
            return (elapsedSec.coerceAtLeast(0) * 1000 / goal).coerceAtMost(1000).toInt()
        }

    /** Constants and the status rule. */
    companion object {
        /** The status of [f] at [nowSec]. */
        fun of(f: Fast, nowSec: Long): FastStatus {
            val end = f.endSec ?: nowSec
            val elapsed = (end - f.startSec).coerceAtLeast(0)
            val goal = f.goalMin * 60L
            return FastStatus(f, elapsed, (goal - elapsed).coerceAtLeast(0), elapsed >= goal)
        }
    }
}

/** Shows or clears the ongoing "fasting" notification. */
interface FastNotifier {
    /** Shows (or updates) the timer for the running [fast]. */
    fun show(fast: Fast)

    /** Removes the timer. */
    fun clear()

    /** Constants and the no-op notifier. */
    companion object {
        /** Shows nothing (tests, or notifications off). */
        val NONE: FastNotifier = object : FastNotifier {
            override fun show(fast: Fast) = Unit

            override fun clear() = Unit
        }
    }
}

/**
 * Fasting: start, end, edit, and the running fast's status. Only one fast runs at a time; starting
 * a new one ends the running one first. The running fast shows a timer notification through
 * [notifier] (in the app process).
 */
class FastService(
    private val db: SelfWatchDb,
    private val clock: Clock,
    private val notifier: FastNotifier = FastNotifier.NONE,
) {
    /** Starts a fast now (or at [startSec]) aiming for [goalMin] minutes; returns its id. */
    suspend fun start(goalMin: Int, startSec: Long = clock.nowSec()): Long {
        require(goalMin > 0) { "goal must be positive" }
        db.fasts.current()?.let { db.fasts.end(it.id, startSec.coerceAtLeast(it.startSec)) }
        val id = db.fasts.start(startSec, goalMin)
        notifier.show(Fast(id, startSec, null, goalMin))
        return id
    }

    /** Ends the running fast now (or at [endSec]); returns its final status, or null if none was running. */
    suspend fun end(endSec: Long = clock.nowSec()): FastStatus? {
        val f = db.fasts.current() ?: return null
        val end = endSec.coerceAtLeast(f.startSec)
        db.fasts.end(f.id, end)
        notifier.clear()
        return FastStatus.of(f.copy(endSec = end), end)
    }

    /** Corrects fast `f.id` (times or goal). Returns true if it existed. */
    suspend fun edit(f: Fast): Boolean {
        val end = f.endSec
        require(end == null || end >= f.startSec) { "end before start" }
        val ok = db.fasts.update(f)
        refreshNotification()
        return ok
    }

    /** Deletes fast [id]. Returns true if it existed. */
    suspend fun delete(id: Long): Boolean {
        val ok = db.fasts.delete(id)
        refreshNotification()
        return ok
    }

    /** The running fast's status now, or null. */
    suspend fun current(): FastStatus? = db.fasts.current()?.let { FastStatus.of(it, clock.nowSec()) }

    /** The last [limit] fasts with their status, newest first. */
    suspend fun history(limit: Int): List<FastStatus> = db.fasts.list(limit).map { FastStatus.of(it, clock.nowSec()) }

    /** The running fast's status after every fast change (re-read the clock for a ticking display). */
    fun observeCurrent(): Flow<FastStatus?> = db.changes.observe(setOf(Table.FAST)).map { current() }

    /** Re-shows or clears the notification to match the database (call at app start). */
    suspend fun refreshNotification() {
        val f = db.fasts.current()
        if (f == null) notifier.clear() else notifier.show(f)
    }
}

/**
 * The ongoing fasting notification: a counting chronometer from the fast's start, on its own
 * low-importance channel. Skipped (and logged) when notifications are not allowed.
 */
class AndroidFastNotifier(ctx: Context) : FastNotifier {
    private val app: Context = ctx.applicationContext ?: ctx

    override fun show(fast: Fast) {
        if (!allowed()) {
            SwLog.i(TAG, "fast notification skipped: notifications not allowed")
            return
        }
        ensureChannel()
        val hours = fast.goalMin / 60
        val minutes = fast.goalMin % 60
        val n = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_fast_timer)
            .setContentTitle(app.getString(R.string.fast_notification_title))
            .setContentText(app.getString(R.string.fast_notification_goal, hours, minutes))
            .setWhen(fast.startSec * 1000)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            SwLog.w(TAG, "fast notification refused", e)
        }
    }

    override fun clear() {
        NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
    }

    private fun allowed(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(app).areNotificationsEnabled()
    }

    private fun ensureChannel() {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, app.getString(R.string.fast_channel_name), NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    /** Ids. */
    companion object {
        /** Notification channel id. */
        const val CHANNEL_ID: String = "fast"

        /** Notification id. */
        const val NOTIFICATION_ID: Int = 4101

        private const val TAG = "FastNotifier"
    }
}
