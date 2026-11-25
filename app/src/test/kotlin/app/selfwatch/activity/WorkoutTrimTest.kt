/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.TrackPoint
import app.selfwatch.model.health.Workout
import app.selfwatch.ui.format.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trim arithmetic and the per-sport dashboard numbers (plain JVM). */
class WorkoutTrimMathTest {

    private val t0 = 1_795_000_000L

    /**
     * A one-hour recording, a point every 10 s: a 30-minute run north at 3 m/s with heart rate 150 and a
     * steady climb, then 30 minutes of driving home (15 m/s, heart rate 80, flat).
     */
    private val points: List<TrackPoint> = (0..360).map { i ->
        val t = t0 + i * 10L
        val running = i <= 180
        // 3 m/s north is about 270 E7 units (1e-7 degree is about 1.11 cm) per second.
        val lat = if (running) 476_000_000 + i * 2_700 else 476_000_000 + 180 * 2_700 + (i - 180) * 13_500
        val alt = if (running) 10_000 + i * 1_000 / 6 else 40_000
        TrackPoint(t, lat, -1_223_000_000, alt, if (running) 150 else 80, if (running) 85 else null, null, if (running) 3_000 else 15_000)
    }

    private val original = Workout(
        id = 5, deviceId = 1, sport = Sports.RUNNING, subSport = null, startSec = t0, endSec = t0 + 3600, durationSec = 3600,
        distanceM = 32_000, kcal = 900, avgHr = 115, maxHr = 150, ascentM = 30, descentM = 0, hasTrack = true,
        fitPath = "w.fit", trackPath = null, routePreview = byteArrayOf(1), source = DataSource.WATCH,
    )

    private val runOnly = TrimWindow(t0, t0 + 1800)

    @Test
    fun cuttingTheDriveRecomputesEveryStat() {
        val w = WorkoutTrimMath.trim(original, points, runOnly)
        assertEquals(t0, w.startSec)
        assertEquals(t0 + 1800, w.endSec)
        assertEquals(1800, w.durationSec)
        // GPS: 5.4 km run of 32.4 km total, so the watch's 32 km scales to about 5.3 km.
        assertEquals(5_330.0, w.distanceM!!.toDouble(), 60.0)
        assertEquals(150, w.avgHr)
        assertEquals(150, w.maxHr)
        // Calories follow the heart beats: 150 bpm for 30 min against 80 bpm for 30 min → 150/230 of 900.
        assertEquals(587.0, w.kcal!!.toDouble(), 5.0)
        assertEquals(30, w.ascentM)
        assertEquals(0, w.descentM)
        assertTrue(w.hasTrack)
        assertNotNull(w.routePreview)
        // What does not change: id, device, sport, files, source.
        assertEquals(original.copy(startSec = w.startSec, endSec = w.endSec, durationSec = w.durationSec, distanceM = w.distanceM, kcal = w.kcal, avgHr = w.avgHr, maxHr = w.maxHr, ascentM = w.ascentM, descentM = w.descentM, hasTrack = w.hasTrack, routePreview = w.routePreview), w)
        // Pace follows from the new duration and distance: 5:38 /km instead of 1:52.
        assertEquals("5:38", Sports.pace(w.durationSec, w.distanceM, Units.METRIC))
    }

    @Test
    fun cuttingTheStartWorksTheSameWay() {
        val w = WorkoutTrimMath.trim(original, points, TrimWindow(t0 + 1800, t0 + 3600))
        assertEquals(80, w.avgHr)
        assertEquals(26_670.0, w.distanceM!!.toDouble(), 60.0)
        assertEquals(0, w.ascentM)
    }

    @Test
    fun windowsAreClampedToTheRecordingAndAMinute() {
        val full = WorkoutTrimMath.fullWindow(original, points)
        assertEquals(TrimWindow(t0, t0 + 3600), full)
        assertEquals(full, WorkoutTrimMath.clamp(TrimWindow(t0 - 500, t0 + 9_999), full))
        assertEquals(60L, WorkoutTrimMath.clamp(TrimWindow(t0 + 100, t0 + 110), full).lengthSec)
        assertEquals(TrimWindow(t0 + 3540, t0 + 3600), WorkoutTrimMath.clamp(TrimWindow(t0 + 3590, t0 + 3600), full))
        assertTrue(WorkoutTrimMath.isWhole(full, full))
        assertFalse(WorkoutTrimMath.isWhole(runOnly, full))
        // The whole window gives the original back (up to rounding of the GPS share).
        val whole = WorkoutTrimMath.trim(original, points, full)
        assertEquals(original.durationSec, whole.durationSec)
        assertEquals(original.distanceM, whole.distanceM)
        assertEquals(original.kcal, whole.kcal)
    }

