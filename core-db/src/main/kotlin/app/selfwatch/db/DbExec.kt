/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteStatement
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.SwError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Thrown by a DAO when the database stayed busy (the other process held the write lock) through the
 * busy timeout and every retry. [error] is the [SwError.Io] to show or log.
 */
public class DbBusyException(public val error: SwError.Io, cause: Throwable) : RuntimeException(error.detail, cause)

/** Retries after the busy timeout, per WP-03. */
internal const val BUSY_RETRIES: Int = 3

/** Gap between busy retries, milliseconds. */
internal const val BUSY_RETRY_GAP_MS: Long = 200

private const val TAG = "SelfWatchDb"

/** Runs [block], retrying [BUSY_RETRIES] times with [BUSY_RETRY_GAP_MS] gaps on `SQLITE_BUSY`. */
internal suspend fun <T> busyRetry(block: () -> T): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: SQLiteDatabaseLockedException) {
            if (attempt >= BUSY_RETRIES) {
                SwLog.e(TAG, "database busy after $BUSY_RETRIES retries", e)
                throw DbBusyException(SwError.Io("database busy"), e)
            }
            attempt++
            SwLog.w(TAG, "database busy, retry $attempt")
            delay(BUSY_RETRY_GAP_MS)
        }
    }
}

/**
 * Marks a coroutine that runs inside [SelfWatchDb.inTransaction]: DAO calls in it run directly on the
 * transaction's own thread and connection (no dispatch, no nested transaction) and collect the tables
 * they changed, which are announced once after the commit.
 */
internal class TxContext(val owner: SelfWatchDb, val db: SQLiteDatabase, val thread: Thread) : AbstractCoroutineContextElement(TxContext) {
    /** Tables written inside the transaction. */
    val tables: MutableSet<Table> = HashSet()

    /** Runs [block] on the transaction's connection; a call from another thread is a bug. */
    fun <T> run(block: (SQLiteDatabase) -> T): T {
        check(Thread.currentThread() === thread) { "database transaction used from another thread" }
        return block(db)
    }

    companion object Key : CoroutineContext.Key<TxContext>
}

/** The open transaction of this database in the calling coroutine, or null. */
private suspend fun SelfWatchDb.currentTx(): TxContext? = currentCoroutineContext()[TxContext]?.takeIf { it.owner === this }

/** Runs a read on `Dispatchers.IO` (or inside the caller's [SelfWatchDb.inTransaction]). */
internal suspend fun <T> SelfWatchDb.read(block: (SQLiteDatabase) -> T): T {
    currentTx()?.let { return it.run(block) }
    return withContext(Dispatchers.IO) { busyRetry { block(readableDatabase) } }
}

/**
 * Runs [block] in one non-exclusive transaction on `Dispatchers.IO`, then (after the commit) tells
 * [DbChanges] that [tables] changed. Inside [SelfWatchDb.inTransaction] it joins that transaction and
 * the notice waits for its commit.
 */
internal suspend fun <T> SelfWatchDb.write(tables: Set<Table>, block: (SQLiteDatabase) -> T): T {
    currentTx()?.let { tx ->
        val v = tx.run(block)
        tx.tables += tables
        return v
    }
    return withContext(Dispatchers.IO) {
        val result = busyRetry {
            val db = writableDatabase
            db.beginTransactionNonExclusive()
            try {
                val v = block(db)
                db.setTransactionSuccessful()
                v
            } finally {
                db.endTransaction()
            }
        }
        changes.notify(tables)
        result
    }
}

/** See [SelfWatchDb.inTransaction]. */
internal suspend fun <T> SelfWatchDb.transaction(tables: Set<Table>, block: suspend (SQLiteDatabase) -> T): T {
    currentTx()?.let { tx ->
        tx.tables += tables
        return block(tx.db)
    }
    // A thread of its own: Android binds a transaction to the thread that began it, so the block runs
    // (and resumes after any suspension) on that one thread.
    val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "SelfWatchDb-tx") }
    return executor.asCoroutineDispatcher().use { dispatcher ->
        withContext(dispatcher) {
            val db = writableDatabase
            busyRetry { db.beginTransactionNonExclusive() }
            val tx = TxContext(this@transaction, db, Thread.currentThread())
            tx.tables += tables
            val result = try {
                val v = withContext(tx) { block(db) }
                db.setTransactionSuccessful()
                v
            } finally {
                db.endTransaction()
            }
            changes.notify(tx.tables)
            result
        }
    }
}

/** Runs [block] with a compiled [sql] statement and closes it. */
internal inline fun <T> SQLiteDatabase.withStatement(sql: String, block: (SQLiteStatement) -> T): T {
    val st = compileStatement(sql)
    try {
        return block(st)
    } finally {
        st.close()
    }
}

/** Runs a query and maps every row. */
internal inline fun <T> SQLiteDatabase.queryList(sql: String, args: Array<String>?, map: (Cursor) -> T): List<T> =
    rawQuery(sql, args).use { c ->
        val out = ArrayList<T>(c.count.coerceAtLeast(0))
        while (c.moveToNext()) out.add(map(c))
        out
    }

/** Runs a query and maps the first row, or returns null. */
internal inline fun <T> SQLiteDatabase.queryOne(sql: String, args: Array<String>?, map: (Cursor) -> T): T? =
    rawQuery(sql, args).use { c -> if (c.moveToFirst()) map(c) else null }

/** A single long from the first column of the first row, or null. */
internal fun SQLiteDatabase.longOrNull(sql: String, args: Array<String>?): Long? =
    queryOne(sql, args) { it.longOrNull(0) }

internal fun Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)

internal fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)

internal fun Cursor.strOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

internal fun Cursor.bool(i: Int): Boolean = getInt(i) != 0

internal fun SQLiteStatement.bindLongOrNull(i: Int, v: Long?) {
    if (v == null) bindNull(i) else bindLong(i, v)
}

internal fun SQLiteStatement.bindIntOrNull(i: Int, v: Int?) {
    if (v == null) bindNull(i) else bindLong(i, v.toLong())
}

internal fun SQLiteStatement.bindStrOrNull(i: Int, v: String?) {
    if (v == null) bindNull(i) else bindString(i, v)
}

internal fun SQLiteStatement.bindBool(i: Int, v: Boolean) {
    bindLong(i, if (v) 1L else 0L)
}

/** Query arguments as strings (Android's rawQuery binds text; SQLite compares by affinity). */
internal fun args(vararg v: Any?): Array<String> = Array(v.size) { v[it].toString() }
