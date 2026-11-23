/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.net

import app.selfwatch.db.DbChanges
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.db.dao.NetLogDao
import app.selfwatch.db.dao.NetLogEntry
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.net.NetFeature
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The user-readable record of every request (table `net_log`): feature, host, method, bytes up and
 * down, and the HTTP status or [NetLogEntry.BLOCKED] / [NetLogEntry.OFFLINE] / [NetLogEntry.ERROR].
 *
 * Only the host is ever stored, never the URL path or query (they can hold search words);
 * [record] strips anything that is not a host name, in case a caller passes a URL.
 */
public class NetworkLog internal constructor(
    private val insert: suspend (NetLogEntry) -> Unit,
    private val since: suspend (Long) -> List<NetLogEntry>,
    private val recentRows: suspend (Int) -> List<NetLogEntry>,
    private val pruneOlder: suspend (Long) -> Int,
    private val changes: () -> Flow<Unit>,
    private val nowSec: () -> Long = { System.currentTimeMillis() / 1000 },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    /** The log in [dao], refreshed on [changes] to table `net_log`. */
    public constructor(dao: NetLogDao, changes: DbChanges) : this(
        insert = { dao.insert(it) },
        since = { dao.since(it) },
        recentRows = { dao.recent(it) },
        pruneOlder = { dao.prune(it) },
        changes = { changes.observe(setOf(Table.NET_LOG)) },
    )

    /** The log in [db]. */
    public constructor(db: SelfWatchDb) : this(db.netLog, db.changes)

    /**
     * Writes one row: [up] and [down] are body bytes, [result] the HTTP status or a
     * [NetLogEntry] code. A database failure is logged, never thrown, so it cannot break a request.
     */
    public suspend fun record(f: NetFeature, host: String, method: String, up: Long, down: Long, result: Int) {
        val entry = NetLogEntry(
            id = 0,
            tSec = nowSec(),
            feature = f,
            host = hostOnly(host),
            method = method.uppercase().filter { it in 'A'..'Z' }.take(METHOD_MAX),
            bytesUp = up.coerceAtLeast(0),
            bytesDown = down.coerceAtLeast(0),
            result = result,
        )
        try {
            insert(entry)
        } catch (e: RuntimeException) {
            SwLog.w(TAG, "network log write failed", e)
        }
    }

    /** Today's rows (local time), oldest first, now and after every new row. */
    public fun today(): Flow<List<NetLogEntry>> = changes().map {
        since(LocalDate.now(zone()).atStartOfDay(zone()).toEpochSecond())
    }

    /** Up to [limit] newest rows, newest first, now and after every new row. */
    public fun recent(limit: Int): Flow<List<NetLogEntry>> = changes().map { recentRows(limit) }

    /** Deletes rows older than [NetLogDao.RETENTION_DAYS] days; returns how many. For the daily tick. */
    public suspend fun prune(): Int = pruneOlder(nowSec() - NetLogDao.RETENTION_DAYS * SECONDS_PER_DAY)

    internal companion object {
        private const val TAG: String = "NetworkLog"
        private const val METHOD_MAX: Int = 10
        private const val HOST_MAX: Int = 253
        private const val SECONDS_PER_DAY: Long = 86_400

        /**
         * Reduces [raw] to a lower-case host name: drops any scheme, user info, port, path, query and
         * fragment.
         */
        internal fun hostOnly(raw: String): String {
            var h = raw.trim()
            val scheme = h.indexOf("://")
            if (scheme >= 0) h = h.substring(scheme + 3)
            val end = h.indexOfAny(charArrayOf('/', '?', '#', '\\'))
            if (end >= 0) h = h.substring(0, end)
            val at = h.lastIndexOf('@')
            if (at >= 0) h = h.substring(at + 1)
            h = if (h.startsWith("[")) {
                h.substring(0, (h.indexOf(']') + 1).takeIf { it > 0 } ?: h.length)
            } else {
                h.substringBefore(':')
            }
            return h.lowercase().take(HOST_MAX)
        }
    }
}
