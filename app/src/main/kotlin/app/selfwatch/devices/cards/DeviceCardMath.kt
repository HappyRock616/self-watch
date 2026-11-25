/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.devices.cards

import androidx.annotation.StringRes
import app.selfwatch.R
import app.selfwatch.cards.CardStatus
import app.selfwatch.db.dao.BatteryReading
import app.selfwatch.db.dao.SyncFileCount

/** What a stored sync file held, in words the Last sync card uses. */
enum class SyncedKind(@field:StringRes @get:StringRes val label: Int) {
    WORKOUTS(R.string.synced_workouts),
    ACTIVITY(R.string.synced_activity),
    SLEEP(R.string.synced_sleep),
    METRICS(R.string.synced_metrics),
    HRV(R.string.synced_hrv),
    WEIGHT(R.string.synced_weight),
    MEASUREMENTS(R.string.synced_measurements),
    OTHER(R.string.synced_other),
}

/** The pure parts of the Battery and Last sync cards. */
object DeviceCardMath {
    /** Garmin's file-transfer data type for FIT files (the subtype is the FIT file type). */
    const val FIT_DATA_TYPE: Int = 128

    /** Bluetooth health-sensor measurements are keyed from this type up (`HealthSensorDriver.KEY_TYPE_BASE`). */
    const val SENSOR_TYPE_BASE: Int = 0x10000

    /** Files done this long before the end of the last sync count as part of it (1 h). */
    const val SYNC_WINDOW_SEC: Long = 3_600L

    /** Battery at or above this is fine; below [LOW_PCT] it needs charging soon. */
    const val OK_PCT: Int = 50

    /** Below this the battery is low. */
    const val LOW_PCT: Int = 20

    private const val DAY_SEC = 86_400.0
    private const val MIN_SPAN_SEC = 6 * 3_600L

    /** The kind of a sync file of [fileType] and [subtype] (FIT file types: 4 activity, 32 monitoring, …). */
    fun kindOf(fileType: Int, subtype: Int): SyncedKind = when {
        fileType >= SENSOR_TYPE_BASE -> SyncedKind.MEASUREMENTS
        fileType != FIT_DATA_TYPE -> SyncedKind.OTHER
        subtype == 4 -> SyncedKind.WORKOUTS
        subtype == 15 || subtype == 28 || subtype == 32 -> SyncedKind.ACTIVITY
        subtype == 49 -> SyncedKind.SLEEP
        subtype == 44 -> SyncedKind.METRICS
        subtype == 68 -> SyncedKind.HRV
        subtype == 9 -> SyncedKind.WEIGHT
        else -> SyncedKind.OTHER
    }

    /** [files] summed by kind, in [SyncedKind] order (kinds with no files left out). */
    fun summarize(files: List<SyncFileCount>): Map<SyncedKind, Int> {
        val out = sortedMapOf<SyncedKind, Int>()
        for (f in files) {
            val k = kindOf(f.fileType, f.fileSubtype)
            out[k] = (out[k] ?: 0) + f.count
        }
        return LinkedHashMap(out)
    }

    /** How a battery at [pct] stands: fine, getting low, or low. */
    fun batteryStatus(pct: Int): CardStatus = when {
        pct >= OK_PCT -> CardStatus.GOOD
        pct >= LOW_PCT -> CardStatus.CLOSE
        else -> CardStatus.OFF
    }

    /** How a last sync [ageSec] ago stands: within a day, within three days, or longer. */
    fun syncStatus(ageSec: Long): CardStatus = when {
        ageSec < DAY_SEC -> CardStatus.GOOD
        ageSec < 3 * DAY_SEC -> CardStatus.CLOSE
        else -> CardStatus.OFF
    }

    /**
     * About how many percent a day the battery drops, from [history] (oldest first): every drop between
     * readings summed (charging ignored), over the time the readings span. Null with under 6 hours of
     * readings or no drop at all.
     */
    fun dailyDrain(history: List<BatteryReading>): Int? {
        if (history.size < 2) return null
        val span = history.last().tSec - history.first().tSec
        if (span < MIN_SPAN_SEC) return null
        var drop = 0
        for (i in 1 until history.size) {
            val d = history[i - 1].pct - history[i].pct
            if (d > 0) drop += d
        }
        if (drop == 0) return null
        return Math.round(drop * DAY_SEC / span).toInt().coerceAtLeast(1)
    }
}
