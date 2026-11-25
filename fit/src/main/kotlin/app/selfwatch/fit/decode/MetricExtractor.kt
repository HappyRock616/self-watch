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
import app.selfwatch.model.health.MetricSample
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Turns the metric messages that can appear in any health file (monitoring, sleep, metrics, HRV status)
 * into [MetricSample]s. One sample per kind and second; a later duplicate replaces an earlier one.
 * VO2 max (`max_met_data`) is handled by [Vo2Max].
 */
internal class MetricExtractor(private val deviceId: Long) {
    private val samples = LinkedHashMap<Long, MetricSample>()

    /** Handles [rec] if it is a metric message; returns true if it was one. */
    fun accept(rec: FitRecord): Boolean {
        when (rec.global) {
            FitMsg.STRESS_LEVEL -> {
                // Ref: Gadgetbridge 160beab41 service/devices/garmin/fit/messages/FitStressLevel.java (time from stress_level_time, field 1)
                // Ref: Gadgetbridge 160beab41 service/devices/garmin/fit/FitImporter.java (negative stress values are not measurements)
                val t = rec.time(1) ?: rec.tSec ?: return true
                val v = rec.int(0) ?: return true
                // Ref: Gadgetbridge 160beab41 FitCodeGenerator/src/main/resources/fit_profile.json (stress_level field 3 body_energy, SINT8)
                rec.int(3)?.takeIf { it in 0..100 }?.let { add(MetricKind.ENERGY_LEVEL, t, it) }
                if (v in 0..100) add(MetricKind.STRESS, t, v)
            }
            FitMsg.RESPIRATION_RATE -> {
                val t = rec.tSec ?: return true
                val raw = rec.long(0) ?: return true // breaths/min ×100
                if (raw > 0 && raw < 10_000) add(MetricKind.RESPIRATION, t, (raw / 10.0).roundToInt())
            }
            FitMsg.SPO2_DATA -> {
                val t = rec.tSec ?: return true
                val v = rec.int(0) ?: return true
                if (v in 50..100) add(MetricKind.SPO2, t, v)
            }
            FitMsg.MONITORING_HR_DATA -> {
                // Ref: Gadgetbridge 160beab41 service/devices/garmin/fit/FitImporter.java (prefer current_day_resting_heart_rate, else resting_heart_rate)
                val t = rec.tSec ?: return true
                val v = rec.int(1)?.takeIf { it in 20..250 } ?: rec.int(0)?.takeIf { it in 20..250 } ?: return true
                add(MetricKind.RESTING_HR, t, v)
            }
            FitMsg.SKIN_TEMP_OVERNIGHT -> {
                val t = rec.tSec ?: return true
                val dev = rec.double(1) ?: return true // °C from baseline
                if (dev > -20.0 && dev < 20.0) add(MetricKind.SKIN_TEMP_DELTA, t, (dev * 100).roundToInt())
            }
            FitMsg.HRV_VALUE -> {
                val t = rec.tSec ?: return true
                val raw = rec.long(0) ?: return true // ms ×128
                val ms = (raw / 128.0).roundToLong()
                if (ms in 1..1000) add(MetricKind.HRV_RMSSD, t, ms.toInt())
            }
            FitMsg.MAX_MET_DATA -> Vo2Max.fromMaxMet(rec)?.let { (kind, t, x10) -> add(kind, t, x10) }
            else -> return false
        }
        return true
    }

    /** Adds a heart-rate sample (used by the monitoring decoder after its per-minute bucketing). */
    fun addHeartRate(tSec: Long, bpm: Int) = add(MetricKind.HEART_RATE, tSec, bpm)

    /** Adds the watch's resting metabolic rate for the local day starting at [dayStartSec] (monitoring decoder). */
    fun addRestingKcal(dayStartSec: Long, kcalPerDay: Int) = add(MetricKind.RESTING_KCAL, dayStartSec, kcalPerDay)

    private fun add(kind: MetricKind, tSec: Long, value: Int) {
        // One map key per (second, kind): the kind code takes the low 4 bits, so codes must stay below 16.
        samples[(tSec shl 4) or kind.code.toLong()] = MetricSample(deviceId, kind, tSec, value)
    }

    /** All samples, sorted by time then kind. */
    fun result(): List<MetricSample> = samples.values.sortedWith(compareBy({ it.tSec }, { it.kind.code }))
}
