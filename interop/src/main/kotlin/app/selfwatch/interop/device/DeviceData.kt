/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.device

import app.selfwatch.interop.api.DeviceSeries
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.health.SleepStageSample

/** Collects watch data for one device of an import, then builds a sorted, de-duplicated [DeviceSeries]. */
internal class SeriesBuilder(private val sourceKey: String, private val name: String, private val model: String) {
    /** Minutes by time (a later value for the same minute replaces an earlier one). */
    val activity: HashMap<Long, ActivityMinute> = HashMap()

    /** Metric samples by kind and time. */
    val metrics: HashMap<Pair<Int, Long>, MetricSample> = HashMap()

    /** Sleep stages by time. */
    val stages: HashMap<Long, SleepStageSample> = HashMap()

    /** Sleep sessions by start. */
    val sessions: HashMap<Long, SleepSession> = HashMap()

    /** Adds a metric sample. */
    fun metric(s: MetricSample) {
        metrics[s.kind.code to s.tSec] = s
    }

    /** The series, everything sorted by time. */
    fun build(): DeviceSeries = DeviceSeries(
        sourceKey = sourceKey, name = name, model = model,
        activity = activity.values.sortedBy { it.tSec },
        metrics = metrics.values.sortedWith(compareBy({ it.tSec }, { it.kind.code })),
        sleepStages = stages.values.sortedBy { it.tSec },
        sleepSessions = sessions.values.sortedBy { it.startSec },
    )

    /** Constants and helpers. */
    companion object {
        /** Earliest plausible sample time (2000-01-01). */
        const val MIN_SEC: Long = 946_684_800L

        /** Latest plausible sample time (2100-01-01). */
        const val MAX_SEC: Long = 4_102_444_800L

        /** True for a plausible UTC epoch second. */
        fun plausible(tSec: Long): Boolean = tSec in MIN_SEC until MAX_SEC

        /**
         * Sleep sessions made from stage samples (one sample per stage change or per minute): a gap of
         * more than [gapSec] starts a new night; a night ends one minute after its last sample. Nights
         * shorter than 15 minutes are dropped. [score] gives the score for a night, if any.
         */
        fun sessionsFromStages(
            stages: List<SleepStageSample>,
            gapSec: Long = 3 * 3600L,
            score: (Long, Long) -> Int? = { _, _ -> null },
        ): List<SleepSession> {
            val sorted = stages.filter { it.stage != SleepStage.UNMEASURABLE }.sortedBy { it.tSec }
            val out = ArrayList<SleepSession>()
            var i = 0
            while (i < sorted.size) {
                var j = i
                while (j + 1 < sorted.size && sorted[j + 1].tSec - sorted[j].tSec <= gapSec) j++
                val start = sorted[i].tSec
                val end = sorted[j].tSec + 60
                if (end - start >= 15 * 60) out.add(SleepSession(sorted[i].deviceId, start, end, score(start, end)))
                i = j + 1
            }
            return out
        }
    }
}
