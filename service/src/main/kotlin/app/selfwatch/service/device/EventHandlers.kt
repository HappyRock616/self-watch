/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.device

import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchEvent
import kotlin.coroutines.cancellation.CancellationException

/** The watch an event came from, with a way to answer it. */
public interface WatchLink {
    /** The `device` row id. */
    public val deviceId: Long

    /** Sends [cmd] to this watch. */
    public suspend fun send(cmd: WatchCommand): Outcome<Unit>
}

/** Find-my-phone requests (later: `relay.FindPhoneAlert`). */
public fun interface FindPhoneHandler {
    /** The watch asks to start or stop ringing the phone. */
    public suspend fun onFindPhone(from: WatchLink, event: WatchEvent.FindPhone)

    /** The no-op handler. */
    public companion object {
        /** Ignores every event. */
        public val NONE: FindPhoneHandler = FindPhoneHandler { _, _ -> }
    }
}

/** Media buttons pressed on the watch (later: `relay.MusicRelay`). */
public fun interface MusicHandler {
    /** A media button was pressed. */
    public suspend fun onMusicControl(from: WatchLink, event: WatchEvent.MusicControl)

    /** The no-op handler. */
    public companion object {
        /** Ignores every event. */
        public val NONE: MusicHandler = MusicHandler { _, _ -> }
    }
}

/** Accept or reject from the watch (later: `relay.CallRelay`). */
public fun interface CallHandler {
    /** The user accepted or rejected the ringing call. */
    public suspend fun onCallAction(from: WatchLink, event: WatchEvent.CallAction)

    /** The no-op handler. */
    public companion object {
        /** Ignores every event. */
        public val NONE: CallHandler = CallHandler { _, _ -> }
    }
}

/** Notification actions and replies from the watch (later: `relay.NotificationRelay`). */
public fun interface NotificationActionHandler {
    /** The user picked an action or sent a reply. */
    public suspend fun onNotificationAction(from: WatchLink, event: WatchEvent.NotificationAction)

    /** The no-op handler. */
    public companion object {
        /** Ignores every event. */
        public val NONE: NotificationActionHandler = NotificationActionHandler { _, _ -> }
    }
}

/** Connect IQ app messages (later: `link.LinkRouter`). */
public fun interface AppMessageHandler {
    /** A watch app sent a message. */
    public suspend fun onAppMessage(from: WatchLink, event: WatchEvent.AppMessageReceived)

    /** The no-op handler. */
    public companion object {
        /** Ignores every event. */
        public val NONE: AppMessageHandler = AppMessageHandler { _, _ -> }
    }
}

/** Web requests from watch apps (later: `web.WebRequestRouter`); answer with [WatchCommand.WebResponse]. */
public fun interface WebRequestHandler {
    /** A watch app asks for a web request. */
    public suspend fun onWebRequest(from: WatchLink, event: WatchEvent.WebRequest)

    /** The default handler. */
    public companion object {
        /** Ignores every request (the watch app times out). */
        public val NONE: WebRequestHandler = WebRequestHandler { _, _ -> }
    }
}

/** Installed watch apps and their settings (later: `watchapps.WatchAppsStore`). */
public interface WatchAppsHandler {
    /** The watch reported its installed apps. */
    public suspend fun onInstalledApps(from: WatchLink, event: WatchEvent.InstalledApps) {}

    /** The watch reported one app's settings. */
    public suspend fun onAppSettings(from: WatchLink, event: WatchEvent.AppSettings) {}

    /** The no-op handler. */
    public companion object {
        /** Ignores every event. */
        public val NONE: WatchAppsHandler = object : WatchAppsHandler {}
    }
}

/** Where watch events go. Every handler defaults to a no-op until its relay exists. */
public data class EventHandlers(
    /** [WatchEvent.FindPhone]. */
    val findPhone: FindPhoneHandler = FindPhoneHandler.NONE,
    /** [WatchEvent.MusicControl]. */
    val music: MusicHandler = MusicHandler.NONE,
    /** [WatchEvent.CallAction]. */
    val call: CallHandler = CallHandler.NONE,
    /** [WatchEvent.NotificationAction]. */
    val notificationAction: NotificationActionHandler = NotificationActionHandler.NONE,
    /** [WatchEvent.AppMessageReceived]. */
    val appMessage: AppMessageHandler = AppMessageHandler.NONE,
    /** [WatchEvent.WebRequest]. */
    val webRequest: WebRequestHandler = WebRequestHandler.NONE,
    /** [WatchEvent.InstalledApps] and [WatchEvent.AppSettings]. */
    val watchApps: WatchAppsHandler = WatchAppsHandler.NONE,
)

/** Sends each [WatchEvent] to its handler. A failing handler is logged and never stops the connection. */
public class EventRouter(private val handlers: EventHandlers) {

    /**
     * Routes [event] from [from]. [WatchEvent.Battery] is not routed (the [DeviceManager] keeps it);
     * returns false for it.
     */
    public suspend fun route(from: WatchLink, event: WatchEvent): Boolean {
        try {
            when (event) {
                is WatchEvent.FindPhone -> handlers.findPhone.onFindPhone(from, event)
                is WatchEvent.MusicControl -> handlers.music.onMusicControl(from, event)
                is WatchEvent.CallAction -> handlers.call.onCallAction(from, event)
                is WatchEvent.NotificationAction -> handlers.notificationAction.onNotificationAction(from, event)
                is WatchEvent.AppMessageReceived -> handlers.appMessage.onAppMessage(from, event)
                is WatchEvent.WebRequest -> handlers.webRequest.onWebRequest(from, event)
                is WatchEvent.InstalledApps -> handlers.watchApps.onInstalledApps(from, event)
                is WatchEvent.AppSettings -> handlers.watchApps.onAppSettings(from, event)
                is WatchEvent.Battery, WatchEvent.SecurePairingRequired, is WatchEvent.GarminSignInRequested,
                is WatchEvent.LiveHeartRate, is WatchEvent.LiveSteps -> return false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SwLog.e(TAG, "watch event handler failed", e)
        }
        return true
    }

    private companion object {
        const val TAG = "EventRouter"
    }
}
