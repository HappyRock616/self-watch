/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.files

import app.selfwatch.fit.decode.HrvDecoder
import app.selfwatch.fit.decode.MonitoringCarry
import app.selfwatch.fit.decode.MonitoringDecoder
import app.selfwatch.fit.decode.SleepDecoder
import app.selfwatch.fit.decode.WeightDecoder
import app.selfwatch.fit.decode.WorkoutDecoder
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.watch.IngestBatch
import java.io.ByteArrayInputStream
import java.time.ZoneId

/**
 * The real [FitDecoders], backed by the `fit` module.
 *
 * One instance per watch: monitoring files carry running step totals from one file to the next
 * ([MonitoringCarry]), so files must be decoded in time order through the same instance (the driver's
 * [SyncPlan] downloads oldest first). Not thread-safe; the driver decodes one file at a time.
 *
 * @param zone time zone for local-midnight counter resets when a file carries no offset of its own.
 */
public class FitModuleDecoders(private val zone: ZoneId? = null) : FitDecoders {
    private val carry = MonitoringCarry()

    override fun supports(kind: FitFileKind): Boolean = when (kind) {
        FitFileKind.ACTIVITY, FitFileKind.WEIGHT, FitFileKind.MONITOR_A, FitFileKind.MONITOR_DAILY,
        FitFileKind.MONITOR, FitFileKind.METRICS, FitFileKind.SLEEP, FitFileKind.HRV_STATUS,
        FitFileKind.SKIN_TEMP -> true
    }

    override fun decode(kind: FitFileKind, bytes: ByteArray, deviceId: Long, fitPath: String?): Outcome<IngestBatch> {
        val input = ByteArrayInputStream(bytes)
        return when (kind) {
            FitFileKind.ACTIVITY -> when (val w = WorkoutDecoder.summary(input, deviceId)) {
                is Outcome.Ok -> Outcome.Ok(IngestBatch(key = null, workout = w.value.copy(fitPath = fitPath)))
                is Outcome.Fail -> w
            }
            FitFileKind.WEIGHT -> when (val m = WeightDecoder.decode(input)) {
                is Outcome.Ok -> Outcome.Ok(
                    IngestBatch(key = null, body = m.value.map { it.copy(source = DataSource.WATCH, deviceId = deviceId) }),
                )
                is Outcome.Fail -> m
            }
            FitFileKind.MONITOR_A, FitFileKind.MONITOR_DAILY, FitFileKind.MONITOR, FitFileKind.METRICS,
            FitFileKind.SKIN_TEMP -> MonitoringDecoder.decode(input, deviceId, zone, carry)
            FitFileKind.SLEEP -> SleepDecoder.decode(input, deviceId)
            FitFileKind.HRV_STATUS -> HrvDecoder.decode(input, deviceId)
        }
    }
}
