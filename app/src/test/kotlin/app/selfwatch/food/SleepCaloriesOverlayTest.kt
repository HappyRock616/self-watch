/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.time.Day
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

private const val M = SleepCaloriesOverlay.MISSING
private const val HOUR = 3_600L

/** The sleep and calories series (overlay O2): night N sits with the food of day N + 1. */
class SleepCaloriesOverlayTest {
    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val first = Day(20261101)

    /** A night that starts on the evening of [eveningOf] at 23:00 and lasts [hours]. */
    private fun night(eveningOf: Day, hours: Double): SleepSession {
        val start = eveningOf.startSec(zone) + 23 * HOUR
        return SleepSession(1, start, start + (hours * HOUR).toLong(), null)
    }

    @Test
    fun nightIsPairedWithTheNextDaysFood() {
        val last = first.plusDays(3)
        val o = SleepCaloriesOverlay.build(
            first,
            last,
            listOf(
                night(first.plusDays(-1), 8.0), // the night into 1 Nov
                night(first, 5.5), // the night into 2 Nov
                night(first.plusDays(2), 7.0), // the night into 4 Nov
                night(last, 6.0), // ends on 5 Nov: after the range
            ),
            zone,
            mapOf(first to 19_004L, first.plusDays(1) to 25_000L, first.plusDays(2) to 21_000L, first.plusDays(9) to 30_000L),
        )
        assertEquals(4, o.size)
        assertArrayEquals(intArrayOf(480, 330, M, 420), o.sleepMin)
        // The 5.5 h night shows on 2 Nov, with that day's 2,500 kcal.
        assertArrayEquals(intArrayOf(1_900, 2_500, 2_100, M), o.kcalIn)
        assertEquals(first.plusDays(1), o.day(1))
        assertTrue(o.hasPairs)
        assertTrue(o.hasData)
    }

    @Test
    fun theLongestSessionEndingThatDayIsTheNight() {
        val nap = SleepSession(1, first.startSec(zone) + 14 * HOUR, first.startSec(zone) + 15 * HOUR, null)
        val o = SleepCaloriesOverlay.build(first, first, listOf(nap, night(first.plusDays(-1), 6.5)), zone, emptyMap())
        assertArrayEquals(intArrayOf(390), o.sleepMin)
        assertFalse(o.hasPairs)
    }

    @Test
    fun averagesAfterShortAndLongerNightsNeedThreeDaysEach() {
        val days = 8
        val sleep = listOf(5.0, 6.0, 6.5, 8.0, 7.5, 7.0, 9.0, 4.0)
        val kcal = listOf(2_600, 2_400, 2_500, 2_000, 2_100, 2_200, 1_900, 3_000)
        val o = SleepCaloriesOverlay.build(
            first,
            first.plusDays(days - 1),
            sleep.mapIndexed { i, h -> night(first.plusDays(i - 1), h) },
            zone,
            kcal.mapIndexed { i, k -> first.plusDays(i) to k * 10L }.toMap(),
        )
        val c = o.compare()
        assertEquals(4, c.shortDays)
        assertEquals(2_625, c.shortKcal)
        assertEquals(4, c.longDays)
        assertEquals(2_050, c.longKcal)
        // With only two short nights there is no short average.
        val two = SleepCaloriesOverlay.build(first, first.plusDays(1), listOf(night(first.plusDays(-1), 5.0), night(first, 5.0)), zone, mapOf(first to 20_000L, first.plusDays(1) to 20_000L))
        assertNull(two.compare().shortKcal)
        assertEquals(2, two.compare().shortDays)
    }

    @Test
    fun layerChoiceIsStoredAsHiddenIds() {
        assertEquals(emptySet<String>(), SleepCaloriesLayers.decode(null))
        assertEquals(setOf(SleepCaloriesLayers.KCAL), SleepCaloriesLayers.decode("kcal_in, nonsense"))
        assertEquals("sleep,kcal_in", SleepCaloriesLayers.encode(setOf(SleepCaloriesLayers.KCAL, SleepCaloriesLayers.SLEEP)))
        assertEquals("", SleepCaloriesLayers.encode(emptySet()))
        assertEquals(emptySet<String>(), SleepCaloriesLayers.decode(""))
    }
}
