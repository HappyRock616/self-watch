/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.demo

import app.selfwatch.fit.decode.FitFileType
import app.selfwatch.fit.decode.FitMsg
import app.selfwatch.fit.io.FieldDef
import app.selfwatch.fit.io.FitBaseType
import app.selfwatch.fit.io.FitTime
import app.selfwatch.fit.io.FitWriter
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random

/** One generated workout: what it is and when. */
data class DemoWorkout(
    /** FIT sport (1 run, 2 bike, 11 walk). */
    val sport: Int,
    val startSec: Long,
    val durationSec: Int,
    val distanceM: Int,
    val avgHr: Int,
    val kcal: Int,
)

/** A generated track point (degrees, metres, bpm, mm/s). */
data class DemoPoint(val tSec: Long, val lat: Double, val lon: Double, val altM: Double, val hr: Int, val speedMms: Int, val distM: Double)

/**
 * Writes small but complete FIT activity files (file_id, records with position, altitude, heart rate,
 * distance and speed, and one session) with the project's own [FitWriter], so demo workouts decode,
 * draw and export like real ones.
 */
object DemoFit {
    private const val STEP_SEC = 5
    private const val M_PER_DEG_LAT = 111_320.0
    private const val SEMI_PER_DEG = 2147483648.0 / 180.0

    /**
     * A closed loop of [w]'s distance around ([lat0], [lon0]): an ellipse with a couple of harmonics so it
     * looks like real streets and paths, walked at steady speed with a little noise.
     */
    fun track(w: DemoWorkout, lat0: Double, lon0: Double, seed: Int): List<DemoPoint> {
        val rnd = Random(seed)
        val wobble1 = rnd.nextDouble(0.08, 0.18)
        val wobble2 = rnd.nextDouble(0.04, 0.10)
        val phase = rnd.nextDouble(0.0, 2 * PI)
        val aspect = rnd.nextDouble(0.6, 1.4)
        // Shape on a unit scale, then scaled to the wanted length.
        val n = 720
        val shape = (0..n).map { i ->
            val a = 2 * PI * i / n
            val r = 1 + wobble1 * sin(3 * a + phase) + wobble2 * cos(5 * a + 2 * phase)
            Pair(r * cos(a) * aspect, r * sin(a))
        }
        val cum = DoubleArray(shape.size)
        for (i in 1 until shape.size) {
            val dx = shape[i].first - shape[i - 1].first
            val dy = shape[i].second - shape[i - 1].second
            cum[i] = cum[i - 1] + kotlin.math.sqrt(dx * dx + dy * dy)
        }
        val k = w.distanceM / cum.last()
        val mPerDegLon = M_PER_DEG_LAT * cos(Math.toRadians(lat0))
        val points = ArrayList<DemoPoint>()
        val steps = w.durationSec / STEP_SEC
        var j = 0
        for (s in 0..steps) {
            val frac = s.toDouble() / steps
            val d = (frac * w.distanceM).coerceAtMost(w.distanceM.toDouble())
            val target = d / k
            while (j < cum.size - 2 && cum[j + 1] < target) j++
            val seg = (cum[j + 1] - cum[j]).coerceAtLeast(1e-9)
            val t = ((target - cum[j]) / seg).coerceIn(0.0, 1.0)
            val x = (shape[j].first + (shape[j + 1].first - shape[j].first) * t) * k
            val y = (shape[j].second + (shape[j + 1].second - shape[j].second) * t) * k
            val speed = w.distanceM.toDouble() / w.durationSec * (1 + 0.06 * sin(frac * 23 + phase) + rnd.nextDouble(-0.02, 0.02))
            val warm = (frac * 6).coerceAtMost(1.0)
            val hr = (w.avgHr - 25 + 25 * warm + 8 * sin(frac * 9) + rnd.nextInt(-2, 3)).roundToInt()
            points += DemoPoint(
                tSec = w.startSec + s * STEP_SEC,
                lat = lat0 + (y - shape[0].second * k) / M_PER_DEG_LAT,
                lon = lon0 + (x - shape[0].first * k) / mPerDegLon,
                altM = 410 + 18 * sin(2 * PI * frac + phase) + 6 * sin(6 * PI * frac),
                hr = hr,
                speedMms = (speed * 1000).roundToInt(),
                distM = d,
            )
        }
        return points
    }

