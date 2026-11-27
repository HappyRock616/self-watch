/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.model.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HrZoneMathTest {
    @Test
    fun percentOfMaxMatchesGarminDefaults() {
        // Born 1983, 2026: 220 − 43 = 177.
        assertEquals(listOf(89, 106, 124, 142, 159), HrZoneMath.bounds(HrZoneMethod.MAX_HR, 177, 75))
    }

    @Test
    fun heartRateReserveUsesRestingHr() {
        // Garmin forum example: max 155, resting 57 → zone 1 from 106.
        assertEquals(listOf(106, 116, 126, 135, 145), HrZoneMath.bounds(HrZoneMethod.HRR, 155, 57))
    }

    @Test
    fun zoneOfCountsTheBorderInTheHigherZone() {
        val b = listOf(89, 106, 124, 142, 159)
        assertEquals(0, HrZoneMath.zoneOf(88, b))
        assertEquals(1, HrZoneMath.zoneOf(105, b))
        assertEquals(2, HrZoneMath.zoneOf(106, b))
        assertEquals(4, HrZoneMath.zoneOf(142, b))
        assertEquals(5, HrZoneMath.zoneOf(200, b))
    }

    @Test
    fun anxiousHeartRateBelowZone2IsNotActive() {
        val b = HrZoneMath.bounds(HrZoneMethod.MAX_HR, 177, 75)
        assertEquals(1, HrZoneMath.intensityOf(HrZoneMath.zoneOf(100, b), moving = true))
        assertEquals(0, HrZoneMath.intensityOf(HrZoneMath.zoneOf(100, b), moving = false))
        assertEquals(2, HrZoneMath.intensityOf(HrZoneMath.zoneOf(120, b), moving = false))
        assertEquals(3, HrZoneMath.intensityOf(HrZoneMath.zoneOf(150, b), moving = true))
    }

    @Test
    fun maxHrIsManualElseAgeAndAHigherWorkoutIsOnlySuggested() {
        assertEquals(190 to MaxHrOrigin.MANUAL, HrZoneMath.maxHr(190, 1983, 2026))
        assertEquals(177 to MaxHrOrigin.AGE, HrZoneMath.maxHr(null, 1983, 2026))
        assertEquals(180 to MaxHrOrigin.DEFAULT, HrZoneMath.maxHr(null, null, 2026))
        assertEquals(177 to MaxHrOrigin.AGE, HrZoneMath.maxHr(40, 1983, 2026))
        assertEquals(185, HrZoneMath.suggestedMax(185, 177))
        assertNull(HrZoneMath.suggestedMax(160, 177))
        assertNull(HrZoneMath.suggestedMax(250, 177))
        assertNull(HrZoneMath.suggestedMax(null, 177))
    }

    @Test
    fun restingHrIsManualElseMeasuredElse60() {
        assertEquals(55 to RestingHrOrigin.MANUAL, HrZoneMath.restingHr(55, 75))
        assertEquals(75 to RestingHrOrigin.MEASURED, HrZoneMath.restingHr(null, 75))
        assertEquals(60 to RestingHrOrigin.DEFAULT, HrZoneMath.restingHr(null, null))
    }

    @Test
    fun storedBoundsRoundTripAndBadOnesAreRejected() {
        assertEquals(listOf(90, 110, 125, 140, 160), HrZoneMath.parse("90,110,125,140,160"))
        assertNull(HrZoneMath.parse("90,80,125,140,160"))
        assertNull(HrZoneMath.parse("90,110"))
        assertNull(HrZoneMath.parse(null))
        assertEquals("1,2,3,4,5", HrZoneMath.format(listOf(1, 2, 3, 4, 5)))
        assertTrue(HrZoneMath.valid(listOf(90, 110, 125, 140, 160)))
        assertFalse(HrZoneMath.valid(listOf(90, 90, 125, 140, 160)))
        assertEquals(HrZoneMethod.HRR, HrZoneMethod.of("hrr"))
        assertEquals(HrZoneMethod.MAX_HR, HrZoneMethod.of("x"))
    }
}
