/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.fit.decode

import app.selfwatch.fit.geo.LatLonE7
import app.selfwatch.fit.geo.Polyline
import app.selfwatch.fit.geo.RouteSimplifier
import app.selfwatch.fit.io.FitReader
import app.selfwatch.fit.io.FitRecord
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.TrackPoint
import app.selfwatch.model.health.Workout
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import java.io.InputStream
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Decodes activity files (`GARMIN/Activity`, file type 4): the workout summary, the track and a small
 * route preview.
 *
 * **Rules**
 * - Summary from the `session` messages: sport and sub-sport of the first session; start the earliest
 *   start; duration the summed timer time (moving time, what the watch shows as "Time"); end the latest
 *   start + elapsed time; distance, kcal, ascent and descent summed; average heart rate weighted by timer
 *   time; maximum heart rate the highest. Without a session, the summary comes from the track records.
 * - Track points: position from semicircles to degrees ×1e7; altitude from `enhanced_altitude` else
 *   `altitude` (both /5 − 500 m); speed from `enhanced_speed` else `speed` (mm/s); temperature °C.
 */
object WorkoutDecoder {
    private const val TAG = "WorkoutDecoder"

    // Ref: Gadgetbridge 160beab41 FitCodeGenerator/src/main/resources/fit_profile.json (session 18 and record 20 field numbers and scales)
    private const val S_START = 2
    private const val S_SPORT = 5
    private const val S_SUB_SPORT = 6
    private const val S_ELAPSED = 7
    private const val S_TIMER = 8
    private const val S_DISTANCE = 9
    private const val S_KCAL = 11
    private const val S_AVG_HR = 16
    private const val S_MAX_HR = 17
    private const val S_ASCENT = 22
    private const val S_DESCENT = 23

    private const val R_LAT = 0
    private const val R_LON = 1
    private const val R_ALT = 2
    private const val R_HR = 3
    private const val R_CADENCE = 4
    private const val R_DISTANCE = 5
    private const val R_SPEED = 6
    private const val R_TEMP = 13
    private const val R_ENH_SPEED = 73
    private const val R_ENH_ALT = 78

    private const val SEMI_TO_E7 = 1.8e9 / 2147483648.0

    // FIT sport codes.
    private const val SPORT_RUNNING = 1
    private const val SPORT_CYCLING = 2

    /**
     * The workout summary of [file]. `id` is 0 and the file paths are null (the caller fills them in);
     * source is [DataSource.WATCH] when [deviceId] is given, else [DataSource.IMPORT]. The route preview
     * is filled in from the same pass.
     */
    fun summary(file: InputStream, deviceId: Long?): Outcome<Workout> {
        var sport: Int? = null
        var subSport: Int? = null
        var start: Long? = null
        var end: Long? = null
        var timerSec = 0.0
        var anyTimer = false
        var distCm: Long? = null
        var kcal: Long? = null
        var ascent: Long? = null
        var descent: Long? = null
        var hrWeighted = 0.0
        var hrWeight = 0.0
        var hrPlain: Int? = null
        var maxHr: Int? = null
        var sessions = 0

        var firstRec: Long? = null
        var lastRec: Long? = null
        var recDistCm: Long? = null
        var recMaxHr: Int? = null
        var recHrSum = 0L
        var recHrN = 0
        val route = ArrayList<LatLonE7>()

        val result = FitReader(file).read { rec ->
            when (rec.global) {
                FitMsg.SESSION -> {
                    sessions++
                    if (sport == null) {
                        sport = rec.int(S_SPORT)
                        subSport = rec.int(S_SUB_SPORT)
                    }
                    val st = rec.time(S_START) ?: rec.tSec
                    val elapsed = rec.long(S_ELAPSED)?.let { it / 1000.0 }
                    val timer = rec.long(S_TIMER)?.let { it / 1000.0 } ?: elapsed
                    if (st != null) {
                        start = minOf(start ?: st, st)
                        val e = st + ((elapsed ?: timer ?: 0.0).roundToLong())
                        end = maxOf(end ?: e, e)
                    }
                    if (timer != null) { timerSec += timer; anyTimer = true }
                    rec.long(S_DISTANCE)?.let { distCm = (distCm ?: 0) + it }
                    rec.long(S_KCAL)?.let { kcal = (kcal ?: 0) + it }
                    rec.long(S_ASCENT)?.let { ascent = (ascent ?: 0) + it }
                    rec.long(S_DESCENT)?.let { descent = (descent ?: 0) + it }
                    rec.int(S_AVG_HR)?.let { hr ->
                        if (timer != null && timer > 0) { hrWeighted += hr * timer; hrWeight += timer } else hrPlain = hr
                    }
                    rec.int(S_MAX_HR)?.let { maxHr = maxOf(maxHr ?: it, it) }
                }
                FitMsg.RECORD -> {
                    val t = rec.tSec ?: return@read
                    if (firstRec == null) firstRec = t
                    lastRec = t
                    rec.long(R_DISTANCE)?.let { recDistCm = maxOf(recDistCm ?: it, it) }
                    rec.int(R_HR)?.takeIf { it in 1..254 }?.let { hr ->
                        recMaxHr = maxOf(recMaxHr ?: hr, hr); recHrSum += hr; recHrN++
                    }
                    position(rec)?.let { route.add(it) }
                }
            }
        }
        return when (result) {
            is Outcome.Fail -> result
            is Outcome.Ok -> {
                if (!result.value.crcOk) SwLog.w(TAG, "activity file CRC mismatch; using its data anyway")
                val st = start ?: firstRec
                val en = end ?: lastRec
                if (st == null || en == null) {
                    return Outcome.Fail(SwError.BadInput("activity file has no session and no records"))
                }
                val useSession = sessions > 0
                val duration = if (useSession && anyTimer) timerSec.roundToLong() else en - st
                val avgHr = when {
                    hrWeight > 0 -> (hrWeighted / hrWeight).roundToInt()
                    hrPlain != null -> hrPlain
                    !useSession && recHrN > 0 -> (recHrSum.toDouble() / recHrN).roundToInt()
                    else -> null
                }
                val dist = if (useSession) distCm else recDistCm
                Outcome.Ok(
                    Workout(
                        id = 0,
                        deviceId = deviceId,
                        sport = sport ?: 0,
                        subSport = subSport,
                        startSec = st,
                        endSec = maxOf(en, st),
                        durationSec = duration.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                        distanceM = dist?.let { (it / 100.0).roundToLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt() },
                        kcal = kcal?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                        avgHr = avgHr,
                        maxHr = if (useSession) maxHr ?: recMaxHr else recMaxHr,
                        ascentM = ascent?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                        descentM = descent?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                        hasTrack = route.isNotEmpty(),
                        fitPath = null,
                        trackPath = null,
                        routePreview = if (route.isEmpty()) null else Polyline.encode(RouteSimplifier.simplify(route, 200)),
                        source = if (deviceId != null) DataSource.WATCH else DataSource.IMPORT,
                    ),
                )
            }
        }
    }

