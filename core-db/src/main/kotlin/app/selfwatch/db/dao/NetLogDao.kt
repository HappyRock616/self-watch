/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db.dao

import android.database.Cursor
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.db.args
import app.selfwatch.db.queryList
import app.selfwatch.db.read
import app.selfwatch.db.withStatement
import app.selfwatch.db.write
import app.selfwatch.model.net.NetFeature

/**
 * One network request.
 *
 * @property result HTTP status, or [BLOCKED], [OFFLINE], [ERROR].
 */
public data class NetLogEntry(
    val id: Long,
    val tSec: Long,
    val feature: NetFeature,
    val host: String,
    val method: String,
    val bytesUp: Long,
    val bytesDown: Long,
    val result: Int,
) {
    /** Result codes that are not HTTP statuses. */
    public companion object {
        /** The firewall blocked the request. */
        public const val BLOCKED: Int = -1

        /** No network. */
        public const val OFFLINE: Int = -2

        /** The request failed (DNS, TLS, timeout …). */
        public const val ERROR: Int = -3
    }
}

/** Network log (table `net_log`). Rows older than [RETENTION_DAYS] days are pruned daily with [prune]. */
public class NetLogDao internal constructor(private val db: SelfWatchDb) {

    /** Logs [e] (its `id` is ignored); returns the new id. */
    public suspend fun insert(e: NetLogEntry): Long = db.write(setOf(Table.NET_LOG)) { d ->
        d.withStatement(
            "INSERT INTO net_log (t_sec, feature, host, method, bytes_up, bytes_down, result) VALUES (?, ?, ?, ?, ?, ?, ?)",
        ) { st ->
            st.bindLong(1, e.tSec)
            st.bindLong(2, e.feature.code.toLong())
            st.bindString(3, e.host)
            st.bindString(4, e.method)
            st.bindLong(5, e.bytesUp)
            st.bindLong(6, e.bytesDown)
            st.bindLong(7, e.result.toLong())
            st.executeInsert()
        }
    }

    /** Up to [limit] entries, newest first, only for [feature] when given. */
    public suspend fun recent(limit: Int, feature: NetFeature? = null): List<NetLogEntry> = db.read { d ->
        val n = limit.coerceAtLeast(0)
        if (feature == null) {
            d.queryList("$SELECT ORDER BY t_sec DESC, id DESC LIMIT $n", null, ::map).filterNotNull()
        } else {
            d.queryList("$SELECT WHERE feature = ? ORDER BY t_sec DESC, id DESC LIMIT $n", args(feature.code), ::map).filterNotNull()
        }
    }

    /** Entries at or after [sinceSec], oldest first. */
    public suspend fun since(sinceSec: Long): List<NetLogEntry> = db.read {
        it.queryList("$SELECT WHERE t_sec >= ? ORDER BY t_sec, id", args(sinceSec), ::map).filterNotNull()
    }

    /** Deletes entries older than [olderThanSec]; returns how many. */
    public suspend fun prune(olderThanSec: Long): Int = db.write(setOf(Table.NET_LOG)) {
        it.delete("net_log", "t_sec < ?", args(olderThanSec))
    }

    /** Retention. */
    public companion object {
        /** Days the network log keeps. */
        public const val RETENTION_DAYS: Int = 90

        private const val SELECT = "SELECT id, t_sec, feature, host, method, bytes_up, bytes_down, result FROM net_log"

        private fun map(c: Cursor): NetLogEntry? {
            val f = NetFeature.fromCode(c.getInt(2)) ?: return null
            return NetLogEntry(c.getLong(0), c.getLong(1), f, c.getString(3), c.getString(4), c.getLong(5), c.getLong(6), c.getInt(7))
        }
    }
}
