/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body

import app.selfwatch.cards.MainGoal
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.TimeRange
import kotlin.math.roundToInt

/**
 * Last night's sleep against 7–9 hours, with its stages as detail.
 *
 * @property lastMin minutes asleep on the latest night (null with no night in the last two days).
 * @property lastDay the day that night ended.
 * @property avgMin the average of the last 7 nights that have a value.
 * @property stageMin minutes per stage of the latest night (empty when the watch gave no stages).
 * @property nights minutes asleep for each of the last 14 days, oldest first (-1 for no value).
 */
data class SleepInfo(val lastMin: Int?, val lastDay: Day?, val avgMin: Int?, val stageMin: Map<SleepStage, Int>, val nights: List<Int>) {
    /** True with no sleep in the last 14 days. */
    val isEmpty: Boolean get() = lastMin == null && nights.all { it < 0 }
}

/**
 * Waist-to-height ratio. [heightCm] null means the profile has no height; [waistX100] null means no
 * waist was logged.
 *
 * @property ratioX100 waist ÷ height ×100.
 * @property changeX100 waist change over 30 days (cm ×100).
 * @property bmi BMI from the latest weight and the height.
 */
data class WaistHeightInfo(
    val heightCm: Int?,
    val waistX100: Int?,
    val waistSec: Long?,
    val ratioX100: Int?,
    val changeX100: Int?,
    val bmi: Double?,
)

/**
 * Body fat trend.
 *
 * @property trendX100 the smoothed value at the latest reading (% ×100).
 * @property change90X100 the trend's change over 90 days (null with nothing older).
 * @property spark the smoothed trend at each reading of the last 90 days.
 */
data class BodyFatInfo(val latestX100: Int, val latestSec: Long, val trendX100: Int, val change90X100: Int?, val spark: List<Int>)

/**
 * Body stress (the watch's 0–100 score) for one day: context only, never mood.
 *
 * @property day the day shown (today, or the latest day with readings this week).
 * @property avg that day's average score.
 * @property restMin minutes at rest (score 0–25) that day.
 * @property daily the average of each of the last 7 days, oldest first (-1 for no readings).
 */
data class StressInfo(val day: Day, val avg: Int, val restMin: Int, val daily: List<Int>)

/** Reads the phase-2 Body cards' numbers. */
class BodyTrendLoader(private val db: SelfWatchDb, private val clock: Clock) {

    /** Last night's sleep, the week's average and 14 nights. */
    suspend fun sleep(): SleepInfo {
        val today = clock.today()
        val first = today.plusDays(1 - SLEEP_DAYS)
        val byDay = db.summaries.days(DayRange(first, today)).associateBy { it.day }
        val nights = DayRange(first, today).days().map { byDay[it]?.sleepMin ?: -1 }
        val lastIdx = nights.indexOfLast { it >= 0 }.takeIf { it >= nights.size - 2 }
        val lastDay = lastIdx?.let { first.plusDays(it) }
        val night = lastDay?.let { db.sleep.night(it, clock.zone()) }
        val stages = night?.takeIf { it.stages.isNotEmpty() }?.let { SleepNights.stageMinutes(it.stages, it.session.endSec) }.orEmpty()
        val week = nights.takeLast(7).filter { it >= 0 }
        return SleepInfo(
            lastMin = lastIdx?.let { nights[it] },
            lastDay = lastDay,
            avgMin = if (week.isEmpty()) null else week.average().roundToInt(),
            stageMin = stages.filterKeys { it != SleepStage.UNMEASURABLE },
            nights = nights,
        )
    }

