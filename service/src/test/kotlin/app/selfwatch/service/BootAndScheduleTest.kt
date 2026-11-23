/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service

import android.Manifest
import android.app.job.JobScheduler
import android.content.Intent
import app.selfwatch.db.SettingKeys
import app.selfwatch.service.device.ConnectionMode
import app.selfwatch.service.device.SyncScheduler
import app.selfwatch.service.schedule.JobSyncScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf

class BootAndScheduleTest : ServiceDbTest() {

    private class Recording : SyncScheduler {
        val scheduled = ArrayList<Int>()

        override fun schedule(hours: Int) {
            scheduled += hours
        }

        override fun cancel() {}
    }

    @Test
    fun bootWithoutAWatchDoesNothing() = runBlocking {
        val s = Recording()
        assertEquals(BootOutcome.NO_DEVICES, BootReceiver.onBoot(app, db, s))
        assertTrue(s.scheduled.isEmpty())
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun bootInAlwaysModeStartsTheService() = runBlocking {
        addDevice()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertEquals(BootOutcome.STARTED, BootReceiver.onBoot(app, db, Recording()))
        val started = shadowOf(app).nextStartedService
        assertEquals(SyncCommands.ACTION_START, started.action)
        assertEquals(SyncService::class.java.name, started.component?.className)
    }

    @Test
    fun bootWithoutPermissionDoesNotStart() = runBlocking {
        addDevice()
        assertEquals(BootOutcome.NOT_ALLOWED, BootReceiver.onBoot(app, db, Recording()))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun bootInSyncOnlyModeSchedulesTheJob() = runBlocking {
        addDevice()
        db.settings.set(SettingKeys.CONNECTION_MODE, ConnectionMode.SYNC_ONLY.key)
        db.settings.setInt(SettingKeys.CONNECTION_SYNC_ONLY_HOURS, 99)
        val s = Recording()
        assertEquals(BootOutcome.SCHEDULED, BootReceiver.onBoot(app, db, s))
        assertEquals(listOf(24), s.scheduled) // clamped
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun receiverIgnoresOtherBroadcasts() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_SCREEN_ON))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun jobSchedulerJobIsPeriodicPersistedAndCancellable() {
        val js = app.getSystemService(JobScheduler::class.java)
        val scheduler = JobSyncScheduler(app)
        scheduler.schedule(4)
        val job = js.getPendingJob(JobSyncScheduler.JOB_ID)!!
        assertTrue(job.isPeriodic)
        assertTrue(job.isPersisted)
        assertEquals(4 * 3_600_000L, job.intervalMillis)
        assertEquals("app.selfwatch.service.schedule.SyncJobService", job.service.className)

        scheduler.schedule(6)
        assertEquals(6 * 3_600_000L, js.getPendingJob(JobSyncScheduler.JOB_ID)!!.intervalMillis)

        scheduler.cancel()
        assertNull(js.getPendingJob(JobSyncScheduler.JOB_ID))
    }
}
