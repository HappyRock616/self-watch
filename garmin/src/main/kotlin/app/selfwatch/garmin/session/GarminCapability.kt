/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.session

import java.util.EnumSet

/**
 * Capability flags exchanged in CONFIGURATION messages. The order is the bit position: entry n is
 * bit `n % 8` of byte `n / 8`.
 *
 * Ref: Gadgetbridge 160beab41 devices/garmin/GarminCapability.java (names and bit order)
 */
public enum class GarminCapability {
    CONNECT_MOBILE_FIT_LINK, GOLF_FIT_LINK, VIVOKID_JR_FIT_LINK, SYNC, DEVICE_INITIATES_SYNC,
    HOST_INITIATED_SYNC_REQUESTS, GNCS, ADVANCED_MUSIC_CONTROLS, FIND_MY_PHONE, FIND_MY_WATCH,
    CONNECTIQ_HTTP, CONNECTIQ_SETTINGS, CONNECTIQ_WATCH_APP_DOWNLOAD, CONNECTIQ_WIDGET_DOWNLOAD,
    CONNECTIQ_WATCH_FACE_DOWNLOAD, CONNECTIQ_DATA_FIELD_DOWNLOAD, CONNECTIQ_APP_MANAGEMENT, COURSE_DOWNLOAD,
    WORKOUT_DOWNLOAD, GOLF_COURSE_DOWNLOAD, DELTA_SOFTWARE_UPDATE_FILES, FITPAY, LIVETRACK, LIVETRACK_AUTO_START,
    LIVETRACK_MESSAGING, GROUP_LIVETRACK, WEATHER_CONDITIONS, WEATHER_ALERTS, GPS_EPHEMERIS_DOWNLOAD,
    EXPLICIT_ARCHIVE, SWING_SENSOR, SWING_SENSOR_REMOTE, INCIDENT_DETECTION, TRUEUP, INSTANT_INPUT, SEGMENTS,
    AUDIO_PROMPT_LAP, AUDIO_PROMPT_PACE_SPEED, AUDIO_PROMPT_HEART_RATE, AUDIO_PROMPT_POWER, AUDIO_PROMPT_NAVIGATION,
    AUDIO_PROMPT_CADENCE, SPORT_GENERIC, SPORT_RUNNING, SPORT_CYCLING, SPORT_TRANSITION, SPORT_FITNESS_EQUIPMENT,
    SPORT_SWIMMING, STOP_SYNC_AFTER_SOFTWARE_UPDATE, CALENDAR, WIFI_SETUP, SMS_NOTIFICATIONS, BASIC_MUSIC_CONTROLS,
    AUDIO_PROMPTS_SPEECH, DELTA_SOFTWARE_UPDATES, GARMIN_DEVICE_INFO_FILE_TYPE, SPORT_PROFILE_SETUP, HSA_SUPPORT,
    SPORT_STRENGTH, SPORT_CARDIO, UNION_PAY, IPASS, CIQ_AUDIO_CONTENT_PROVIDER, UNION_PAY_INTERNATIONAL,
    REQUEST_PAIR_FLOW, LOCATION_UPDATE, LTE_SUPPORT, DEVICE_DRIVEN_LIVETRACK_SUPPORT, CUSTOM_CANNED_TEXT_LIST_SUPPORT,
    EXPLORE_SYNC, INCIDENT_DETECT_AND_ASSISTANCE, CURRENT_TIME_REQUEST_SUPPORT, CONTACTS_SUPPORT,
    LAUNCH_REMOTE_CIQ_APP_SUPPORT, DEVICE_MESSAGES, WAYPOINT_TRANSFER, MULTI_LINK_SERVICE, OAUTH_CREDENTIALS,
    GOLF_9_PLUS_9, ANTI_THEFT_ALARM, INREACH, EVENT_SHARING, HSA_FEATURE_MESSAGE, HANDS_CALIBRATION,
    LOCATION_UPDATE_PROVIDER, LIVE_TRACK_COURSE_SHARING, SIMPLE_SETUP_FEATURE_LIST, WIND_DATA_APP_SUPPORT,
    HTTP_RAW_RESOURCE_REQUEST, SAMD, SYNC_2, NAVIGATION_SYNC, REALTIME_SETTINGS, HAND_CALIBRATION_IMG_DETECT,
    TOYSTORE_MOBILE, FEATURE_CAPABILITIES_EXCHANGE, DEEP_LINKING, GARMIN_CONNECT_SERVER, CREDENTIALS_OAUTH2_SUPPORT,
    GENERIC_CREDENTIALS,
    CAPABILITY_100, CAPABILITY_101, CAPABILITY_102, CAPABILITY_103, CAPABILITY_104, CAPABILITY_105, CAPABILITY_106,
    CAPABILITY_107, CAPABILITY_108, CAPABILITY_109, CAPABILITY_110, CAPABILITY_111, CAPABILITY_112, CAPABILITY_113,
    CAPABILITY_114, CAPABILITY_115, CAPABILITY_116, CAPABILITY_117, CAPABILITY_118, CAPABILITY_119, CAPABILITY_120,
    CAPABILITY_121, CAPABILITY_122, CAPABILITY_123, CAPABILITY_124, CAPABILITY_125, CAPABILITY_126, CAPABILITY_127,
    CAPABILITY_128,
    ;

