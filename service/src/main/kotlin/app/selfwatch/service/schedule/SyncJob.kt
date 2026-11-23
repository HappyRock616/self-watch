/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.schedule

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import app.selfwatch.model.log.SwLog
import app.selfwatch.service.SyncCommands
import app.selfwatch.service.device.SyncScheduler

/**
 * The sync-only schedule as JobScheduler job [JOB_ID]: periodic, persisted across reboots, with no
 * network constraint (the sync is Bluetooth only). JobScheduler has no Bluetooth constraint, so the
 * job just starts the service, which connects if it can.
 */
public class JobSyncScheduler(ctx: Context) : SyncScheduler {
    private val app: Context = ctx.applicationContext ?: ctx

    override fun schedule(hours: Int) {
        val js = app.getSystemService(JobScheduler::class.java) ?: return
        val intervalMs = hours.coerceAtLeast(1) * HOUR_MS
        val pending = js.getPendingJob(JOB_ID)
        if (pending != null && pending.isPeriodic && pending.intervalMillis == intervalMs) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(app, SyncJobService::class.java))
            .setPeriodic(intervalMs, FLEX_MS)
            .setPersisted(true)
            .build()
        val result = js.schedule(job)
        if (result != JobScheduler.RESULT_SUCCESS) SwLog.w(TAG, "sync-only job not scheduled ($result)")
    }

    override fun cancel() {
        val js = app.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(JOB_ID) != null) js.cancel(JOB_ID)
    }

    /** Ids and timing. */
    public companion object {
        /** The JobScheduler id of the sync-only job (WP-07 `syncJobId`). */
        public const val JOB_ID: Int = 1001

        private const val HOUR_MS = 3_600_000L
        private const val FLEX_MS = 30 * 60_000L
        private const val TAG = "JobSyncScheduler"
    }
}

/** Runs when the sync-only schedule fires: starts [app.selfwatch.service.SyncService] for one cycle. */
public class SyncJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        if (!SyncCommands.start(this, SyncCommands.ACTION_SCHEDULED_SYNC)) SwLog.i(TAG, "scheduled sync not started")
        // The service does the work; the job itself is done.
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    private companion object {
        const val TAG = "SyncJobService"
    }
}
