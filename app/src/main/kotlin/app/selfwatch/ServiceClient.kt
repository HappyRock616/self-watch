/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch

import android.content.Context
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Day
import app.selfwatch.service.SyncCommands

/**
 * Sends commands to the always-on service (`SyncService` in process `:watch`) as explicit intents,
 * through the service's [SyncCommands] (WP-10). Each call returns false when Android or the start
 * rules refused (for example no Bluetooth permission and no paired watch yet).
 */
class ServiceClient(private val ctx: Context) {

    /** Commands the screens can send. */
    enum class Command { SYNC_NOW, FIND_WATCH, TEST_NOTIFICATION }

    /** Whether the service can take commands. */
    val isAvailable: Boolean get() = true

    /** Sends [command]; returns true when it was handed to the service. */
    fun send(command: Command): Boolean = when (command) {
        Command.SYNC_NOW -> syncNow()
        Command.FIND_WATCH, Command.TEST_NOTIFICATION -> {
            SwLog.i(TAG, "${command.name} is not supported by the service yet")
            false
        }
    }

    /** Starts the service (or makes it re-read the paired watches and the connection mode). */
    fun start(): Boolean = SyncCommands.start(ctx, SyncCommands.ACTION_START)

    /** Asks the service to sync every paired watch now (starting it if needed). */
    fun syncNow(): Boolean = SyncCommands.start(ctx, SyncCommands.ACTION_SYNC_NOW)

    /** Disconnects every watch and stops the service. */
    fun stop() {
        SyncCommands.stop(ctx)
    }

    /** Asks the service to rebuild [day]'s summary (after diary, water or weight changes). */
    fun refreshDay(day: Day): Boolean = SyncCommands.refreshDay(ctx, day)

    private companion object {
        const val TAG = "ServiceClient"
    }
}
