/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.model.log.SwLog
import app.selfwatch.service.device.ConnectionMode
import app.selfwatch.service.device.DeviceManager
import app.selfwatch.service.device.SyncScheduler
import app.selfwatch.service.schedule.JobSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * After a reboot or an app update, brings the watch back: `always` mode starts [SyncService]
 * (`connectedDevice` may start from the boot broadcast on every API level, including 35+);
 * `sync_only` mode makes sure the persisted job is scheduled. Nothing happens without a paired watch.
 */
public class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext ?: context
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                onBoot(app, SelfWatchDb.get(app), JobSyncScheduler(app))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.e(TAG, "boot start failed", e)
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }

    /** The boot logic. */
    public companion object {
        private const val TAG = "BootReceiver"

        /** What to do after boot. Returns what was done, for tests and logs. */
        public suspend fun onBoot(ctx: Context, db: SelfWatchDb, scheduler: SyncScheduler): BootOutcome {
            if (db.devices.active().isEmpty()) return BootOutcome.NO_DEVICES
            return when (ConnectionMode.fromKey(db.settings.get(SettingKeys.CONNECTION_MODE))) {
                ConnectionMode.SYNC_ONLY -> {
                    val hours = db.settings.getInt(SettingKeys.CONNECTION_SYNC_ONLY_HOURS) ?: DeviceManager.DEFAULT_SYNC_HOURS
                    scheduler.schedule(hours.coerceIn(1, DeviceManager.MAX_SYNC_HOURS))
                    BootOutcome.SCHEDULED
                }
                ConnectionMode.ALWAYS ->
                    if (SyncCommands.start(ctx, SyncCommands.ACTION_START)) BootOutcome.STARTED else BootOutcome.NOT_ALLOWED
            }
        }
    }
}

/** What [BootReceiver.onBoot] did. */
public enum class BootOutcome {
    /** No paired watch. */
    NO_DEVICES,

    /** `always` mode: the service was started. */
    STARTED,

    /** `always` mode, but the start rules or Android refused. */
    NOT_ALLOWED,

    /** `sync_only` mode: the job is scheduled. */
    SCHEDULED,
}
