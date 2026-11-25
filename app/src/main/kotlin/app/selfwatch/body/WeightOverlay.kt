/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body

import app.selfwatch.cards.ChartChoice
import app.selfwatch.cards.ChartId
import app.selfwatch.cards.MainGoal
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.model.health.DaySummary
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * The calorie and activity series drawn with the weight on the Weight screen, one value per bucket of
 * [bucketDays] days starting at [firstDay] (bucket `i` covers `firstDay + i × bucketDays` up to the next).
 * Energy is in kcal per day; [MISSING] marks a bucket without data.
 *
 * Energy balance and activity are smoothed to a weekly scale, so they can be read against the smoothed
 * weight trend rather than day-to-day noise: with daily buckets, [netKcal], [activeMin] and [steps] are
 * trailing [ROLLING_DAYS]-day averages (over the days in the window that have data, at least
 * [ROLLING_MIN_DAYS] of them); with weekly buckets every series is the week's average per day. [kcalIn]
 * and [kcalOut] stay as logged per day in daily buckets.
 *
 * @property kcalIn food logged in the diary.
 * @property kcalOut active plus resting calories from the day summaries.
 * @property netKcal in minus out, over the days that have both (negative is a deficit).
 * @property activeMin active minutes.
 * @property steps steps.
 */
class WeightOverlay(
    val firstDay: Day,
    val bucketDays: Int,
    val kcalIn: IntArray,
    val kcalOut: IntArray,
    val netKcal: IntArray,
    val activeMin: IntArray,
    val steps: IntArray,
) {
    /** Number of buckets. */
    val size: Int get() = kcalIn.size

    /** The first day of bucket [i]. */
    fun bucketStart(i: Int): Day = firstDay.plusDays(i * bucketDays)

    /** True when any calorie or activity bucket has data. */
    val hasData: Boolean
        get() = listOf(kcalIn, kcalOut, activeMin, steps).any { a -> a.any { it != MISSING } }

    /** Constants and the pure assembly. */
    companion object {
        /** A bucket without data (the same sentinel the signed chart bars use). */
        const val MISSING: Int = Int.MIN_VALUE

        /** Ranges up to this many days get one bucket per day; longer ones one per week. */
        const val DAILY_UP_TO_DAYS: Int = 92

        /** Length of the rolling window for net calories and activity, in days. */
        const val ROLLING_DAYS: Int = 7

        /** Days with data a rolling window needs before it shows a value. */
        const val ROLLING_MIN_DAYS: Int = 4

        /** Bucket length for a range of [spanDays] days. */
        fun bucketDaysFor(spanDays: Int): Int = if (spanDays <= DAILY_UP_TO_DAYS) 1 else WEEK

        /**
         * Builds the series for [first]..[last] (inclusive) from the diary's energy per day
         * ([kcalInX10ByDay], tenths of a kcal) and the [summaries]. The [ROLLING_DAYS] − 1 days before
         * [first] feed the first rolling averages; other days outside the range are ignored.
         */
        fun build(first: Day, last: Day, kcalInX10ByDay: Map<Day, Long>, summaries: List<DaySummary>): WeightOverlay {
            val spanDays = (ChronoUnit.DAYS.between(first.toLocalDate(), last.toLocalDate()) + 1).toInt().coerceAtLeast(0)
            val bucket = bucketDaysFor(spanDays)
            val lead = ROLLING_DAYS - 1
            // Per-day values from `first - lead` to `last`; day d sits at index d - first + lead.
            val days = spanDays + lead
            val inD = IntArray(days) { MISSING }
            val outD = IntArray(days) { MISSING }
            val netD = IntArray(days) { MISSING }
            val minD = IntArray(days) { MISSING }
            val stepD = IntArray(days) { MISSING }
            fun index(day: Day): Int {
                val i = ChronoUnit.DAYS.between(first.toLocalDate(), day.toLocalDate()).toInt() + lead
                return if (i in 0 until days) i else -1
            }
            for ((day, x10) in kcalInX10ByDay) {
                val i = index(day)
                if (i >= 0) inD[i] = ((x10 + HALF) / TENTHS).toInt()
            }
            for (s in summaries) {
                val i = index(s.day)
                if (i < 0) continue
                outKcal(s)?.let { out ->
                    outD[i] = out
                    if (inD[i] != MISSING) netD[i] = inD[i] - out
                }
                s.activeMin?.let { minD[i] = it }
                s.steps?.let { stepD[i] = it }
            }
            if (bucket == 1) {
                fun raw(a: IntArray) = IntArray(spanDays) { a[it + lead] }
                fun rolling(a: IntArray) = IntArray(spanDays) { rollingMean(a, it + lead, ROLLING_DAYS, ROLLING_MIN_DAYS) }
                return WeightOverlay(first, 1, raw(inD), raw(outD), rolling(netD), rolling(minD), rolling(stepD))
            }
            val n = (spanDays + bucket - 1) / bucket
            fun weekly(a: IntArray) = IntArray(n) { b ->
                val from = lead + b * bucket
                rollingMean(a, minOf(from + bucket, days) - 1, minOf(bucket, days - from), 1)
            }
            return WeightOverlay(first, bucket, weekly(inD), weekly(outD), weekly(netD), weekly(minD), weekly(stepD))
        }

        /**
         * The mean of the present values among the [window] values ending at index [end] (inclusive), rounded,
         * or [MISSING] when fewer than [minDays] are present.
         */
        fun rollingMean(values: IntArray, end: Int, window: Int, minDays: Int): Int {
            var sum = 0L
            var count = 0
            for (i in maxOf(0, end - window + 1)..minOf(end, values.size - 1)) {
                if (values[i] == MISSING) continue
                sum += values[i]
                count++
            }
            return if (count == 0 || count < minDays) MISSING else (sum.toDouble() / count).roundToInt()
        }

        /** Active plus resting calories of [s], or null when it has neither. */
        fun outKcal(s: DaySummary): Int? =
            if (s.activeKcal == null && s.restingKcal == null) null else (s.activeKcal ?: 0) + (s.restingKcal ?: 0)

        /**
         * Loads the series for [first]..[last]: food from the diary in one grouped query (the summary's
         * kcal_in can lag behind the diary), energy out and activity from the day summaries, both reaching
         * back far enough for the first rolling average.
         */
        suspend fun load(db: SelfWatchDb, first: Day, last: Day): WeightOverlay {
            val range = DayRange(first.plusDays(1 - ROLLING_DAYS), last)
            return build(first, last, db.diary.dailyKcalX10(range), db.summaries.days(range))
        }

        private const val WEEK = 7
        private const val HALF = 5L
        private const val TENTHS = 10L
    }
}

