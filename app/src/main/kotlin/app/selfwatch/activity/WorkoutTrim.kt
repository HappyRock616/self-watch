/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import app.selfwatch.interop.workout.TrackStats
import app.selfwatch.model.health.TrackPoint
import app.selfwatch.model.health.Workout
import kotlin.math.roundToInt

/** A trim window in epoch seconds, [startSec] before [endSec]. */
data class TrimWindow(val startSec: Long, val endSec: Long) {
    /** Seconds kept. */
    val lengthSec: Long get() = endSec - startSec
}

/**
 * Trim arithmetic: the summary of a workout cut down to a window of its recording.
 *
 * Every value is worked out from the original summary and the track, so trimming again (or with other
 * ends) always starts from the whole recording:
 * - **Start, end**: the window. **Duration** (timer time) shrinks with the elapsed time kept, so pauses in
 *   the original stay in proportion, and never exceeds the window.
 * - **Distance**: the original distance times the share of the GPS distance inside the window (the watch's
 *   own distance is usually better than GPS, so it is scaled, not replaced); from speed samples without
 *   positions; else in proportion to time.
 * - **Heart rate**: average and maximum of the samples inside; the original without samples.
 * - **Calories**: the original times the share of heart-rate beats inside (harder minutes burn more); in
 *   proportion to time without heart rate.
 * - **Climb**: the original ascent and descent times the share of the climb inside; worked out from the
 *   altitude samples when the original has none; unchanged without altitude.
 * - **Route**: the preview drawn from the positions inside.
 */
object WorkoutTrimMath {
    /** The shortest window a trim keeps, seconds. */
    const val MIN_WINDOW_SEC: Long = 60L

    /** The whole recording of [w]: its stored start and end, widened to the track when the track runs longer. */
    fun fullWindow(w: Workout, points: List<TrackPoint>): TrimWindow {
        val start = minOf(w.startSec, points.firstOrNull()?.tSec ?: w.startSec)
        val end = maxOf(w.endSec, points.lastOrNull()?.tSec ?: w.endSec, start + 1)
        return TrimWindow(start, end)
    }

    /**
     * [want] kept inside [full] and at least [MIN_WINDOW_SEC] long (or all of [full] when that is shorter).
     */
    fun clamp(want: TrimWindow, full: TrimWindow): TrimWindow {
        val min = minOf(MIN_WINDOW_SEC, full.lengthSec)
        var s = want.startSec.coerceIn(full.startSec, full.endSec)
        var e = want.endSec.coerceIn(full.startSec, full.endSec)
        if (e - s < min) {
            // Grow the end first; when that hits the recording's end, pull the start back instead, so the
            // window stays inside the recording either way.
            e = minOf(full.endSec, s + min)
            s = e - min
        }
        return TrimWindow(s, e)
    }

    /** True when [window] covers all of [full] (nothing would be cut). */
    fun isWhole(window: TrimWindow, full: TrimWindow): Boolean = window.startSec <= full.startSec && window.endSec >= full.endSec

    /** The points of [points] inside [window]. */
    fun inside(points: List<TrackPoint>, window: TrimWindow): List<TrackPoint> = points.filter { it.tSec in window.startSec..window.endSec }

