/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.session

import app.selfwatch.garmin.files.FitFileKind
import app.selfwatch.garmin.gfdi.GfdiReplies
import app.selfwatch.model.device.Feature
import java.util.EnumSet

/**
 * Which app features a watch gets: `fromCapabilities ∩ implemented − overrides` (WP-06 §7,
 * COMPATIBILITY §5 feature-to-capability map).
 */
public object Features {
    /** Features this module implements today. Later tasks add theirs. */
    public val IMPLEMENTED: Set<Feature> = EnumSet.of(
        Feature.TIME_SYNC, Feature.BATTERY, Feature.WATCH_APP_MESSAGES,
        Feature.HEALTH_ACTIVITY, Feature.HEALTH_SLEEP, Feature.HEALTH_HR, Feature.HEALTH_HRV, Feature.HEALTH_SPO2,
        Feature.HEALTH_STRESS, Feature.HEALTH_RESPIRATION, Feature.HEALTH_SKIN_TEMP, Feature.WORKOUTS,
        Feature.NOTIFICATIONS, Feature.CALLS, Feature.MUSIC, Feature.WEATHER, Feature.FIND_PHONE, Feature.FIND_WATCH,
        Feature.ALARMS, Feature.WATCH_APP_INSTALL, Feature.WATCH_APP_MANAGE, Feature.WATCH_APP_SETTINGS, Feature.WATCH_APP_WEB,
        Feature.WATCH_APP_LAUNCH,
    )

    /** Features the watch's capability flags allow (before intersecting with [IMPLEMENTED]). */
    public fun fromCapabilities(caps: Set<GarminCapability>): Set<Feature> {
        val f = EnumSet.noneOf(Feature::class.java)
        fun on(cap: GarminCapability, vararg feats: Feature) {
            if (cap in caps) f.addAll(feats)
        }
        on(GarminCapability.GNCS, Feature.NOTIFICATIONS, Feature.CALLS)
        if (GarminCapability.CUSTOM_CANNED_TEXT_LIST_SUPPORT in caps || GarminCapability.SMS_NOTIFICATIONS in caps) {
            f.add(Feature.NOTIFICATION_REPLY)
        }
        on(GarminCapability.BASIC_MUSIC_CONTROLS, Feature.MUSIC)
        on(GarminCapability.ADVANCED_MUSIC_CONTROLS, Feature.MUSIC)
        on(GarminCapability.FIND_MY_PHONE, Feature.FIND_PHONE)
        on(GarminCapability.FIND_MY_WATCH, Feature.FIND_WATCH)
        on(GarminCapability.WEATHER_CONDITIONS, Feature.WEATHER)
        on(GarminCapability.CONNECTIQ_WATCH_APP_DOWNLOAD, Feature.WATCH_APP_INSTALL, Feature.WATCH_APP_MESSAGES)
        on(GarminCapability.CONNECTIQ_WATCH_FACE_DOWNLOAD, Feature.WATCH_APP_INSTALL)
        on(GarminCapability.CONNECTIQ_WIDGET_DOWNLOAD, Feature.WATCH_APP_INSTALL)
        on(GarminCapability.CONNECTIQ_DATA_FIELD_DOWNLOAD, Feature.WATCH_APP_INSTALL)
        on(GarminCapability.CONNECTIQ_APP_MANAGEMENT, Feature.WATCH_APP_MANAGE)
        on(GarminCapability.CONNECTIQ_SETTINGS, Feature.WATCH_APP_SETTINGS)
        on(GarminCapability.CONNECTIQ_HTTP, Feature.WATCH_APP_WEB)
        on(GarminCapability.LAUNCH_REMOTE_CIQ_APP_SUPPORT, Feature.WATCH_APP_LAUNCH)
        on(GarminCapability.HTTP_RAW_RESOURCE_REQUEST, Feature.WATCH_APP_WEB)
        on(GarminCapability.GPS_EPHEMERIS_DOWNLOAD, Feature.GPS_ASSIST)
        // Ref: Gadgetbridge 160beab41 devices/garmin/watches/GarminWatchCoordinator.java (getAlarmSlotCount: 0 with REALTIME_SETTINGS)
        if (GarminCapability.REALTIME_SETTINGS !in caps) f.add(Feature.ALARMS)
        on(GarminCapability.CURRENT_TIME_REQUEST_SUPPORT, Feature.TIME_SYNC)
        on(GarminCapability.WORKOUT_DOWNLOAD, Feature.WORKOUTS)
        f.add(Feature.BATTERY)
        return f
    }

