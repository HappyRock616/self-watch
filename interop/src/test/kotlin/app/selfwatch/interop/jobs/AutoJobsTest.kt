/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.jobs

import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.pm.PackageManager
import app.selfwatch.db.SettingKeys
import app.selfwatch.interop.DbTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoJobsTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val scheduler get() = ctx.getSystemService(JobScheduler::class.java)

    @Before
    fun setUp() {
        DbTestSupport.fresh()
        scheduler.cancelAll()
    }

    @Test
    fun backupJobFollowsTheSettings() = runBlocking {
        val s = DbTestSupport.db.settings
        AutoJobs.sync(ctx)
        assertFalse(AutoJobs.backupScheduled(ctx))
        s.setBool(SettingKeys.BACKUP_AUTO, true)
        s.set(SettingKeys.BACKUP_FOLDER_URI, "content://com.android.externalstorage.documents/tree/primary%3ABackups")
        s.setInt(SettingKeys.BACKUP_EVERY_DAYS, 3)
        AutoJobs.sync(ctx)
        val job = scheduler.allPendingJobs.single { it.id == AutoJobs.JOB_BACKUP }
        assertTrue(job.isPeriodic)
        assertEquals(TimeUnit.DAYS.toMillis(3), job.intervalMillis)
        assertTrue(job.isPersisted)
        assertEquals(AutoJobService::class.java.name, job.service.className)
        s.setBool(SettingKeys.BACKUP_AUTO, false)
        AutoJobs.sync(ctx)
        assertFalse(AutoJobs.backupScheduled(ctx))
    }

    @Test
    fun workoutAddedSchedulesOneGpxRun() {
        assertTrue(AutoJobs.onWorkoutAdded(ctx, 41))
        assertTrue(AutoJobs.onWorkoutAdded(ctx, 42))
        val job = scheduler.allPendingJobs.single { it.id == AutoJobs.JOB_GPX }
        assertEquals(42L, job.extras.getLong(AutoJobs.EXTRA_WORKOUT_ID))
    }

    @Test
    fun jobsSkipQuietlyWhenNotSetUp() = runBlocking {
        assertTrue(AutoJobs.runBackup(ctx))
        assertTrue(AutoJobs.runGpx(ctx, 1))
        val s = DbTestSupport.db.settings
        s.setBool(SettingKeys.BACKUP_AUTO, true)
        s.set(SettingKeys.BACKUP_FOLDER_URI, "content://com.android.externalstorage.documents/tree/primary%3ABackups")
        // No persisted permission for that folder: skipped, not retried.
        assertTrue(AutoJobs.runBackup(ctx))
    }

    @Test
    fun serviceIsDeclaredWithTheJobPermission() {
        val info = ctx.packageManager.getServiceInfo(ComponentName(ctx, AutoJobService::class.java), PackageManager.ComponentInfoFlags.of(0))
        assertEquals("android.permission.BIND_JOB_SERVICE", info.permission)
        assertFalse(info.exported)
    }
}
