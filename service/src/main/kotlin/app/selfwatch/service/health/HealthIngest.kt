/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.health

import android.database.SQLException
import app.selfwatch.db.DbBusyException
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.TimeRange
import app.selfwatch.model.watch.HealthSink
import app.selfwatch.model.watch.IngestBatch
import app.selfwatch.model.watch.SyncFileKey
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/**
 * Stores what the watch driver hands over.
 *
 * [ingest] writes the whole batch and marks its sync file done in one database transaction
 * (`HealthWriteDao.ingest`), so a failure leaves nothing behind and the file is retried. The DAO
 * announces the changed tables (`DbChanges.notify`) after the commit; then the minutes are marked
 * moderate or vigorous from their heart-rate zone ([IntensityClassifier], zones from [HrZoneStore]) and
 * the day summaries of the affected days (in the phone's time zone) are rebuilt through [summaries].
 */
public class HealthIngest(
    private val db: SelfWatchDb,
    private val clock: Clock,
    private val summaries: SummaryBuilder?,
    private val onWorkoutAdded: (Long) -> Unit = {},
) : HealthSink {

    override suspend fun ingest(batch: IngestBatch): Outcome<Unit> {
        try {
            db.healthWrite.ingest(batch, clock.nowSec()).workoutId?.let(onWorkoutAdded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DbBusyException) {
            return Outcome.Fail(e.error)
        } catch (e: SQLException) {
            SwLog.e(TAG, "ingest failed; nothing stored", e)
            return Outcome.Fail(SwError.Io("ingest failed"))
        } catch (e: IllegalStateException) {
            SwLog.e(TAG, "ingest failed; nothing stored", e)
            return Outcome.Fail(SwError.Io("ingest failed"))
        }
        val zoneDays = try {
            classify(batch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SwLog.e(TAG, "intensity from heart-rate zones failed", e)
            emptySet()
        }
        val builder = summaries ?: return Outcome.Ok(Unit)
        val today = clock.today()
        val days = (affectedDays(batch, clock.zone()) + zoneDays).filterTo(HashSet()) { it <= today }
        try {
            builder.refresh(days)
            StepAutoGoal.update(db, clock)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The data is committed; the summaries catch up on the next refresh.
            SwLog.e(TAG, "summary refresh after ingest failed", e)
        }
        return Outcome.Ok(Unit)
    }

    /** Re-marks the minutes the batch's activity and heart rate touch; returns the days that changed. */
    private suspend fun classify(batch: IngestBatch): Set<Day> {
        val times = batch.activity.map { it.tSec } + batch.metrics.filter { it.kind == MetricKind.HEART_RATE }.map { it.tSec }
        if (times.isEmpty()) return emptySet()
        val range = TimeRange(times.min() - ZONE_MARGIN_SEC, times.max() + ZONE_MARGIN_SEC)
        return IntensityClassifier.reclassify(db, range, HrZoneStore(db, clock).current().bounds, clock.zone())
    }

    override suspend fun alreadyHave(key: SyncFileKey): Boolean = db.syncFiles.isDone(key)

    /** Helpers. */
    public companion object {
        private const val TAG = "HealthIngest"
        private const val HALF_DAY_SEC = 12 * 3600L
        private const val ZONE_MARGIN_SEC = 120L

        /**
         * The local days in [zone] whose summary [batch] can change: the day of every sample, both
         * ends of sleep sessions and workouts, and for heart rate and HRV also the day 12 hours later
         * (night samples feed the next morning's resting HR and HRV).
         */
        public fun affectedDays(batch: IngestBatch, zone: ZoneId): Set<Day> {
            val days = HashSet<Day>()
            for (m in batch.activity) days += Day.of(m.tSec, zone)
            for (m in batch.metrics) {
                days += Day.of(m.tSec, zone)
                days += Day.of(m.tSec + HALF_DAY_SEC, zone)
            }
            for (s in batch.sleepStages) days += Day.of(s.tSec, zone)
            for (s in batch.sleepSessions) {
                days += Day.of(s.startSec, zone)
                days += Day.of(s.endSec, zone)
            }
            batch.workout?.let {
                days += Day.of(it.startSec, zone)
                days += Day.of(it.endSec, zone)
            }
            for (b in batch.body) days += Day.of(b.tSec, zone)
            return days
        }
    }
}
