/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.health

import app.selfwatch.db.SelfWatchDb
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.HrZoneMath
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.TimeRange
import java.time.ZoneId

/**
 * Marks each stored activity minute moderate or vigorous from its heart-rate zone ([HrZoneMath]):
 * zone 2–3 moderate (2), zone 4–5 vigorous (3), below zone 2 still (0) or moving (1).
 *
 * **Rules**
 * - A minute's zone is the highest zone of the heart-rate readings in it. The watch reads heart rate
 *   about once a minute, sometimes every two; a minute with no reading takes the reading of the minute
 *   before it (never older).
 * - Only the watch's own minutes and readings are used (a heart-rate strap has no activity minutes).
 *   A minute in zone 2+ with a reading but no activity row (say, a stationary bike) is added with no
 *   steps.
 * - "Moving" is steps in the minute, or the watch's own moving mark.
 * - Without a reading the minute is not exercise, whatever the watch's raw level says.
 */
public object IntensityClassifier {
    private const val MIN = 60L
    private const val CHUNK_SEC = 7 * 86_400L

    /**
     * The minutes of one device whose intensity changes, plus new zone-2+ minutes. [minutes] and [hr]
     * belong to the same device; [bounds] are the zone bounds.
     */
    public fun classify(deviceId: Long, minutes: List<ActivityMinute>, hr: List<MetricSample>, bounds: List<Int>): List<ActivityMinute> {
        val zoneByMinute = java.util.TreeMap<Long, Int>()
        for (s in hr) {
            if (s.value <= 0) continue
            val m = Math.floorDiv(s.tSec, MIN) * MIN
            val z = HrZoneMath.zoneOf(s.value, bounds)
            if ((zoneByMinute[m] ?: -1) < z) zoneByMinute[m] = z
        }
        fun zoneAt(m: Long): Int {
            zoneByMinute[m]?.let { return it }
            return zoneByMinute[m - MIN] ?: 0
        }
        val out = ArrayList<ActivityMinute>()
        val seen = HashSet<Long>(minutes.size * 2)
        for (m in minutes) {
            seen += m.tSec
            val level = HrZoneMath.intensityOf(zoneAt(m.tSec), moving = m.steps > 0 || m.intensity == 1)
            if (level != m.intensity) out += m.copy(intensity = level)
        }
        for ((m, z) in zoneByMinute) {
            if (m in seen || z < HrZoneMath.MODERATE_ZONE) continue
            out += ActivityMinute(deviceId, m, 0, 0, 0, HrZoneMath.intensityOf(z, moving = false), null)
        }
        return out
    }

    /**
     * Re-marks the stored minutes in [range] with [bounds], a week at a time. Returns the local days in
     * [zone] that changed (their day summaries need a refresh).
     */
    public suspend fun reclassify(db: SelfWatchDb, range: TimeRange, bounds: List<Int>, zone: ZoneId): Set<Day> {
        val days = HashSet<Day>()
        var start = Math.floorDiv(range.startSec, MIN) * MIN
        while (start < range.endSec) {
            val end = minOf(start + CHUNK_SEC, range.endSec)
            val minutes = db.activity.minutes(TimeRange(start, end)).groupBy { it.deviceId }
            if (minutes.isNotEmpty()) {
                // One minute earlier, for the carried reading.
                val hr = db.metrics.series(MetricKind.HEART_RATE, TimeRange(start - MIN, end)).groupBy { it.deviceId }
                val changes = ArrayList<ActivityMinute>()
                for ((dev, ms) in minutes) {
                    val c = classify(dev, ms, hr[dev].orEmpty().sortedBy { it.tSec }, bounds)
                    changes += c.filter { it.tSec in start until end }
                }
                if (changes.isNotEmpty()) {
                    db.activity.setIntensity(changes)
                    for (c in changes) days += Day.of(c.tSec, zone)
                }
            }
            start = end
        }
        return days
    }
}
