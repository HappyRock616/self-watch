/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingsStore
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.TrackPoint
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.TimeRange
import kotlin.math.roundToInt

/**
 * One layer of the per-sport dashboard ("All activities" with a sport picked). [id] is stored in the
 * per-sport choice; never change it. Raw values: heart rate and recovery in bpm, calories in kcal,
 * distance and climb in metres, duration in seconds, [PACE] as average speed in mm/s (shown as pace for
 * runs, walks and hikes and as speed otherwise), cadence in steps or revolutions a minute, [VO2_MAX] in
 * ml/kg/min ×10 (runs and rides only).
 */
enum class SportMetric(val id: String) {
    AVG_HR("avg_hr"),
    MAX_HR("max_hr"),
    KCAL("kcal"),
    DISTANCE("distance"),
    DURATION("duration"),
    PACE("pace"),
    RECOVERY("recovery"),
    ASCENT("ascent"),
    CADENCE("cadence"),

    // Last: the chart colour follows the ordinal, so new layers go at the end.
    VO2_MAX("vo2_max"),
    ;

    /** Lookup. */
    companion object {
        /** Every id, in chip order. */
        val IDS: List<String> = entries.map { it.id }
    }
}

/** One workout on the dashboard and its raw values (only the metrics it has). */
data class SportPoint(val workout: Workout, val values: Map<SportMetric, Int>)

/**
 * The dashboard of one [sport]: its last workouts, oldest first. [metrics] are the metrics at least one of
 * them has (a chip each). VO2 max shows on runs and rides once the watch has sent an estimate; power joins
 * when a device sends it (none is stored yet).
 */
data class SportDashboard(val sport: Int, val points: List<SportPoint>) {
    /** Metrics with a value on at least one workout, in chip order. */
    val metrics: List<SportMetric> get() = SportMetric.entries.filter { m -> points.any { m in it.values } }

    /** True with too few workouts for a trend. */
    val tooFew: Boolean get() = points.size < SportTrendMath.MIN_WORKOUTS
}

/** Pure dashboard arithmetic. */
object SportTrendMath {
    /** Workouts on the dashboard (the most recent of the sport). */
    const val WORKOUTS: Int = 20

    /** Workouts needed for a trend. */
    const val MIN_WORKOUTS: Int = 2

    /** Workouts in the trend line's moving average. */
    const val TREND_WINDOW: Int = 3

    /**
     * How long after a workout's end a VO2 max update still belongs to it: the watch updates its estimate
     * when the workout is saved, and the metrics file can stamp it a little later.
     */
    const val VO2_AFTER_SEC: Long = 3 * 3600L

    /**
     * The raw values of [w], with its heart-rate [recoveryDrop] (1-minute drop), average [cadence] and the
     * VO2 max ([vo2X10], ml/kg/min ×10) when known.
     */
    fun values(w: Workout, recoveryDrop: Int?, cadence: Int?, vo2X10: Int? = null): Map<SportMetric, Int> = buildMap {
        w.avgHr?.takeIf { it > 0 }?.let { put(SportMetric.AVG_HR, it) }
        w.maxHr?.takeIf { it > 0 }?.let { put(SportMetric.MAX_HR, it) }
        w.kcal?.takeIf { it > 0 }?.let { put(SportMetric.KCAL, it) }
        val dist = w.distanceM?.takeIf { it > 0 }
        dist?.let { put(SportMetric.DISTANCE, it) }
        if (w.durationSec > 0) put(SportMetric.DURATION, w.durationSec)
        if (dist != null && w.durationSec > 0) put(SportMetric.PACE, (dist * 1000.0 / w.durationSec).roundToInt())
        recoveryDrop?.let { put(SportMetric.RECOVERY, it) }
        w.ascentM?.let { put(SportMetric.ASCENT, it) }
        cadence?.takeIf { it > 0 }?.let { put(SportMetric.CADENCE, it) }
        vo2X10?.takeIf { it > 0 }?.let { put(SportMetric.VO2_MAX, it) }
    }

