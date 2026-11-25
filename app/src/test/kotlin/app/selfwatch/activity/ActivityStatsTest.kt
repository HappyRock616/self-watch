/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import app.selfwatch.cards.MainGoal
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.DaySummary
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.Day
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** The pure arithmetic of the phase-2 Activity cards and overlays. */
class ActivityStatsTest {
    private val zone: ZoneId = ZoneId.of("Europe/London")

    @Test
    fun weeksStartOnMonday() {
        // 2026-11-23 is a Monday, 2026-11-29 a Sunday.
        assertEquals(Day(20261123), ActivityWeek.start(Day(20261123)))
        assertEquals(Day(20261123), ActivityWeek.start(Day(20261129)))
        assertEquals(Day(20261116), ActivityWeek.start(Day(20261122)))
        // Across a month and a year end.
        assertEquals(Day(20261228), ActivityWeek.start(Day(20270103)))
        val days = ActivityWeek.days(Day(20261126))
        assertEquals(7, days.size)
        assertEquals(Day(20261123), days.first())
        assertEquals(Day(20261129), days.last())
    }

    @Test
    fun vigorousMinutesCountDoubleAndDuplicatesOnce() {
        val day = Day(20261123)
        val start = day.startSec(zone)
        val minutes = (0 until 10).map { minute(start + 8 * 3_600 + it * 60L, 2) } +
            (0 until 5).map { minute(start + 9 * 3_600 + it * 60L, 3) } +
            // A second watch saw the same five vigorous minutes as moderate: counted once, as vigorous.
            (0 until 5).map { minute(start + 9 * 3_600 + it * 60L, 2, device = 2) } +
            (0 until 7).map { minute(start + 10 * 3_600 + it * 60L, 1) } +
            // Yesterday 23:59 belongs to yesterday.
            listOf(minute(start - 30, 3))
        val by = IntensityMath.byDay(minutes, zone)
        assertEquals(DayIntensity(10, 5), by[day])
        assertEquals(20, by.getValue(day).intensityMin)
        assertEquals(DayIntensity(0, 1), by[day.plusDays(-1)])
        // A day with only sedentary minutes has data (0), so the summary is not used for it.
        val quiet = Day(20261124)
        val quietBy = IntensityMath.byDay(listOf(minute(quiet.startSec(zone) + 3_600, 0)), zone)
        assertEquals(0, quietBy.getValue(quiet).intensityMin)
        val per = IntensityMath.perDay(listOf(day, quiet, quiet.plusDays(1), quiet.plusDays(2)), by + quietBy, mapOf(quiet to 40, quiet.plusDays(1) to 25))
        assertEquals(listOf(20, 0, 25, null), per)
        assertEquals(0, IntensityMath.activeDays(per, 30))
        assertEquals(2, IntensityMath.activeDays(listOf(30, 29, null, 45), 30))
    }

    @Test
    fun activeDayIsASeventhOfTheWeeklyGoal() {
        assertEquals(43, IntensityMath.activeDayMin(300))
        assertEquals(21, IntensityMath.activeDayMin(150))
        assertEquals(1, IntensityMath.activeDayMin(1))
        assertEquals(43, ActivityGoals.dailyShare(300))
        assertEquals(300, ActivityGoals.DEFAULT_ACTIVE_MIN_WEEK)
        assertEquals(10_000, ActivityGoals.DEFAULT_STEPS)
    }

    @Test
    fun strengthDaysFromSportTypes() {
        val d = Day(20261123).startSec(zone)
        val strength = workout(d + 3_600, sport = 10, sub = 20)
        val strength2 = workout(d + 7_200, sport = 10, sub = 20)
        val yoga = workout(d + 86_400, sport = 10, sub = 43)
        val pilates = workout(d + 2 * 86_400, sport = 10, sub = 44)
        val run = workout(d + 3 * 86_400, sport = 1)
        val list = listOf(strength, strength2, yoga, pilates, run)
        // Two sessions on one day count as one day.
        assertEquals(1, StrengthRules.days(list, withBalance = false, zone))
        assertEquals(3, StrengthRules.days(list, withBalance = true, zone))
        assertFalse(StrengthRules.counts(workout(d, sport = 10, sub = null), withBalance = true))
    }

    @Test
    fun maxHeartRate() {
        assertEquals(MaxHr(179, MaxHrSource.AGE), HrZones.maxHr(null, 1985, 2026))
        assertEquals(MaxHr(192, MaxHrSource.STORED), HrZones.maxHr(192, 1985, 2026))
        assertEquals(MaxHr(180, MaxHrSource.DEFAULT), HrZones.maxHr(null, null, 2026))
        // Implausible values are ignored.
        assertEquals(MaxHr(179, MaxHrSource.AGE), HrZones.maxHr(40, 1985, 2026))
        assertEquals(MaxHr(180, MaxHrSource.DEFAULT), HrZones.maxHr(null, 2025, 2026))
    }

