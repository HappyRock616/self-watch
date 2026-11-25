/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.fit.encode

import app.selfwatch.fit.Synth
import app.selfwatch.fit.decode.WorkoutDecoder
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.TrackPoint
import app.selfwatch.model.health.Workout
import app.selfwatch.model.result.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ActivityFileWriterTest {
    private val t = Synth.T0 + 7 * 3600

    @Test
    fun roundTripsSummaryAndTrack() {
        val points = (0 until 30).map { i ->
            TrackPoint(t + i * 10L, 476_000_000 + i * 900, -1_223_000_000, 1_200 + i * 10, 140 + i % 5, 85, 210, 3_000)
        }
        val w = Workout(
            0, 7, 1, 0, t, t + 290, 280, 2_500, 210, 142, 144, 3, 0, true, "a.fit", null, null, DataSource.WATCH,
        )
        val out = ByteArrayOutputStream()
        ActivityFileWriter.write(out, w, points)
        val bytes = out.toByteArray()

        val summary = (WorkoutDecoder.summary(ByteArrayInputStream(bytes), 7) as Outcome.Ok).value
        assertEquals(t, summary.startSec)
        assertEquals(t + 290, summary.endSec)
        assertEquals(280, summary.durationSec)
        assertEquals(2_500, summary.distanceM)
        assertEquals(210, summary.kcal)
        assertEquals(142, summary.avgHr)
        assertEquals(144, summary.maxHr)
        assertEquals(3, summary.ascentM)
        assertEquals(1, summary.sport)
        assertTrue(summary.hasTrack)

        val back = ArrayList<TrackPoint>()
        WorkoutDecoder.track(ByteArrayInputStream(bytes)) { back.add(it) }
        assertEquals(points.size, back.size)
        for ((a, b) in points.zip(back)) {
            assertEquals(a.tSec, b.tSec)
            assertEquals(a.hrBpm, b.hrBpm)
            assertEquals(a.cadence, b.cadence)
            assertEquals(a.speedMms, b.speedMms)
            assertEquals(a.tempC10, b.tempC10)
            assertEquals(a.altCm!!.toDouble(), b.altCm!!.toDouble(), 20.0)
            assertEquals(a.latE7!!.toDouble(), b.latE7!!.toDouble(), 10.0)
            assertEquals(a.lonE7!!.toDouble(), b.lonE7!!.toDouble(), 10.0)
        }
    }
}
