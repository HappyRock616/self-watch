/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.model.health.HrZoneSet
import app.selfwatch.model.health.MetricKind
import app.selfwatch.service.health.HrZoneStore
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.TimeRange

/**
 * Reads what the phase-2 Activity cards and the training overlays need from the database; the
 * arithmetic is in [IntensityMath], [HrZones], [StrengthRules] and [LongSessionCheck].
 *
 * **Heart rate per workout.** Zone time comes from the watch's heart-rate readings stored during the
 * workout (the `metric` table, all-day monitoring, which keeps recording during workouts), preferring
 * the workout's own watch. When they cover less than half of the workout, the workout's average heart
 * rate stands for its whole duration. Second-by-second FIT records are not decoded here: the card
 * loaders have no file access, and decoding every workout of six weeks on each refresh would be slow.
 */
class ActivityStatsLoader(private val db: SelfWatchDb, private val clock: Clock) {

    /** Age in whole years this calendar year, or null without a birth year. */
    suspend fun age(): Int? = db.settings.getInt(SettingKeys.PROFILE_BIRTH_YEAR)?.let { clock.today().year - it }?.takeIf { it in 0..AGE_MAX }

    /** The heart-rate zones in use (Goals › Heart-rate zones). */
    suspend fun zoneSet(): HrZoneSet = HrZoneStore(db, clock).current()

    /** The maximum heart rate for the zones. */
    suspend fun maxHr(): MaxHr = MaxHr.of(zoneSet())

    /** Intensity minutes per day of [days] (null where nothing was recorded). */
    suspend fun intensityPerDay(days: List<Day>): List<Int?> {
        if (days.isEmpty()) return emptyList()
        val zone = clock.zone()
        val range = DayRange(days.first(), days.last())
        val minutes = db.activity.minutes(TimeRange(range.first.startSec(zone), range.last.plusDays(1).startSec(zone)))
        val byDay = IntensityMath.byDay(minutes, zone)
        val summary = db.summaries.days(range).mapNotNull { s -> s.activeMin?.let { s.day to it } }.toMap()
        return IntensityMath.perDay(days, byDay, summary)
    }

    /** True when any activity minute or day summary with active minutes exists in [days]. */
    suspend fun hasActivity(days: DayRange): Boolean {
        val zone = clock.zone()
        if (db.activity.totalsByDay(days, zone).any { it.minutes > 0 }) return true
        return db.summaries.days(days).any { it.activeMin != null || it.steps != null }
    }

    /** Workouts that started on [days], oldest first. */
    suspend fun workouts(days: DayRange): List<Workout> = db.workouts.forExport(days, clock.zone())

    /** Each workout of [workouts] with its zone time in [zones]. */
    suspend fun zones(workouts: List<Workout>, zones: HrZoneSet): List<SessionZones> = workouts.map { w ->
        val readings = heartRate(w)
        val sec = HrZones.zoneSeconds(readings, w.startSec, w.endSec, w.durationSec, w.avgHr, zones::zoneOf)
        val fromAverage = sec != null && HrZones.zoneSeconds(readings, w.startSec, w.endSec, w.durationSec, null, zones::zoneOf) == null
        SessionZones(w, sec, fromAverage)
    }

    /** Training load per local day for the workouts in [days]. */
    suspend fun dailyLoad(days: DayRange, zones: HrZoneSet): Map<Day, Int> {
        val zone = clock.zone()
        val out = HashMap<Day, Int>()
        for (s in zones(workouts(days), zones)) {
            if (s.zoneSec == null) continue
            val d = Day.of(s.workout.startSec, zone)
            out[d] = (out[d] ?: 0) + s.load
        }
        return out
    }

    private suspend fun heartRate(w: Workout): List<Pair<Long, Int>> {
        val end = if (w.endSec > w.startSec) w.endSec else w.startSec + w.durationSec
        val range = TimeRange(w.startSec, end)
        val own = w.deviceId?.let { db.metrics.series(MetricKind.HEART_RATE, range, it) }.orEmpty()
        val samples = own.ifEmpty { db.metrics.series(MetricKind.HEART_RATE, range) }
        return samples.map { it.tSec to it.value }
    }

    private companion object {
        const val AGE_MAX = 120
    }
}
