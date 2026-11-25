/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.fit.encode

import app.selfwatch.fit.decode.FitFileType
import app.selfwatch.fit.decode.FitMsg
import app.selfwatch.fit.io.FieldDef
import app.selfwatch.fit.io.FitBaseType
import app.selfwatch.fit.io.FitTime
import app.selfwatch.fit.io.FitWriter
import app.selfwatch.model.health.TrackPoint
import app.selfwatch.model.health.Workout
import java.io.OutputStream
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Writes a FIT activity file (file type 4) from a workout summary and its track: one `file_id`, a
 * `record` per track point, one `lap`, one `session` and the `activity` message. Used to export a trimmed
 * workout, whose original FIT file still holds the time that was cut off. Values the summary or a point
 * does not have are written as invalid. Record distance is the running GPS distance (when the points have
 * positions).
 */
// Ref: Gadgetbridge 160beab41 FitCodeGenerator/src/main/resources/fit_profile.json (file_id 0, session 18,
// lap 19, record 20, activity 34: field numbers, base types and scales)
object ActivityFileWriter {
    /** Manufacturer "development" (255), so the file does not claim to come from a real watch. */
    private const val MANUFACTURER_DEVELOPMENT = 255
    private const val PRODUCT = 1
    private const val SERIAL = 1L

    private const val EVENT_TIMER = 0
    private const val EVENT_LAP = 9
    private const val EVENT_ACTIVITY = 26
    private const val EVENT_TYPE_START = 0
    private const val EVENT_TYPE_STOP = 1
    private const val ACTIVITY_MANUAL = 0
    private const val MS = 1000L
    private const val E7_TO_SEMI = 2147483648.0 / 1.8e9

