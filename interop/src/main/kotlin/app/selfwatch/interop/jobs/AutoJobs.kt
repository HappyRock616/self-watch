/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.jobs

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.PersistableBundle
import android.provider.DocumentsContract
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.interop.api.ExportRequest
import app.selfwatch.interop.api.OutputSink
import app.selfwatch.interop.api.TrackFormat
import app.selfwatch.interop.backup.BackupWriter
import app.selfwatch.interop.db.DbExportSource
import app.selfwatch.interop.workout.WorkoutExporter
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Automatic backup and automatic GPX export, run by [AutoJobService] through `JobScheduler` in the app
 * process. Both write through the Storage Access Framework into a folder the user picked with
 * `ACTION_OPEN_DOCUMENT_TREE` (the app calls `takePersistableUriPermission` on it); no storage
 * permission is used.
 *
 * - **Backup** (settings `backup.auto`, `backup.folder_uri`, `backup.every_days`, `backup.encrypt`):
 *   a periodic job every N days. When `backup.encrypt` is on, [passphrase] must supply the passphrase;
 *   without one the job skips rather than write an unencrypted file.
 * - **GPX** (settings `export.auto_gpx`, `export.folder_uri`): the service broadcasts "workout added",
 *   the app calls [onWorkoutAdded], and a one-off job writes a GPX file for every workout newer than
 *   the last one exported (kept in setting [LAST_GPX_ID]).
 *
 * The app calls [sync] at start and whenever the backup settings change.
 */
object AutoJobs {
    /** Job id of the periodic backup. */
    const val JOB_BACKUP: Int = 0x5357_0001

    /** Job id of the GPX export run. */
    const val JOB_GPX: Int = 0x5357_0002

    /** Setting: id of the newest workout already exported as GPX. */
    const val LAST_GPX_ID: String = "export.auto_gpx_last_id"

    /** Job extra: the workout that triggered the GPX run. */
    const val EXTRA_WORKOUT_ID: String = "workout_id"

    private const val TAG = "AutoJobs"

    /** Gives the backup passphrase for encrypted automatic backups (the app wires its keystore-backed store); null = none. */
    @Volatile
    var passphrase: (suspend () -> CharArray?)? = null

    private fun scheduler(ctx: Context): JobScheduler? = ctx.getSystemService(JobScheduler::class.java)

    private fun component(ctx: Context) = ComponentName(ctx, AutoJobService::class.java)

    /** Schedules or cancels the backup job to match the settings. */
    suspend fun sync(ctx: Context) {
        val s = SelfWatchDb.get(ctx).settings
        val on = s.getBool(SettingKeys.BACKUP_AUTO) && !s.get(SettingKeys.BACKUP_FOLDER_URI).isNullOrEmpty()
        if (on) scheduleBackup(ctx, s.getInt(SettingKeys.BACKUP_EVERY_DAYS) ?: DEFAULT_DAYS) else cancelBackup(ctx)
    }

    /** Schedules the periodic backup every [everyDays] days (1 to 90), on any network or none, persisted across reboots. */
    fun scheduleBackup(ctx: Context, everyDays: Int): Boolean {
        val days = everyDays.coerceIn(1, 90).toLong()
        val info = JobInfo.Builder(JOB_BACKUP, component(ctx))
            .setPeriodic(TimeUnit.DAYS.toMillis(days), TimeUnit.HOURS.toMillis(6))
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .setPersisted(true)
            .build()
        return scheduler(ctx)?.schedule(info) == JobScheduler.RESULT_SUCCESS
    }

    /** Cancels the periodic backup. */
    fun cancelBackup(ctx: Context) {
        scheduler(ctx)?.cancel(JOB_BACKUP)
    }

    /** True when the periodic backup is scheduled. */
    fun backupScheduled(ctx: Context): Boolean = scheduler(ctx)?.allPendingJobs?.any { it.id == JOB_BACKUP } == true

    /** Hook for the service's "workout added" broadcast: schedules a GPX run (replacing a pending one). */
    fun onWorkoutAdded(ctx: Context, workoutId: Long): Boolean {
        val extras = PersistableBundle().apply { putLong(EXTRA_WORKOUT_ID, workoutId) }
        val info = JobInfo.Builder(JOB_GPX, component(ctx))
            .setOverrideDeadline(TimeUnit.MINUTES.toMillis(10))
            .setExtras(extras)
            .build()
        return scheduler(ctx)?.schedule(info) == JobScheduler.RESULT_SUCCESS
    }