    /** Waist against height, with BMI. */
    suspend fun waistHeight(): WaistHeightInfo {
        val today = clock.today()
        val height = db.settings.profile().heightCm
        val waist = db.body.latest(BodyKind.WAIST)
        val series = db.body.series(BodyKind.WAIST, DayRange(today.plusDays(-LONG_DAYS), today), clock.zone())
        val weight = db.body.latest(BodyKind.WEIGHT)?.valueX100
        return WaistHeightInfo(
            heightCm = height,
            waistX100 = waist?.valueX100,
            waistSec = waist?.tSec,
            ratioX100 = waist?.let { BodyCardMath.waistToHeightX100(it.valueX100, height) },
            changeX100 = waist?.let { BodyLoader.changeOver(series, it, CHANGE_DAYS) },
            bmi = WeightRules.bmi(weight, height),
        )
    }

    /** The body fat trend, or null with no readings. */
    suspend fun bodyFat(): BodyFatInfo? {
        val today = clock.today()
        val latest = db.body.latest(BodyKind.BODY_FAT) ?: return null
        val series = db.body.series(BodyKind.BODY_FAT, DayRange(today.plusDays(-LONG_DAYS), today), clock.zone())
            .ifEmpty { listOf(latest) }
        val trend = WeightMath.trend(LongArray(series.size) { series[it].tSec }, IntArray(series.size) { series[it].valueX100 })
        return BodyFatInfo(
            latestX100 = latest.valueX100,
            latestSec = latest.tSec,
            trendX100 = trend.last().roundToInt(),
            change90X100 = WeightMath.trendChange(series, LONG_DAYS),
            spark = trend.map { it.roundToInt() },
        )
    }

    /** Overnight HRV per day, oldest first, ending today. */
    suspend fun hrvDaily(days: Int): List<Int?> {
        val today = clock.today()
        val first = today.plusDays(1 - days)
        val byDay = db.summaries.days(DayRange(first, today)).associateBy { it.day }
        return DayRange(first, today).days().map { byDay[it]?.hrvAvgMs }
    }

    /** HRV's 7-day average against the user's usual band. */
    suspend fun hrv(): HrvInfo = HrvMath.evaluate(hrvDaily(HrvMath.BASELINE_DAYS))

    /**
     * Resting heart rate per day of [days]: the day summary's value, else the watch's daily resting
     * heart-rate reading.
     */
    suspend fun restingByDay(days: DayRange): Map<Day, Int> {
        val out = HashMap<Day, Int>()
        for (s in db.metrics.dailyStats(MetricKind.RESTING_HR, days, clock.zone())) out[s.day] = s.min
        for (s in db.summaries.days(days)) s.restingHr?.let { out[s.day] = it }
        return out
    }

    /** The resting heart-rate trend, or null with too few days. */
    suspend fun restingHr(): RestingHrTrend? {
        val today = clock.today()
        val range = DayRange(today.plusDays(1 - RestingHrMath.HISTORY_DAYS), today)
        val byDay = restingByDay(range)
        return RestingHrMath.evaluate(range.days().map { byDay[it] })
    }

    /** Body stress for today (or the latest day with readings this week), or null with none this week. */
    suspend fun stress(): StressInfo? {
        val today = clock.today()
        val zone = clock.zone()
        val range = DayRange(today.plusDays(-6), today)
        val samplesFrom = range.first.startSec(zone)
        val samples = db.metrics.series(MetricKind.STRESS, TimeRange(samplesFrom, today.plusDays(1).startSec(zone)))
            .filter { it.value in 0..MAX_STRESS }
        if (samples.isEmpty()) return null
        val byDay = samples.groupBy { Day.of(it.tSec, zone) }
        val day = byDay.keys.max()
        val list = byDay.getValue(day)
        val daily = range.days().map { d -> byDay[d]?.let { l -> l.map { it.value }.average().roundToInt() } ?: -1 }
        return StressInfo(
            day = day,
            avg = list.map { it.value }.average().roundToInt(),
            restMin = BodyCardMath.restMinutes(LongArray(list.size) { list[it].tSec }, IntArray(list.size) { list[it].value }),
            daily = daily,
        )
    }

