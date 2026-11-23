/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.workout

import app.selfwatch.fit.geo.LatLonE7
import app.selfwatch.fit.geo.Polyline
import app.selfwatch.fit.geo.RouteSimplifier
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.TrackPoint
import app.selfwatch.model.health.Workout
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** FIT sport codes and their names in GPX `<type>` and TCX `Sport`. */
object Sports {
    /** FIT `generic`. */
    const val GENERIC: Int = 0

    /** FIT `running`. */
    const val RUNNING: Int = 1

    /** FIT `cycling`. */
    const val CYCLING: Int = 2

    /** FIT `swimming`. */
    const val SWIMMING: Int = 5

    /** FIT `walking`. */
    const val WALKING: Int = 11

    /** FIT `hiking`. */
    const val HIKING: Int = 17

    private val NAMES = mapOf(
        0 to "other", 1 to "running", 2 to "cycling", 4 to "fitness_equipment", 5 to "swimming", 10 to "training",
        11 to "walking", 12 to "cross_country_skiing", 13 to "alpine_skiing", 15 to "rowing", 16 to "mountaineering",
        17 to "hiking", 19 to "paddling",
    )

    private val ALIASES = mapOf(
        "run" to 1, "running" to 1, "trail_running" to 1, "street_running" to 1, "treadmill_running" to 1,
        "bike" to 2, "biking" to 2, "cycling" to 2, "ride" to 2, "road_biking" to 2, "mountain_biking" to 2,
        "swim" to 5, "swimming" to 5, "lap_swimming" to 5, "open_water_swimming" to 5,
        "walk" to 11, "walking" to 11, "hike" to 17, "hiking" to 17, "rowing" to 15, "row" to 15,
        "paddling" to 19, "kayaking" to 19, "cross_country_skiing" to 12, "alpine_skiing" to 13,
        "mountaineering" to 16, "training" to 10, "fitness_equipment" to 4,
    )

    /** GPX `<type>` text for a FIT [sport]. */
    fun gpxName(sport: Int): String = NAMES[sport] ?: "other"

    /** TCX `Sport` attribute (only Running, Biking and Other exist) for a FIT [sport]. */
    fun tcxName(sport: Int): String = when (sport) {
        RUNNING -> "Running"
        CYCLING -> "Biking"
        else -> "Other"
    }

    /** FIT sport for a GPX type or TCX sport name (any case, spaces or underscores), [GENERIC] if unknown. */
    fun fromName(name: String?): Int {
        if (name == null) return GENERIC
        val key = name.trim().lowercase().replace(' ', '_').replace('-', '_')
        return ALIASES[key] ?: NAMES.entries.firstOrNull { it.value == key }?.key ?: GENERIC
    }
}

/** Distance, climb and summary maths over track points. */
object TrackStats {
    private const val EARTH_RADIUS_M = 6_371_008.8
    private const val CLIMB_THRESHOLD_CM = 300

    /** Great-circle distance in metres between two positions in degrees ×1e7. */
    fun distanceM(latE7a: Int, lonE7a: Int, latE7b: Int, lonE7b: Int): Double {
        val la1 = Math.toRadians(latE7a / 1e7)
        val la2 = Math.toRadians(latE7b / 1e7)
        val dLa = la2 - la1
        val dLo = Math.toRadians((lonE7b - lonE7a) / 1e7)
        val h = sin(dLa / 2) * sin(dLa / 2) + cos(la1) * cos(la2) * sin(dLo / 2) * sin(dLo / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Cumulative distance in metres at each point (positions only; points without one repeat the last value). */
    fun cumulativeM(points: List<TrackPoint>): DoubleArray {
        val out = DoubleArray(points.size)
        var total = 0.0
        var lastLat: Int? = null
        var lastLon: Int? = null
        points.forEachIndexed { i, p ->
            val lat = p.latE7
            val lon = p.lonE7
            if (lat != null && lon != null) {
                if (lastLat != null && lastLon != null) total += distanceM(lastLat, lastLon, lat, lon)
                lastLat = lat
                lastLon = lon
            }
            out[i] = total
        }
        return out
    }

    /** Ascent and descent in metres with a 3 m dead band against GPS noise. */
    fun climbM(points: List<TrackPoint>): Pair<Int, Int>? {
        var anchor: Int? = null
        var up = 0L
        var down = 0L
        var any = false
        for (p in points) {
            val a = p.altCm ?: continue
            any = true
            val base = anchor
            if (base == null) {
                anchor = a
                continue
            }
            val d = a - base
            if (d >= CLIMB_THRESHOLD_CM) {
                up += d
                anchor = a
            } else if (d <= -CLIMB_THRESHOLD_CM) {
                down -= d
                anchor = a
            }
        }
        return if (any) (up / 100).toInt() to (down / 100).toInt() else null
    }

    /** Route preview polyline for [points] (empty positions skipped), or null without positions. */
    fun routePreview(points: List<TrackPoint>, maxPoints: Int = 200): ByteArray? {
        val route = points.mapNotNull { p ->
            val lat = p.latE7
            val lon = p.lonE7
            if (lat != null && lon != null) LatLonE7(lat, lon) else null
        }
        return if (route.isEmpty()) null else Polyline.encode(RouteSimplifier.simplify(route, maxPoints))
    }

    /**
     * A workout summary computed from [points]; any of the stated values (from a TCX lap, say) win over
     * computed ones. `id` is 0, the file paths are null and the source is [DataSource.IMPORT].
     */
    fun summarize(
        points: List<TrackPoint>,
        sport: Int,
        statedStartSec: Long? = null,
        statedDurationSec: Int? = null,
        statedDistanceM: Int? = null,
        statedKcal: Int? = null,
        statedAvgHr: Int? = null,
        statedMaxHr: Int? = null,
    ): Workout? {
        val start = statedStartSec ?: points.firstOrNull()?.tSec ?: return null
        val lastT = points.lastOrNull()?.tSec ?: start
        val duration = statedDurationSec ?: (lastT - start).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val end = maxOf(lastT, start + duration)
        val hrs = points.mapNotNull { it.hrBpm }
        val dist = statedDistanceM ?: cumulativeM(points).lastOrNull()?.takeIf { it > 0 }?.roundToInt()
        val climb = climbM(points)
        val preview = routePreview(points)
        return Workout(
            id = 0,
            deviceId = null,
            sport = sport,
            subSport = null,
            startSec = start,
            endSec = end,
            durationSec = duration,
            distanceM = dist,
            kcal = statedKcal,
            avgHr = statedAvgHr ?: if (hrs.isEmpty()) null else (hrs.sumOf { it.toLong() } / hrs.size).toInt(),
            maxHr = statedMaxHr ?: hrs.maxOrNull(),
            ascentM = climb?.first,
            descentM = climb?.second,
            hasTrack = preview != null,
            fitPath = null,
            trackPath = null,
            routePreview = preview,
            source = DataSource.IMPORT,
        )
    }
}