    @Test
    fun withoutATrackValuesScaleWithTime() {
        val plain = original.copy(hasTrack = false, routePreview = null, fitPath = null, durationSec = 3000)
        val w = WorkoutTrimMath.trim(plain, emptyList(), TrimWindow(t0, t0 + 900))
        assertEquals(750, w.durationSec) // pauses stay in proportion
        assertEquals(8_000, w.distanceM)
        assertEquals(225, w.kcal)
        assertEquals(115, w.avgHr) // no samples: unchanged
        assertEquals(30, w.ascentM) // no altitude: unchanged
        assertFalse(w.hasTrack)
        assertNull(w.routePreview)
    }

    @Test
    fun speedSamplesGiveTheDistanceWithoutPositions() {
        val noGps = points.map { it.copy(latE7 = null, lonE7 = null) }
        val w = WorkoutTrimMath.trim(original, noGps, runOnly)
        assertEquals(5_330.0, w.distanceM!!.toDouble(), 80.0)
        assertEquals(5_400.0, WorkoutTrimMath.integrateSpeedM(noGps.take(181)), 1.0)
        assertEquals(0.5, WorkoutTrimMath.beatShare(emptyList(), emptyList(), 0.5), 0.0)
    }
}

/** The per-sport dashboard's numbers and stored choices (plain JVM). */
class SportTrendsTest {
    private fun ride(i: Int, sport: Int = Sports.CYCLING) = Workout(
        id = i.toLong(), deviceId = 1, sport = sport, subSport = null, startSec = 1_795_000_000L + i * 86_400L,
        endSec = 1_795_000_000L + i * 86_400L + 3600, durationSec = 3600, distanceM = 30_000 + i * 1000, kcal = 700,
        avgHr = 135 + i, maxHr = 170, ascentM = 250, descentM = 250, hasTrack = false, fitPath = null, trackPath = null,
        routePreview = null, source = DataSource.WATCH,
    )

    @Test
    fun valuesCoverEveryMetricAWorkoutHas() {
        val v = SportTrendMath.values(ride(0), recoveryDrop = 24, cadence = 88)
        assertEquals(135, v[SportMetric.AVG_HR])
        assertEquals(170, v[SportMetric.MAX_HR])
        assertEquals(700, v[SportMetric.KCAL])
        assertEquals(30_000, v[SportMetric.DISTANCE])
        assertEquals(3600, v[SportMetric.DURATION])
        assertEquals(8_333, v[SportMetric.PACE]) // 30 km/h in mm/s
        assertEquals(24, v[SportMetric.RECOVERY])
        assertEquals(250, v[SportMetric.ASCENT])
        assertEquals(88, v[SportMetric.CADENCE])
        val bare = SportTrendMath.values(ride(0).copy(avgHr = null, maxHr = null, distanceM = null, kcal = null, ascentM = null), null, null)
        assertEquals(setOf(SportMetric.DURATION), bare.keys)
    }

    @Test
    fun recentKeepsTheLastWorkoutsOfTheSportOldestFirst() {
        val all = (0 until 30).map { ride(it) } + ride(40, Sports.RUNNING)
        val recent = SportTrendMath.recent(all.shuffled(), Sports.CYCLING)
        assertEquals(SportTrendMath.WORKOUTS, recent.size)
        assertEquals((10 until 30).map { it.toLong() }, recent.map { it.id })
        val d = SportDashboard(Sports.CYCLING, recent.map { SportPoint(it, SportTrendMath.values(it, null, null)) })
        assertFalse(d.tooFew)
        assertFalse(SportMetric.RECOVERY in d.metrics)
        assertTrue(SportDashboard(Sports.CYCLING, d.points.take(1)).tooFew)
    }

