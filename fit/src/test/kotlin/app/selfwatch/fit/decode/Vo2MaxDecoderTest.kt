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
import app.selfwatch.fit.io.FitBaseType.ENUM
import app.selfwatch.fit.io.FitBaseType.SINT32
import app.selfwatch.fit.io.FitBaseType.UINT16
import app.selfwatch.fit.io.FitBaseType.UINT32
import app.selfwatch.fit.io.FitBaseType.UINT8
import app.selfwatch.fit.msg
import app.selfwatch.fit.testdataFit
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.result.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/** VO2 max from `max_met_data` (metrics and activity files) and `physiological_metrics` (activity files). */
class Vo2MaxDecoderTest {
    private val dev = 3L

    private fun metricsOf(bytes: ByteArray): List<MetricSample> =
        (MonitoringDecoder.decode(ByteArrayInputStream(bytes), dev) as Outcome.Ok).value.metrics

    @Test
    fun metricsFileFixtureGivesRunningAndCyclingOncePerUpdate() {
        val bytes = File(testdataFit(), "synthetic/metrics_vo2max.fit").readBytes()
        val m = metricsOf(bytes)
        // The repeated running estimate lands on one row (same update_time); the placeholder 0 is dropped.
        assertEquals(
            listOf(
                MetricSample(dev, MetricKind.VO2_MAX_RUNNING, Synth.VO2_UPDATE, 483),
                MetricSample(dev, MetricKind.VO2_MAX_CYCLING, Synth.VO2_UPDATE, 510),
            ),
            m,
        )
    }

    @Test
    fun sportDecidesTheKindAndCategoryStandsInWithoutOne() {
        assertEquals(MetricKind.VO2_MAX_RUNNING, Vo2Max.kindFor(1))
        assertEquals(MetricKind.VO2_MAX_CYCLING, Vo2Max.kindFor(2))
        // Generic (single-estimate watches) and unknown count as running, as Garmin Connect shows them.
        assertEquals(MetricKind.VO2_MAX_RUNNING, Vo2Max.kindFor(0))
        assertEquals(MetricKind.VO2_MAX_RUNNING, Vo2Max.kindFor(null))
        assertEquals(MetricKind.VO2_MAX_CYCLING, Vo2Max.kindFor(null, 1))
        assertEquals(MetricKind.VO2_MAX_RUNNING, Vo2Max.kindFor(null, 0))
        // A sport, when present, wins over the category.
        assertEquals(MetricKind.VO2_MAX_RUNNING, Vo2Max.kindFor(1, 1))
    }

    @Test
    fun withoutUpdateTimeTheRecordTimeIsUsedAndImplausibleValuesAreDropped() {
        val t = Synth.T0 + 3600
        val bytes = fit {
            msg(0, FitMsg.MAX_MET_DATA, F(253, UINT32, ft(t)), F(2, UINT16, 455), F(8, ENUM, 1))
            msg(0, FitMsg.MAX_MET_DATA, F(253, UINT32, ft(t + 60)), F(2, UINT16, 1200), F(5, ENUM, 1))
            msg(0, FitMsg.MAX_MET_DATA, F(253, UINT32, ft(t + 120)), F(2, UINT16, 50), F(5, ENUM, 1))
        }
        assertEquals(listOf(MetricSample(dev, MetricKind.VO2_MAX_CYCLING, t, 455)), metricsOf(bytes))
        assertEquals(483, Vo2Max.x10(48.29))
        assertEquals(null, Vo2Max.x10(5.0))
        assertEquals(null, Vo2Max.x10(120.0))
    }

    /** A run (or [sport]) with a session and, optionally, the given extra messages. */
    private fun activity(sport: Int, extra: app.selfwatch.fit.io.FitWriter.() -> Unit): ByteArray {
        val start = Synth.T0 + 15 * 3600
        return fit {
            Synth.fileId(this, FitFileType.ACTIVITY, start)
            msg(1, FitMsg.RECORD, F(253, UINT32, ft(start)), F(3, UINT8, 120))
            msg(1, FitMsg.RECORD, F(253, UINT32, ft(start + 1800)), F(3, UINT8, 150))
            msg(
                2, FitMsg.SESSION,
                F(253, UINT32, ft(start + 1800)), F(2, UINT32, ft(start)), F(5, ENUM, sport),
                F(7, UINT32, 1_800_000L), F(8, UINT32, 1_800_000L),
            )
            extra()
        }
    }

    @Test
    fun activityFilePhysiologicalMetricsGiveTheWorkoutsEstimate() {
        val end = Synth.T0 + 15 * 3600 + 1800
        // met_max 47.6 ml/kg/min = 13.6 METs ×65536; the message has no sport, so the session's counts.
        val raw = (47.6 / 3.5 * 65536).toLong()
        val run = activity(1) { msg(3, FitMsg.PHYSIOLOGICAL_METRICS, F(253, UINT32, ft(end + 5)), F(7, SINT32, raw)) }
        assertEquals(listOf(MetricSample(dev, MetricKind.VO2_MAX_RUNNING, end + 5, 476)), WorkoutDecoder.vo2Max(ByteArrayInputStream(run), dev))
        val ride = activity(2) { msg(3, FitMsg.PHYSIOLOGICAL_METRICS, F(253, UINT32, ft(end)), F(7, SINT32, raw), F(11, ENUM, 2)) }
        assertEquals(MetricKind.VO2_MAX_CYCLING, WorkoutDecoder.vo2Max(ByteArrayInputStream(ride), dev).single().kind)
        // A walk's met_max is not a VO2 max estimate Garmin shows.
        val walk = activity(11) { msg(3, FitMsg.PHYSIOLOGICAL_METRICS, F(253, UINT32, ft(end)), F(7, SINT32, raw)) }
        assertTrue(WorkoutDecoder.vo2Max(ByteArrayInputStream(walk), dev).isEmpty())
        // No estimate at all.
        assertTrue(WorkoutDecoder.vo2Max(ByteArrayInputStream(activity(1) {}), dev).isEmpty())
    }

    @Test
    fun activityFileMaxMetDataWinsOverTheWorkoutsOwnEstimate() {
        val end = Synth.T0 + 15 * 3600 + 1800
        val run = activity(1) {
            msg(3, FitMsg.PHYSIOLOGICAL_METRICS, F(253, UINT32, ft(end)), F(7, SINT32, (47.6 / 3.5 * 65536).toLong()))
            msg(4, FitMsg.MAX_MET_DATA, F(253, UINT32, ft(end + 2)), F(0, UINT32, ft(end + 1)), F(2, UINT16, 481), F(5, ENUM, 1))
        }
        assertEquals(listOf(MetricSample(dev, MetricKind.VO2_MAX_RUNNING, end + 1, 481)), WorkoutDecoder.vo2Max(ByteArrayInputStream(run), dev))
    }
}