    @Test
    fun zoneBoundaries() {
        // Max 200: 100 bpm is 50%, 120 is 60% …
        assertEquals(0, HrZones.zoneOf(99, 200))
        assertEquals(1, HrZones.zoneOf(100, 200))
        assertEquals(1, HrZones.zoneOf(119, 200))
        assertEquals(2, HrZones.zoneOf(120, 200))
        assertEquals(3, HrZones.zoneOf(140, 200))
        assertEquals(3, HrZones.zoneOf(159, 200))
        assertEquals(4, HrZones.zoneOf(160, 200))
        assertEquals(5, HrZones.zoneOf(180, 200))
        assertEquals(5, HrZones.zoneOf(230, 200))
    }

    @Test
    fun zoneTimeFromReadings() {
        val start = 1_000_000L
        // 30-minute workout, a reading every minute: 10 min at 130 (z2), 10 at 150 (z3), 10 at 170 (z4) for max 200.
        val readings = (0 until 30).map { i -> start + i * 60L to (if (i < 10) 130 else if (i < 20) 150 else 170) }
        val z = HrZones.zoneSeconds(readings, start, start + 1_800, 1_800, 140, 200) ?: error("no zones")
        assertArrayEquals(intArrayOf(0, 0, 600, 600, 600, 0), z)
        // Load = 10×2 + 10×3 + 10×4.
        assertEquals(90, HrZones.load(z))
        assertEquals(1_200, HrZones.easySec(z))
        assertEquals(600, HrZones.hardSec(z))
        // Readings outside the workout are ignored; a long gap counts at most 5 minutes.
        val sparse = listOf(start - 60 to 190, start to 150, start + 600 to 150, start + 1_200 to 150)
        val s = HrZones.zoneSeconds(sparse, start, start + 1_800, 1_500, null, 200) ?: error("no zones")
        // Covered 900 s of 1,800 (exactly half) → scaled to the 1,500 s moving time, all zone 3.
        assertArrayEquals(intArrayOf(0, 0, 0, 1_500, 0, 0), s)
    }

    @Test
    fun zoneTimeFallsBackToAverage() {
        val start = 1_000_000L
        // One reading covers 5 of 60 minutes: not enough, the average (zone 4 at max 200) stands for all.
        val z = HrZones.zoneSeconds(listOf(start to 120), start, start + 3_600, 3_600, 165, 200) ?: error("no zones")
        assertArrayEquals(intArrayOf(0, 0, 0, 0, 3_600, 0), z)
        assertEquals(240, HrZones.load(z))
        assertNull(HrZones.zoneSeconds(emptyList(), start, start + 3_600, 3_600, null, 200))
        // A missing end time uses the duration.
        assertNotNull(HrZones.zoneSeconds(emptyList(), start, 0, 600, 150, 200))
    }

    @Test
    fun longSessionFlag() {
        val day = 86_400L
        val now = 100 * day
        val runs = listOf(
            run(now - 40 * day, 15_000), // outside the 30 days before
            run(now - 20 * day, 8_000),
            run(now - 10 * day, 7_000),
        )
        // 8.8 km is exactly 10% longer than 8 km: not flagged.
        assertNull(LongSessionCheck.check(runs + run(now - day, 8_800), now))
        val long = run(now - 2 * day, 8_900)
        val flagged = LongSessionCheck.check(runs + long, now) ?: error("not flagged")
        assertEquals(long, flagged.workout)
        assertEquals(8_000, flagged.previousLongest)
        assertEquals(11, flagged.pctLonger)
        assertTrue(flagged.byDistance)
        // A later, shorter run does not hide the flagged one; the newest flagged session is reported.
        assertEquals(long, LongSessionCheck.check(runs + long + run(now - day, 5_000), now)?.workout)
        // Older than 7 days: no longer reported.
        assertNull(LongSessionCheck.check(runs + run(now - 8 * day, 9_500), now))
        // Walks are compared with walks and hikes only, by time when there is no distance.
        val walks = listOf(walk(now - 15 * day, 3_600, 11), walk(now - 5 * day, 4_200, 17))
        val flaggedWalk = LongSessionCheck.check(walks + runs, now) ?: error("walk not flagged")
        assertFalse(flaggedWalk.byDistance)
        assertEquals(17, flaggedWalk.pctLonger)
        // No earlier session of the same kind: nothing to compare.
        assertNull(LongSessionCheck.check(listOf(run(now - day, 20_000)), now))
        // Other sports are not checked.
        assertNull(LongSessionCheck.check(listOf(workout(now - 20 * day, 2), workout(now - day, 2, duration = 99_000)), now))
    }