    @Test
    fun paceCadenceAndTrend() {
        assertEquals(300, SportTrendMath.paceSec(3_333, 1000.0)) // 3.33 m/s = 5:00 /km
        assertEquals(483, SportTrendMath.paceSec(3_333, Sports.METERS_PER_MILE)) // = 8:03 /mi
        assertNull(SportTrendMath.paceSec(0, 1000.0))
        assertEquals(87, SportTrendMath.cadence(listOf(85, 0, 89, null).map { TrackPoint(0, null, null, null, null, it, null, null) }))
        assertNull(SportTrendMath.cadence(emptyList()))
        assertEquals(listOf(15, 20, 30, 35), SportTrendMath.trend(intArrayOf(10, 20, 30, 40)).toList())
    }

    @Test
    fun vo2MaxJoinsRunsAndRidesFromTheEstimateAfterEachWorkout() {
        assertEquals(MetricKind.VO2_MAX_RUNNING, SportTrendMath.vo2Kind(Sports.RUNNING))
        assertEquals(MetricKind.VO2_MAX_CYCLING, SportTrendMath.vo2Kind(Sports.CYCLING))
        assertNull(SportTrendMath.vo2Kind(Sports.WALKING))
        val w = ride(1)
        val samples = listOf(
            MetricSample(1, MetricKind.VO2_MAX_CYCLING, w.startSec - 86_400, 500),
            // Saved with the workout, stamped a few minutes after its end: this is the one.
            MetricSample(1, MetricKind.VO2_MAX_CYCLING, w.endSec + 300, 505),
            MetricSample(1, MetricKind.VO2_MAX_CYCLING, w.endSec + SportTrendMath.VO2_AFTER_SEC + 60, 510),
        )
        assertEquals(505, SportTrendMath.vo2For(w, samples))
        assertNull(SportTrendMath.vo2For(w, samples.drop(1).map { it.copy(tSec = it.tSec + 86_400) }))
        val v = SportTrendMath.values(w, null, null, vo2X10 = 505)
        assertEquals(505, v[SportMetric.VO2_MAX])
        assertFalse(SportMetric.VO2_MAX in SportTrendMath.values(w, null, null))
        // Off by default (only heart rate and distance show until the user picks).
        assertTrue("vo2_max" in SportDashboardPrefs.decodeHidden(null))
    }

    @Test
    fun paceIsMinutesAndSecondsInTheChosenUnits() {
        // 25:00 for 5 km: 5:00 /km, 8:03 /mi.
        assertEquals("5:00", Sports.pace(1500, 5000, Units.METRIC))
        assertEquals("8:03", Sports.pace(1500, 5000, Units.IMPERIAL))
        assertEquals("5:00", Sports.paceText(300.0, Units.METRIC))
        // Rounded to the second before splitting: never "4:60".
        assertEquals("5:00", Sports.paceText(299.6, Units.METRIC))
        assertEquals("4:59", Sports.paceText(299.4, Units.METRIC))
        assertNull(Sports.pace(1500, 0, Units.METRIC))
    }

    @Test
    fun choicesDecodeWithDefaultsPerSport() {
        // Never chosen: average heart rate and distance on (duration when the sport has no distance).
        val withDistance = SportDashboardPrefs.decodeHidden(null, SportMetric.entries)
        assertFalse("avg_hr" in withDistance)
        assertFalse("distance" in withDistance)
        assertTrue("duration" in withDistance)
        val noDistance = SportDashboardPrefs.decodeHidden(null, listOf(SportMetric.AVG_HR, SportMetric.DURATION))
        assertFalse("duration" in noDistance)
        assertEquals(setOf("kcal", "cadence"), SportDashboardPrefs.decodeHidden("cadence, kcal,gone"))
        assertEquals("kcal,cadence", SportDashboardPrefs.encodeHidden(setOf("cadence", "kcal")))
        assertEquals("", SportDashboardPrefs.encodeHidden(emptySet()))
        assertEquals(emptySet<String>(), SportDashboardPrefs.decodeHidden(""))
        assertEquals(setOf(1, 2), SportDashboardPrefs.decodeRemoved("2,1,x"))
        assertEquals("1,2", SportDashboardPrefs.encodeRemoved(setOf(2, 1)))
        assertNull(SportDashboardPrefs.encodeRemoved(emptySet()))
        assertEquals("chart.sport_trend.2.layers", SportDashboardPrefs.layersKey(Sports.CYCLING))
    }
}