    /**
     * Each signal's value per night over [days]: skin temperature change and breathing averaged over
     * the sleep sessions that ended that day (all readings of the night when there are no sessions), and
     * the resting heart rate of that day.
     */
    suspend fun signalNights(days: DayRange): Map<SignalKind, Map<Day, Int>> {
        val zone = clock.zone()
        val sessions = db.sleep.sessions(days, zone)
        val range = TimeRange(days.first.startSec(zone) - NIGHT_LEAD_SEC, days.last.plusDays(1).startSec(zone))
        val skin = perNight(db.metrics.series(MetricKind.SKIN_TEMP_DELTA, range), sessions)
        val resp = perNight(db.metrics.series(MetricKind.RESPIRATION, range).filter { it.value > 0 }, sessions)
        return mapOf(
            SignalKind.SKIN_TEMP to skin.filterKeys { it in days },
            SignalKind.RESTING_HR to restingByDay(days),
            SignalKind.RESPIRATION to resp.filterKeys { it in days },
        )
    }

    private fun perNight(samples: List<MetricSample>, sessions: List<SleepSession>): Map<Day, Int> {
        val zone = clock.zone()
        if (sessions.isEmpty()) return BodyLoader.nightlyAverages(samples, zone).toMap()
        val out = HashMap<Day, MutableList<Int>>()
        for (s in sessions) {
            val day = Day.of(s.endSec, zone)
            val inside = samples.filter { it.tSec >= s.startSec && it.tSec < s.endSec }
            if (inside.isNotEmpty()) out.getOrPut(day) { ArrayList() }.addAll(inside.map { it.value })
        }
        return out.mapValues { (_, v) -> v.average().roundToInt() }
    }

    /** Last night's body signals against baseline. */
    suspend fun signals(): SignalsInfo {
        val today = clock.today()
        val days = DayRange(today.plusDays(-(BodySignalsMath.BASELINE_NIGHTS + 1)), today)
        val values = signalNights(days)
        val nightsWithData = days.days().filter { d -> values.values.any { d in it } }
        val last = nightsWithData.lastOrNull()?.takeIf { it >= today.plusDays(-1) }
        val nightsBefore = nightsWithData.count { last == null || it < last }
        val result = last?.let { l -> BodySignalsMath.evaluate(days.days().filter { it <= l }, values) }
        return SignalsInfo(result, nightsBefore, BodySignalsMath.BASELINE_MIN)
    }

    /** The weight card's band for the user's main goal, or null when the goal has none or there is no weigh-in. */
    suspend fun weightBand(): WeightBand? {
        val goal = MainGoal.byId(db.settings.get(SettingKeys.MAIN_GOAL))
        val today = clock.today()
        val weights = db.body.series(BodyKind.WEIGHT, DayRange(today.plusDays(-LONG_DAYS), today), clock.zone()).sortedBy { it.tSec }
        if (weights.isEmpty()) return null
        val trend = WeightMath.trend(LongArray(weights.size) { weights[it].tSec }, IntArray(weights.size) { weights[it].valueX100 })
        val rateWeeks = WeightPlan.RATE_WEEKS
        val rate = WeightMath.trendChange(weights, rateWeeks * WEEK)?.let { change ->
            // Over the time actually covered, when the history is shorter than the window.
            val span = (weights.last().tSec - weights.first().tSec).coerceAtLeast(DAY_SEC)
            val weeks = minOf(rateWeeks.toDouble(), span.toDouble() / (WEEK * DAY_SEC))
            if (weeks < MIN_RATE_WEEKS) null else (change / weeks).roundToInt()
        }
        val goalKg = db.goals.goalOn(GoalKind.WEIGHT_KG_X100, today)?.value
        return WeightPlan.band(goal, trend.last().roundToInt(), rate, trend.first().roundToInt(), goalKg)
    }

    private companion object {
        const val SLEEP_DAYS = 14
        const val LONG_DAYS = 90
        const val CHANGE_DAYS = 30
        const val WEEK = 7
        const val DAY_SEC = 86_400L
        const val MIN_RATE_WEEKS = 1.0
        const val MAX_STRESS = 100
        const val NIGHT_LEAD_SEC = 12 * 3_600L
    }
}
