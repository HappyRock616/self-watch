/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import android.content.Context
import androidx.annotation.DrawableRes
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

    /** The icon of [sport]. */
    @DrawableRes
    fun icon(sport: Int): Int = when (sport) {
        RUNNING -> R.drawable.ic_sport_run
        CYCLING -> R.drawable.ic_sport_bike
        SWIMMING -> R.drawable.ic_sport_swim
        WALKING, HIKING -> R.drawable.ic_sport_walk
        else -> R.drawable.ic_sport_strength
    }

    /**
     * The stats line of a workout: duration, distance, pace (or speed), average heart rate and calories,
     * each only when recorded ("42 min · 5.2 km · 5:12 /km · 142 bpm · 420 kcal").
     */
    fun stats(ctx: Context, w: Workout, units: Units): String {
        val labels = FmtLabels.from(ctx)
        val loc = Locale.getDefault()
        val parts = ArrayList<String>()
        parts += Fmt.duration(w.durationSec.toLong(), loc, labels)
        w.distanceM?.takeIf { it > 0 }?.let { parts += Fmt.distance(it.toDouble(), units, loc, labels) }
        if (usesPace(w.sport)) {
            pace(w.durationSec, w.distanceM, units)?.let { parts += it + " " + paceUnit(ctx, units) }
        } else {
            speed(w.durationSec, w.distanceM, units)?.let {
                parts += it + " " + ctx.getString(if (units == Units.METRIC) R.string.unit_kmh else R.string.unit_mph)
            }
        }
        w.avgHr?.let { parts += it.toString() + " " + ctx.getString(R.string.unit_bpm) }
        w.kcal?.let { parts += Fmt.energy(it, loc, labels) }
        return parts.joinToString(" · ")
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
     * Pace per kilometre (or mile, following Me › Settings units) as "5:12", from [durationSec] and
     * [distanceM]; null when either is zero. Every pace in the app is written this way ([paceText]).
     */
    fun pace(durationSec: Int, distanceM: Int?, units: Units): String? {
        val d = distanceM ?: return null
        if (d <= 0 || durationSec <= 0) return null
        return paceText(durationSec * 1000.0 / d, units)
    }

    /** A pace of [secPerKm] seconds a kilometre as "m:ss" per km or, in US units, per mile. */
    fun paceText(secPerKm: Double, units: Units): String = Fmt.minSec(secPerKm * metersPerUnit(units) / 1000.0)

    /** The pace unit, "/km" or "/mi", for [units]. */
    fun paceUnit(ctx: Context, units: Units): String =
        ctx.getString(if (units == Units.METRIC) R.string.unit_per_km else R.string.unit_per_mi)

    /** Metres in the distance unit of [units]: a kilometre, or a mile in US units. */
    fun metersPerUnit(units: Units): Double = if (units == Units.METRIC) 1000.0 else METERS_PER_MILE

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
