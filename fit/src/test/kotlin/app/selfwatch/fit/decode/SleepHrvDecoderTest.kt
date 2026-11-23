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
import app.selfwatch.fit.io.FitBaseType.ENUM
import app.selfwatch.fit.io.FitBaseType.UINT16
import app.selfwatch.fit.io.FitBaseType.UINT32
import app.selfwatch.fit.io.FitBaseType.UINT8
import app.selfwatch.fit.msg
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.valueOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class SleepHrvDecoderTest {
    private val t0 = Synth.T0
    private val dev = 3L

    @Test
    fun stagesMapAndSessionComesFromSamples() {
        val b = fit {
            define(1, FitMsg.SLEEP_LEVEL, listOf(FieldDef(253, 4, UINT32), FieldDef(0, 1, ENUM)))
            listOf(1, 2, 3, 4, 0, 9, 2).forEachIndexed { i, v -> data(1, listOf(ft(t0 + i * 60L), v)) }
            msg(2, FitMsg.SLEEP_ASSESSMENT, F(6, UINT8, 82))
        }
        val batch = SleepDecoder.decode(ByteArrayInputStream(b), dev).valueOrNull()!!
        assertEquals(
            listOf(SleepStage.AWAKE, SleepStage.LIGHT, SleepStage.DEEP, SleepStage.REM, SleepStage.UNMEASURABLE, SleepStage.LIGHT),
            batch.sleepStages.map { it.stage },
        )
        assertEquals(listOf(0L, 60, 120, 180, 240, 360), batch.sleepStages.map { it.tSec - t0 })
        assertEquals(listOf(SleepSession(dev, t0, t0 + 420, 82)), batch.sleepSessions)
    }

    @Test
    fun summaryMessageGivesBoundsAndFallbackScore() {
        val b = fit {
            msg(1, FitMsg.SLEEP_LEVEL, F(253, UINT32, ft(t0 + 600)), F(0, ENUM, 2))
            msg(2, FitMsg.SLEEP_SUMMARY, F(253, UINT32, ft(t0 + 30_000)), F(0, UINT8, 77), F(8, UINT32, ft(t0 + 27_000)), F(10, UINT32, ft(t0)))
        }
        val s = SleepDecoder.decode(ByteArrayInputStream(b), dev).valueOrNull()!!.sleepSessions
        assertEquals(listOf(SleepSession(dev, t0, t0 + 27_000, 77)), s)
    }

    @Test
    fun noSleepDataGivesNoSession() {
        val b = fit { Synth.fileId(this, FitFileType.SLEEP, t0) }
        val batch = SleepDecoder.decode(ByteArrayInputStream(b), dev).valueOrNull()!!
        assertTrue(batch.sleepSessions.isEmpty())
        assertTrue(batch.sleepStages.isEmpty())
    }

    @Test
    fun syntheticNight() {
        val batch = SleepDecoder.decode(ByteArrayInputStream(Synth.sleepNight()), dev).valueOrNull()!!
        assertEquals(450, batch.sleepStages.size)
        val s = batch.sleepSessions.single()
        assertEquals(450 * 60L, s.endSec - s.startSec)
        assertEquals(82, s.score)
        val minutes = batch.sleepStages.groupingBy { it.stage }.eachCount()
        assertEquals(450, minutes.values.sum())
        assertTrue(minutes.getValue(SleepStage.DEEP) > 0 && minutes.getValue(SleepStage.REM) > 0)
        assertEquals(setOf(MetricKind.SPO2, MetricKind.HRV_RMSSD, MetricKind.SKIN_TEMP_DELTA), batch.metrics.map { it.kind }.toSet())
        assertEquals(-40, batch.metrics.single { it.kind == MetricKind.SKIN_TEMP_DELTA }.value)
    }

    @Test
    fun hrvValuesAndNightly() {
        val bytes = Synth.hrvNight()
        val batch = HrvDecoder.decode(ByteArrayInputStream(bytes), dev).valueOrNull()!!
        assertEquals(90, batch.metrics.size)
        assertTrue(batch.metrics.all { it.kind == MetricKind.HRV_RMSSD && it.value in 40..54 })
        val n = HrvDecoder.nightly(ByteArrayInputStream(bytes)).valueOrNull()!!.single()
        assertEquals(HrvNight(Synth.T0 - Synth.TZ_SEC - 3600 + 27_000, 46, 47, 4), n)
    }

    @Test
    fun hrvNightlyInvalidFieldsAreNull() {
        val b = fit { msg(1, FitMsg.HRV_STATUS_SUMMARY, F(253, UINT32, ft(t0)), F(0, UINT16, null), F(1, UINT16, 50 * 128)) }
        assertEquals(HrvNight(t0, 50, null, null), HrvDecoder.nightly(ByteArrayInputStream(b)).valueOrNull()!!.single())
        assertTrue(HrvDecoder.nightly(ByteArrayInputStream(b.copyOf(10))) is Outcome.Fail)
    }
}
