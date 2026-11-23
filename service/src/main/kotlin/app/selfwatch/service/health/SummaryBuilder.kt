/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.health

import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.dao.NetLogDao
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.DaySummary
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.TimeRange
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalTime
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/** The watch's own resting metabolic rate (FIT `monitoring_info`), when known. */
public fun interface WatchRestingKcal {
    /** Resting kcal per whole day for [day], or null when the watch reported none. */
    public suspend fun kcalPerDay(day: Day): Int?

    /** Sources. */
    public companion object {
        /** Always null, so the next source (the stored metric, then Mifflin-St Jeor) is used. */
        public val NONE: WatchRestingKcal = WatchRestingKcal { null }

        /**
         * The stored [MetricKind.RESTING_KCAL] samples (one per day, from the watch's `monitoring_info`)
         * of [day] in [clock]'s zone; the newest when several devices or files gave one. Non-positive
         * values count as unknown.
         */
        public fun fromMetrics(db: SelfWatchDb, clock: Clock): WatchRestingKcal = WatchRestingKcal { day ->
            db.metrics.series(MetricKind.RESTING_KCAL, day.toTimeRange(clock.zone()))
                .filter { it.value > 0 }
                .maxByOrNull { it.tSec }
                ?.value
        }
    }
}

/**
 * Fills `day_summary` rows from the stored health, diary, water and body data (WP-07 rules; the pure
 * parts are in [SummaryRules]). Days are local days in the phone's current time zone ([Clock.zone]).
 *
 * | Column | Rule |
 * |---|---|
 * | steps, distance_m, active_kcal | Sum of the day's activity minutes; when devices overlap on a minute, only the highest-priority device counts |
 * | active_min | Moderate minutes count 1, vigorous minutes count 2 |
 * | resting_kcal | The [WatchRestingKcal] hook, else the day's stored `RESTING_KCAL` metric (the watch's resting metabolic rate, [WatchRestingKcal.fromMetrics]), else Mifflin-St Jeor from the profile and the latest weight on or before the day; prorated for today |
 * | resting_hr | The `RESTING_HR` metric of the day, else the lowest 30-minute average heart rate during the night |
 * | hrv_avg_ms | Average HRV during the night that ended that morning |
 * | sleep_min | Asleep minutes of the night that ended that morning |
 * | kcal_in, protein_g, fat_g, carb_g, water_ml | Diary and water totals of the day |
 * | weight_kg_x100 | The day's last weight |
 * | tz_offset_min | The zone's offset at local noon of the day |
 */
