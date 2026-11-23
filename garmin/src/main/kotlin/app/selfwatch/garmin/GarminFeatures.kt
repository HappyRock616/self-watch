/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin

import app.selfwatch.garmin.features.AlarmsHandler
import app.selfwatch.garmin.features.AppSettingsHandler
import app.selfwatch.garmin.features.FindHandler
import app.selfwatch.garmin.features.GpsAssist
import app.selfwatch.garmin.features.MusicHandler
import app.selfwatch.garmin.features.NotificationsHandler
import app.selfwatch.garmin.features.WatchAppsHandler
import app.selfwatch.garmin.features.WeatherHandler
import app.selfwatch.garmin.features.WebRequestHandler
import app.selfwatch.garmin.pb.CorePbHandler
import app.selfwatch.garmin.pb.ProtobufService
import app.selfwatch.garmin.session.GfdiHandler
import app.selfwatch.model.time.Clock

/**
 * The standard feature handlers for [GarminDriver] (A15–A24). Keep the instance to reach
 * [protobuf], [gpsAssist], and the calls that are not watch commands: [apps] (refresh the app list) and
 * [appSettings] (read an app's settings); pass [handlers] to the driver.
 *
 * Every handler checks the watch's capability flags itself (COMPATIBILITY §5): without the flag its
 * messages get UNSUPPORTED and its commands fail with `SwError.Unsupported`.
 *
 * @param weatherRepushMs push the last forecast again on this interval while connected; null = only when
 *   the app sends one or the watch asks.
 * @param webResponseTimeoutMs how long a watch-app web request waits for the app's `WebResponse`.
 */
public class GarminFeatures(
    clock: Clock,
    weatherRepushMs: Long? = null,
    webResponseTimeoutMs: Long = 30_000,
) {
    /** Protobuf request/response service; later tasks register their [app.selfwatch.garmin.pb.PbHandler]s here. */
    public val protobuf: ProtobufService = ProtobufService(listOf(CorePbHandler()))

    /** Notifications and calls. */
    public val notifications: NotificationsHandler = NotificationsHandler(clock)

    /** Music control. */
    public val music: MusicHandler = MusicHandler()

    /** Weather. */
    public val weather: WeatherHandler = WeatherHandler(clock, weatherRepushMs)

    /** Find my phone / watch. */
    public val find: FindHandler = FindHandler(protobuf).also { protobuf.register(it.pb) }

    /** GPS assistance (skeleton). */
    public val gpsAssist: GpsAssist = GpsAssist()

    /** Alarms (settings file upload). */
    public val alarms: AlarmsHandler = AlarmsHandler(clock)

    /** Connect IQ apps: list, install, delete, launch. */
    public val apps: WatchAppsHandler = WatchAppsHandler(protobuf).also { protobuf.register(it.pb) }

    /** Connect IQ app settings. */
    public val appSettings: AppSettingsHandler = AppSettingsHandler(protobuf).also { protobuf.register(it.pb) }

    /** Watch-app web requests (and the data transfer service for large answers). */
    public val web: WebRequestHandler = WebRequestHandler(protobuf, webResponseTimeoutMs).also { protobuf.register(it.pb) }

    /** All handlers, in the order the driver should ask them. */
    public val handlers: List<GfdiHandler> = listOf(protobuf, notifications, music, weather, find, gpsAssist, alarms, apps, appSettings, web)
}
