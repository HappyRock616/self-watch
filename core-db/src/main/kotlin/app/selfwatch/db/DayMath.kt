/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db

import android.database.sqlite.SQLiteDatabase
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.TimeRange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A stretch of time with one UTC offset: `[startSec, endSec)` at [offsetSec]. */
internal data class OffsetSegment(val startSec: Long, val endSec: Long, val offsetSec: Int)

/** The instants of [days] in [zone]. */
internal fun DayRange.toTimeRange(zone: ZoneId): TimeRange = TimeRange(first.startSec(zone), last.plusDays(1).startSec(zone))

/**
 * Cuts the instants of [days] in [zone] into stretches of constant UTC offset, so SQL can turn
 * `t_sec` into a local day number with `(t_sec + offset) / 86400` inside each stretch.
 */
internal fun offsetSegments(days: DayRange, zone: ZoneId): List<OffsetSegment> {
    val range = days.toTimeRange(zone)
    val rules = zone.rules
    val out = ArrayList<OffsetSegment>()
    var s = range.startSec
    while (s < range.endSec) {
        val at = Instant.ofEpochSecond(s)
        val off = rules.getOffset(at).totalSeconds
        val next = rules.nextTransition(at)?.instant?.epochSecond ?: Long.MAX_VALUE
        val e = minOf(range.endSec, next)
        out.add(OffsetSegment(s, e, off))
        s = e
    }
    return out
}

/** The [Day] of a local epoch-day number (days since 1970-01-01). */
internal fun dayOfEpochDay(n: Long): Day = Day.of(LocalDate.ofEpochDay(n))

/**
 * The device ids present in [table] (optionally restricted by `kind = [kind]`), found with a loose
 * index scan over the primary key so it costs one index seek per device, not a table scan.
 */
internal fun SQLiteDatabase.deviceIdsIn(table: String, kind: Int? = null): List<Long> {
    val where = if (kind == null) "" else "kind = $kind AND "
    val first = if (kind == null) "" else "WHERE kind = $kind"
    val sql = "WITH RECURSIVE ids(x) AS (" +
        "SELECT (SELECT min(device_id) FROM $table $first) " +
        "UNION ALL SELECT (SELECT min(device_id) FROM $table WHERE $where device_id > x) FROM ids WHERE x IS NOT NULL) " +
        "SELECT x FROM ids WHERE x IS NOT NULL"
    return queryList(sql, null) { it.getLong(0) }
}

/** `(1,2,3)` for an SQL `IN` list of numbers. */
internal fun inList(ids: Collection<Long>): String = ids.joinToString(",", "(", ")")
