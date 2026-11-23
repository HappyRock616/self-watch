/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.fit.decode

import app.selfwatch.fit.io.FitReader
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.watch.IngestBatch
import java.io.InputStream
import kotlin.math.roundToInt

/**
 * One night's HRV status from `hrv_status_summary` (370).
 *
 * @property tSec UTC epoch seconds of the summary.
 * @property lastNightAvgMs last night's average RMSSD in ms, or null.
 * @property weeklyAvgMs 7-day average in ms, or null.
 * @property status 0 none, 1 poor, 2 low, 3 unbalanced, 4 balanced; null if missing.
 */
data class HrvNight(val tSec: Long, val lastNightAvgMs: Int?, val weeklyAvgMs: Int?, val status: Int?)

/**
 * Decodes HRV status files (file type 68). [decode] gives the 5-minute RMSSD values (`hrv_value`, 371)
 * as [app.selfwatch.model.health.MetricKind.HRV_RMSSD] samples, plus any other metric messages in the
 * file; [nightly] gives the nightly summaries.
 */
object HrvDecoder {
    private const val TAG = "HrvDecoder"

    /** Decodes [file] for the watch [deviceId]. */
    fun decode(file: InputStream, deviceId: Long): Outcome<IngestBatch> {
        val metrics = MetricExtractor(deviceId)
        val result = FitReader(file).read { rec -> metrics.accept(rec) }
        return when (result) {
            is Outcome.Fail -> result
            is Outcome.Ok -> {
                if (!result.value.crcOk) SwLog.w(TAG, "HRV file CRC mismatch; using its data anyway")
                Outcome.Ok(IngestBatch(key = null, metrics = metrics.result()))
            }
        }
    }

    /** The nightly summaries in [file], in file order. */
    fun nightly(file: InputStream): Outcome<List<HrvNight>> {
        val out = ArrayList<HrvNight>()
        val result = FitReader(file).read { rec ->
            if (rec.global != FitMsg.HRV_STATUS_SUMMARY) return@read
            val t = rec.tSec ?: return@read
            // Ref: Gadgetbridge 160beab41 FitCodeGenerator/src/main/resources/fit_profile.json (hrv_status_summary: 0 weekly_average, 1 last_night_average, scale 128 ms; 6 status)
            out.add(HrvNight(t, ms(rec.long(1)), ms(rec.long(0)), rec.int(6)?.takeIf { it in 0..10 }))
        }
        return when (result) {
            is Outcome.Fail -> result
            is Outcome.Ok -> Outcome.Ok(out)
        }
    }

    private fun ms(raw: Long?): Int? = raw?.let { (it / 128.0).roundToInt() }?.takeIf { it in 1..1000 }
}
