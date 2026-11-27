/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.health

import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.model.health.HrZoneMath
import app.selfwatch.model.health.HrZoneMethod
import app.selfwatch.model.health.HrZoneSet
import app.selfwatch.model.health.MaxHrOrigin
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.RestingHrOrigin
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.TimeRange
import kotlin.math.roundToInt

/**
 * The heart-rate zones in use ([HrZoneMath] rules) from the settings and the stored data: auto by
 * default (maximum 220 − age; resting from the watch), with manual override.
 */
public class HrZoneStore(private val db: SelfWatchDb, private val clock: Clock) {

    /** The zones in use now. */
    public suspend fun current(): HrZoneSet {
        val s = db.settings
        val method = HrZoneMethod.of(s.get(METHOD_KEY))
        val (max, maxOrigin) = maxHr(s.getInt(MAX_HR_KEY))
        val (rest, restOrigin) = HrZoneMath.restingHr(s.getInt(RESTING_HR_KEY), measuredRestingHr())
        val manual = HrZoneMath.parse(s.get(BOUNDS_KEY))
        return HrZoneSet(
            method, max, maxOrigin, rest, restOrigin,
            bounds = manual ?: HrZoneMath.bounds(method, max, rest),
            manual = manual != null,
        )
    }

    /**
     * Saves the user's choices: [method]; [maxHr] and [restingHr] typed in (null: auto); [bounds] set by
     * hand (null: auto).
     */
    public suspend fun save(method: HrZoneMethod, maxHr: Int?, restingHr: Int?, bounds: List<Int>?) {
        db.settings.setAll(
            mapOf(
                METHOD_KEY to method.code,
                MAX_HR_KEY to maxHr?.takeIf { it in HrZoneMath.MAX_RANGE }?.toString(),
                RESTING_HR_KEY to restingHr?.takeIf { it in HrZoneMath.RESTING_RANGE }?.toString(),
                BOUNDS_KEY to bounds?.takeIf { HrZoneMath.valid(it) }?.let { HrZoneMath.format(it) },
            ),
        )
    }

    /**
     * Re-marks every stored minute with the zones in use (after the user changes them, and once after
     * the update to 0.0.4), rebuilds the day summaries that changed and records the zones applied.
     * Automatic drift (a new maximum, a new resting average) applies to new data only, as on Garmin.
     */
    public suspend fun applyToHistory(summaries: SummaryBuilder) {
        val zones = current()
        db.activity.span()?.let { span ->
            val today = clock.today()
            val days = IntensityClassifier.reclassify(db, span, zones.bounds, clock.zone())
            summaries.refresh(days.filterTo(HashSet()) { it <= today })
        }
        db.settings.set(APPLIED_KEY, zones.method.code + "|" + HrZoneMath.format(zones.bounds))
    }

    /** True until [applyToHistory] has run once. */
    public suspend fun historyPending(): Boolean = db.settings.get(APPLIED_KEY) == null

    /** The maximum heart rate for a typed-in [manual] value (null: 220 − age). */
    public suspend fun maxHr(manual: Int?): Pair<Int, MaxHrOrigin> =
        HrZoneMath.maxHr(manual, db.settings.getInt(SettingKeys.PROFILE_BIRTH_YEAR), clock.today().year)

    /** The resting heart rate for a typed-in [manual] value (null: the watch's 7-day average). */
    public suspend fun restingHr(manual: Int?): Pair<Int, RestingHrOrigin> = HrZoneMath.restingHr(manual, measuredRestingHr())

    /** The highest heart rate of a workout in the last year, or null (offered as a suggested maximum). */
    public suspend fun highestWorkoutHr(): Int? {
        val today = clock.today()
        return db.workouts.forExport(DayRange(today.plusDays(-DETECT_DAYS), today), clock.zone())
            .mapNotNull { it.maxHr }.filter { it in HrZoneMath.MAX_RANGE }.maxOrNull()
    }

    /** The watch's average resting heart rate of the last 7 days, or null. */
    private suspend fun measuredRestingHr(): Int? {
        val now = clock.nowSec()
        val v = db.metrics.series(MetricKind.RESTING_HR, TimeRange(now - RESTING_DAYS * DAY_SEC, now + 1))
            .map { it.value }.filter { it in HrZoneMath.RESTING_RANGE }
        return if (v.isEmpty()) null else v.average().roundToInt()
    }

    /** Setting keys. */
    public companion object {
        /** [HrZoneMethod.code]; absent: % of max HR. */
        public const val METHOD_KEY: String = "zones.method"

        /** A maximum heart rate typed in by the user (absent: auto). Same key as before 0.0.4. */
        public const val MAX_HR_KEY: String = "activity.max_hr"

        /** A resting heart rate typed in by the user (absent: the watch's). */
        public const val RESTING_HR_KEY: String = "zones.resting_hr"

        /** Zone bounds set by hand, "z1,z2,z3,z4,z5" (absent: auto). */
        public const val BOUNDS_KEY: String = "zones.bounds"

        /** The zones last used to work out the stored intensity minutes ("method|z1,…"). */
        public const val APPLIED_KEY: String = "zones.applied"

        private const val DETECT_DAYS = 365
        private const val RESTING_DAYS = 7L
        private const val DAY_SEC = 86_400L
    }
}
