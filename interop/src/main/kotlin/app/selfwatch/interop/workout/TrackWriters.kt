/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.workout

import app.selfwatch.interop.io.IsoTime
import app.selfwatch.interop.io.Numbers
import app.selfwatch.interop.io.XmlWriter
import app.selfwatch.model.health.TrackPoint
import app.selfwatch.model.health.Workout
import java.io.OutputStream
import kotlin.math.roundToInt

/** XML namespaces of the track formats. */
object TrackNs {
    /** GPX 1.1. */
    const val GPX: String = "http://www.topografix.com/GPX/1/1"

    /** Garmin TrackPointExtension v1 (hr, cad, atemp). */
    const val TPX_V1: String = "http://www.garmin.com/xmlschemas/TrackPointExtension/v1"

    /** XML Schema instance. */
    const val XSI: String = "http://www.w3.org/2001/XMLSchema-instance"

    /** TCX (Training Center Database v2). */
    const val TCX: String = "http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2"
}

/**
 * Writes GPX 1.1: one `trk` with one `trkseg`; each point with a position becomes a `trkpt` with
 * `ele`, `time` and, when known, Garmin's TrackPointExtension (`atemp`, `hr`, `cad`, in the schema's
 * order). Points without a position are left out (GPX requires one).
 */
object GpxWriter {
    /** Writes [workout] with [points] to [out] (flushed, not closed). [name] is the track name. */
    fun write(out: OutputStream, workout: Workout, points: List<TrackPoint>, name: String) {
        val x = XmlWriter(out)
        x.declaration()
        x.start("gpx")
            .attr("version", "1.1")
            .attr("creator", "Self Watch")
            .attr("xmlns", TrackNs.GPX)
            .attr("xmlns:gpxtpx", TrackNs.TPX_V1)
            .attr("xmlns:xsi", TrackNs.XSI)
            .attr(
                "xsi:schemaLocation",
                "${TrackNs.GPX} http://www.topografix.com/GPX/1/1/gpx.xsd " +
                    "${TrackNs.TPX_V1} http://www8.garmin.com/xmlschemas/TrackPointExtensionv1.xsd",
            )
        x.start("metadata").element("name", name).element("time", IsoTime.format(workout.startSec)).end("metadata")
        x.start("trk").element("name", name).element("type", Sports.gpxName(workout.sport))
        x.start("trkseg")
        for (p in points) {
            val lat = p.latE7 ?: continue
            val lon = p.lonE7 ?: continue
            x.start("trkpt").attr("lat", Numbers.formatScaled(lat, 10_000_000)).attr("lon", Numbers.formatScaled(lon, 10_000_000))
            p.altCm?.let { x.element("ele", Numbers.formatScaled(it, 100)) }
            x.element("time", IsoTime.format(p.tSec))
            val hr = p.hrBpm?.takeIf { it in 1..255 }
            val cad = p.cadence?.takeIf { it in 0..254 }
            val temp = p.tempC10
            if (hr != null || cad != null || temp != null) {
                x.start("extensions").start("gpxtpx:TrackPointExtension")
                temp?.let { x.element("gpxtpx:atemp", Numbers.formatScaled(it, 10)) }
                hr?.let { x.element("gpxtpx:hr", it.toString()) }
                cad?.let { x.element("gpxtpx:cad", it.toString()) }
                x.end("gpxtpx:TrackPointExtension").end("extensions")
            }
            x.end("trkpt")
        }
        x.end("trkseg").end("trk").end("gpx")
        x.finish()
    }
}

/**
 * Writes TCX v2: one Activity with one Lap holding the workout totals and a Track of every point
 * (position, altitude, cumulative distance, heart rate, cadence). Suits indoor workouts too: a
 * workout without points gets a lap without a track.
 */
object TcxWriter {
    /** Writes [workout] with [points] to [out] (flushed, not closed). */
    fun write(out: OutputStream, workout: Workout, points: List<TrackPoint>) {
        val x = XmlWriter(out)
        val cumulative = TrackStats.cumulativeM(points)
        x.declaration()
        x.start("TrainingCenterDatabase").attr("xmlns", TrackNs.TCX).attr("xmlns:xsi", TrackNs.XSI)
            .attr(
                "xsi:schemaLocation",
                "${TrackNs.TCX} http://www.garmin.com/xmlschemas/TrainingCenterDatabasev2.xsd",
            )
        x.start("Activities").start("Activity").attr("Sport", Sports.tcxName(workout.sport))
        x.element("Id", IsoTime.format(workout.startSec))
        x.start("Lap").attr("StartTime", IsoTime.format(workout.startSec))
        x.element("TotalTimeSeconds", workout.durationSec.toString())
        val dist = workout.distanceM ?: cumulative.lastOrNull()?.roundToInt() ?: 0
        x.element("DistanceMeters", dist.toString())
        x.element("Calories", (workout.kcal ?: 0).coerceIn(0, 65535).toString())
        workout.avgHr?.takeIf { it in 1..255 }?.let { x.start("AverageHeartRateBpm").element("Value", it.toString()).end("AverageHeartRateBpm") }
        workout.maxHr?.takeIf { it in 1..255 }?.let { x.start("MaximumHeartRateBpm").element("Value", it.toString()).end("MaximumHeartRateBpm") }
        x.element("Intensity", "Active")
        x.element("TriggerMethod", "Manual")
        if (points.isNotEmpty()) {
            x.start("Track")
            points.forEachIndexed { i, p ->
                x.start("Trackpoint")
                x.element("Time", IsoTime.format(p.tSec))
                val lat = p.latE7
                val lon = p.lonE7
                if (lat != null && lon != null) {
                    x.start("Position")
                        .element("LatitudeDegrees", Numbers.formatScaled(lat, 10_000_000))
                        .element("LongitudeDegrees", Numbers.formatScaled(lon, 10_000_000))
                        .end("Position")
                }
                p.altCm?.let { x.element("AltitudeMeters", Numbers.formatScaled(it, 100)) }
                x.element("DistanceMeters", Numbers.format(cumulative[i], 2))
                p.hrBpm?.takeIf { it in 1..255 }?.let { x.start("HeartRateBpm").element("Value", it.toString()).end("HeartRateBpm") }
                p.cadence?.takeIf { it in 0..254 }?.let { x.element("Cadence", it.toString()) }
                x.end("Trackpoint")
            }
            x.end("Track")
        }
        x.end("Lap").end("Activity").end("Activities").end("TrainingCenterDatabase")
        x.finish()
    }
}