    /**
     * The VO2 max estimates in activity [file] for device [deviceId] (see [Vo2Max]); empty when there are
     * none or the file cannot be read.
     *
     * The watch's `max_met_data` wins when the file has it: that is the estimate the watch now shows,
     * after this workout. Only without it does the workout's own `physiological_metrics.met_max` count,
     * and then only for runs and rides (Garmin estimates VO2 max from nothing else), stamped at the
     * message's time, else the workout's last record.
     */
    fun vo2Max(file: InputStream, deviceId: Long): List<MetricSample> {
        var sessionSport: Int? = null
        var lastT: Long? = null
        val maxMet = LinkedHashMap<Pair<MetricKind, Long>, MetricSample>()
        var physio: Triple<Int?, Long?, Int>? = null // sport, time, VO2 max ×10
        val result = FitReader(file).read { rec ->
            rec.tSec?.let { t -> lastT = maxOf(lastT ?: t, t) }
            when (rec.global) {
                FitMsg.SESSION -> if (sessionSport == null) sessionSport = rec.int(S_SPORT)
                FitMsg.MAX_MET_DATA -> Vo2Max.fromMaxMet(rec)?.let { (kind, t, x10) ->
                    maxMet[kind to t] = MetricSample(deviceId, kind, t, x10)
                }
                FitMsg.PHYSIOLOGICAL_METRICS -> Vo2Max.fromPhysiological(rec)?.let { x10 ->
                    physio = Triple(rec.int(Vo2Max.PM_SPORT), rec.tSec, x10)
                }
            }
        }
        if (result is Outcome.Fail) return emptyList()
        if (maxMet.isNotEmpty()) return maxMet.values.sortedBy { it.tSec }
        val (sport, t, x10) = physio ?: return emptyList()
        val s = sport ?: sessionSport
        if (s != SPORT_RUNNING && s != SPORT_CYCLING) return emptyList()
        val at = t ?: lastT ?: return emptyList()
        return listOf(MetricSample(deviceId, Vo2Max.kindFor(s), at, x10))
    }

    /** Streams the track of [file] to [onPoint] in file order; returns the number of points. */
    fun track(file: InputStream, onPoint: (TrackPoint) -> Unit): Outcome<Int> {
        var count = 0
        val result = FitReader(file).read { rec ->
            if (rec.global != FitMsg.RECORD) return@read
            val t = rec.tSec ?: return@read
            val pos = position(rec)
            val altRaw = rec.long(R_ENH_ALT) ?: rec.long(R_ALT)
            val speed = rec.long(R_ENH_SPEED) ?: rec.long(R_SPEED)
            onPoint(
                TrackPoint(
                    tSec = t,
                    latE7 = pos?.latE7,
                    lonE7 = pos?.lonE7,
                    altCm = altRaw?.let { (it * 20 - 50_000).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt() },
                    hrBpm = rec.int(R_HR)?.takeIf { it in 1..254 },
                    cadence = rec.int(R_CADENCE),
                    tempC10 = rec.int(R_TEMP)?.let { it * 10 },
                    speedMms = speed?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                ),
            )
            count++
        }
        return when (result) {
            is Outcome.Fail -> result
            is Outcome.Ok -> Outcome.Ok(count)
        }
    }

    /**
     * The route of [file] simplified to at most [maxPoints] points (Douglas-Peucker) and encoded as a
     * polyline (see [Polyline]). Empty if the file has no positions or cannot be read.
     */
    fun routePreview(file: InputStream, maxPoints: Int = 200): ByteArray {
        val route = ArrayList<LatLonE7>()
        val result = FitReader(file).read { rec -> if (rec.global == FitMsg.RECORD) position(rec)?.let { route.add(it) } }
        if (result is Outcome.Fail) return ByteArray(0)
        return Polyline.encode(RouteSimplifier.simplify(route, maxPoints))
    }

    private fun position(rec: FitRecord): LatLonE7? {
        val lat = rec.long(R_LAT) ?: return null
        val lon = rec.long(R_LON) ?: return null
        val latE7 = (lat * SEMI_TO_E7).roundToLong()
        val lonE7 = (lon * SEMI_TO_E7).roundToLong()
        if (latE7 !in -900_000_000L..900_000_000L || lonE7 !in -1_800_000_000L..1_800_000_000L) return null
        return LatLonE7(latE7.toInt(), lonE7.toInt())
    }
}
