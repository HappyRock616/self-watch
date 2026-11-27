/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.health

import app.selfwatch.db.SettingKeys
import app.selfwatch.db.dao.NetLogEntry
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.health.SleepStageSample
import app.selfwatch.model.net.NetFeature
import app.selfwatch.model.time.Day
import app.selfwatch.service.ServiceDbTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SummaryBuilderTest : ServiceDbTest() {

    private fun minute(dev: Long, t: Long, steps: Int, intensity: Int = 0, kcalX10: Int = 0, distanceCm: Int = 0) =
        ActivityMinute(dev, t, steps, kcalX10, distanceCm, intensity, null)

    private suspend fun stepsOn(day: Day): Int? {
        SummaryBuilder(db, clock).refresh(setOf(day))
        return db.summaries.day(day)?.steps
    }

    @Test
    fun totalsFollowTheLocalDayAcrossTheAutumnDstChange() = runBlocking {
        val dev = addDevice()
        val day = Day(20261101) // 25 hours long in Detroit
        val range = day.toTimeRange(zone)
        assertEquals(25 * 3600L, range.durationSec)
        val inside = (range.startSec until range.endSec step 3600L).map { minute(dev, it, 100) }
        val outside = listOf(minute(dev, range.startSec - 60, 7000), minute(dev, range.endSec, 9000))
        db.activity.insert(inside + outside)

        assertEquals(2500, stepsOn(day))
        assertEquals(-300, db.summaries.day(day)!!.tzOffsetMin) // EST at noon
        assertEquals(9000, stepsOn(day.plusDays(1)))
        assertEquals(7000, stepsOn(day.plusDays(-1)))
        assertEquals(-240, db.summaries.day(day.plusDays(-1))!!.tzOffsetMin) // still EDT
    }

    @Test
    fun totalsFollowTheLocalDayAcrossTheSpringDstChange() = runBlocking {
        val dev = addDevice()
        val day = Day(20260308) // 23 hours long
        val range = day.toTimeRange(zone)
        assertEquals(23 * 3600L, range.durationSec)
        db.activity.insert((range.startSec until range.endSec step 3600L).map { minute(dev, it, 100) })
        assertEquals(2300, stepsOn(day))
    }

    @Test
    fun higherPriorityDeviceWinsOverlappingMinutes() = runBlocking {
        val main = addDevice("AA:00:00:00:00:01", priority = 5)
        val spare = addDevice("AA:00:00:00:00:02", priority = 1)
        val day = Day(20261120)
        val t = at(day, 10)
        db.activity.insert(
            listOf(
                minute(main, t, 100, kcalX10 = 40, distanceCm = 9000),
                minute(spare, t, 300, kcalX10 = 90, distanceCm = 25000),
                minute(spare, t + 60, 50, kcalX10 = 15, distanceCm = 4000), // only the spare saw this minute
            ),
        )
        SummaryBuilder(db, clock).refresh(setOf(day))
        val s = db.summaries.day(day)!!
        assertEquals(150, s.steps)
        assertEquals(6, s.activeKcal) // (40 + 15) / 10 = 5.5, rounded half up
        assertEquals(130, s.distanceM)

        // Swap priorities: the other device now wins the shared minute.
        db.devices.setPriority(spare, 9)
        assertEquals(350, stepsOn(day))
    }

    @Test
    fun activeMinutesCountModerateOnceAndVigorousTwice() = runBlocking {
        val dev = addDevice()
        val day = Day(20261120)
        val t = at(day, 7)
        db.activity.insert(listOf(0, 1, 2, 3, 3, 2).mapIndexed { i, intensity -> minute(dev, t + i * 60L, 0, intensity) })
        SummaryBuilder(db, clock).refresh(setOf(day))
        assertEquals(6, db.summaries.day(day)!!.activeMin) // 1 + 2 + 2 + 1
    }

    @Test
    fun restingHrPrefersTheWatchValue() = runBlocking {
        val dev = addDevice()
        val day = Day(20261120)
        db.metrics.insert(
            listOf(
                MetricSample(dev, MetricKind.RESTING_HR, at(day, 9), 54),
                MetricSample(dev, MetricKind.HEART_RATE, at(day, 3), 40),
            ),
        )
        SummaryBuilder(db, clock).refresh(setOf(day))
        assertEquals(54, db.summaries.day(day)!!.restingHr)
    }

    @Test
    fun restingHrFallsBackToTheLowestHalfHourOfTheWholeDay() = runBlocking {
        val dev = addDevice()
        val day = Day(20261120)
        val start = at(day.plusDays(-1), 23)
        val end = at(day, 7)
        db.sleep.insertSession(SleepSession(dev, start, end, null))
        val samples = ArrayList<MetricSample>()
        var t = start
        while (t < end) {
            val inDip = t >= at(day, 3) && t < at(day, 3, 30)
            samples += MetricSample(dev, MetricKind.HEART_RATE, t, if (inDip) 50 else 62)
            t += 60
        }
        // Lower, after waking: still part of the local day, so it counts.
        for (i in 0 until 40) samples += MetricSample(dev, MetricKind.HEART_RATE, at(day, 8) + i * 60L, 45)
        // Lower still, but the evening before: another local day, not counted.
        for (i in 0 until 40) samples += MetricSample(dev, MetricKind.HEART_RATE, at(day.plusDays(-1), 20) + i * 60L, 40)
        db.metrics.insert(samples)

        SummaryBuilder(db, clock).refresh(setOf(day))
        val s = db.summaries.day(day)!!
        assertEquals(45, s.restingHr)
        assertEquals(480, s.sleepMin)
    }

    @Test
    fun hrvAndSleepComeFromTheNightThatEndedThatMorning() = runBlocking {
        val dev = addDevice()
        val day = Day(20261120)
        val start = at(day.plusDays(-1), 23)
        db.sleep.insertSession(SleepSession(dev, start, start + 8 * 3600, 85))
        db.sleep.insertStages(
            listOf(
                SleepStageSample(dev, start, SleepStage.AWAKE),
                SleepStageSample(dev, start + 1800, SleepStage.LIGHT),
                SleepStageSample(dev, start + 3 * 3600, SleepStage.DEEP),
                SleepStageSample(dev, start + 5 * 3600, SleepStage.AWAKE),
                SleepStageSample(dev, start + 5 * 3600 + 600, SleepStage.REM),
            ),
        )
        db.metrics.insert(
            listOf(40, 50, 61).mapIndexed { i, v -> MetricSample(dev, MetricKind.HRV_RMSSD, start + 3600L * (i + 1), v) } +
                MetricSample(dev, MetricKind.HRV_RMSSD, at(day, 15), 99), // daytime: ignored
        )
        SummaryBuilder(db, clock).refresh(setOf(day))
        val s = db.summaries.day(day)!!
        assertEquals(50, s.hrvAvgMs)
        // 8 h minus 30 min awake at the start minus 10 min awake later.
        assertEquals(8 * 60 - 30 - 10, s.sleepMin)
    }

    @Test
    fun foodWaterAndWeightComeFromTheirDay() = runBlocking {
        val day = Day(20261120)
        db.diary.add(entry(day, kcalX10 = 520, proteinX10 = 3))
        db.diary.add(entry(day, kcalX10 = 1005, proteinX10 = 3))
        db.diary.add(entry(day.plusDays(1), kcalX10 = 9990, proteinX10 = 0))
        db.water.add(day, at(day, 9), 250)
        db.water.add(day, at(day, 14), 500)
        db.body.insert(weight(at(day, 7), 8_120))
        db.body.insert(weight(at(day, 21), 8_090))

        SummaryBuilder(db, clock).refresh(setOf(day))
        val s = db.summaries.day(day)!!
        assertEquals(153, s.kcalIn) // 152.5 rounded half up
        assertEquals(1, s.proteinG)
        assertNull(s.fatG)
        assertEquals(750, s.waterMl)
        assertEquals(8_090, s.weightKgX100)
        assertNull("no device data", s.steps)
    }

    @Test
    fun restingKcalUsesMifflinStJeorAndIsProratedToday() = runBlocking {
        db.settings.setAll(
            mapOf(
                SettingKeys.PROFILE_HEIGHT_CM to "180",
                SettingKeys.PROFILE_BIRTH_YEAR to "1990",
                SettingKeys.PROFILE_SEX to "male",
            ),
        )
        val past = Day(20261120)
        db.body.insert(weight(at(past, 7), 8_000))
        val today = clock.today()

        val builder = SummaryBuilder(db, clock)
        builder.refresh(setOf(past, today))
        // 10 × 80 + 6.25 × 180 − 5 × 36 + 5 = 1750
        assertEquals(1750, db.summaries.day(past)!!.restingKcal)
        assertEquals(875, db.summaries.day(today)!!.restingKcal) // noon: half the day

        // A watch value wins over the formula.
        SummaryBuilder(db, clock) { 1600 }.refresh(setOf(past))
        assertEquals(1600, db.summaries.day(past)!!.restingKcal)
    }

    @Test
    fun storedWatchRestingKcalWinsOverTheFormula() = runBlocking {
        db.settings.setAll(
            mapOf(SettingKeys.PROFILE_HEIGHT_CM to "180", SettingKeys.PROFILE_BIRTH_YEAR to "1990", SettingKeys.PROFILE_SEX to "male"),
        )
        val dev = addDevice()
        val past = Day(20261120)
        val today = clock.today()
        db.body.insert(weight(at(past, 7), 8_000))
        // One RESTING_KCAL sample per day, stamped at the local day's start (from monitoring_info).
        db.metrics.insert(
            listOf(
                MetricSample(dev, MetricKind.RESTING_KCAL, past.startSec(zone), 1680),
                MetricSample(dev, MetricKind.RESTING_KCAL, today.startSec(zone), 1700),
            ),
        )
        assertEquals(1680, WatchRestingKcal.fromMetrics(db, clock).kcalPerDay(past))
        assertNull(WatchRestingKcal.fromMetrics(db, clock).kcalPerDay(past.plusDays(-1)))

        SummaryBuilder(db, clock).refresh(setOf(past, past.plusDays(-1), today))
        assertEquals(1680, db.summaries.day(past)!!.restingKcal) // not the formula's 1750
        assertEquals(850, db.summaries.day(today)!!.restingKcal) // prorated: half of 1700 at noon
        // An explicit hook still wins over the stored value.
        SummaryBuilder(db, clock) { 1600 }.refresh(setOf(past))
        assertEquals(1600, db.summaries.day(past)!!.restingKcal)
    }

    @Test
    fun emptyDaysGetNoRowButClearedDaysAreRewritten() = runBlocking {
        val day = Day(20261119)
        val builder = SummaryBuilder(db, clock)
        builder.refresh(setOf(day))
        assertNull(db.summaries.day(day))

        val id = db.water.add(day, at(day, 9), 300)
        builder.refresh(setOf(day))
        assertEquals(300, db.summaries.day(day)!!.waterMl)
        db.water.delete(id)
        builder.refresh(setOf(day))
        assertNull(db.summaries.day(day)!!.waterMl)
    }

    @Test
    fun futureDaysAreSkipped() = runBlocking {
        val tomorrow = clock.today().plusDays(1)
        db.water.add(tomorrow, at(tomorrow, 9), 300)
        SummaryBuilder(db, clock).refresh(setOf(tomorrow))
        assertNull(db.summaries.day(tomorrow))
    }

    @Test
    fun dayChangeFinishesYesterdayAndPrunesTheNetworkLog() = runBlocking {
        val yesterday = clock.today().plusDays(-1)
        db.water.add(yesterday, at(yesterday, 20), 400)
        val now = clock.nowSec()
        db.netLog.insert(NetLogEntry(0, now - 91 * 86_400L, NetFeature.FOOD_DATA, "example.org", "GET", 1, 2, 200))
        db.netLog.insert(NetLogEntry(0, now - 3600, NetFeature.FOOD_DATA, "example.org", "GET", 1, 2, 200))

        SummaryBuilder(db, clock).onDayChange(yesterday)

        assertEquals(400, db.summaries.day(yesterday)!!.waterMl)
        assertEquals(1L, count("net_log"))
    }

    private fun entry(day: Day, kcalX10: Int, proteinX10: Int) = DiaryEntry(
        id = 0, day = day, mealSlotId = 1, tSec = at(day, 12), food = null, name = "Test", brand = null,
        amountX100 = 100, servingLabel = "1", gramsX10 = null,
        nutrients = Nutrients(kcalX10, proteinX10, null, null, null, null, null, null, null), importId = null,
    )

    private fun weight(t: Long, kgX100: Int) =
        BodyMeasure(0, t, BodyKind.WEIGHT, null, kgX100, DataSource.MANUAL, null, null)
}
