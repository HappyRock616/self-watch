/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.relay

import app.selfwatch.model.log.SwLog
import app.selfwatch.model.watch.WatchEvent
import app.selfwatch.service.device.MusicHandler
import app.selfwatch.service.device.NotificationActionHandler
import app.selfwatch.service.device.WatchLink

/**
 * Notification actions and replies from the watch, performed by the bound [NotificationRelay]
 * (found through [RelayHub.listener]). Without notification access nothing happens.
 */
public class NotificationActionRouter(
    private val listener: () -> ListenerAccess? = { RelayHub.listener },
) : NotificationActionHandler {
    override suspend fun onNotificationAction(from: WatchLink, event: WatchEvent.NotificationAction) {
        val l = listener()
        if (l == null) {
            SwLog.i(TAG, "notification action but no listener access")
            return
        }
        if (!l.performAction(event.id, event.action, event.replyText)) SwLog.i(TAG, "notification action not performed")
    }

    private companion object {
        const val TAG = "NotificationActionRouter"
    }
}

/** Media buttons from the watch, pressed on the active media session through [RelayHub.listener]. */
public class MusicControlRouter(
    private val listener: () -> ListenerAccess? = { RelayHub.listener },
) : MusicHandler {
    override suspend fun onMusicControl(from: WatchLink, event: WatchEvent.MusicControl) {
        val l = listener()
        if (l == null || !l.musicControl(event.action)) SwLog.i(TAG, "music button ${event.action}: no media session")
    }

    private companion object {
        const val TAG = "MusicControlRouter"
    }
}
