/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.health

import app.selfwatch.model.food.Sex
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.SleepSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SummaryRulesTest {
    private fun hr(t: Long, v: Int, dev: Long = 1) = MetricSample(dev, MetricKind.HEART_RATE, t, v)

    @Test
    fun intensityWeights() {
        assertEquals(listOf(0, 0, 1, 2, 2), listOf(0, 1, 2, 3, 4).map { SummaryRules.intensityWeight(it) })
    }

    @Test
    fun activityIsNullWithoutMinutesAndDedupesWithinAMinute() {
        assertNull(SummaryRules.activity(emptyList()) { 0 })
        // Two devices, timestamps 20 s apart in the same minute: one counts.
        val t = SummaryRules.activity(
            listOf(ActivityMinute(2, 600, 10, 0, 0, 3, null), ActivityMinute(1, 620, 99, 0, 0, 0, null)),
        ) { if (it == 2L) 0 else 1 }!!
        assertEquals(10, t.steps)
        assertEquals(2, t.activeMin)
    }

    @Test
    fun lowestWindowNeedsEnoughSamplesAndMustEndInTheNight() {
        // Four very low samples are not enough for a window.
        val sparse = listOf(hr(0, 40), hr(60, 40), hr(120, 40), hr(180, 40)) + (50..100).map { hr(it * 60L, 60) }
        assertEquals(60, SummaryRules.lowestWindowAverage(sparse, endSec = 10_000))
        // A window that would run past the end of the night is not used.
        val late = (0 until 60).map { hr(it * 60L, if (it >= 30) 45 else 58) }
        assertEquals(58, SummaryRules.lowestWindowAverage(late, endSec = 1800))
        assertNull(SummaryRules.lowestWindowAverage(emptyList(), 3600))
        // Zero readings (no contact) are ignored.
        assertEquals(55, SummaryRules.lowestWindowAverage((0 until 40).map { hr(it * 60L, if (it % 2 == 0) 0 else 55) }, 7200))
    }

    @Test
    fun topDeviceUsesRankThenId() {
        val samples = listOf(hr(0, 50, dev = 3), hr(0, 60, dev = 2), hr(60, 61, dev = 2))
        assertEquals(listOf(60, 61), SummaryRules.topDevice(samples) { if (it == 2L) 0 else 5 }.map { it.value })
        // Equal rank: the lower device id wins.
        assertEquals(listOf(60, 61), SummaryRules.topDevice(samples) { 0 }.map { it.value })
    }

    @Test
    fun sleepWithoutStagesIsTheWholeSession() {
        assertEquals(450, SummaryRules.sleepMinutes(SleepSession(1, 0, 450 * 60L, null), emptyList()))
    }

    @Test
    fun mifflinStJeor() {
        assertEquals(1750, SummaryRules.mifflinStJeor(8_000, 180, 36, Sex.MALE))
        assertEquals(1584, SummaryRules.mifflinStJeor(8_000, 180, 36, Sex.FEMALE)) // 1745 − 161
        assertEquals(1667, SummaryRules.mifflinStJeor(8_000, 180, 36, Sex.UNSPECIFIED)) // 1745 − 78
        assertNull(SummaryRules.mifflinStJeor(null, 180, 36, Sex.MALE))
        assertNull(SummaryRules.mifflinStJeor(8_000, null, 36, Sex.MALE))
        assertNull(SummaryRules.mifflinStJeor(8_000, 180, 0, Sex.MALE))
    }

    @Test
    fun prorate() {
        assertEquals(1750, SummaryRules.prorate(1750, 0, 100, 100))
        assertEquals(0, SummaryRules.prorate(1750, 0, 100, -5))
        assertEquals(438, SummaryRules.prorate(1750, 0, 100, 25)) // 437.5 rounded half up
    }
}
