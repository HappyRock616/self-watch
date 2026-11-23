/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.fit.decode

import app.selfwatch.fit.io.FitReader
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.health.SleepStageSample
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.watch.IngestBatch
import java.io.InputStream

/**
 * Decodes sleep files (`GARMIN/Sleep`, file type 49) into stage samples, one sleep session with its
 * score, and the metrics recorded overnight (SpO2, respiration, stress, HRV, skin temperature).
 *
 * **Rules**
 * - `sleep_level` values map 0 unmeasurable, 1 awake, 2 light, 3 deep, 4 REM to [SleepStage].
 * - Session start and end come from `sleep_summary` (411) or `daily_sleep` (384) when present
 *   (community-documented messages); otherwise from the first stage sample to the last stage sample
 *   plus one sample interval.
 * - Score: `sleep_assessment.overall_sleep_score`, else the summary score.
 */
object SleepDecoder {
    private const val TAG = "SleepDecoder"

    /** Decodes [file] for the watch [deviceId]. */
    fun decode(file: InputStream, deviceId: Long): Outcome<IngestBatch> {
        val metrics = MetricExtractor(deviceId)
        val stages = LinkedHashMap<Long, SleepStageSample>()
        var score: Int? = null
        var summaryScore: Int? = null
        var start: Long? = null
        var end: Long? = null
        val result = FitReader(file).read { rec ->
            if (metrics.accept(rec)) return@read
            when (rec.global) {
                FitMsg.SLEEP_LEVEL -> {
                    // Ref: Gadgetbridge 160beab41 FitCodeGenerator/src/main/resources/fit_profile.json (SleepLevel enum: 0 UNMEASURABLE, 1 AWAKE, 2 LIGHT, 3 DEEP, 4 REM)
                    val t = rec.tSec ?: return@read
                    val stage = when (rec.int(0)) {
                        0 -> SleepStage.UNMEASURABLE
                        1 -> SleepStage.AWAKE
                        2 -> SleepStage.LIGHT
                        3 -> SleepStage.DEEP
                        4 -> SleepStage.REM
                        else -> return@read
                    }
                    stages[t] = SleepStageSample(deviceId, t, stage)
                }
                FitMsg.SLEEP_ASSESSMENT -> rec.int(6)?.takeIf { it in 0..100 }?.let { score = it }
                FitMsg.SLEEP_SUMMARY -> {
                    rec.int(0)?.takeIf { it in 0..100 }?.let { summaryScore = it }
                    val s = rec.time(10)
                    val e = rec.time(8)
                    if (s != null && e != null && e > s) { start = s; end = e }
                }
                FitMsg.DAILY_SLEEP -> {
                    if (summaryScore == null) rec.int(2)?.takeIf { it in 0..100 }?.let { summaryScore = it }
                    val s = rec.time(9)
                    val e = rec.time(11)
                    if (start == null && s != null && e != null && e > s) { start = s; end = e }
                }
            }
        }
        return when (result) {
            is Outcome.Fail -> result
            is Outcome.Ok -> {
                if (!result.value.crcOk) SwLog.w(TAG, "sleep file CRC mismatch; using its data anyway")
                val samples = stages.values.sortedBy { it.tSec }
                val s0 = start
                val e0 = end
                val sessions = when {
                    s0 != null && e0 != null -> listOf(SleepSession(deviceId, s0, e0, score ?: summaryScore))
                    samples.isNotEmpty() -> {
                        val first = samples.first().tSec
                        val last = samples.last().tSec
                        listOf(SleepSession(deviceId, first, last + interval(samples), score ?: summaryScore))
                    }
                    else -> emptyList()
                }
                Outcome.Ok(IngestBatch(key = null, metrics = metrics.result(), sleepStages = samples, sleepSessions = sessions))
            }
        }
    }

    /** The smallest gap between stage samples, between 30 s and 5 min; 60 s if unknown. */
    private fun interval(samples: List<SleepStageSample>): Long {
        var best = Long.MAX_VALUE
        for (i in 1 until samples.size) {
            val d = samples[i].tSec - samples[i - 1].tSec
            if (d in 1 until best) best = d
        }
        return if (best == Long.MAX_VALUE) 60 else best.coerceIn(30, 300)
    }
}
