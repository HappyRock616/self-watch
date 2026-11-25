/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import app.selfwatch.db.SelfWatchDb
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.TimeRange
import kotlin.math.abs

/**
 * Heart-rate recovery after one workout.
 *
 * @property endBpm heart rate when the workout ended.
 * @property oneMinDrop [endBpm] minus the heart rate one minute later (null: no reading then).
 * @property twoMinDrop [endBpm] minus the heart rate two minutes later (null: no reading then).
 */
data class HrRecovery(val endBpm: Int, val oneMinDrop: Int?, val twoMinDrop: Int?)

/**
 * Heart-rate recovery card data.
 *
 * @property workout the newest of the last workouts with a recovery, else the newest workout (null: none yet).
 * @property latest its recovery (null: none of them has enough heart-rate readings around its end).
 * @property trend the 1-minute drop of each of the last [RecoveryHrMath.TREND_WORKOUTS] workouts that has
 *   one, oldest first.
 */
data class RecoveryInfo(val workout: Workout?, val latest: HrRecovery?, val trend: List<Int>) {
    /** Mean of [trend] (null: empty). */
    val trendMean: Int? get() = trend.takeIf { it.isNotEmpty() }?.average()?.let { Math.round(it).toInt() }
}

/**
 * Heart-rate recovery: how far the heart rate falls in the first one and two minutes after a workout ends.
 *
 * Method: the end is the workout's stop time as the watch recorded it. The heart rate at the end is the
 * reading closest to it from [END_BEFORE_SEC] before to [END_AFTER_SEC] after (the last minute of effort);
 * the heart rate one and two minutes later is the reading closest to end + 60 s and end + 120 s, within
 * [TOLERANCE_SEC]. The readings are the stored heart rate (the watch's all-day readings, once a minute or
 * so; workout files stop at the end, so they cannot show the recovery).
 *
 * Limits: it needs the watch worn for two minutes after stopping and readings in that time (many watches
 * read less often at rest, so a minute can be missing); stopping the workout late (after walking about)
 * makes the drop look smaller; it compares a person with themselves, never with a population.
 */
object RecoveryHrMath {
    /** Before the end, how far back a reading still counts as "at the end". */
    const val END_BEFORE_SEC: Long = 60L

    /** After the end, how far forward a reading still counts as "at the end". */
    const val END_AFTER_SEC: Long = 15L

    /** How far a reading may be from the 1- and 2-minute marks. */
    const val TOLERANCE_SEC: Long = 30L

    /** Workouts in the trend. */
    const val TREND_WORKOUTS: Int = 10

    /** Readings below this are no measurement. */
    private const val MIN_BPM = 25

    /**
     * The recovery after a workout that ended at [endSec], from heart-rate readings [samples] (time,
     * bpm); null when there is no reading at the end or one minute later.
     */
    fun evaluate(endSec: Long, samples: List<Pair<Long, Int>>): HrRecovery? {
        val valid = samples.filter { it.second >= MIN_BPM }
        val end = valid.filter { it.first in (endSec - END_BEFORE_SEC)..(endSec + END_AFTER_SEC) }
            .minByOrNull { abs(it.first - endSec) }?.second ?: return null
        fun at(mark: Long): Int? = valid.filter { abs(it.first - mark) <= TOLERANCE_SEC }.minByOrNull { abs(it.first - mark) }?.second
        val one = at(endSec + ONE_MIN) ?: return null
        val two = at(endSec + 2 * ONE_MIN)
        return HrRecovery(end, end - one, two?.let { end - it })
    }

    private const val ONE_MIN = 60L
}

/** Reads the recovery card's numbers. */
class RecoveryHrLoader(private val db: SelfWatchDb) {

    /** The latest workout's recovery and the last workouts' 1-minute drops. */
    suspend fun load(): RecoveryInfo {
        val workouts = db.workouts.list(RecoveryHrMath.TREND_WORKOUTS)
        if (workouts.isEmpty()) return RecoveryInfo(null, null, emptyList())
        val results = workouts.map { it to recovery(it) }
        // The newest workout with readings after it (a strength set or a swim often has none).
        val (latest, recovery) = results.firstOrNull { it.second != null } ?: results.first()
        return RecoveryInfo(latest, recovery, results.reversed().mapNotNull { it.second?.oneMinDrop })
    }

    private suspend fun recovery(w: Workout): HrRecovery? {
        val range = TimeRange(w.endSec - RecoveryHrMath.END_BEFORE_SEC, w.endSec + 2 * 60 + RecoveryHrMath.TOLERANCE_SEC + 1)
        val samples = db.metrics.series(MetricKind.HEART_RATE, range).map { it.tSec to it.value }
        return RecoveryHrMath.evaluate(w.endSec, samples)
    }
}
