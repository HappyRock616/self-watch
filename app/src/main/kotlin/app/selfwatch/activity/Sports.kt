/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import android.content.Context
import androidx.annotation.StringRes
import app.selfwatch.R
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.Day
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import app.selfwatch.ui.format.Units
import java.time.ZoneId
import java.util.Locale

/** Names for FIT `sport` codes and the one-line workout summaries used in lists. */
object Sports {
    // FIT profile `sport` enum values (FIT SDK profile, public documentation).
    const val GENERIC: Int = 0
    const val RUNNING: Int = 1
    const val CYCLING: Int = 2
    const val SWIMMING: Int = 5
    const val TRAINING: Int = 10
    const val WALKING: Int = 11
    const val HIKING: Int = 17
    const val ROWING: Int = 15

    /** The label of [sport]. */
    @StringRes
    fun label(sport: Int): Int = when (sport) {
        RUNNING -> R.string.sport_run
        CYCLING -> R.string.sport_bike
        SWIMMING -> R.string.sport_swim
        TRAINING -> R.string.sport_training
        WALKING -> R.string.sport_walk
        HIKING -> R.string.sport_hike
        ROWING -> R.string.sport_row
        else -> R.string.sport_workout
    }

    /** True for sports where pace (time per distance) reads better than speed. */
    fun usesPace(sport: Int): Boolean = sport == RUNNING || sport == WALKING || sport == HIKING

    /** "Today · 3.2 km · 42 min" for a list row. */
    fun subtitle(ctx: Context, w: Workout, units: Units, zone: ZoneId = ZoneId.systemDefault()): String {
        val labels = FmtLabels.from(ctx)
        val loc = Locale.getDefault()
        val parts = ArrayList<String>()
        parts += Fmt.day(ctx, Day.of(w.startSec, zone).yyyymmdd)
        w.distanceM?.takeIf { it > 0 }?.let { parts += Fmt.distance(it.toDouble(), units, loc, labels) }
        parts += Fmt.duration(w.durationSec.toLong(), loc, labels)
        return parts.joinToString(" · ")
    }

    /**
     * Pace per kilometre (or mile) as "5:12", from [durationSec] and [distanceM]; null when either is
     * zero.
     */
    fun pace(durationSec: Int, distanceM: Int?, units: Units): String? {
        val d = distanceM ?: return null
        if (d <= 0 || durationSec <= 0) return null
        val perUnit = if (units == Units.METRIC) 1000.0 else METERS_PER_MILE
        val sec = (durationSec * perUnit / d).toLong()
        return String.format(Locale.getDefault(), "%d:%02d", sec / 60, sec % 60)
    }

    /** Average speed in km/h or mph, one decimal; null without distance. */
    fun speed(durationSec: Int, distanceM: Int?, units: Units): String? {
        val d = distanceM ?: return null
        if (d <= 0 || durationSec <= 0) return null
        val perUnit = if (units == Units.METRIC) 1000.0 else METERS_PER_MILE
        return Fmt.number(d / perUnit / (durationSec / 3600.0), 1)
    }

    /** Metres in a mile. */
    const val METERS_PER_MILE: Double = 1609.344
}
