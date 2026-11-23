/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body

import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.health.SleepStageSample
import app.selfwatch.ui.format.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class BodyRulesTest {

    private fun weight(tSec: Long, kgX100: Int) = BodyMeasure(0, tSec, BodyKind.WEIGHT, null, kgX100, DataSource.MANUAL, null, null)

    @Test
    fun energyFollowsGoalMinusFoodPlusActivity() {
        val e = Energy(goalKcal = 2_000, inKcal = 1_180, activeKcal = 420, restingKcal = 1_600)
        assertEquals(2_020, e.outKcal)
        assertEquals(1_240, e.remainingKcal)
        assertEquals(-840, e.netKcal)
        Locale.setDefault(Locale.US)
        assertEquals("2,000 − 1,180 + 420 = 1,240", EnergyFormula.numbers(e))
    }

    @Test
    fun energyWithMissingParts() {
        val noGoal = Energy(null, 900, null, null)
        assertNull(noGoal.remainingKcal)
        assertNull(noGoal.outKcal)
        assertNull(noGoal.netKcal)
        assertNull(EnergyFormula.numbers(noGoal))
        // Nothing eaten yet: the whole goal plus activity is left.
        assertEquals(2_300, Energy(2_000, null, 300, 500).remainingKcal)
        assertTrue(Energy(null, null, null, null).isEmpty)
    }

    @Test
    fun weightTrendSmoothsNoiseAndReportsChange() {
        val day = 86_400L
        // Steady loss of 0.1 kg a day with ±0.4 kg noise.
        val entries = (0 until 60).map { i -> weight(i * day, 85_00 - i * 10 + if (i % 2 == 0) 40 else -40) }
        val trend = WeightMath.trend(LongArray(60) { entries[it].tSec }, IntArray(60) { entries[it].valueX100 })
        // The trend moves far less than the raw zig-zag from one day to the next.
        assertTrue(kotlin.math.abs(trend[31] - trend[30]) < 40)
        val week = WeightMath.trendChange(entries, 7) ?: error("no change")
        assertTrue("trend fell over the week: $week", week in -120..-30)
        assertNull(WeightMath.trendChange(entries.take(1), 7))
    }

    @Test
    fun measurementUnitsRoundTrip() {
        assertEquals(91.0, BodyUnits.toDisplay(BodyKind.WAIST, 91_00, Units.METRIC), 1e-9)
        assertEquals(35.83, BodyUnits.toDisplay(BodyKind.WAIST, 91_00, Units.IMPERIAL), 0.01)
        assertEquals(91_00, BodyUnits.fromDisplay(BodyKind.WAIST, 35.8268, Units.IMPERIAL))
        assertEquals(82_40, BodyUnits.fromDisplay(BodyKind.WEIGHT, 181.66, Units.IMPERIAL))
        assertEquals(24.0, BodyUnits.toDisplay(BodyKind.BODY_FAT, 24_00, Units.IMPERIAL), 1e-9)
        assertEquals("in", BodyUnits.unit(BodyKind.HIPS, Units.IMPERIAL))
        assertEquals("cm", BodyUnits.unit(BodyKind.CUSTOM, Units.METRIC, "cm"))
    }

    @Test
    fun measureInputRejectsNonsense() {
        Locale.setDefault(Locale.US)
        assertEquals(82_40, MeasureInput.parse(BodyKind.WEIGHT, "82.4", Units.METRIC))
        assertNull(MeasureInput.parse(BodyKind.WEIGHT, "0", Units.METRIC))
        assertNull(MeasureInput.parse(BodyKind.WEIGHT, "-3", Units.METRIC))
        assertNull(MeasureInput.parse(BodyKind.WEIGHT, "abc", Units.METRIC))
        assertNull(MeasureInput.parse(BodyKind.BODY_FAT, "140", Units.METRIC))
        assertEquals("181.7", MeasureInput.text(BodyKind.WEIGHT, 82_40, Units.IMPERIAL))
        val zone = ZoneId.of("Europe/Berlin")
        val t = LocalDate.of(2026, 11, 20).atTime(7, 10).atZone(zone).toEpochSecond()
        val moved = MeasureInput.moveToDay(t, LocalDate.of(2026, 11, 18), zone)
        assertEquals(LocalDate.of(2026, 11, 18).atTime(7, 10).atZone(zone).toEpochSecond(), moved)
    }

    @Test
    fun measurementChangeOverThirtyDays() {
        val day = 86_400L
        val series = listOf(
            weight(0, 9_400),
            weight(20 * day, 9_300),
            weight(45 * day, 9_200),
        )
        // Against the newest reading at least 30 days older (day 0), not the one 25 days older.
        assertEquals(-200, BodyLoader.changeOver(series, series[2], 30))
        assertNull(BodyLoader.changeOver(series.take(1), series[0], 30))
    }

    @Test
    fun sleepNightAssembly() {
        val bed = 1_000_000L
        val stages = listOf(
            SleepStageSample(1, bed, SleepStage.AWAKE),
            SleepStageSample(1, bed + 600, SleepStage.LIGHT),
            SleepStageSample(1, bed + 3_000, SleepStage.DEEP),
            SleepStageSample(1, bed + 6_600, SleepStage.REM),
        )
        val end = bed + 9_000
        val min = SleepNights.stageMinutes(stages, end)
        assertEquals(10, min[SleepStage.AWAKE])
        assertEquals(40, min[SleepStage.LIGHT])
        assertEquals(60, min[SleepStage.DEEP])
        assertEquals(40, min[SleepStage.REM])
        assertEquals(140, SleepNights.asleep(min))
    }

    @Test
    fun bmi() {
        assertEquals(26.0, WeightRules.bmi(82_40, 178) ?: 0.0, 0.05)
        assertNull(WeightRules.bmi(82_40, null))
    }
}