    /** The VO2 max kind the dashboard of [sport] shows: running for runs, cycling for rides, else none. */
    fun vo2Kind(sport: Int): MetricKind? = when (sport) {
        Sports.RUNNING -> MetricKind.VO2_MAX_RUNNING
        Sports.CYCLING -> MetricKind.VO2_MAX_CYCLING
        else -> null
    }

    /**
     * The VO2 max (×10) the watch showed after [w]: the newest of [samples] (oldest first) stamped no
     * later than [VO2_AFTER_SEC] after the workout ended; null when none is that old.
     */
    fun vo2For(w: Workout, samples: List<MetricSample>): Int? =
        samples.lastOrNull { it.tSec <= w.endSec + VO2_AFTER_SEC }?.value

    /** The last [WORKOUTS] of [sport] in [workouts] (any order), oldest first. */
    fun recent(workouts: List<Workout>, sport: Int): List<Workout> =
        workouts.filter { it.sport == sport }.sortedByDescending { it.startSec }.take(WORKOUTS).reversed()

    /** Average cadence of the points that have one (zeros, when coasting or standing, left out), or null. */
    fun cadence(points: List<TrackPoint>): Int? {
        val c = points.mapNotNull { it.cadence?.takeIf { v -> v in 1..254 } }
        return if (c.isEmpty()) null else (c.sumOf { it.toLong() }.toDouble() / c.size).roundToInt()
    }

    /** A centred moving average of [v] over [window] values (the dashboard's trend line). */
    fun trend(v: IntArray, window: Int = TREND_WINDOW): IntArray = WorkoutMath.smooth(v, window)

    /**
     * Pace in whole seconds per km ([perUnitM] = 1000) or per mile from a speed in mm/s; null when
     * standing. The chart draws it with scale 60 (minutes) and writes it as m:ss.
     */
    fun paceSec(speedMms: Int, perUnitM: Double): Int? {
        if (speedMms <= 0) return null
        return (perUnitM * 1000.0 / speedMms).roundToInt()
    }

    /** The layers shown before the user picks: average heart rate and distance (duration without distance). */
    fun defaultOn(metrics: Collection<SportMetric>): Set<SportMetric> {
        val second = if (SportMetric.DISTANCE in metrics) SportMetric.DISTANCE else SportMetric.DURATION
        return setOf(SportMetric.AVG_HR, second)
    }
}

/**
 * The per-sport dashboard choices, in settings: the layers hidden for each sport
 * (`chart.sport_trend.<sport>.layers`, a comma list of [SportMetric] ids; absent = [SportTrendMath.defaultOn])
 * and the sports whose dashboard was removed ([REMOVED_KEY], a comma list of FIT sport codes).
 */
object SportDashboardPrefs {
    /** Sports whose dashboard the user removed. */
    const val REMOVED_KEY: String = "activity.sport_dashboard.removed"

    /** The settings key of [sport]'s hidden layers. */
    fun layersKey(sport: Int): String = "chart.sport_trend.$sport.layers"

    /**
     * The hidden ids stored as [stored]; null gives the defaults for a sport whose workouts have [metrics]
     * ([SportTrendMath.defaultOn]). Unknown ids are dropped.
     */
    fun decodeHidden(stored: String?, metrics: Collection<SportMetric> = SportMetric.entries): Set<String> {
        if (stored == null) {
            val on = SportTrendMath.defaultOn(metrics).map { it.id }
            return SportMetric.IDS.filter { it !in on }.toSet()
        }
        return stored.split(',').map { it.trim() }.filter { it in SportMetric.IDS }.toSet()
    }

    /** [hidden] as stored, in chip order. */
    fun encodeHidden(hidden: Set<String>): String = SportMetric.IDS.filter { it in hidden }.joinToString(",")

    /** The removed sports stored as [stored]. */
    fun decodeRemoved(stored: String?): Set<Int> = stored?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet().orEmpty()

