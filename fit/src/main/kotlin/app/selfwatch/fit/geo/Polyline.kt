/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.fit.geo

import java.io.ByteArrayOutputStream

/**
 * A position in degrees ×1e7 (the [app.selfwatch.model.health.TrackPoint] unit).
 *
 * @property latE7 latitude ×1e7.
 * @property lonE7 longitude ×1e7.
 */
data class LatLonE7(val latE7: Int, val lonE7: Int)

/**
 * Encoded-polyline format (the well-known Google format, our own implementation): positions rounded to
 * 1e-5 degrees, stored as zig-zag deltas in 5-bit ASCII chunks. Used for `workout.route_preview`.
 */
object Polyline {
    /** Encodes [points] to ASCII bytes. */
    fun encode(points: List<LatLonE7>): ByteArray {
        val out = ByteArrayOutputStream(points.size * 8)
        var lastLat = 0L
        var lastLon = 0L
        for (p in points) {
            val lat = roundE5(p.latE7)
            val lon = roundE5(p.lonE7)
            put(out, lat - lastLat)
            put(out, lon - lastLon)
            lastLat = lat
            lastLon = lon
        }
        return out.toByteArray()
    }

    /** Decodes [bytes] made by [encode]; positions come back with 1e-5 degree precision. Stops at bad input. */
    fun decode(bytes: ByteArray): List<LatLonE7> {
        val out = ArrayList<LatLonE7>()
        var i = 0
        var lat = 0L
        var lon = 0L
        while (i < bytes.size) {
            val a = take(bytes, i) ?: break
            val b = take(bytes, a.second) ?: break
            lat += a.first
            lon += b.first
            i = b.second
            out.add(LatLonE7((lat * 100).toInt(), (lon * 100).toInt()))
        }
        return out
    }

    private fun roundE5(e7: Int): Long = Math.floorDiv(e7.toLong() + 50, 100L)

    private fun put(out: ByteArrayOutputStream, delta: Long) {
        var v = if (delta < 0) (delta shl 1).inv() else delta shl 1
        while (v >= 0x20) {
            out.write(((0x20 or (v and 0x1F).toInt()) + 63))
            v = v shr 5
        }
        out.write((v + 63).toInt())
    }

    /** Reads one value starting at [start]: (value, next index), or null if the input ends or is not polyline. */
    private fun take(bytes: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = start
        while (i < bytes.size && shift <= 35) {
            val c = (bytes[i].toInt() and 0xFF) - 63
            if (c < 0 || c > 63) return null
            i++
            result = result or ((c and 0x1F).toLong() shl shift)
            shift += 5
            if (c < 0x20) {
                val v = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                return v to i
            }
        }
        return null
    }
}
