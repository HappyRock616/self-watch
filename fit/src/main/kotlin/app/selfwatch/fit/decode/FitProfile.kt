/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.fit.decode

import app.selfwatch.fit.io.FitRecord
import app.selfwatch.fit.io.FitValue

// Global message and field numbers we use. Every number below was checked (T-A05) against
// Ref: Gadgetbridge 160beab41 FitCodeGenerator/src/main/resources/fit_profile.json (messages and fields)

/** Global message numbers. */
object FitMsg {
    /** file_id. */
    const val FILE_ID: Int = 0

    /** session. */
    const val SESSION: Int = 18

    /** lap. */
    const val LAP: Int = 19

    /** record (one track point). */
    const val RECORD: Int = 20

    /** event. */
    const val EVENT: Int = 21

    /** device_info. */
    const val DEVICE_INFO: Int = 23

    /** weight_scale. */
    const val WEIGHT_SCALE: Int = 30

    /** activity. */
    const val ACTIVITY: Int = 34

    /** monitoring. */
    const val MONITORING: Int = 55

    /** hrv (beat intervals during a workout). */
    const val HRV: Int = 78

    /** monitoring_info. */
    const val MONITORING_INFO: Int = 103

    /** physiological_metrics (training effect and the workout's VO2 max estimate, activity files). */
    const val PHYSIOLOGICAL_METRICS: Int = 140

    /** field_description (developer fields). */
    const val FIELD_DESCRIPTION: Int = 206

    /** monitoring_hr_data (resting heart rate). */
    const val MONITORING_HR_DATA: Int = 211

    /** stress_level. */
    const val STRESS_LEVEL: Int = 227

    /** max_met_data (VO2 max estimate, metrics files and some activity files). */
    const val MAX_MET_DATA: Int = 229

    /** spo2_data. */
    const val SPO2_DATA: Int = 269

    /** sleep_level (stage samples). */
    const val SLEEP_LEVEL: Int = 275

    /** respiration_rate. */
    const val RESPIRATION_RATE: Int = 297

    /** sleep_assessment (scores). */
    const val SLEEP_ASSESSMENT: Int = 346

    /** hrv_status_summary (nightly HRV). */
    const val HRV_STATUS_SUMMARY: Int = 370
    const val METRIC_RECOVERY: Int = 284
    const val NAP_EVENT: Int = 412

    /** hrv_value (5-minute RMSSD). */
    const val HRV_VALUE: Int = 371

    /** daily_sleep (community-documented; start, end, score). */
    const val DAILY_SLEEP: Int = 384

    /** skin_temp_raw. */
    const val SKIN_TEMP_RAW: Int = 397

    /** skin_temp_overnight (nightly deviation from baseline). */
    const val SKIN_TEMP_OVERNIGHT: Int = 398

    /** sleep_summary (community-documented; start, end, score, stage minutes). */
    const val SLEEP_SUMMARY: Int = 411
}

/** `file_id.type` values. */
object FitFileType {
    /** Activity (workout). */
    const val ACTIVITY: Int = 4

    /** Weight. */
    const val WEIGHT: Int = 9

    /** Monitoring A. */
    const val MONITORING_A: Int = 15

    /** Monitoring (daily). */
    const val MONITORING_DAILY: Int = 28

    /** Monitoring B (the usual `GARMIN/Monitor` file). */
    const val MONITORING_B: Int = 32

    /** Metrics. */
    const val METRICS: Int = 44

    /** Sleep. */
    const val SLEEP: Int = 49

    /** HRV status. */
    const val HRV_STATUS: Int = 68
}
// Ref: Gadgetbridge 160beab41 app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/garmin/FileType.java (file type numbers)

/** Common field number of the timestamp field in every message. */
internal const val F_TIMESTAMP: Int = 253

/** The integer value of field [num], or null if missing or invalid. Arrays give their first valid element. */
internal fun FitRecord.long(num: Int): Long? = fields[num].asLong()

/** The integer value of field [num] as Int, or null. */
internal fun FitRecord.int(num: Int): Int? = fields[num].asLong()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else null }

/** The numeric value of field [num] as Double (floats and integers), or null. */
internal fun FitRecord.double(num: Int): Double? = when (val v = fields[num]) {
    is FitValue.D -> if (v.v.isNaN() || v.v.isInfinite()) null else v.v
    is FitValue.I -> v.v.toDouble()
    is FitValue.A -> v.v.firstNotNullOfOrNull { (it as? FitValue.D)?.v ?: (it as? FitValue.I)?.v?.toDouble() }
    else -> null
}

/** UTC epoch seconds of a FIT date_time field [num], or null. */
internal fun FitRecord.time(num: Int): Long? = long(num)?.let { app.selfwatch.fit.io.FitTime.toEpoch(it) }

internal fun FitValue?.asLong(): Long? = when (this) {
    is FitValue.I -> v
    is FitValue.D -> if (v.isNaN() || v.isInfinite()) null else v.toLong()
    is FitValue.A -> v.firstNotNullOfOrNull { (it as? FitValue.I)?.v }
    else -> null
}
