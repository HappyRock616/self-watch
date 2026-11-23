/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db.dao

import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SyncFileState
import app.selfwatch.db.Table
import app.selfwatch.db.write
import app.selfwatch.model.watch.IngestBatch

/** Ids written by [HealthWriteDao.ingest]: the workout row id (if the batch had one). */
public data class IngestResult(val workoutId: Long?)

/**
 * Stores a whole [IngestBatch] in one transaction, for `service.HealthIngest`: activity, metrics,
 * sleep, workout and body rows, and (if the batch has a key) the `sync_file` row marked done. Either
 * all of it commits or none of it.
 */
public class HealthWriteDao internal constructor(private val db: SelfWatchDb) {

    /** Writes [batch] atomically; the sync file is marked [SyncFileState.DONE] at [nowSec]. */
    public suspend fun ingest(batch: IngestBatch, nowSec: Long): IngestResult {
        val tables = HashSet<Table>()
        if (batch.activity.isNotEmpty()) tables += Table.ACTIVITY
        if (batch.metrics.isNotEmpty()) tables += Table.METRIC
        if (batch.sleepStages.isNotEmpty()) tables += Table.SLEEP_STAGE
        if (batch.sleepSessions.isNotEmpty()) tables += Table.SLEEP_SESSION
        if (batch.workout != null) tables += Table.WORKOUT
        if (batch.body.isNotEmpty()) tables += Table.BODY
        if (batch.key != null) tables += Table.SYNC_FILE
        return db.write(tables) { d ->
            ActivityDao.insertIn(d, batch.activity)
            MetricDao.insertIn(d, batch.metrics)
            SleepDao.insertStagesIn(d, batch.sleepStages)
            for (s in batch.sleepSessions) SleepDao.insertSessionIn(d, s)
            val workoutId = batch.workout?.let { WorkoutDao.insertIn(d, it, null) }
            for (m in batch.body) BodyDao.insertIn(d, m)
            batch.key?.let { SyncFileDao.markIn(d, it, SyncFileState.DONE, nowSec) }
            IngestResult(workoutId)
        }
    }
}
