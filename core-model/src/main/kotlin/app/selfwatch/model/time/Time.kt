/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.model.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A local calendar day stored as a `YYYYMMDD` integer (for example `20261123`).
 *
 * A day has no time zone of its own: it is the calendar day the user experienced. Convert from an
 * instant with [of] (`tSec`, zone) and back to an instant range with [toTimeRange]; both are correct
 * across daylight-saving changes (a day can be 23 or 25 hours long).
 */
@JvmInline
public value class Day(public val yyyymmdd: Int) : Comparable<Day> {

    /** The year, for example `2026`. */
    public val year: Int get() = yyyymmdd / 10_000

    /** The month, `1..12`. */
    public val month: Int get() = (yyyymmdd / 100) % 100

    /** The day of the month, `1..31`. */
    public val dayOfMonth: Int get() = yyyymmdd % 100

    /** This day as a [LocalDate]. Throws [java.time.DateTimeException] if the value is not a real date. */
    public fun toLocalDate(): LocalDate = LocalDate.of(year, month, dayOfMonth)

    /** The day [n] calendar days later (or earlier when [n] is negative). */
    public fun plusDays(n: Int): Day = of(toLocalDate().plusDays(n.toLong()))

    /** UTC epoch seconds of the first instant of this day in [zone] (handles midnight DST gaps). */
    public fun startSec(zone: ZoneId): Long = toLocalDate().atStartOfDay(zone).toEpochSecond()

    /** The instants of this day in [zone]: from its start to the start of the next day (end exclusive). */
    public fun toTimeRange(zone: ZoneId): TimeRange = TimeRange(startSec(zone), plusDays(1).startSec(zone))

    override fun compareTo(other: Day): Int = yyyymmdd.compareTo(other.yyyymmdd)

    override fun toString(): String = yyyymmdd.toString()

    /** Factories for [Day]. */
    public companion object {
        /** The day of [date]. */
        public fun of(date: LocalDate): Day = Day(date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth)

        /** The local day in [zone] that contains the UTC epoch second [tSec]. */
        public fun of(tSec: Long, zone: ZoneId): Day = of(Instant.ofEpochSecond(tSec).atZone(zone).toLocalDate())
    }
}

/** A span of time in UTC epoch seconds, [startSec] inclusive and [endSec] exclusive. */
public data class TimeRange(val startSec: Long, val endSec: Long) {
    /** Length in seconds (never negative). */
    public val durationSec: Long get() = (endSec - startSec).coerceAtLeast(0)

    /** True when [tSec] lies in `[startSec, endSec)`. */
    public operator fun contains(tSec: Long): Boolean = tSec >= startSec && tSec < endSec
}

/** A run of calendar days, [first] and [last] both inclusive. */
public data class DayRange(val first: Day, val last: Day) {
    /** True when [day] lies in `[first, last]`. */
    public operator fun contains(day: Day): Boolean = day >= first && day <= last

    /** The days in order, first to last (empty if [last] is before [first]). */
    public fun days(): List<Day> {
        val out = ArrayList<Day>()
        var d = first
        while (d <= last) {
            out.add(d)
            d = d.plusDays(1)
        }
        return out
    }
}

/** The source of "now" and the user's time zone. Inject it; tests use `FakeClock` from the test fixtures. */
public interface Clock {
    /** Current time as UTC epoch seconds. */
    public fun nowSec(): Long

    /** The phone's current time zone. */
    public fun zone(): ZoneId

    /** Today's local day in [zone]. */
    public fun today(): Day = Day.of(nowSec(), zone())
}

/** The real clock: system time and the system default time zone. */
public object SystemClock : Clock {
    override fun nowSec(): Long = System.currentTimeMillis() / 1000

    override fun zone(): ZoneId = ZoneId.systemDefault()
}