/**
 * The Weight chart's layers and the user's choice of which are shown, kept in settings under [KEY] as
 * the comma list of hidden layer ids. No stored value means the main goal's defaults ([ChartDefaults]).
 */
object WeightLayers {
    /** Settings key. */
    const val KEY: String = "chart.weight.layers"

    /** Weight dots, trend and goal (always shown). */
    const val WEIGHT: String = "weight"

    /** Calories eaten per day. */
    const val KCAL_IN: String = "kcal_in"

    /** Calories burned per day. */
    const val KCAL_OUT: String = "kcal_out"

    /** Calories in minus out, a 7-day average (the balance to read against the weight trend). */
    const val NET: String = "net"

    /** Active minutes, a 7-day average. */
    const val ACTIVE_MIN: String = "active_min"

    /** Steps, a 7-day average. */
    const val STEPS: String = "steps"

    /** Layers that can be switched, in chip order. */
    val TOGGLEABLE: List<String> = listOf(NET, ACTIVE_MIN, KCAL_IN, KCAL_OUT, STEPS)

    /** The stored choice; with none, the main goal's defaults ([ChartDefaults]). */
    val CHOICE: ChartChoice by lazy { ChartChoice(ChartId.WEIGHT) }

    /** The hidden ids stored as [stored] (null: never chosen, [goal]'s defaults). Unknown ids and [WEIGHT] are dropped. */
    fun decode(stored: String?, goal: MainGoal): Set<String> = CHOICE.decode(stored, goal)

    /** [hidden] as stored (in chip order, so equal sets store equal text). */
    fun encode(hidden: Set<String>): String = CHOICE.encode(hidden)

    /** The hidden layer ids. */
    suspend fun load(db: SelfWatchDb): Set<String> = CHOICE.load(db)

    /** Saves the hidden layer ids. */
    suspend fun save(db: SelfWatchDb, hidden: Set<String>): Unit = CHOICE.save(db, hidden)
}
