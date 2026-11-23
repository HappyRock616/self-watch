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
import app.selfwatch.db.args
import app.selfwatch.db.longOrNull
import app.selfwatch.db.read
import app.selfwatch.model.time.TimeRange

/**
 * How much watch data the database holds, for the Devices screen's "Data so far" check after a sync.
 *
 * @property activityMinutes `activity_minute` rows in the day asked for.
 * @property steps steps in those minutes.
 * @property metrics `metric` rows (heart rate, stress, SpO2…) in the day asked for.
 * @property sleepStages `sleep_stage` rows in the night asked for.
 * @property workouts every `workout` row.
 * @property filesStored watch files stored (`sync_file` state done).
 * @property lastIngestSec when the newest file was stored, or null.
 */
public data class DataCounts(
    val activityMinutes: Long,
    val steps: Long,
    val metrics: Long,
    val sleepStages: Long,
    val workouts: Long,
    val filesStored: Long,
    val lastIngestSec: Long?,
)

/** Row counts across the health tables (read-only). */
public class StatsDao internal constructor(private val db: SelfWatchDb) {

    /** The counts for [day] (activity and metrics) and [night] (sleep stages). */
    public suspend fun counts(day: TimeRange, night: TimeRange): DataCounts = db.read { d ->
        val dayArgs = args(day.startSec, day.endSec)
        DataCounts(
            activityMinutes = d.longOrNull("SELECT COUNT(*) FROM activity_minute WHERE t_sec >= ? AND t_sec < ?", dayArgs) ?: 0,
            steps = d.longOrNull("SELECT COALESCE(SUM(steps), 0) FROM activity_minute WHERE t_sec >= ? AND t_sec < ?", dayArgs) ?: 0,
            metrics = d.longOrNull("SELECT COUNT(*) FROM metric WHERE t_sec >= ? AND t_sec < ?", dayArgs) ?: 0,
            sleepStages = d.longOrNull(
                "SELECT COUNT(*) FROM sleep_stage WHERE t_sec >= ? AND t_sec < ?",
                args(night.startSec, night.endSec),
            ) ?: 0,
            workouts = d.longOrNull("SELECT COUNT(*) FROM workout", null) ?: 0,
            filesStored = d.longOrNull("SELECT COUNT(*) FROM sync_file WHERE state = ${SyncFileState.DONE.code}", null) ?: 0,
            lastIngestSec = d.longOrNull("SELECT MAX(done_sec) FROM sync_file WHERE state = ${SyncFileState.DONE.code}", null),
        )
    }
}
