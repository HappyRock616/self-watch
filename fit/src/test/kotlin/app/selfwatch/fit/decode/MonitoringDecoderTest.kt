/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.fit.decode

import app.selfwatch.fit.F
import app.selfwatch.fit.Synth
import app.selfwatch.fit.fit
import app.selfwatch.fit.ft
import app.selfwatch.fit.io.FieldDef
import app.selfwatch.fit.io.FitBaseType.BYTE
import app.selfwatch.fit.io.FitBaseType.ENUM
import app.selfwatch.fit.io.FitBaseType.FLOAT32
import app.selfwatch.fit.io.FitBaseType.SINT16
import app.selfwatch.fit.io.FitBaseType.UINT16
import app.selfwatch.fit.io.FitBaseType.UINT32
import app.selfwatch.fit.io.FitBaseType.UINT8
import app.selfwatch.fit.io.FitWriter
import app.selfwatch.fit.msg
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.valueOrNull
import app.selfwatch.model.time.Day
import app.selfwatch.model.watch.IngestBatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.ZoneId
import java.time.ZoneOffset

class MonitoringDecoderTest {
    private val t0 = Synth.T0
    private val dev = 7L

    /** Counter record: time, steps, activity type, active kcal, distance cm, packed type/intensity. */
    private fun FitWriter.counters() = define(
        2, FitMsg.MONITORING,
        listOf(FieldDef(253, 4, UINT32), FieldDef(3, 4, UINT32), FieldDef(5, 1, ENUM), FieldDef(19, 2, UINT16),
            FieldDef(2, 4, UINT32), FieldDef(24, 1, BYTE)),
    )

    private fun FitWriter.c(t: Long, steps: Long?, type: Int?, kcal: Int? = null, distCm: Long? = null, intensity: Int? = null) =
        data(2, listOf(ft(t), steps, type, kcal, distCm, intensity?.let { (it shl 5) or ((type ?: 0) and 0x1F) }))

    private fun decode(b: ByteArray, zone: ZoneId? = null, carry: MonitoringCarry? = null): IngestBatch =
        (MonitoringDecoder.decode(ByteArrayInputStream(b), dev, zone, carry) as Outcome.Ok).value

    private fun List<ActivityMinute>.at(t: Long) = single { it.tSec == t }

    @Test
    fun cumulativeCountersBecomeMinuteDeltasPerActivityType() {
        val b = fit {
            counters()
            c(t0 + 60, 100, 6, kcal = 4, distCm = 7000, intensity = 1)
            c(t0 + 90, 150, 6, kcal = 6, distCm = 10_500)
            c(t0 + 120, 180, 6, kcal = 7, distCm = 12_600, intensity = 2)
            c(t0 + 180, 50, 1, kcal = 5, distCm = 9000, intensity = 3)
            c(t0 + 240, 200, 6, kcal = 8, distCm = 14_000)
        }
        val a = decode(b, ZoneOffset.UTC).activity
        assertEquals(listOf(t0, t0 + 60, t0 + 120, t0 + 180), a.map { it.tSec })
        assertEquals(ActivityMinute(dev, t0, 100, 40, 7000, 1, 6), a.at(t0))
        // Two records end in the minute starting at t0+60: (150-100) + (180-150).
        assertEquals(ActivityMinute(dev, t0 + 60, 80, 30, 5600, 2, 6), a.at(t0 + 60))
        // Running has its own counter.
        assertEquals(ActivityMinute(dev, t0 + 120, 50, 50, 9000, 3, 1), a.at(t0 + 120))
        assertEquals(20, a.at(t0 + 180).steps)
        assertEquals(10, a.at(t0 + 180).activeKcalX10)
        assertEquals(250, a.sumOf { it.steps })
    }

    @Test
    fun countersResetAtLocalMidnightAndTheMidnightRecordBelongsToTheDayBefore() {
        val zone = ZoneOffset.ofHours(-6)
        val midnight = t0 + 6 * 3600 // local midnight
        val b = fit {
            counters()
            c(midnight - 600, 5000, 6)
            c(midnight, 5100, 6)
            c(midnight + 900, 30, 6)
            c(midnight + 1800, 80, 6)
        }
        val a = decode(b, zone).activity
        assertEquals(100, a.at(midnight - 60).steps) // the 23:59 minute
        assertEquals(30, a.at(midnight + 840).steps)
        assertEquals(50, a.at(midnight + 1740).steps)
        val day1 = Day.of(midnight - 1, zone)
        assertEquals(5100, a.filter { Day.of(it.tSec, zone) == day1 }.sumOf { it.steps })
        assertEquals(80, a.filter { Day.of(it.tSec, zone) != day1 }.sumOf { it.steps })
    }