    /**
     * Runs one automatic backup now (what the job does). Returns true when a backup was written or none
     * was due (disabled, no folder, no passphrase), false on a failure worth retrying.
     */
    suspend fun runBackup(ctx: Context): Boolean {
        val s = SelfWatchDb.get(ctx).settings
        if (!s.getBool(SettingKeys.BACKUP_AUTO)) return true
        val tree = s.get(SettingKeys.BACKUP_FOLDER_URI)?.takeIf { it.isNotEmpty() }?.let(Uri::parse) ?: return true
        if (!canWrite(ctx.contentResolver, tree)) {
            SwLog.w(TAG, "backup folder permission lost; skipping")
            return true
        }
        val pass = if (s.getBool(SettingKeys.BACKUP_ENCRYPT, true)) {
            passphrase?.invoke() ?: run {
                SwLog.w(TAG, "encrypted backup needs a passphrase; skipping")
                return true
            }
        } else {
            null
        }
        val writer = BackupWriter(ctx)
        val name = BackupWriter.fileName(System.currentTimeMillis() / 1000, java.time.ZoneId.systemDefault(), pass != null)
        val mime = if (pass != null) "application/octet-stream" else "application/zip"
        return writeDocument(ctx.contentResolver, tree, mime, name) { sink ->
            writer.write(sink, pass).also { pass?.fill(' ') } is Outcome.Ok
        }
    }

    /**
     * Writes GPX files for workouts newer than the last exported one (or just [triggerId] the first
     * time). Returns false on a failure worth retrying.
     */
    suspend fun runGpx(ctx: Context, triggerId: Long): Boolean {
        val db = SelfWatchDb.get(ctx)
        val s = db.settings
        if (!s.getBool(SettingKeys.EXPORT_AUTO_GPX)) return true
        val tree = s.get(SettingKeys.EXPORT_FOLDER_URI)?.takeIf { it.isNotEmpty() }?.let(Uri::parse) ?: return true
        if (!canWrite(ctx.contentResolver, tree)) {
            SwLog.w(TAG, "export folder permission lost; skipping")
            return true
        }
        val last = s.getLong(LAST_GPX_ID) ?: (triggerId - 1)
        val pending = db.workouts.list(PAGE).filter { it.id > last }.sortedBy { it.id }
        val exporter = WorkoutExporter(TrackFormat.GPX, DbExportSource(ctx))
        for (w in pending) {
            val req = ExportRequest(workoutIds = listOf(w.id))
            val ok = writeDocument(ctx.contentResolver, tree, exporter.mimeType(req), exporter.suggestedName(req)) { sink ->
                exporter.export(req, sink) is Outcome.Ok
            }
            if (!ok) return false
            s.setLong(LAST_GPX_ID, w.id)
        }
        return true
    }

    private fun canWrite(resolver: ContentResolver, tree: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

    /** Creates [name] in the folder [tree] and lets [write] fill it; deletes the document when [write] fails. */
    private suspend fun writeDocument(resolver: ContentResolver, tree: Uri, mime: String, name: String, write: suspend (OutputSink) -> Boolean): Boolean {
        val doc = try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            DocumentsContract.createDocument(resolver, parent, mime, name)
        } catch (e: FileNotFoundException) {
            SwLog.w(TAG, "cannot create document", e)
            null
        } catch (e: IllegalArgumentException) {
            SwLog.w(TAG, "bad folder uri", e)
            null
        } catch (e: SecurityException) {
            SwLog.w(TAG, "no access to folder", e)
            null
        } ?: return false
        val ok = try {
            write(OutputSink.ofUri(resolver, doc, name))
        } catch (e: CancellationException) {
            deleteQuietly(resolver, doc)
            throw e
        }
        if (!ok) deleteQuietly(resolver, doc)
        return ok
    }

    private fun deleteQuietly(resolver: ContentResolver, doc: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, doc)
        } catch (e: FileNotFoundException) {
            SwLog.w(TAG, "cannot delete failed document", e)
        } catch (e: SecurityException) {
            SwLog.w(TAG, "cannot delete failed document", e)
        }
    }

    private const val DEFAULT_DAYS = 7
    private const val PAGE = 50
}

/**
 * Runs [AutoJobs] work for `JobScheduler` in the app process. Each job runs in a coroutine owned by
 * the service; stopping the job cancels it and asks for a reschedule.
 */
class AutoJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val job = scope.launch {
            val ok = try {
                when (params.jobId) {
                    AutoJobs.JOB_BACKUP -> AutoJobs.runBackup(applicationContext)
                    AutoJobs.JOB_GPX -> AutoJobs.runGpx(applicationContext, params.extras.getLong(AutoJobs.EXTRA_WORKOUT_ID, 0))
                    else -> true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.e(TAG, "job ${params.jobId} failed", e)
                false
            }
            running.remove(params.jobId)
            jobFinished(params, !ok)
        }
        running[params.jobId] = job
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "AutoJobService"
    }
}