public class SummaryBuilder(
    private val db: SelfWatchDb,
    private val clock: Clock,
    private val watchRestingKcal: WatchRestingKcal = WatchRestingKcal.NONE,
) {
    private val lock = Mutex()
    private val storedRestingKcal = WatchRestingKcal.fromMetrics(db, clock)

    /**
     * Rebuilds the rows of [days] (future days are skipped). A past day with no data at all gets no
     * row; today gets one as soon as resting kcal is known.
     */
    public suspend fun refresh(days: Set<Day>) {
        lock.withLock {
            val today = clock.today()
            for (day in days.sorted()) {
                if (day > today) continue
                val s = build(day) ?: continue
                db.summaries.upsertDay(s, clock.nowSec())
            }
        }
    }

    /** Rebuilds today's row. */
    public suspend fun refreshToday(): Unit = refresh(setOf(clock.today()))

    /** The day changed from [previous]: finish its row, start today's and prune the network log. */
    public suspend fun onDayChange(previous: Day) {
        refresh(setOf(previous, clock.today()))
        pruneNetLog()
    }

    /** Deletes network log rows older than [NetLogDao.RETENTION_DAYS] days. Returns the count. */
    public suspend fun pruneNetLog(): Int = try {
        db.netLog.prune(clock.nowSec() - NetLogDao.RETENTION_DAYS * DAY_SEC)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SwLog.e(TAG, "net_log prune failed", e)
        0
    }

    /**
     * Runs until cancelled: refreshes today every [TICK_SEC] seconds (resting kcal grows through the
     * day) and calls [onDayChange] just after each local midnight.
     */
    public suspend fun runTicks() {
        var current = clock.today()
        safely { refreshToday() }
        pruneNetLog()
        while (true) {
            val zone = clock.zone()
            val untilMidnight = clock.today().plusDays(1).startSec(zone) - clock.nowSec() + MIDNIGHT_GRACE_SEC
            delay(untilMidnight.coerceIn(1, TICK_SEC) * 1000L)
            val today = clock.today()
            if (today != current) {
                safely { onDayChange(current) }
                current = today
            } else {
                safely { refreshToday() }
            }
        }
    }

    /**
     * The summary of [day] computed now, or null when the day has no data at all and no stored row
     * (an existing row is always rewritten, so deleted data clears it).
     */
    public suspend fun build(day: Day): DaySummary? {
        val zone = clock.zone()
        val range = day.toTimeRange(zone)
        val ranks = db.devices.active().mapIndexed { i, d -> d.id to i }.toMap()
        val rank: (Long) -> Int = { ranks[it] ?: Int.MAX_VALUE }

        val activity = SummaryRules.activity(db.activity.minutes(range), rank)
        val night = db.sleep.night(day, zone)
        val nightRange = night?.let { TimeRange(it.session.startSec, it.session.endSec) }
        val restingHr = restingHr(day, range, nightRange, zone, rank)
        val hrv = nightRange?.let { SummaryRules.average(SummaryRules.topDevice(db.metrics.series(MetricKind.HRV_RMSSD, it), rank)) }
        val sleepMin = night?.let { SummaryRules.sleepMinutes(it.session, it.stages) }
        val food = db.diary.dayTotals(day)
        val water = db.water.dayTotal(day).takeIf { it > 0 }
        val weight = db.body.series(BodyKind.WEIGHT, DayRange(day, day), zone).lastOrNull()?.valueX100
        val restingKcal = restingKcal(day, range, zone)

        val s = DaySummary(
            day = day,
            tzOffsetMin = tzOffsetMin(day, zone),
            steps = activity?.steps,
            activeKcal = activity?.activeKcal,
            restingKcal = restingKcal,
            distanceM = activity?.distanceM,
            activeMin = activity?.activeMin,
            restingHr = restingHr,
            hrvAvgMs = hrv,
            sleepMin = sleepMin,
            kcalIn = SummaryRules.fromX10(food.kcalX10),
            proteinG = SummaryRules.fromX10(food.proteinX10),
            fatG = SummaryRules.fromX10(food.fatX10),
            carbG = SummaryRules.fromX10(food.carbX10),
            weightKgX100 = weight,
            waterMl = water,
        )
        val hasData = activity != null || restingHr != null || hrv != null || sleepMin != null || weight != null ||
            water != null || s.kcalIn != null || s.proteinG != null || s.fatG != null || s.carbG != null
        // Resting kcal alone (from the profile) makes a row only for today (the energy card needs it).
        val keep = hasData || (restingKcal != null && day == clock.today())
        if (!keep && db.summaries.day(day) == null) return null
        return s
    }

    private suspend fun restingHr(day: Day, range: TimeRange, night: TimeRange?, zone: ZoneId, rank: (Long) -> Int): Int? {
        val daily = SummaryRules.topDevice(db.metrics.series(MetricKind.RESTING_HR, range).filter { it.value > 0 }, rank)
        daily.maxByOrNull { it.tSec }?.let { return it.value }
        val window = night ?: TimeRange(range.startSec, day.toLocalDate().atTime(NIGHT_FALLBACK_END).atZone(zone).toEpochSecond())
        val hr = SummaryRules.topDevice(db.metrics.series(MetricKind.HEART_RATE, window).filter { it.value > 0 }, rank)
        return SummaryRules.lowestWindowAverage(hr, window.endSec)
    }

    private suspend fun restingKcal(day: Day, range: TimeRange, zone: ZoneId): Int? {
        val perDay = watchRestingKcal.kcalPerDay(day) ?: storedRestingKcal.kcalPerDay(day) ?: run {
            val profile = db.settings.profile()
            val weight = db.body.series(BodyKind.WEIGHT, DayRange(day.plusDays(-WEIGHT_LOOKBACK_DAYS), day), zone).lastOrNull()
                ?: db.body.latest(BodyKind.WEIGHT)?.takeIf { it.tSec < range.endSec }
            SummaryRules.mifflinStJeor(weight?.valueX100, profile.heightCm, profile.birthYear?.let { day.year - it }, profile.sex)
        } ?: return null
        return SummaryRules.prorate(perDay, range.startSec, range.endSec, clock.nowSec())
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SwLog.e(TAG, "summary refresh failed", e)
        }
    }

    /** Timing constants. */
    public companion object {
        /** Seconds between refreshes of today's row. */
        public const val TICK_SEC: Long = 15 * 60L

        /** Seconds after local midnight at which the day change is handled. */
        public const val MIDNIGHT_GRACE_SEC: Long = 5

        /** End of the night window for the resting-HR fallback when no sleep was recorded. */
        public val NIGHT_FALLBACK_END: LocalTime = LocalTime.of(6, 0)

        private const val DAY_SEC = 86_400L
        private const val WEIGHT_LOOKBACK_DAYS = 365
        private const val TAG = "SummaryBuilder"

        /** The offset of [zone] at local noon of [day], in minutes. */
        public fun tzOffsetMin(day: Day, zone: ZoneId): Int =
            zone.rules.getOffset(day.toLocalDate().atTime(12, 0).atZone(zone).toInstant()).totalSeconds / 60
    }
}