    @Test
    fun timeZoneComesFromMonitoringInfoWhenNoZoneIsGiven() {
        val midnight = t0 + 6 * 3600
        val b = fit {
            msg(1, FitMsg.MONITORING_INFO, F(253, UINT32, ft(midnight - 3600)), F(0, UINT32, ft(midnight - 3600) - 6 * 3600))
            counters()
            c(midnight - 600, 5000, 6)
            c(midnight + 900, 30, 6)
        }
        assertEquals(30, decode(b).activity.at(midnight + 840).steps) // reset seen at local (not UTC) midnight
        // With UTC forced, the same data looks like a decrease within one day: no steps.
        assertEquals(0, decode(b, ZoneOffset.UTC).activity.at(midnight + 840).steps)
    }

    @Test
    fun counterGoingDownWithinADayAddsNothingAndRebases() {
        val b = fit {
            counters()
            c(t0 + 3600, 500, 6)
            c(t0 + 3660, 400, 6)
            c(t0 + 3720, 450, 6)
        }
        val a = decode(b, ZoneOffset.UTC).activity
        assertEquals(listOf(500, 0, 50), a.map { it.steps })
    }

    @Test
    fun carryAvoidsDoubleCountingAcrossFiles() {
        val first = fit { counters(); c(t0 + 3600, 900, 6) }
        val second = fit { counters(); c(t0 + 7200, 1000, 6) }
        val carry = MonitoringCarry()
        decode(first, ZoneOffset.UTC, carry)
        assertEquals(100, decode(second, ZoneOffset.UTC, carry).activity.single().steps)
        assertEquals(1000, decode(second, ZoneOffset.UTC).activity.single().steps)
    }

    @Test
    fun carryIsNotUpdatedOnFailure() {
        val carry = MonitoringCarry()
        decode(fit { counters(); c(t0 + 3600, 900, 6) }, ZoneOffset.UTC, carry)
        val broken = fit { counters(); c(t0 + 7200, 5000, 6) }
        assertTrue(MonitoringDecoder.decode(ByteArrayInputStream(broken.copyOf(broken.size - 5)), dev, ZoneOffset.UTC, carry) is Outcome.Fail)
        val next = fit { counters(); c(t0 + 7300, 1000, 6) }
        assertEquals(100, decode(next, ZoneOffset.UTC, carry).activity.single().steps)
    }

    @Test
    fun cycles16IsUsedWhenFullCyclesAreMissing() {
        val b = fit {
            counters()
            c(t0 + 60, 65_530, 6)
            define(3, FitMsg.MONITORING, listOf(FieldDef(253, 4, UINT32), FieldDef(9, 2, UINT16), FieldDef(5, 1, ENUM)))
            data(3, listOf(ft(t0 + 120), (65_540L and 0xFFFF), 6)) // wrapped past 0xFFFF
        }
        val a = decode(b, ZoneOffset.UTC).activity
        assertEquals(10, a.at(t0 + 60).steps)
    }

    @Test
    fun heartRateFromTimestamp16IsTheLastValuePerMinute() {
        val base = (ft(t0) and 0xFFFF.toLong().inv()) + 0xFFC0 // close to a 16-bit rollover
        val baseEpoch = app.selfwatch.fit.io.FitTime.toEpoch(base)
        val b = fit {
            msg(1, FitMsg.MONITORING, F(253, UINT32, base), F(27, UINT8, 58))
            define(3, FitMsg.MONITORING, listOf(FieldDef(26, 2, UINT16), FieldDef(27, 1, UINT8)))
            data(3, listOf((base + 30) and 0xFFFF, 60))
            data(3, listOf((base + 50) and 0xFFFF, 62))
            data(3, listOf((base + 70) and 0xFFFF, 65)) // past the rollover
            data(3, listOf((base + 80) and 0xFFFF, 0)) // 0 = no reading
        }
        val batch = decode(b, ZoneOffset.UTC)
        assertTrue(batch.activity.isEmpty())
        val hr = batch.metrics.filter { it.kind == MetricKind.HEART_RATE }
        val m0 = Math.floorDiv(baseEpoch, 60L) * 60
        val expected = mutableMapOf<Long, Int>()
        for ((dt, v) in listOf(0L to 58, 30L to 60, 50L to 62, 70L to 65)) expected[Math.floorDiv(baseEpoch + dt, 60L) * 60] = v
        assertEquals(expected.toSortedMap().toList(), hr.map { it.tSec to it.value })
        assertTrue(hr.first().tSec == m0)
    }