    /**
     * Health features from the watch's supported file types (FIT subtypes). When the list is empty
     * (the watch did not answer), monitoring, sleep and activities are assumed.
     */
    public fun fromFileTypes(types: List<GfdiReplies.FileTypeInfo>): Set<Feature> {
        val subs = types.filter { it.dataType == FitFileKind.FIT_DATA_TYPE }.map { it.subType }.toSet()
            .ifEmpty { setOf(FitFileKind.ACTIVITY.subType, FitFileKind.MONITOR.subType, FitFileKind.SLEEP.subType) }
        val f = EnumSet.noneOf(Feature::class.java)
        if (FitFileKind.ACTIVITY.subType in subs) f.addAll(listOf(Feature.WORKOUTS, Feature.HEALTH_ACTIVITY))
        if (subs.any { it == FitFileKind.MONITOR.subType || it == FitFileKind.MONITOR_A.subType || it == FitFileKind.MONITOR_DAILY.subType }) {
            f.addAll(listOf(Feature.HEALTH_ACTIVITY, Feature.HEALTH_HR, Feature.HEALTH_STRESS, Feature.HEALTH_SPO2, Feature.HEALTH_RESPIRATION))
        }
        if (FitFileKind.SLEEP.subType in subs) f.add(Feature.HEALTH_SLEEP)
        if (FitFileKind.HRV_STATUS.subType in subs) f.add(Feature.HEALTH_HRV)
        if (FitFileKind.SKIN_TEMP.subType in subs) f.add(Feature.HEALTH_SKIN_TEMP)
        return f
    }

    /**
     * Final feature set. [overrides] are `models.json` overrides: a key equal to a [Feature] name
     * (case and underscores ignored, e.g. "watchAppWeb") with value false removes that feature.
     * [appChannel] is false when the watch-app message service is missing.
     */
    public fun resolve(
        caps: Set<GarminCapability>,
        fileTypes: List<GfdiReplies.FileTypeInfo>,
        overrides: Map<String, Boolean>,
        appChannel: Boolean,
    ): Set<Feature> {
        val f = EnumSet.noneOf(Feature::class.java)
        f.addAll(fromCapabilities(caps))
        f.addAll(fromFileTypes(fileTypes))
        f.retainAll(IMPLEMENTED)
        if (!appChannel) f.remove(Feature.WATCH_APP_MESSAGES)
        fun norm(s: String) = s.replace("_", "").lowercase()
        for ((k, v) in overrides) {
            if (v) continue
            val feature = OVERRIDE_ALIASES[k] ?: Feature.entries.firstOrNull { norm(it.name) == norm(k) }
            feature?.let { f.remove(it) }
        }
        return f
    }

    /** `models.json` override keys taken from the reference's `supportsX()` names that map to a [Feature]. */
    private val OVERRIDE_ALIASES: Map<String, Feature> = mapOf(
        "spo2" to Feature.HEALTH_SPO2,
        "respiratoryRate" to Feature.HEALTH_RESPIRATION,
        "stressMeasurement" to Feature.HEALTH_STRESS,
        "hrvMeasurement" to Feature.HEALTH_HRV,
        "heartRateMeasurement" to Feature.HEALTH_HR,
        "activityTracking" to Feature.HEALTH_ACTIVITY,
        "weather" to Feature.WEATHER,
        "findDevice" to Feature.FIND_WATCH,
        "music" to Feature.MUSIC,
    )
}