    /** Bit-set conversions and the sets Self Watch advertises. */
    public companion object {
        /** Decodes a CONFIGURATION bit set; bits beyond the known flags are ignored. */
        public fun fromBits(bytes: ByteArray): Set<GarminCapability> {
            val all = entries
            val out = EnumSet.noneOf(GarminCapability::class.java)
            for (i in 0 until bytes.size * 8) {
                if ((bytes[i / 8].toInt() shr (i % 8)) and 1 != 0 && i < all.size) out.add(all[i])
            }
            return out
        }

        /** Encodes [caps] as a bit set covering every known flag. */
        public fun toBits(caps: Set<GarminCapability>): ByteArray {
            val out = ByteArray((entries.size + 7) / 8)
            for (c in caps) out[c.ordinal / 8] = (out[c.ordinal / 8].toInt() or (1 shl (c.ordinal % 8))).toByte()
            return out
        }

        /**
         * What Self Watch advertises today: the sync/file protocol, time, multi-link, and the Connect IQ
         * flag the watch checks before app messages, plus notifications, music, find and weather (A16–A19), and
         * Connect IQ app management, install of every app kind, settings, launch and web requests (A21–A23).
         * GPS ephemeris is left out, as in the reference.
         */
        public val SELF_WATCH: Set<GarminCapability> = EnumSet.of(
            CONNECT_MOBILE_FIT_LINK, SYNC, DEVICE_INITIATES_SYNC, HOST_INITIATED_SYNC_REQUESTS, EXPLICIT_ARCHIVE,
            CURRENT_TIME_REQUEST_SUPPORT, MULTI_LINK_SERVICE, CONNECTIQ_WATCH_APP_DOWNLOAD,
            GNCS, BASIC_MUSIC_CONTROLS, ADVANCED_MUSIC_CONTROLS, FIND_MY_PHONE, FIND_MY_WATCH, WEATHER_CONDITIONS,
            CONNECTIQ_HTTP, CONNECTIQ_SETTINGS, CONNECTIQ_WIDGET_DOWNLOAD, CONNECTIQ_WATCH_FACE_DOWNLOAD,
            CONNECTIQ_DATA_FIELD_DOWNLOAD, CONNECTIQ_APP_MANAGEMENT, LAUNCH_REMOTE_CIQ_APP_SUPPORT, HTTP_RAW_RESOURCE_REQUEST,
        )

        /**
         * The reference's advertised set: every flag except GPS ephemeris, LiveTrack, LiveTrack auto
         * start and CAPABILITY_104..111, 114..119. A fallback if a watch refuses to sync with [SELF_WATCH].
         *
         * Ref: Gadgetbridge 160beab41 devices/garmin/GarminCapability.java (OUR_CAPABILITIES)
         */
        public val REFERENCE: Set<GarminCapability> = EnumSet.allOf(GarminCapability::class.java).apply {
            remove(GPS_EPHEMERIS_DOWNLOAD)
            remove(LIVETRACK)
            remove(LIVETRACK_AUTO_START)
            for (n in (104..111) + (114..119)) remove(valueOf("CAPABILITY_$n"))
        }
    }
}