    /** [original] (the untrimmed summary) cut to [window] of its recording [points]. */
    fun trim(original: Workout, points: List<TrackPoint>, window: TrimWindow): Workout {
        val full = fullWindow(original, points)
        val w = clamp(window, full)
        // Shares are of the whole recording (not of the stored summary's span), so every fallback that
        // scales by time agrees with the track-based shares when the track runs past the summary.
        val timeShare = w.lengthSec.toDouble() / full.lengthSec
        val kept = inside(points, w)
        // Timer time can be less than elapsed (auto-pause), so scale it rather than use the window length.
        val duration = (original.durationSec * timeShare).roundToInt().coerceIn(0, w.lengthSec.toInt())

        val hrs = kept.mapNotNull { p -> p.hrBpm?.takeIf { it in 1..254 } }
        val avgHr = if (hrs.isEmpty()) original.avgHr else (hrs.sumOf { it.toLong() }.toDouble() / hrs.size).roundToInt()
        val maxHr = if (hrs.isEmpty()) original.maxHr else hrs.max()

        val preview = TrackStats.routePreview(kept)
        return original.copy(
            startSec = w.startSec,
            endSec = w.endSec,
            durationSec = duration,
            distanceM = distance(original, points, kept, timeShare),
            kcal = original.kcal?.let { (it * beatShare(points, kept, timeShare)).roundToInt() },
            avgHr = avgHr,
            maxHr = maxHr,
            ascentM = climb(original.ascentM, points, kept) { it.first },
            descentM = climb(original.descentM, points, kept) { it.second },
            hasTrack = preview != null,
            routePreview = preview,
        )
    }

    private fun distance(original: Workout, all: List<TrackPoint>, kept: List<TrackPoint>, timeShare: Double): Int? {
        val gpsAll = TrackStats.cumulativeM(all).lastOrNull() ?: 0.0
        val gpsKept = TrackStats.cumulativeM(kept).lastOrNull() ?: 0.0
        val base = original.distanceM
        // Best source first: GPS share of the watch's distance, GPS alone, the speed sensor's share (indoor
        // or footpod), the speed sensor alone, and last a plain share of time.
        return when {
            gpsAll > 0 && base != null -> (base * (gpsKept / gpsAll)).roundToInt()
            gpsAll > 0 -> gpsKept.roundToInt()
            hasSpeed(all) && base != null -> {
                val total = integrateSpeedM(all)
                if (total > 0) (base * (integrateSpeedM(kept) / total)).roundToInt() else (base * timeShare).roundToInt()
            }
            hasSpeed(all) -> integrateSpeedM(kept).roundToInt()
            else -> base?.let { (it * timeShare).roundToInt() }
        }
    }

    private fun hasSpeed(points: List<TrackPoint>): Boolean = points.any { it.speedMms != null }

    /** Metres from speed samples: each sample's speed over the time to the next one (gaps over 60 s count 60 s). */
    fun integrateSpeedM(points: List<TrackPoint>): Double {
        var m = 0.0
        for (i in 0 until points.size - 1) {
            val v = points[i].speedMms ?: continue
            val dt = (points[i + 1].tSec - points[i].tSec).coerceIn(0, MAX_STEP_SEC)
            m += v / 1000.0 * dt
        }
        return m
    }

    /** The share of heart beats (heart rate × time) inside the window; [timeShare] without heart rate. */
    fun beatShare(all: List<TrackPoint>, kept: List<TrackPoint>, timeShare: Double): Double {
        val total = beats(all)
        return if (total <= 0) timeShare else (beats(kept) / total).coerceIn(0.0, 1.0)
    }

    private fun beats(points: List<TrackPoint>): Double {
        val hr = points.filter { it.hrBpm != null && (it.hrBpm ?: 0) in 1..254 }
        var b = 0.0
        for (i in 0 until hr.size - 1) b += (hr[i].hrBpm ?: 0) * (hr[i + 1].tSec - hr[i].tSec).coerceIn(0, MAX_STEP_SEC) / 60.0
        return b
    }

    private fun climb(original: Int?, all: List<TrackPoint>, kept: List<TrackPoint>, pick: (Pair<Int, Int>) -> Int): Int? {
        val whole = TrackStats.climbM(all) ?: return original
        val part = TrackStats.climbM(kept)?.let(pick) ?: 0
        if (original == null) return part
        val total = pick(whole)
        // A flat track (no climb to share out) keeps the watch's figure rather than dividing by zero.
        return if (total <= 0) original else (original * (part.toDouble() / total)).roundToInt()
    }

    /**
     * Longest gap between two samples that counts in full. A longer gap is a pause or a lost signal; counting
     * it whole would credit distance or beats to time the user was stopped.
     */
    private const val MAX_STEP_SEC = 60L
}