    @Test
    fun otherMetricsAreExtracted() {
        val b = fit {
            msg(1, FitMsg.STRESS_LEVEL, F(0, SINT16, 35), F(1, UINT32, ft(t0 + 180)))
            msg(1, FitMsg.STRESS_LEVEL, F(0, SINT16, -1), F(1, UINT32, ft(t0 + 360)))
            msg(2, FitMsg.RESPIRATION_RATE, F(253, UINT32, ft(t0 + 600)), F(0, SINT16, 1550))
            msg(2, FitMsg.RESPIRATION_RATE, F(253, UINT32, ft(t0 + 660)), F(0, SINT16, -200))
            msg(3, FitMsg.SPO2_DATA, F(253, UINT32, ft(t0 + 900)), F(0, UINT8, 95), F(1, UINT8, 3))
            msg(4, FitMsg.MONITORING_HR_DATA, F(253, UINT32, ft(t0 + 1200)), F(0, UINT8, 52), F(1, UINT8, 50))
            msg(5, FitMsg.SKIN_TEMP_OVERNIGHT, F(253, UINT32, ft(t0 + 1500)), F(1, FLOAT32, -0.35))
            msg(6, FitMsg.HRV_VALUE, F(253, UINT32, ft(t0 + 1800)), F(0, UINT16, 45 * 128 + 10))
        }
        val m = decode(b, ZoneOffset.UTC).metrics.map { Triple(it.kind, it.tSec - t0, it.value) }
        assertEquals(
            listOf(
                Triple(MetricKind.STRESS, 180L, 35),
                Triple(MetricKind.RESPIRATION, 600L, 155),
                Triple(MetricKind.SPO2, 900L, 95),
                Triple(MetricKind.RESTING_HR, 1200L, 50),
                Triple(MetricKind.SKIN_TEMP_DELTA, 1500L, -35),
                Triple(MetricKind.HRV_RMSSD, 1800L, 45),
            ),
            m,
        )
    }

    @Test
    fun syntheticDayTotalsMatch() {
        val (bytes, stepsDay) = Synth.monitoringDay()
        val batch = MonitoringDecoder.decode(ByteArrayInputStream(bytes), dev).valueOrNull()!!
        val zone = ZoneOffset.ofTotalSeconds(Synth.TZ_SEC)
        val day = Day.of(Synth.T0 - Synth.TZ_SEC + 1, zone)
        assertEquals(stepsDay, batch.activity.filter { Day.of(it.tSec, zone) == day }.sumOf { it.steps })
        assertTrue(batch.activity.any { Day.of(it.tSec, zone) != day && it.steps > 0 })
        assertEquals(1500 * 5, batch.activity.filter { it.activityType == 1 }.sumOf { it.steps })
        assertTrue(batch.metrics.count { it.kind == MetricKind.HEART_RATE } > 700)
        assertTrue(batch.metrics.any { it.kind == MetricKind.STRESS })
        assertTrue(batch.metrics.any { it.kind == MetricKind.SPO2 })
        assertEquals(50, batch.metrics.single { it.kind == MetricKind.RESTING_HR }.value)
        assertNull(batch.key)
    }

    @Test
    fun restingMetabolicRateBecomesOneSamplePerLocalDay() {
        val (bytes, _) = Synth.monitoringDay()
        val localMidnight = Synth.T0 - Synth.TZ_SEC
        // The file's own offset (monitoring_info.local_timestamp − timestamp) finds the local day.
        val own = MonitoringDecoder.decode(ByteArrayInputStream(bytes), dev).valueOrNull()!!
        assertEquals(
            listOf(MetricSample(dev, MetricKind.RESTING_KCAL, localMidnight, 1650)),
            own.metrics.filter { it.kind == MetricKind.RESTING_KCAL },
        )
        // A zone given by the caller wins; UTC puts the 06:00Z message on the UTC day.
        val utc = MonitoringDecoder.decode(ByteArrayInputStream(bytes), dev, ZoneOffset.UTC, null).valueOrNull()!!
        assertEquals(listOf(Synth.T0), utc.metrics.filter { it.kind == MetricKind.RESTING_KCAL }.map { it.tSec })
    }

    @Test
    fun missingOrInvalidRestingMetabolicRateIsSkipped() {
        val b = fit {
            msg(1, FitMsg.MONITORING_INFO, F(253, UINT32, ft(t0)), F(5, UINT16, 0))
            counters()
            c(t0 + 60, 100, 6)
        }
        assertTrue(decode(b, ZoneOffset.UTC).metrics.none { it.kind == MetricKind.RESTING_KCAL })
    }

    @Test
    fun truncatedFileFails() {
        val (bytes, _) = Synth.monitoringDay()
        assertTrue(MonitoringDecoder.decode(ByteArrayInputStream(bytes.copyOf(bytes.size / 2)), dev) is Outcome.Fail)
    }
}
