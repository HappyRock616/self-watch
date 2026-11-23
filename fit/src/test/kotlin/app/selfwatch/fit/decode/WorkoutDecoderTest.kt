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
import app.selfwatch.fit.geo.LatLonE7
import app.selfwatch.fit.geo.Polyline
import app.selfwatch.fit.geo.RouteSimplifier
import app.selfwatch.fit.io.FitBaseType.UINT32
import app.selfwatch.fit.io.FitBaseType.UINT8
import app.selfwatch.fit.msg
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.TrackPoint
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.valueOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.math.abs

class WorkoutDecoderTest {
    private val start = Synth.T0 + 15 * 3600

    @Test
    fun summaryFromSession() {
        val w = WorkoutDecoder.summary(ByteArrayInputStream(Synth.activity(1200)), 9L).valueOrNull()!!
        assertEquals(1, w.sport)
        assertEquals(0, w.subSport)
        assertEquals(start, w.startSec)
        assertEquals(start + 1230, w.endSec)
        assertEquals(1200, w.durationSec)
        assertEquals(3000, w.distanceM)
        assertEquals(120, w.kcal)
        assertEquals(149, w.avgHr)
        assertEquals(159, w.maxHr)
        assertEquals(12, w.ascentM)
        assertEquals(11, w.descentM)
        assertTrue(w.hasTrack)
        assertEquals(9L, w.deviceId)
        assertEquals(DataSource.WATCH, w.source)
        val preview = Polyline.decode(w.routePreview!!)
        assertTrue(preview.size in 3..200)
        assertEquals(DataSource.IMPORT, WorkoutDecoder.summary(ByteArrayInputStream(Synth.activity(10)), null).valueOrNull()!!.source)
    }

    @Test
    fun trackPointsDecode() {
        val pts = ArrayList<TrackPoint>()
        val n = WorkoutDecoder.track(ByteArrayInputStream(Synth.activity(500)), pts::add).valueOrNull()
        assertEquals(500, n)
        val p = pts[0]
        assertEquals(start, p.tSec)
        assertEquals(41.88, p.latE7!! / 1e7, 1e-6)
        assertTrue(abs(p.lonE7!! / 1e7 - (-87.63 + 160.0 / (111_320.0 * Math.cos(Math.toRadians(41.88))))) < 1e-6)
        assertEquals(18_000, p.altCm!!) // 180 m
        assertEquals(140, p.hrBpm)
        assertEquals(85, p.cadence)
        assertEquals(180, p.tempC10)
        assertEquals(2500, p.speedMms)
        assertEquals(start + 499, pts.last().tSec)
    }

    @Test
    fun compressedAndBigEndianActivitiesGiveTheSameTrack() {
        val plain = ArrayList<TrackPoint>()
        WorkoutDecoder.track(ByteArrayInputStream(Synth.activity(300)), plain::add)
        val odd = ArrayList<TrackPoint>()
        WorkoutDecoder.track(ByteArrayInputStream(Synth.activity(300, compressed = true, bigEndian = true)), odd::add)
        assertEquals(plain, odd)
    }

    @Test
    fun summaryWithoutSessionUsesRecords() {
        val b = fit {
            msg(1, FitMsg.RECORD, F(253, UINT32, ft(start)), F(3, UINT8, 100), F(5, UINT32, 0))
            msg(1, FitMsg.RECORD, F(253, UINT32, ft(start + 600)), F(3, UINT8, 120), F(5, UINT32, 150_000))
        }
        val w = WorkoutDecoder.summary(ByteArrayInputStream(b), null).valueOrNull()!!
        assertEquals(0, w.sport)
        assertEquals(600, w.durationSec)
        assertEquals(1500, w.distanceM)
        assertEquals(110, w.avgHr)
        assertEquals(120, w.maxHr)
        assertTrue(!w.hasTrack)
        assertNull(w.routePreview)
    }

    @Test
    fun emptyActivityFails() {
        val b = fit { Synth.fileId(this, FitFileType.ACTIVITY, start) }
        assertTrue(WorkoutDecoder.summary(ByteArrayInputStream(b), null) is Outcome.Fail)
        assertEquals(0, WorkoutDecoder.routePreview(ByteArrayInputStream(b)).size)
        assertEquals(0, WorkoutDecoder.routePreview(ByteArrayInputStream(b.copyOf(5))).size)
    }

    @Test
    fun routePreviewIsBoundedAndRoundTrips() {
        val bytes = Synth.activity(3000)
        val all = ArrayList<TrackPoint>()
        WorkoutDecoder.track(ByteArrayInputStream(bytes), all::add)
        val preview = WorkoutDecoder.routePreview(ByteArrayInputStream(bytes))
        val pts = Polyline.decode(preview)
        assertTrue(pts.size <= 200)
        assertTrue(pts.size >= 100)
        assertEquals(all.first().latE7!! / 100, pts.first().latE7 / 100, 1)
        assertEquals(all.last().lonE7!! / 100, pts.last().lonE7 / 100, 1)
        // Every kept point is close to (within 1e-5°) a real track point.
        val real = all.map { LatLonE7(it.latE7!!, it.lonE7!!) }
        for (p in pts) assertTrue(real.any { abs(it.latE7 - p.latE7) <= 100 && abs(it.lonE7 - p.lonE7) <= 100 })
        assertEquals(50, Polyline.decode(WorkoutDecoder.routePreview(ByteArrayInputStream(bytes), 50)).size)
    }

    private fun assertEquals(a: Int, b: Int, tol: Int) = assertTrue("$a vs $b", abs(a - b) <= tol)

    @Test
    fun polylineKnownVectorAndRoundTrip() {
        val pts = listOf(LatLonE7(385_000_000, -1_202_000_000), LatLonE7(407_000_000, -1_209_500_000), LatLonE7(432_520_000, -1_264_530_000))
        val enc = Polyline.encode(pts)
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", String(enc, Charsets.US_ASCII))
        assertEquals(pts, Polyline.decode(enc))
        val odd = listOf(LatLonE7(-1, 1), LatLonE7(-899_999_999, 1_799_999_999), LatLonE7(0, 0))
        val back = Polyline.decode(Polyline.encode(odd))
        for (i in odd.indices) {
            assertTrue(abs(odd[i].latE7 - back[i].latE7) <= 50)
            assertTrue(abs(odd[i].lonE7 - back[i].lonE7) <= 50)
        }
        assertTrue(Polyline.decode("!!garbage".toByteArray()).isEmpty())
        assertArrayEquals(ByteArray(0), Polyline.encode(emptyList()))
    }

    @Test
    fun douglasPeuckerKeepsCornersAndDropsStraightRuns() {
        val line = (0..100).map { LatLonE7(it * 1000, 0) }
        assertEquals(listOf(line.first(), line.last()), RouteSimplifier.simplify(line, 2))
        val ell = (0..50).map { LatLonE7(it * 1000, 0) } + (1..50).map { LatLonE7(50_000, it * 1000) }
        assertEquals(listOf(ell.first(), ell[50], ell.last()), RouteSimplifier.simplify(ell, 3))
        val big = (0 until 50_000).map { LatLonE7((Math.sin(it / 500.0) * 1e6).toInt(), it * 100) }
        val s = RouteSimplifier.simplify(big, 200)
        assertEquals(200, s.size)
        assertEquals(big.first(), s.first())
        assertEquals(big.last(), s.last())
        assertTrue(RouteSimplifier.simplify(emptyList(), 200).isEmpty())
        assertEquals(1, RouteSimplifier.simplify(line, 1).size)
    }
}