    /** [removed] as stored (sorted). */
    fun encodeRemoved(removed: Set<Int>): String? = removed.sorted().joinToString(",").ifEmpty { null }

    /** [sport]'s hidden layer ids (defaults from [metrics], the metrics its workouts have). */
    suspend fun hidden(settings: SettingsStore, sport: Int, metrics: Collection<SportMetric>): Set<String> =
        decodeHidden(settings.get(layersKey(sport)), metrics)

    /** Saves [sport]'s hidden layer ids. */
    suspend fun saveHidden(settings: SettingsStore, sport: Int, hidden: Set<String>) {
        settings.set(layersKey(sport), encodeHidden(hidden))
    }

    /** The sports whose dashboard is removed. */
    suspend fun removed(settings: SettingsStore): Set<Int> = decodeRemoved(settings.get(REMOVED_KEY))

    /** Removes (or with [shown] brings back) [sport]'s dashboard. */
    suspend fun setShown(settings: SettingsStore, sport: Int, shown: Boolean) {
        val now = removed(settings)
        val next = if (shown) now - sport else now + sport
        if (next != now) settings.setAll(mapOf(REMOVED_KEY to encodeRemoved(next)))
    }
}

/**
 * Builds a sport's dashboard: heart-rate recovery from the stored heart rate after each workout, cadence
 * from each workout's track (decoded once per workout and kept in [cadenceCache]), and for runs and rides
 * the VO2 max the watch showed after each one (one metric read for the whole dashboard).
 */
class SportDashboardLoader(
    private val db: SelfWatchDb,
    private val track: suspend (Workout) -> List<TrackPoint>,
    private val cadenceCache: MutableMap<Long, Int> = java.util.concurrent.ConcurrentHashMap(),
) {
    /** The dashboard of [sport] from [workouts] (the list shown). */
    suspend fun load(sport: Int, workouts: List<Workout>): SportDashboard {
        val recent = SportTrendMath.recent(workouts, sport)
        val vo2 = vo2Samples(sport, recent)
        val points = recent.map { w ->
            SportPoint(w, SportTrendMath.values(w, recovery(w), cadence(w), SportTrendMath.vo2For(w, vo2)))
        }
        return SportDashboard(sport, points)
    }

    /**
     * The sport's VO2 max samples up to the last workout's [SportTrendMath.VO2_AFTER_SEC], from up to a
     * year before the first (an estimate from before the dashboard's window still is the one the watch
     * showed then).
     */
    private suspend fun vo2Samples(sport: Int, recent: List<Workout>): List<MetricSample> {
        val kind = SportTrendMath.vo2Kind(sport) ?: return emptyList()
        if (recent.isEmpty()) return emptyList()
        val from = recent.first().startSec - VO2_LOOKBACK_SEC
        val to = recent.maxOf { it.endSec } + SportTrendMath.VO2_AFTER_SEC + 1
        return db.metrics.series(kind, TimeRange(from, to))
    }

    private suspend fun recovery(w: Workout): Int? {
        val range = TimeRange(w.endSec - RecoveryHrMath.END_BEFORE_SEC, w.endSec + 2 * 60 + RecoveryHrMath.TOLERANCE_SEC + 1)
        val samples = db.metrics.series(MetricKind.HEART_RATE, range).map { it.tSec to it.value }
        return RecoveryHrMath.evaluate(w.endSec, samples)?.oneMinDrop
    }

    private suspend fun cadence(w: Workout): Int? {
        if (w.fitPath == null && w.trackPath == null) return null
        // Keyed by id and end, so a trim (which moves the end) reads the track again.
        val key = w.id * 31 + w.endSec
        cadenceCache[key]?.let { return it.takeIf { c -> c > 0 } }
        val c = SportTrendMath.cadence(track(w))
        cadenceCache[key] = c ?: 0
        return c
    }

    private companion object {
        const val VO2_LOOKBACK_SEC = 365L * 86_400L
    }
}
