/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.files

import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.watch.IngestBatch
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real decoders, run on the fit module's synthetic sample files. */
class FitModuleDecodersTest {
    private val dir = File("../testdata/fit/synthetic")
    private fun bytes(name: String) = File(dir, name).readBytes()

    private fun ok(o: Outcome<IngestBatch>): IngestBatch = when (o) {
        is Outcome.Ok -> o.value
        is Outcome.Fail -> throw AssertionError("decode failed: ${o.error}")
    }

    @Test fun supportsEveryKind() {
        val d = FitModuleDecoders()
        FitFileKind.entries.forEach { assertTrue(it.name, d.supports(it)) }
    }

    @Test fun activityKeepsPathAndDevice() {
        val b = ok(FitModuleDecoders().decode(FitFileKind.ACTIVITY, bytes("activity_run_gps.fit"), 7, "/w/7/1.fit"))
        val w = assertNotNullAndGet(b.workout)
        assertEquals("/w/7/1.fit", w.fitPath)
        assertEquals(7L, w.deviceId)
    }

    @Test fun monitoringGivesMinutes() {
        val b = ok(FitModuleDecoders().decode(FitFileKind.MONITOR, bytes("monitor_day.fit"), 3, null))
        assertTrue(b.activity.isNotEmpty())
        assertTrue(b.activity.all { it.deviceId == 3L })
    }

    @Test fun metricsFileGivesVo2MaxPerSport() {
        val b = ok(FitModuleDecoders().decode(FitFileKind.METRICS, bytes("metrics_vo2max.fit"), 3, null))
        assertEquals(mapOf(MetricKind.VO2_MAX_RUNNING to 483, MetricKind.VO2_MAX_CYCLING to 510), b.metrics.associate { it.kind to it.value })
        assertTrue(b.metrics.all { it.deviceId == 3L })
    }

    @Test fun activityWithoutAnEstimateAddsNoMetrics() {
        val b = ok(FitModuleDecoders().decode(FitFileKind.ACTIVITY, bytes("activity_run_gps.fit"), 7, null))
        assertTrue(b.metrics.isEmpty())
    }

    @Test fun sleepGivesStages() {
        val b = ok(FitModuleDecoders().decode(FitFileKind.SLEEP, bytes("sleep_night.fit"), 3, null))
        assertTrue(b.sleepStages.isNotEmpty())
    }

    @Test fun weightIsMarkedAsWatchData() {
        val b = ok(FitModuleDecoders().decode(FitFileKind.WEIGHT, bytes("weight.fit"), 9, null))
        assertTrue(b.body.isNotEmpty())
        assertTrue(b.body.all { it.source == DataSource.WATCH && it.deviceId == 9L })
    }

    @Test fun garbageFailsWithoutThrowing() {
        val o = FitModuleDecoders().decode(FitFileKind.MONITOR, ByteArray(40) { it.toByte() }, 1, null)
        assertTrue(o is Outcome.Fail)
    }

    private fun <T> assertNotNullAndGet(v: T?): T { assertNotNull(v); return v!! }
}