    /** Writes [w] with [points] (time order) to [out]; [created] is the file's creation time (epoch seconds). */
    fun write(out: OutputStream, w: Workout, points: List<TrackPoint>, created: Long = w.endSec) {
        val f = FitWriter(out)
        f.header()
        f.define(
            0, FitMsg.FILE_ID,
            listOf(
                FieldDef(0, 1, FitBaseType.ENUM),
                FieldDef(1, 2, FitBaseType.UINT16),
                FieldDef(2, 2, FitBaseType.UINT16),
                FieldDef(3, 4, FitBaseType.UINT32Z),
                FieldDef(4, 4, FitBaseType.UINT32),
            ),
        )
        f.data(0, listOf(FitFileType.ACTIVITY, MANUFACTURER_DEVELOPMENT, PRODUCT, SERIAL, FitTime.fromEpoch(created)))

        // Timer start.
        f.define(1, FitMsg.EVENT, listOf(FieldDef(253, 4, FitBaseType.UINT32), FieldDef(0, 1, FitBaseType.ENUM), FieldDef(1, 1, FitBaseType.ENUM)))
        f.data(1, listOf(FitTime.fromEpoch(w.startSec), EVENT_TIMER, EVENT_TYPE_START))

        f.define(
            2, FitMsg.RECORD,
            listOf(
                FieldDef(253, 4, FitBaseType.UINT32),
                FieldDef(0, 4, FitBaseType.SINT32),
                FieldDef(1, 4, FitBaseType.SINT32),
                FieldDef(5, 4, FitBaseType.UINT32),
                FieldDef(78, 4, FitBaseType.UINT32),
                FieldDef(3, 1, FitBaseType.UINT8),
                FieldDef(4, 1, FitBaseType.UINT8),
                FieldDef(73, 4, FitBaseType.UINT32),
                FieldDef(13, 1, FitBaseType.SINT8),
            ),
        )
        var distM = 0.0
        var last: TrackPoint? = null
        for (p in points) {
            val lat = p.latE7
            val lon = p.lonE7
            val prev = last
            if (lat != null && lon != null) {
                val pLat = prev?.latE7
                val pLon = prev?.lonE7
                if (pLat != null && pLon != null) distM += distanceM(pLat, pLon, lat, lon)
                last = p
            }
            f.data(
                2,
                listOf(
                    FitTime.fromEpoch(p.tSec),
                    lat?.let { (it * E7_TO_SEMI).roundToLong() },
                    lon?.let { (it * E7_TO_SEMI).roundToLong() },
                    if (last != null) (distM * 100).roundToLong() else null,
                    p.altCm?.let { ((it + 50_000) / 20.0).roundToLong().takeIf { r -> r >= 0 } },
                    p.hrBpm?.takeIf { it in 1..254 },
                    p.cadence?.takeIf { it in 0..254 },
                    p.speedMms?.takeIf { it >= 0 }?.toLong(),
                    p.tempC10?.let { (it / 10).coerceIn(-127, 127) },
                ),
            )
        }

        val elapsedMs = (w.endSec - w.startSec).coerceAtLeast(0) * MS
        val timerMs = w.durationSec.toLong() * MS
        val distCm = w.distanceM?.let { it.toLong() * 100 }
        f.define(
            3, FitMsg.LAP,
            listOf(
                FieldDef(253, 4, FitBaseType.UINT32),
                FieldDef(2, 4, FitBaseType.UINT32),
                FieldDef(7, 4, FitBaseType.UINT32),
                FieldDef(8, 4, FitBaseType.UINT32),
                FieldDef(9, 4, FitBaseType.UINT32),
                FieldDef(0, 1, FitBaseType.ENUM),
                FieldDef(1, 1, FitBaseType.ENUM),
            ),
        )
        f.data(3, listOf(FitTime.fromEpoch(w.endSec), FitTime.fromEpoch(w.startSec), elapsedMs, timerMs, distCm, EVENT_LAP, EVENT_TYPE_STOP))

        f.define(
            4, FitMsg.SESSION,
            listOf(
                FieldDef(253, 4, FitBaseType.UINT32),
                FieldDef(2, 4, FitBaseType.UINT32),
                FieldDef(7, 4, FitBaseType.UINT32),
                FieldDef(8, 4, FitBaseType.UINT32),
                FieldDef(9, 4, FitBaseType.UINT32),
                FieldDef(5, 1, FitBaseType.ENUM),
                FieldDef(6, 1, FitBaseType.ENUM),
                FieldDef(11, 2, FitBaseType.UINT16),
                FieldDef(16, 1, FitBaseType.UINT8),
                FieldDef(17, 1, FitBaseType.UINT8),
                FieldDef(22, 2, FitBaseType.UINT16),
                FieldDef(23, 2, FitBaseType.UINT16),
                FieldDef(25, 2, FitBaseType.UINT16),
                FieldDef(26, 2, FitBaseType.UINT16),
                FieldDef(0, 1, FitBaseType.ENUM),
                FieldDef(1, 1, FitBaseType.ENUM),
            ),
        )
        f.data(
            4,
            listOf(
                FitTime.fromEpoch(w.endSec), FitTime.fromEpoch(w.startSec), elapsedMs, timerMs, distCm,
                w.sport.coerceIn(0, 254), w.subSport?.coerceIn(0, 254),
                w.kcal?.coerceIn(0, 0xFFFE), w.avgHr?.takeIf { it in 1..254 }, w.maxHr?.takeIf { it in 1..254 },
                w.ascentM?.coerceIn(0, 0xFFFE), w.descentM?.coerceIn(0, 0xFFFE), 0, 1, EVENT_LAP, EVENT_TYPE_STOP,
            ),
        )

        f.define(
            5, FitMsg.ACTIVITY,
            listOf(
                FieldDef(253, 4, FitBaseType.UINT32),
                FieldDef(0, 4, FitBaseType.UINT32),
                FieldDef(1, 2, FitBaseType.UINT16),
                FieldDef(2, 1, FitBaseType.ENUM),
                FieldDef(3, 1, FitBaseType.ENUM),
                FieldDef(4, 1, FitBaseType.ENUM),
            ),
        )
        f.data(5, listOf(FitTime.fromEpoch(w.endSec), timerMs, 1, ACTIVITY_MANUAL, EVENT_ACTIVITY, EVENT_TYPE_STOP))
        f.finish()
    }

    /** Great-circle distance in metres between two positions in degrees ×1e7. */
    private fun distanceM(latA: Int, lonA: Int, latB: Int, lonB: Int): Double {
        val la1 = Math.toRadians(latA / 1e7)
        val la2 = Math.toRadians(latB / 1e7)
        val dLa = la2 - la1
        val dLo = Math.toRadians((lonB - lonA) / 1e7)
        val h = Math.sin(dLa / 2) * Math.sin(dLa / 2) + Math.cos(la1) * Math.cos(la2) * Math.sin(dLo / 2) * Math.sin(dLo / 2)
        return 2 * EARTH_RADIUS_M * Math.asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private const val EARTH_RADIUS_M = 6_371_008.8
}