    /** The FIT file of [w] with [points]. */
    fun write(w: DemoWorkout, points: List<DemoPoint>): ByteArray {
        val out = ByteArrayOutputStream()
        val fw = FitWriter(out)
        fw.header()
        fw.define(
            0, FitMsg.FILE_ID,
            listOf(
                FieldDef(0, 1, FitBaseType.ENUM),
                FieldDef(1, 2, FitBaseType.UINT16),
                FieldDef(2, 2, FitBaseType.UINT16),
                FieldDef(3, 4, FitBaseType.UINT32Z),
                FieldDef(4, 4, FitBaseType.UINT32),
            ),
        )
        fw.data(0, listOf(FitFileType.ACTIVITY, DEVELOPMENT_MANUFACTURER, 0, DEMO_SERIAL, FitTime.fromEpoch(w.startSec)))
        fw.define(
            1, FitMsg.RECORD,
            listOf(
                FieldDef(253, 4, FitBaseType.UINT32),
                FieldDef(0, 4, FitBaseType.SINT32),
                FieldDef(1, 4, FitBaseType.SINT32),
                FieldDef(2, 2, FitBaseType.UINT16),
                FieldDef(3, 1, FitBaseType.UINT8),
                FieldDef(5, 4, FitBaseType.UINT32),
                FieldDef(6, 2, FitBaseType.UINT16),
            ),
        )
        var ascent = 0.0
        var descent = 0.0
        var maxHr = 0
        for ((i, p) in points.withIndex()) {
            if (i > 0) {
                val dAlt = p.altM - points[i - 1].altM
                if (dAlt > 0) ascent += dAlt else descent -= dAlt
            }
            maxHr = maxOf(maxHr, p.hr)
            fw.data(
                1,
                listOf(
                    FitTime.fromEpoch(p.tSec),
                    (p.lat * SEMI_PER_DEG).roundToLong(),
                    (p.lon * SEMI_PER_DEG).roundToLong(),
                    ((p.altM + 500) * 5).roundToInt(),
                    p.hr,
                    (p.distM * 100).roundToLong(),
                    p.speedMms,
                ),
            )
        }
        fw.define(
            2, FitMsg.SESSION,
            listOf(
                FieldDef(253, 4, FitBaseType.UINT32),
                FieldDef(2, 4, FitBaseType.UINT32),
                FieldDef(5, 1, FitBaseType.ENUM),
                FieldDef(6, 1, FitBaseType.ENUM),
                FieldDef(7, 4, FitBaseType.UINT32),
                FieldDef(8, 4, FitBaseType.UINT32),
                FieldDef(9, 4, FitBaseType.UINT32),
                FieldDef(11, 2, FitBaseType.UINT16),
                FieldDef(16, 1, FitBaseType.UINT8),
                FieldDef(17, 1, FitBaseType.UINT8),
                FieldDef(22, 2, FitBaseType.UINT16),
                FieldDef(23, 2, FitBaseType.UINT16),
            ),
        )
        fw.data(
            2,
            listOf(
                FitTime.fromEpoch(w.startSec + w.durationSec),
                FitTime.fromEpoch(w.startSec),
                w.sport,
                0,
                w.durationSec * 1000L,
                w.durationSec * 1000L,
                w.distanceM * 100L,
                w.kcal,
                w.avgHr,
                maxHr,
                ascent.roundToInt(),
                descent.roundToInt(),
            ),
        )
        fw.finish()
        return out.toByteArray()
    }

    private const val DEVELOPMENT_MANUFACTURER = 255
    private const val DEMO_SERIAL = 12345L
}
