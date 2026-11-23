/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.source

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.Build
import app.selfwatch.model.log.SwLog
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** File and format constants of the read-only data files (FOODDB_SPEC Part B §1, §2, §10). */
object FoodDbFormat {
    /** `PRAGMA application_id` of foods.db (`0x53574644`, "SWFD"). */
    const val FOODS_APPLICATION_ID: Int = 1398228548

    /** `PRAGMA application_id` of recipes.db (`0x53575242`, "SWRB"). */
    const val RECIPES_APPLICATION_ID: Int = 1398231618

    /** The schema version this app understands (`PRAGMA user_version` and `meta.schema_version`). */
    const val SCHEMA_VERSION: Int = 1

    /** Folder under the app's files dir that holds the data files. */
    const val DIR: String = "fooddata"

    /** The food database file name. */
    const val FOODS_FILE: String = "foods.db"

    /** The recipe book file name. */
    const val RECIPES_FILE: String = "recipes.db"

    /** Opens [file] read-only the way the app always does (no localized collators, so no `android_metadata`). */
    fun openReadOnly(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(
        file.path,
        null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    )

    /**
     * Opens [file] read-write in rollback-journal (DELETE) mode, never WAL, so the file stays a single
     * file. Only for checking a staged copy: SQLite 3.44+ validates FTS4 indexes in `quick_check` /
     * `integrity_check`, and that fails on a read-only connection.
     */
    fun openForChecks(file: File): SQLiteDatabase {
        val flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val params = SQLiteDatabase.OpenParams.Builder().setOpenFlags(flags).setJournalMode("DELETE").build()
            SQLiteDatabase.openDatabase(file, params)
        } else {
            // Android 8 has no "compatibility WAL", so the default journal mode is a rollback journal.
            SQLiteDatabase.openDatabase(file.path, null, flags)
        }
    }

    /** `PRAGMA application_id` of [db]. */
    fun applicationId(db: SQLiteDatabase): Int = db.rawQuery("PRAGMA application_id", null).use { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }
}

/**
 * Shared, lazily opened read-only connection to `files/fooddata/foods.db`.
 *
 * The file is opened on first [use] and checked (`application_id` 1398228548 and `user_version` 1);
 * a missing or foreign file makes [use] return null. The connection closes [idleMs] after the last
 * use (3 minutes by default) so it holds no memory while food screens are closed, and [close] closes
 * it at once (the installer does this before swapping the file).
 *
 * @param scope owns the idle timer (the app's food scope; cancelled with it).
 * @param io where SQLite runs.
 */
class FoodsDbHandle(
    private val file: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val idleMs: Long = IDLE_MS,
    private val expectedApplicationId: Int = FoodDbFormat.FOODS_APPLICATION_ID,
) {
    private val mutex = Mutex()
    private var db: SQLiteDatabase? = null
    private var closer: Job? = null

    /** True when the database file exists (it may still fail its checks when opened). */
    val isInstalled: Boolean get() = file.isFile

    /** True while the connection is open. */
    val isOpen: Boolean get() = db?.isOpen == true

    /**
     * Runs [block] on the open database and returns its result, or null when the file is missing,
     * fails its checks, or SQLite fails (logged; the connection is then closed so the next use
     * reopens it). Uses are serialised.
     */
    suspend fun <T> use(block: (SQLiteDatabase) -> T): T? = mutex.withLock {
        try {
            withContext(io) {
                val d = db ?: open()?.also { db = it }
                d?.let(block)
            }
        } catch (e: SQLiteException) {
            SwLog.w(TAG, "foods.db query failed", e)
            closeNow()
            null
        } finally {
            scheduleClose()
        }
    }

    /** Closes the connection now (the next [use] reopens it). */
    suspend fun close() {
        mutex.withLock {
            closer?.cancel()
            closer = null
            closeNow()
        }
    }

    private fun scheduleClose() {
        closer?.cancel()
        if (db == null) return
        closer = scope.launch {
            delay(idleMs)
            mutex.withLock { closeNow() }
        }
    }

    private fun closeNow() {
        val d = db ?: return
        db = null
        try {
            d.close()
        } catch (e: SQLiteException) {
            SwLog.w(TAG, "closing foods.db failed", e)
        }
    }

    private fun open(): SQLiteDatabase? {
        if (!file.isFile) return null
        val d = FoodDbFormat.openReadOnly(file)
        val appId = FoodDbFormat.applicationId(d)
        val version = d.version
        if (appId != expectedApplicationId || version != FoodDbFormat.SCHEMA_VERSION) {
            SwLog.w(TAG, "foods.db rejected: application_id $appId, user_version $version")
            d.close()
            return null
        }
        return d
    }

    /** Constants. */
    companion object {
        /** Idle time before the connection closes: 3 minutes. */
        const val IDLE_MS: Long = 180_000L

        private const val TAG = "FoodsDbHandle"
    }
}
