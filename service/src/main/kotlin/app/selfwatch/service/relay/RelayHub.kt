/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.relay

import app.selfwatch.db.SettingKeys
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.watch.MusicAction
import app.selfwatch.model.watch.WatchCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.cancellation.CancellationException

/** Sends a command to every connected watch. [app.selfwatch.service.SyncService] implements it on its DeviceManager. */
public fun interface WatchBroadcaster {
    /** Sends [cmd] to every connected watch; failures are logged by the implementation. */
    public suspend fun sendToAll(cmd: WatchCommand)
}

/** What the bound notification listener offers the rest of the `:watch` process. */
public interface ListenerAccess {
    /**
     * Performs [action] on the relayed notification [id]: [NotificationActions.REPLY] (with
     * [replyText]), [NotificationActions.DISMISS], [NotificationActions.MUTE], or an action label.
     * Returns false when the notification or action is gone.
     */
    public suspend fun performAction(id: Int, action: String, replyText: String?): Boolean

    /** Presses [action] on the active media session; false when nothing is playing. */
    public fun musicControl(action: MusicAction): Boolean
}

/**
 * The in-process bridge between the relays and the watches, inside process `:watch`.
 *
 * Android binds [NotificationRelay] by itself (it is a system-bound listener), while the watches
 * live in [app.selfwatch.service.SyncService]'s DeviceManager. Neither can own the other, so both
 * register here:
 * - **SyncService** calls [attach] with a [WatchBroadcaster] in `onCreate` and [detach] in
 *   `onDestroy`. Relays send with [sendToAll]; while no service is attached, commands are dropped
 *   (nothing is connected anyway).
 * - **NotificationRelay** calls [attachListener] in `onListenerConnected` and [detachListener] in
 *   `onListenerDisconnected`/`onDestroy`. The watch-event handlers (notification actions, music
 *   buttons) reach it through [listener]; [listenerConnected] feeds the Xiaomi "fix it" check.
 *
 * Both run on the same process, so plain volatile references are enough. Nothing here holds an
 * Activity; services detach themselves before they die.
 */
public object RelayHub {
    @Volatile
    private var broadcaster: WatchBroadcaster? = null

    @Volatile
    private var access: ListenerAccess? = null

    private val connected = MutableStateFlow(false)

    /** True while the notification listener is bound. */
    public val listenerConnected: StateFlow<Boolean> = connected.asStateFlow()

    /** The bound listener, or null. */
    public val listener: ListenerAccess? get() = access

    /** True while a [WatchBroadcaster] is attached. */
    public val hasWatches: Boolean get() = broadcaster != null

    /** Registers the service's broadcaster (replacing any earlier one). */
    public fun attach(b: WatchBroadcaster) {
        broadcaster = b
    }

    /** Removes [b] if it is the attached broadcaster. */
    public fun detach(b: WatchBroadcaster) {
        if (broadcaster === b) broadcaster = null
    }

    /** Registers the bound listener. */
    public fun attachListener(l: ListenerAccess) {
        access = l
        connected.value = true
    }

    /** Removes [l] if it is the registered listener. */
    public fun detachListener(l: ListenerAccess) {
        if (access === l) {
            access = null
            connected.value = false
        }
    }

    /** Sends [cmd] to every connected watch. Returns false when no service is attached. */
    public suspend fun sendToAll(cmd: WatchCommand): Boolean {
        val b = broadcaster ?: return false
        try {
            b.sendToAll(cmd)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SwLog.w(TAG, "relay send failed", e)
        }
        return true
    }

    private const val TAG = "RelayHub"
}

/** Setting keys used only by the relays (generic `setting` rows; the shared ones are in `SettingKeys`). */
public object RelaySettings {
    /** True once the notification listener has been bound at least once (bool). Arms the "fix it" check. */
    public const val LISTENER_SEEN: String = SettingKeys.NOTIFY_LISTENER_SEEN

    /** The last weather from a weather app, in Self Watch's own compact JSON (see [WeatherStore]). */
    public const val WEATHER_LAST: String = SettingKeys.WEATHER_LAST
}
