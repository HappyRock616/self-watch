/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.fit.decode

import app.selfwatch.fit.io.FitRecord
import app.selfwatch.model.health.MetricKind
import kotlin.math.roundToInt

/**
 * VO2 max from Garmin FIT messages, as [MetricKind.VO2_MAX_RUNNING] or [MetricKind.VO2_MAX_CYCLING]
 * (ml/kg/min ×10).
 *
 * Garmin writes it in two places:
 * - `max_met_data` (229): the watch's current estimate, written to the daily metrics file (type 44) and
 *   to some activity files after a qualifying run or ride. `vo2_max` is ml/kg/min ×10; `sport` says
 *   which estimate it is (running 1, cycling 2; generic 0 on watches with a single VO2 max).
 *   `max_met_category` (0 generic, 1 cycling) says the same on files without `sport`.
 *   `calibrated_data` is read by nobody: Garmin Connect and Gadgetbridge show the estimate either way.
 * - `physiological_metrics` (140), activity files only: `met_max` is the workout's own estimate in METs
 *   ×65536; one MET is 3.5 ml/kg/min, so VO2 max = raw / (65536 / 3.5) = raw / 18724.57.
 */
// Ref: Gadgetbridge 160beab41 FitCodeGenerator/src/main/resources/fit_profile.json (MAX_MET_DATA 229: update_time 0, vo2_max 2 scale 10, sport 5, sub_sport 6, max_met_category 8, calibrated_data 9; PHYSIOLOGICAL_METRICS 140: met_max 7 scale 18724.571428571428, sport 11)
// Ref: Gadgetbridge 160beab41 service/devices/garmin/fit/FitImporter.java (max_met_data → GARMIN_MET_MAX_VO2 at the record's time, extra = max_met_category)
// Ref: Gadgetbridge 160beab41 devices/garmin/GarminWorkoutParser.java (physiological_metrics.met_max → MAXIMUM_OXYGEN_UPTAKE of the workout)
object Vo2Max {
    internal const val MM_UPDATE_TIME = 0
    internal const val MM_VO2 = 2
    internal const val MM_SPORT = 5
    internal const val MM_CATEGORY = 8

    internal const val PM_MET_MAX = 7
    internal const val PM_SPORT = 11

    /** `met_max` raw units per ml/kg/min: 65536 per MET over 3.5 ml/kg/min per MET. */
    private const val MET_MAX_PER_ML = 65536.0 / 3.5

    /** FIT sport codes. */
    private const val SPORT_CYCLING = 2

    /** `max_met_category` cycling. */
    private const val CATEGORY_CYCLING = 1

    /** Plausible VO2 max ×10 (10.0 to 99.9 ml/kg/min); anything else is a placeholder or a bad file. */
    val PLAUSIBLE_X10: IntRange = 100..999

    /**
     * The kind for a FIT [sport] (or, without one, a `max_met_category` [category]): cycling for cycling,
     * running for everything else (running, generic and unknown all mean the watch's main estimate).
     */
    fun kindFor(sport: Int?, category: Int? = null): MetricKind = when {
        sport == SPORT_CYCLING -> MetricKind.VO2_MAX_CYCLING
        sport == null && category == CATEGORY_CYCLING -> MetricKind.VO2_MAX_CYCLING
        else -> MetricKind.VO2_MAX_RUNNING
    }

    /** A value in ml/kg/min as stored (×10), or null when implausible. */
    fun x10(mlKgMin: Double): Int? = (mlKgMin * 10).roundToInt().takeIf { it in PLAUSIBLE_X10 }

    /**
     * Kind, time and value (×10) of a `max_met_data` record, or null without a plausible value. The time
     * is `update_time` (when the watch changed the estimate), so the same estimate repeated in every day's
     * metrics file lands on the same row; the record's own time only when `update_time` is missing.
     */
    internal fun fromMaxMet(rec: FitRecord): Triple<MetricKind, Long, Int>? {
        val raw = rec.int(MM_VO2)?.takeIf { it in PLAUSIBLE_X10 } ?: return null
        val t = rec.time(MM_UPDATE_TIME) ?: rec.tSec ?: return null
        return Triple(kindFor(rec.int(MM_SPORT), rec.int(MM_CATEGORY)), t, raw)
    }

    /** Value (×10) of a `physiological_metrics` record's `met_max`, or null. */
    internal fun fromPhysiological(rec: FitRecord): Int? {
        val raw = rec.long(PM_MET_MAX)?.takeIf { it > 0 } ?: return null
        return x10(raw / MET_MAX_PER_ML)
    }
}
