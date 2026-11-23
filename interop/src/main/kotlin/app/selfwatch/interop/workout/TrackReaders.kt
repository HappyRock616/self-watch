/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.workout

import android.util.Xml
import app.selfwatch.interop.api.ParsedWorkout
import app.selfwatch.interop.api.TrackFormat
import app.selfwatch.interop.io.IsoTime
import app.selfwatch.interop.io.Numbers
import app.selfwatch.interop.io.Texts
import app.selfwatch.model.health.TrackPoint
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.StringReader

/** Creates namespace-aware pull parsers and refuses documents with entity declarations. */
internal object XmlPull {
    /** A parser over [bytes]. Throws [XmlPullParserException] for documents declaring entities. */
    fun parser(bytes: ByteArray): XmlPullParser {
        val text = Texts.decode(bytes)
        // Track files never need a DTD; refusing entity declarations rules out entity-expansion attacks.
        if (text.contains("<!ENTITY", ignoreCase = true)) throw XmlPullParserException("entity declarations are not allowed")
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(StringReader(text))
        return p
    }

    /** Text of the current element (which must have no children), trimmed. */
    fun text(p: XmlPullParser): String = p.nextText().trim()
}

/** Collects one point's values while parsing. */
private class PointBuilder(val latE7: Int?, val lonE7: Int?) {
    var tSec: Long? = null
    var altCm: Int? = null
    var hr: Int? = null
    var cad: Int? = null
    var tempC10: Int? = null
    var speedMms: Int? = null

    fun build(): TrackPoint? {
        val t = tSec ?: return null
        return TrackPoint(t, latE7, lonE7, altCm, hr, cad, tempC10, speedMms)
    }
}

private fun degE7(text: String?): Int? {
    val d = Numbers.parseDecimal(text) ?: return null
    if (d < -180.0 || d > 180.0) return null
    return Numbers.scaled(d, 10_000_000)
}

private fun intIn(text: String, range: IntRange): Int? =
    Numbers.parseDecimal(text)?.let { Numbers.scaled(it, 1) }?.takeIf { it in range }

/**
 * Reads GPX 1.0/1.1 tracks (and routes with times): `trkpt`/`rtept` positions, `ele`, `time`, and
 * heart rate, cadence and temperature from any extension that uses the usual names (`hr`, `cad`,
 * `atemp`, as in Garmin's TrackPointExtension v1 and v2). All segments and tracks of a file make one
 * workout; the sport comes from the first `<type>`.
 */
object GpxReader {
    /** Parses [bytes] (the file [fileName]) into one workout. */
    fun read(fileName: String, bytes: ByteArray): Outcome<ParsedWorkout> {
        val p = XmlPull.parser(bytes)
        val points = ArrayList<TrackPoint>()
        var sawGpx = false
        var type: String? = null
        var current: PointBuilder? = null
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val pt = current
                when (val name = p.name) {
                    "gpx" -> sawGpx = true
                    "trkpt", "rtept" -> current = PointBuilder(degE7(p.getAttributeValue(null, "lat")), degE7(p.getAttributeValue(null, "lon")))
                    "type" -> if (pt == null && type == null) type = XmlPull.text(p)
                    else -> if (pt != null) {
                        when (name) {
                            "ele" -> pt.altCm = Numbers.parseDecimal(XmlPull.text(p))?.let { Numbers.scaled(it, 100) }
                            "time" -> pt.tSec = IsoTime.parse(XmlPull.text(p))
                            "hr", "heartrate" -> pt.hr = intIn(XmlPull.text(p), 1..255)
                            "cad", "cadence" -> pt.cad = intIn(XmlPull.text(p), 0..255)
                            "atemp", "temp" -> pt.tempC10 = Numbers.parseDecimal(XmlPull.text(p))?.let { Numbers.scaled(it, 10) }
                            "speed" -> pt.speedMms = Numbers.parseDecimal(XmlPull.text(p))?.let { Numbers.scaled(it, 1000) }?.takeIf { it >= 0 }
                        }
                    }
                }
            } else if (event == XmlPullParser.END_TAG && (p.name == "trkpt" || p.name == "rtept")) {
                current?.build()?.let { points.add(it) }
                current = null
            }
            event = p.next()
        }
        if (!sawGpx) return Outcome.Fail(SwError.BadInput("not a GPX file"))
        if (points.isEmpty()) return Outcome.Fail(SwError.BadInput("no timed track points"))
        points.sortBy { it.tSec }
        val w = TrackStats.summarize(points, Sports.fromName(type))
            ?: return Outcome.Fail(SwError.BadInput("no timed track points"))
        return Outcome.Ok(ParsedWorkout(w, points, TrackFormat.GPX, fileName, bytes))
    }
}

/**
 * Reads TCX v2: one workout per `Activity`, with totals from its laps (time, distance, calories,
 * average and maximum heart rate) and every `Trackpoint` (position, altitude, heart rate, cadence,
 * speed from the ActivityExtension `Speed`).
 */
object TcxReader {
    private class Lap {
        var timeSec = 0.0
        var distM = 0.0
        var kcal = 0
        var avgHr: Int? = null
        var maxHr: Int? = null
    }

