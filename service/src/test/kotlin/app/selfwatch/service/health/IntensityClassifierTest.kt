/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.health

import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import org.junit.Assert.assertEquals
import org.junit.Test

class IntensityClassifierTest {
    private val dev = 3L
    private val t0 = 1_795_600_000L / 60 * 60
    private val bounds = listOf(89, 106, 124, 142, 159)

    private fun minute(i: Int, steps: Int, intensity: Int = 0) = ActivityMinute(dev, t0 + i * 60L, steps, 0, 0, intensity, null)
    private fun hr(i: Int, bpm: Int, sec: Long = 0) = MetricSample(dev, MetricKind.HEART_RATE, t0 + i * 60L + sec, bpm)

    private fun levels(minutes: List<ActivityMinute>, hr: List<MetricSample>): Map<Int, Int> {
        val changed = IntensityClassifier.classify(dev, minutes, hr, bounds).associateBy { it.tSec }
        val all = (minutes.map { it.tSec } + changed.keys).distinct().sorted()
        return all.associate { t -> ((t - t0) / 60).toInt() to (changed[t]?.intensity ?: minutes.first { it.tSec == t }.intensity) }
    }

    @Test
    fun zone2IsModerateZone4IsVigorousBelowIsNot() {
        val m = listOf(minute(0, 10), minute(1, 80), minute(2, 150), minute(3, 0))
        val h = listOf(hr(0, 100), hr(1, 115), hr(2, 150), hr(3, 104))
        // Minute 3: anxious 104 bpm sitting still is not active.
        assertEquals(mapOf(0 to 1, 1 to 2, 2 to 3, 3 to 0), levels(m, h))
    }

    @Test
    fun aMinuteWithoutAReadingTakesThePreviousMinutesOnly() {
        val m = listOf(minute(0, 90), minute(1, 90), minute(2, 90), minute(3, 90))
        val h = listOf(hr(0, 130, 20))
        assertEquals(mapOf(0 to 2, 1 to 2, 2 to 1, 3 to 1), levels(m, h))
    }

    @Test
    fun oldWatchMarksWithoutHeartRateAreCleared() {
        val m = listOf(minute(0, 0, intensity = 3), minute(1, 40, intensity = 2))
        assertEquals(mapOf(0 to 0, 1 to 1), levels(m, emptyList()))
    }

    @Test
    fun zone2HeartRateWithoutAnActivityRowIsAdded() {
        val out = IntensityClassifier.classify(dev, emptyList(), listOf(hr(5, 120), hr(6, 95)), bounds)
        assertEquals(listOf(ActivityMinute(dev, t0 + 300, 0, 0, 0, 2, null)), out)
    }
}
