/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import app.selfwatch.db.SelfWatchDb
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Sleep against the next day's food (overlay O2), one value per day from [firstDay]: [sleepMin] is the
 * main sleep of the night before day `i` (the longest session that ended on that day), [kcalIn] the
 * food logged in the diary on day `i`. So night N sits with the calories of day N + 1, the day it ends
 * in. [MISSING] marks a day without data.
 */
class SleepCaloriesOverlay(val firstDay: Day, val sleepMin: IntArray, val kcalIn: IntArray) {
    /** Number of days. */
    val size: Int get() = kcalIn.size

    /** Day [i]. */
    fun day(i: Int): Day = firstDay.plusDays(i)

    /** True when at least one day has both sleep and food. */
    val hasPairs: Boolean get() = sleepMin.indices.any { sleepMin[it] != MISSING && kcalIn[it] != MISSING }

    /** True when there is any sleep or any food. */
    val hasData: Boolean get() = sleepMin.any { it != MISSING } || kcalIn.any { it != MISSING }

    /**
     * Average calories on days after a night under [SHORT_NIGHT_MIN] and after a longer one, each null
     * with fewer than [MIN_DAYS] such days; with the day counts.
     */
    fun compare(): Compare {
        val short = ArrayList<Int>()
        val long = ArrayList<Int>()
        for (i in 0 until size) {
            if (sleepMin[i] == MISSING || kcalIn[i] == MISSING) continue
            if (sleepMin[i] < SHORT_NIGHT_MIN) short += kcalIn[i] else long += kcalIn[i]
        }
        fun avg(l: List<Int>): Int? = if (l.size < MIN_DAYS) null else l.average().roundToInt()
        return Compare(avg(short), short.size, avg(long), long.size)
    }

    /** Average calories after short and after longer nights ([shortKcal] null with too few days). */
    data class Compare(val shortKcal: Int?, val shortDays: Int, val longKcal: Int?, val longDays: Int)

    /** Constants and the pure assembly. */
    companion object {
        /** A day without data (the bar chart draws nothing for it). */
        const val MISSING: Int = Int.MIN_VALUE

        /** Nights shorter than this count as short (7 h). */
        const val SHORT_NIGHT_MIN: Int = 7 * 60

        /** Days each side of [compare] needs. */
        const val MIN_DAYS: Int = 3

        /**
         * Builds the series for [first]..[last] (inclusive) from [sessions] (any order; ended-on day in
         * [zone] decides the day) and the diary's energy per day ([kcalInX10ByDay], tenths of a kcal).
         */
        fun build(first: Day, last: Day, sessions: List<SleepSession>, zone: ZoneId, kcalInX10ByDay: Map<Day, Long>): SleepCaloriesOverlay {
            val n = (ChronoUnit.DAYS.between(first.toLocalDate(), last.toLocalDate()) + 1).toInt().coerceAtLeast(0)
            val sleep = IntArray(n) { MISSING }
            val kcal = IntArray(n) { MISSING }
            fun index(day: Day): Int {
                val i = ChronoUnit.DAYS.between(first.toLocalDate(), day.toLocalDate()).toInt()
                return if (i in 0 until n) i else -1
            }
            for (s in sessions) {
                val i = index(Day.of(s.endSec, zone))
                if (i < 0) continue
                val min = ((s.endSec - s.startSec) / SEC_PER_MIN).toInt()
                if (min > 0 && (sleep[i] == MISSING || min > sleep[i])) sleep[i] = min
            }
            for ((day, x10) in kcalInX10ByDay) {
                val i = index(day)
                if (i >= 0) kcal[i] = ((x10 + HALF) / TENTHS).toInt()
            }
            return SleepCaloriesOverlay(first, sleep, kcal)
        }

        /** Loads [first]..[last]: sleep sessions that ended in it and food per day from the diary. */
        suspend fun load(db: SelfWatchDb, first: Day, last: Day, zone: ZoneId): SleepCaloriesOverlay {
            val range = DayRange(first, last)
            return build(first, last, db.sleep.sessions(range, zone), zone, db.diary.dailyKcalX10(range))
        }

        private const val SEC_PER_MIN = 60L
        private const val HALF = 5L
        private const val TENTHS = 10L
    }
}

/**
 * The sleep and calories chart's layers and which the user switched off, kept in settings under [KEY]
 * as the comma list of hidden ids. Both start on.
 */
object SleepCaloriesLayers {
    /** Settings key. */
    const val KEY: String = "chart.sleep_kcal.layers"

    /** Sleep of the night before, hours. */
    const val SLEEP: String = "sleep"

    /** Calories eaten that day. */
    const val KCAL: String = "kcal_in"

    /** Layers in chip order. */
    val ALL: List<String> = listOf(SLEEP, KCAL)

    /** The hidden ids stored as [stored] (null: never chosen, nothing hidden); unknown ids are dropped. */
    fun decode(stored: String?): Set<String> =
        stored?.split(',')?.map { it.trim() }?.filter { it in ALL }?.toSet() ?: emptySet()

    /** [hidden] as stored, in chip order. */
    fun encode(hidden: Set<String>): String = ALL.filter { it in hidden }.joinToString(",")

    /** The hidden layer ids. */
    suspend fun load(db: SelfWatchDb): Set<String> = decode(db.settings.get(KEY))

    /** Saves the hidden layer ids. */
    suspend fun save(db: SelfWatchDb, hidden: Set<String>) {
        db.settings.set(KEY, encode(hidden))
    }
}