    /** Parses [bytes] (the file [fileName]) into its workouts. */
    fun read(fileName: String, bytes: ByteArray): Outcome<List<ParsedWorkout>> {
        val p = XmlPull.parser(bytes)
        val out = ArrayList<ParsedWorkout>()
        val stack = ArrayList<String>()
        var sawRoot = false
        var sport: String? = null
        var startSec: Long? = null
        var laps = ArrayList<Lap>()
        var points = ArrayList<TrackPoint>()
        var lap: Lap? = null
        var pt: PointBuilder? = null
        var lat: Int? = null
        var lon: Int? = null

        fun finishActivity() {
            if (points.isEmpty() && laps.isEmpty()) return
            points.sortBy { it.tSec }
            val hrWeighted = laps.filter { it.avgHr != null && it.timeSec > 0 }
            val avgHr = if (hrWeighted.isEmpty()) null else {
                (hrWeighted.sumOf { (it.avgHr ?: 0) * it.timeSec } / hrWeighted.sumOf { it.timeSec }).toInt()
            }
            val w = TrackStats.summarize(
                points, Sports.fromName(sport),
                statedStartSec = startSec ?: points.firstOrNull()?.tSec,
                statedDurationSec = laps.takeIf { it.isNotEmpty() }?.sumOf { it.timeSec }?.toInt(),
                statedDistanceM = laps.takeIf { l -> l.isNotEmpty() && l.any { it.distM > 0 } }?.sumOf { it.distM }?.toInt(),
                statedKcal = laps.takeIf { l -> l.any { it.kcal > 0 } }?.sumOf { it.kcal },
                statedAvgHr = avgHr,
                statedMaxHr = laps.mapNotNull { it.maxHr }.maxOrNull(),
            )
            if (w != null) out.add(ParsedWorkout(w, points, TrackFormat.TCX, fileName, bytes))
        }

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = p.name
                    val parent = stack.lastOrNull()
                    var consumed = false
                    when (name) {
                        "TrainingCenterDatabase" -> sawRoot = true
                        "Activity" -> {
                            sport = p.getAttributeValue(null, "Sport")
                            startSec = null
                            laps = ArrayList()
                            points = ArrayList()
                        }
                        "Id" -> if (parent == "Activity") {
                            startSec = IsoTime.parse(XmlPull.text(p))
                            consumed = true
                        }
                        "Lap" -> {
                            lap = Lap().also { laps.add(it) }
                            if (startSec == null) startSec = p.getAttributeValue(null, "StartTime")?.let { IsoTime.parse(it) }
                        }
                        "Trackpoint" -> {
                            pt = PointBuilder(null, null)
                            lat = null
                            lon = null
                        }
                    }
                    val l = lap
                    val b = pt
                    if (!consumed && b != null) {
                        consumed = true
                        when (name) {
                            "Time" -> b.tSec = IsoTime.parse(XmlPull.text(p))
                            "LatitudeDegrees" -> lat = degE7(XmlPull.text(p))
                            "LongitudeDegrees" -> lon = degE7(XmlPull.text(p))
                            "AltitudeMeters" -> b.altCm = Numbers.parseDecimal(XmlPull.text(p))?.let { Numbers.scaled(it, 100) }
                            "Value" -> if (parent == "HeartRateBpm") b.hr = intIn(XmlPull.text(p), 1..255) else consumed = false
                            "Cadence", "RunCadence" -> b.cad = intIn(XmlPull.text(p), 0..255)
                            "Speed" -> b.speedMms = Numbers.parseDecimal(XmlPull.text(p))?.let { Numbers.scaled(it, 1000) }?.takeIf { it >= 0 }
                            else -> consumed = false
                        }
                    } else if (!consumed && l != null && parent != null) {
                        consumed = true
                        when {
                            name == "TotalTimeSeconds" && parent == "Lap" -> l.timeSec = Numbers.parseDecimal(XmlPull.text(p)) ?: 0.0
                            name == "DistanceMeters" && parent == "Lap" -> l.distM = Numbers.parseDecimal(XmlPull.text(p)) ?: 0.0
                            name == "Calories" && parent == "Lap" -> l.kcal = intIn(XmlPull.text(p), 0..100_000) ?: 0
                            name == "Value" && parent == "AverageHeartRateBpm" -> l.avgHr = intIn(XmlPull.text(p), 1..255)
                            name == "Value" && parent == "MaximumHeartRateBpm" -> l.maxHr = intIn(XmlPull.text(p), 1..255)
                            else -> consumed = false
                        }
                    }
                    if (!consumed) stack.add(name)
                }
                XmlPullParser.END_TAG -> {
                    if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    when (p.name) {
                        "Trackpoint" -> {
                            val b = pt
                            if (b != null) {
                                val la = lat
                                val lo = lon
                                b.build()?.let { tp -> points.add(if (la != null && lo != null) tp.copy(latE7 = la, lonE7 = lo) else tp) }
                            }
                            pt = null
                        }
                        "Lap" -> lap = null
                        "Activity" -> finishActivity()
                    }
                }
            }
            event = p.next()
        }
        if (!sawRoot) return Outcome.Fail(SwError.BadInput("not a TCX file"))
        if (out.isEmpty()) return Outcome.Fail(SwError.BadInput("no activities"))
        return Outcome.Ok(out)
    }
}
