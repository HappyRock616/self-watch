/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import app.selfwatch.model.log.SwLog
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Tells every process of the app which tables changed.
 *
 * After a write commits, the writer calls [notify]. Inside the process the tables are emitted on
 * [changes] at once. Other processes of the app (the `:watch` service and the screens) hear about it
 * through an explicit broadcast [ACTION] sent only to this package and guarded by the signature
 * permission `<applicationId>.permission.INTERNAL` (declared in core-db's manifest). The receiver is
 * registered not exported, and a process ignores its own broadcasts, so each change arrives once.
 *
 * One instance per process: use [get]. DAOs call [notify] for you through [SelfWatchDb].
 */
public class DbChanges internal constructor(ctx: Context) {
    private val app: Context = ctx.applicationContext ?: ctx
    private val permission: String = permissionFor(app.packageName)
    private val flow = MutableSharedFlow<Set<Table>>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every change set, from this process and (by broadcast) from the app's other processes. */
    public val changes: SharedFlow<Set<Table>> = flow.asSharedFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION) return
            if (intent.getIntExtra(EXTRA_PID, -1) == Process.myPid()) return
            val names = intent.getStringArrayExtra(EXTRA_TABLES) ?: return
            val tables = names.mapNotNullTo(HashSet()) { Table.fromSql(it) }
            if (tables.isNotEmpty()) flow.tryEmit(tables)
        }
    }

    init {
        ContextCompat.registerReceiver(
            app,
            receiver,
            IntentFilter(ACTION),
            permission,
            null,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Announces that [tables] changed. Call after the transaction committed. Never blocks. */
    public fun notify(tables: Set<Table>) {
        if (tables.isEmpty()) return
        flow.tryEmit(tables)
        val intent = Intent(ACTION)
            .setPackage(app.packageName)
            .putExtra(EXTRA_TABLES, tables.map { it.sql }.toTypedArray())
            .putExtra(EXTRA_PID, Process.myPid())
        try {
            app.sendBroadcast(intent, permission)
        } catch (e: RuntimeException) {
            // The in-process flow already fired; only the other process misses this notice.
            SwLog.w(TAG, "DB_CHANGED broadcast failed", e)
        }
    }

    /**
     * Emits whenever one of [tables] changes (in any process of the app). When [emitInitial] is true
     * (the default) it also emits once at the start, so a screen can collect and re-query in one place.
     */
    public fun observe(tables: Set<Table>, emitInitial: Boolean = true): Flow<Unit> {
        val changed = flow.filter { changedSet -> changedSet.any { it in tables } }.map { }
        return if (emitInitial) changed.onStart { emit(Unit) } else changed
    }

    /** Names and the per-process instance. */
    public companion object {
        /** The broadcast action. */
        public const val ACTION: String = "app.selfwatch.DB_CHANGED"

        /** Extra: table names (`String[]`). */
        public const val EXTRA_TABLES: String = "tables"

        /** Extra: the sender's process id (`Int`). */
        public const val EXTRA_PID: String = "pid"

        private const val TAG: String = "DbChanges"

        // Holds only the application context, so it cannot leak an activity.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: DbChanges? = null

        /** The permission that guards the broadcast for the app [packageName]. */
        public fun permissionFor(packageName: String): String = "$packageName.permission.INTERNAL"

        /** This process's instance. */
        public fun get(ctx: Context): DbChanges =
            instance ?: synchronized(this) { instance ?: DbChanges(ctx).also { instance = it } }

        /** Test only (used by core-db's `TestDb` fixture): a new instance, separate from the shared one. */
        @VisibleForTesting
        public fun createForTest(ctx: Context): DbChanges = DbChanges(ctx)

        /** Test only: forgets the shared instance, so the next [get] makes a fresh one. */
        @VisibleForTesting
        public fun resetSharedForTest() {
            synchronized(this) { instance = null }
        }
    }
}