    @Test
    fun trainingOverlaySmoothsAndBands() {
        val first = Day(20261101)
        val last = Day(20261110)
        // 60 nights before: HRV alternating 40 and 60 (mean 50), resting HR 55.
        val summaries = ArrayList<DaySummary>()
        for (i in -60 until 10) {
            val d = first.plusDays(i)
            val hrv = if (i < 0) (if (i % 2 == 0) 40 else 60) else 70
            summaries += summary(d, hrv = hrv, rhr = if (i < 5) 55 else 62)
        }
        val o = TrainingOverlay.build(first, last, mapOf(first to 80, first.plusDays(3) to 120), mapOf(first to 45), summaries)
        assertEquals(10, o.size)
        assertEquals(80, o.load[0])
        assertEquals(0, o.load[1])
        assertEquals(120, o.load[3])
        assertEquals(45, o.intensityMin[0])
        assertEquals(OverlayMath.MISSING, o.intensityMin[1])
        // Day 0: the band over the 60 nights before is 50 ± 0.5 × sd (sd of 40/60 alternating ≈ 10.08).
        assertEquals(45, o.hrvLow[0])
        assertEquals(55, o.hrvHigh[0])
        // The 7-day HRV average on day 0 is the six nights before plus day 0's 70: (3×40 + 3×60 + 70) / 7.
        assertEquals(53, o.hrv7[0])
        assertEquals(70, o.hrv7[9])
        // Resting HR: 55 until day 4, then 62; day 8 averages days 2..8 = (3×55 + 4×62) / 7.
        assertEquals(59, o.restingHr7[8])
        assertTrue(o.hasData)
        // Without enough nights there is no band, and short gaps keep the average.
        val sparse = TrainingOverlay.build(first, last, emptyMap(), emptyMap(), listOf(summary(first, hrv = 50, rhr = null)))
        assertTrue(sparse.hrvLow.all { it == OverlayMath.MISSING })
        assertEquals(OverlayMath.MISSING, sparse.hrv7[0])
        assertFalse(TrainingOverlay.build(first, last, emptyMap(), emptyMap(), emptyList()).hasData)
    }

    @Test
    fun exerciseSleepOverlayIsWeekly() {
        val first = Day(20261102) // a Monday
        val summaries = listOf(
            summary(first, active = 30, sleep = 420),
            summary(first.plusDays(1), active = 20, sleep = 400),
            summary(first.plusDays(6), active = 10, sleep = 440),
            // Week 2: only two nights of sleep (too few), no active minutes.
            summary(first.plusDays(7), sleep = 480),
            summary(first.plusDays(8), sleep = 300),
            // Outside the weeks.
            summary(first.plusDays(-1), active = 99, sleep = 600),
            summary(first.plusDays(21), active = 99, sleep = 600),
        )
        val o = ExerciseSleepOverlay.build(first, 3, summaries)
        assertArrayEquals(intArrayOf(60, OverlayMath.MISSING, OverlayMath.MISSING), o.exerciseMin)
        assertArrayEquals(intArrayOf(420, OverlayMath.MISSING, OverlayMath.MISSING), o.sleepMin)
        assertTrue(o.hasData)
        assertFalse(ExerciseSleepOverlay.build(first, 3, emptyList()).hasData)
    }

    @Test
    fun overlayLayerChoicesRoundTrip() {
        val store = OverlayLayers.TRAINING
        assertEquals(setOf("intensity", "hrv_band", "resting_hr"), store.decode(null, MainGoal.GENERAL_HEALTH))
        assertEquals(setOf("hrv", "load"), store.decode("hrv, nope ,load", MainGoal.GENERAL_HEALTH))
        assertEquals("load,hrv", store.encode(setOf("hrv", "load")))
        assertEquals("", store.encode(emptySet()))
        assertEquals(listOf("exercise", "sleep"), OverlayLayers.EXERCISE_SLEEP.toggleable)
    }

    private fun minute(t: Long, intensity: Int, device: Long = 1) = ActivityMinute(device, t, 100, 50, 7_000, intensity, null)

    private fun workout(start: Long, sport: Int, sub: Int? = null, distance: Int? = null, duration: Int = 1_800) =
        Workout(0, 1, sport, sub, start, start + duration, duration, distance, null, null, null, null, null, false, null, null, null, DataSource.WATCH)

    private fun run(start: Long, metres: Int) = workout(start, Sports.RUNNING, distance = metres)

    private fun walk(start: Long, sec: Int, sport: Int) = workout(start, sport, duration = sec)

    private fun summary(day: Day, hrv: Int? = null, rhr: Int? = null, active: Int? = null, sleep: Int? = null) =
        DaySummary(day, 0, null, null, null, null, active, rhr, hrv, sleep, null, null, null, null, null, null)
}
