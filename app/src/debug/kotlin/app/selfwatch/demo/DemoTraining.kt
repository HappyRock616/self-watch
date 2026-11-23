/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.demo

import app.selfwatch.db.SelfWatchDb
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.Day
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Debug-only training history for the Activity cards: six weeks of runs (mostly easy, some harder), rides
 * and a hike, a long run two days ago that is well over the longest run of the 30 days before it (so the
 * long-session check has something to show), strength and yoga sessions without GPS, and heart-rate
 * readings during older workouts so the zone cards have per-minute data beyond the last week.
 */
internal object DemoTraining {
    private const val HOUR = 3_600L
    private const val RUN = 1
    private const val BIKE = 2
    private const val HIKE = 17
    private const val TRAINING = 10
    private const val SUB_STRENGTH = 20
    private const val SUB_YOGA = 43
    private const val SEED = 4_242

    /** The extra GPS workouts (days ago, sport, start hour, minutes, metres, average HR, kcal). */
    private val PLAN: List<List<Int>> = listOf(
        listOf(2, RUN, 8, 68, 11_500, 141, 820),
        listOf(9, RUN, 7, 45, 7_200, 132, 520),
        listOf(11, RUN, 18, 40, 6_400, 135, 470),
        listOf(13, BIKE, 10, 70, 24_000, 126, 600),
        listOf(16, RUN, 7, 50, 8_000, 133, 560),
        listOf(18, RUN, 18, 35, 5_600, 150, 420),
        listOf(20, HIKE, 9, 120, 8_500, 118, 700),
        listOf(23, RUN, 7, 48, 7_600, 131, 540),
        listOf(25, RUN, 18, 30, 5_000, 152, 380),
        listOf(30, RUN, 7, 45, 7_000, 134, 500),
        listOf(32, RUN, 18, 30, 5_200, 149, 390),
        listOf(37, RUN, 7, 42, 6_800, 133, 480),
        listOf(39, BIKE, 10, 60, 20_000, 128, 520),
    )

    /** Days ago of strength sessions (17:30, 45 minutes); never the last two days, so the newest workout keeps its FIT file. */
    private val STRENGTH_DAYS = listOf(3, 5, 8, 10, 15, 17, 22, 24, 29, 36)

    /** Days ago of yoga sessions (19:00, 40 minutes). */
    private val YOGA_DAYS = listOf(6, 13, 27)

    /** The extra GPS workouts, as [DemoWorkout]s written to FIT like the others. */
    fun plan(today: Day, zone: ZoneId): List<DemoWorkout> = PLAN.map { p ->
        val start = today.plusDays(-p[0]).startSec(zone) + p[2] * HOUR + 10 * 60
        DemoWorkout(p[1], start, p[3] * 60, p[4], p[5], p[6])
    }

    /** Stores the strength and yoga sessions (no track) in the demo batch; returns how many. */
    suspend fun gymSessions(db: SelfWatchDb, deviceId: Long, importId: Long, today: Day, zone: ZoneId, now: Long): Int {
        var n = 0
        val sessions = STRENGTH_DAYS.map { Triple(it, SUB_STRENGTH, 17 * HOUR + 30 * 60) } + YOGA_DAYS.map { Triple(it, SUB_YOGA, 19 * HOUR) }
        for ((daysAgo, sub, at) in sessions) {
            val start = today.plusDays(-daysAgo).startSec(zone) + at
            val minutes = if (sub == SUB_STRENGTH) 45 else 40
            val end = start + minutes * 60L
            if (end > now) continue
            val avg = if (sub == SUB_STRENGTH) 112 else 92
            db.workouts.insert(
                Workout(
                    0, deviceId, TRAINING, sub, start, end, minutes * 60, null, if (sub == SUB_STRENGTH) 260 else 150,
                    avg, avg + 30, null, null, false, null, null, null, DataSource.WATCH,
                ),
                importId,
            )
            n++
        }
        return n
    }

    /**
     * Heart rate once a minute during [workouts] (for days older than the detailed week): a warm-up, a
     * steady middle around the average and, for harder sessions, a few faster stretches.
     */
    fun workoutHeartRate(deviceId: Long, workouts: List<DemoWorkout>): List<MetricSample> {
        val out = ArrayList<MetricSample>()
        for (w in workouts) {
            val rnd = Random(SEED + w.startSec.toInt())
            val n = w.durationSec / 60
            for (i in 0 until n) {
                val f = i.toDouble() / n
                val warm = (f * 8).coerceAtMost(1.0)
                val surge = if (w.avgHr >= 145 && (i / 4) % 3 == 0) 8 else 0
                val hr = w.avgHr - 22 + 22 * warm + surge + 4 * sin(f * 11) + rnd.nextInt(-3, 4)
                out += MetricSample(deviceId, MetricKind.HEART_RATE, w.startSec + i * 60L + 30, hr.roundToInt())
            }
        }
        return out
    }
}
