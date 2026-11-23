/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.model.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class DayTest {
    private val detroit = ZoneId.of("America/Detroit")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, s: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).toEpochSecond(ZoneOffset.UTC)

    @Test fun roundTripsLocalDate() {
        val d = Day(20261123)
        assertEquals(LocalDate.of(2026, 11, 23), d.toLocalDate())
        assertEquals(d, Day.of(LocalDate.of(2026, 11, 23)))
        assertEquals(2026, d.year)
        assertEquals(11, d.month)
        assertEquals(23, d.dayOfMonth)
        assertEquals("20261123", d.toString())
    }

    @Test fun plusDaysCrossesMonthYearAndLeapDay() {
        assertEquals(Day(20270101), Day(20261231).plusDays(1))
        assertEquals(Day(20280229), Day(20280228).plusDays(1))
        assertEquals(Day(20280301), Day(20280229).plusDays(1))
        assertEquals(Day(20261130), Day(20261201).plusDays(-1))
        assertEquals(Day(20261123), Day(20261123).plusDays(0))
    }

    @Test fun ordering() {
        assertTrue(Day(20261123) < Day(20261124))
        assertTrue(Day(20251231) < Day(20260101))
    }

    @Test fun ofEpochSecondUsesZone() {
        // 2026-11-23 03:00 UTC is still the 22nd in Detroit (UTC-5) but the 23rd in Tokyo.
        val t = utc(2026, 11, 23, 3)
        assertEquals(Day(20261122), Day.of(t, detroit))
        assertEquals(Day(20261123), Day.of(t, tokyo))
        assertEquals(Day(20261123), Day.of(t, ZoneOffset.UTC))
    }

    @Test fun springForwardDayIs23Hours() {
        // 2026-03-08: Detroit jumps 02:00 EST -> 03:00 EDT.
        val day = Day(20260308)
        val r = day.toTimeRange(detroit)
        assertEquals(utc(2026, 3, 8, 5), r.startSec) // midnight EST = 05:00 UTC
        assertEquals(utc(2026, 3, 9, 4), r.endSec) // midnight EDT = 04:00 UTC
        assertEquals(23 * 3600L, r.durationSec)
        assertEquals(day, Day.of(r.startSec, detroit))
        assertEquals(day, Day.of(r.endSec - 1, detroit))
        assertEquals(day.plusDays(1), Day.of(r.endSec, detroit))
        assertEquals(Day(20260307), Day.of(r.startSec - 1, detroit))
        // 01:59:59 EST and 03:00:00 EDT are one second apart and the same day.
        assertEquals(day, Day.of(utc(2026, 3, 8, 6, 59, 59), detroit))
        assertEquals(day, Day.of(utc(2026, 3, 8, 7), detroit))
    }

    @Test fun fallBackDayIs25Hours() {
        // 2026-11-01: Detroit falls back 02:00 EDT -> 01:00 EST.
        val day = Day(20261101)
        val r = day.toTimeRange(detroit)
        assertEquals(utc(2026, 11, 1, 4), r.startSec) // midnight EDT
        assertEquals(utc(2026, 11, 2, 5), r.endSec) // midnight EST
        assertEquals(25 * 3600L, r.durationSec)
        // Both 01:30 local times (05:30 and 06:30 UTC) belong to the same day.
        assertEquals(day, Day.of(utc(2026, 11, 1, 5, 30), detroit))
        assertEquals(day, Day.of(utc(2026, 11, 1, 6, 30), detroit))
        assertEquals(day, Day.of(r.endSec - 1, detroit))
        assertEquals(Day(20261102), Day.of(r.endSec, detroit))
    }

    @Test fun ordinaryDayIs24Hours() {
        assertEquals(24 * 3600L, Day(20261123).toTimeRange(detroit).durationSec)
        assertEquals(24 * 3600L, Day(20260308).toTimeRange(tokyo).durationSec)
    }

    @Test fun dayRangeAndTimeRange() {
        val r = DayRange(Day(20261230), Day(20270102))
        assertEquals(listOf(Day(20261230), Day(20261231), Day(20270101), Day(20270102)), r.days())
        assertTrue(Day(20261231) in r)
        assertFalse(Day(20270103) in r)
        assertEquals(emptyList<Day>(), DayRange(Day(20261102), Day(20261101)).days())
        val t = TimeRange(10, 20)
        assertTrue(10L in t)
        assertFalse(20L in t)
        assertEquals(0L, TimeRange(20, 10).durationSec)
    }
}
