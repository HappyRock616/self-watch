/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.model.device

import app.selfwatch.model.result.SwError

/**
 * What sort of device this is. [WEIGHT_SCALE], [HEART_RATE_SENSOR] and [BLOOD_PRESSURE_MONITOR] are
 * devices that follow the open Bluetooth SIG health profiles (added in core-model v1.2).
 */
public enum class DeviceKind {
    /** A Garmin watch (the `garmin` protocol). */
    GARMIN_WATCH,

    /** A scale with the Bluetooth Weight Scale and/or Body Composition service. Added in v1.2. */
    WEIGHT_SCALE,

    /** A chest strap or arm band with the Bluetooth Heart Rate service. Added in v1.2. */
    HEART_RATE_SENSOR,

    /** A cuff with the Bluetooth Blood Pressure service. Added in v1.2. */
    BLOOD_PRESSURE_MONITOR,
}

/** Things the UI can offer for a device. Each driver reports the set its device supports. */
public enum class Feature {
    NOTIFICATIONS, NOTIFICATION_REPLY, CALLS, MUSIC, WEATHER, FIND_PHONE, FIND_WATCH, ALARMS, TIME_SYNC,
    WATCH_APP_INSTALL, WATCH_APP_MANAGE, WATCH_APP_SETTINGS, WATCH_APP_MESSAGES, WATCH_APP_WEB, WATCH_APP_LAUNCH,
    GPS_ASSIST, HEALTH_ACTIVITY, HEALTH_SLEEP, HEALTH_HR, HEALTH_HRV, HEALTH_SPO2, HEALTH_STRESS,
    HEALTH_RESPIRATION, HEALTH_SKIN_TEMP, WORKOUTS, REALTIME_HR, BATTERY,

    /** Weight from a scale. Added in core-model v1.2. */
    HEALTH_WEIGHT,

    /** Body fat, muscle and water from a body composition scale. Added in core-model v1.2. */
    HEALTH_BODY_COMPOSITION,

    /** Blood pressure readings. Added in core-model v1.2. */
    HEALTH_BLOOD_PRESSURE,
}

/** How well a device model is known to work: tested, expected from its family, or unknown. */
public enum class SupportTier { VERIFIED, EXPECTED, UNVERIFIED }

/**
 * A paired device as the app knows it.
 *
 * @property id `selfwatch.db` `device.id`; 0 before the first save.
 * @property address Bluetooth MAC address.
 * @property name advertised name, for example "vívoactive 5".
 * @property model display model name from `models.json`.
 * @property productId Garmin product number, if known.
 * @property firmware firmware version such as "12.34", if known.
 * @property serial unit id, if known.
 * @property batteryPct last reported battery level, if known.
 */
public data class DeviceInfo(
    val id: Long,
    val kind: DeviceKind,
    val address: String,
    val name: String,
    val model: String,
    val productId: Int?,
    val firmware: String?,
    val serial: Long?,
    val tier: SupportTier,
    val features: Set<Feature>,
    val batteryPct: Int?,
)

/** Where a device connection is in its life cycle. */
public sealed interface ConnectionState {
    /** Not connected and not trying. */
    public data object Disconnected : ConnectionState

    /** Opening the Bluetooth link. */
    public data object Connecting : ConnectionState

    /** Link open; the device protocol is starting up. */
    public data object Handshaking : ConnectionState

    /** Connected and idle. */
    public data object Ready : ConnectionState

    /** Syncing: [done] of [total] steps, with a short [label] for the UI. */
    public data class Syncing(val done: Int, val total: Int, val label: String) : ConnectionState

    /** The connection failed with [error]; a retry follows in [retryInSec] seconds, or never if null. */
    public data class Failed(val error: SwError, val retryInSec: Int?) : ConnectionState
}

/** Why a sync started. */
public enum class SyncReason { CONNECT, USER, SCHEDULE, WATCH_REQUEST }
