/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.AppGraph
import app.selfwatch.activity.ActivityGoals
import app.selfwatch.activity.ActivityWeek
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.devices.WatchCard
import app.selfwatch.food.budget.EnergyBudget
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DaySummary
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.SystemClock
import app.selfwatch.model.time.TimeRange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Weight for the Body card.
 *
 * @property weekChangeX100 trend change over the last 7 days (hundredths of a kg; negative is a loss).
 * @property longChangeX100 trend change over the last 90 days.
 * @property spark the trend over the last 30 days, for the sparkline.
 */
data class WeightInfo(
    val latestKgX100: Int,
    val latestSec: Long,
    val weekChangeX100: Int?,
    val longChangeX100: Int?,
    val goalKgX100: Int?,
    val spark: List<Int>,
)

/** One measurement tile: latest value (kind unit ×100) and its change over 30 days. */
data class MeasureTile(val kind: BodyKind, val valueX100: Int?, val changeX100: Int?)

/** Last night's sleep, today's resting heart rate and last night's HRV. */
data class Recovery(val sleepMin: Int?, val restingHr: Int?, val hrvMs: Int?)

/**
 * Today's energy and the last 7 days (oldest first), in vs out, and [balanceKcal], the average daily
 * balance (in − out) over the complete days of the week (an estimate; null with too few days logged).
 */
data class EnergyWeek(val energy: Energy, val week: List<DayEnergy>, val balanceKcal: Int? = null)

/**
 * A day's macros in grams (null when nothing with that nutrient is logged) and their targets.
 *
 * @property proteinGoalG the protein goal, else 1.6 g per kg of the latest weight, else null.
 * @property proteinGoalAuto true when [proteinGoalG] comes from body weight, not a set goal.
 */
data class MacrosInfo(
    val proteinG: Int?,
    val carbG: Int?,
    val fatG: Int?,
    val proteinGoalG: Int?,
    val proteinGoalAuto: Boolean,
    val carbGoalG: Int?,
    val fatGoalG: Int?,
) {
    /** True when nothing is logged that day. */
    val isEmpty: Boolean get() = proteinG == null && carbG == null && fatG == null
}

/** Vigorous minutes today and over the last 7 days, and the weekly goal. */
data class IntensityInfo(val todayMin: Int, val weekMin: Int, val weekGoalMin: Int)

/**
 * Steps today against the daily step goal (default 10,000), and active minutes this week (Monday to
 * today) against the weekly active-minutes goal (default 300). Values are null when not known.
 */
data class StepsInfo(val steps: Int?, val stepsGoal: Int?, val activeMin: Int?, val activeMinGoal: Int?) {
    /** True with no activity data today. */
    val isEmpty: Boolean get() = steps == null && activeMin == null
}

/** Blood oxygen: the latest night's average (%), the day that night ended, and up to 30 nightly averages (oldest first). */
data class Spo2Info(val lastPct: Int, val lastDay: Day, val nights: List<Int>)

/** The watch chip at the top of Body. */
data class WatchChip(val name: String, val batteryPct: Int?, val lastSyncSec: Long?, val connected: Boolean)

/** Body (home) screen state outside the cards: the date and the watch chip. */
data class BodyUiState(
    /** Today. */
    val today: Day,
    /** True until the first load finishes. */
    val loading: Boolean = true,
    /** The paired watch, or null. */
    val watch: WatchChip? = null,
)

/** Reads the Body cards' numbers from the database, one card at a time. */
class BodyLoader(private val db: SelfWatchDb, private val clock: Clock) {

    /** Food eaten on [day] from the diary, or null when nothing is logged. */
    private suspend fun eatenKcal(day: Day): Int? =
        db.diary.dayTotals(day).kcalX10?.let { (it + 5) / 10 }

    private suspend fun summary(day: Day): DaySummary? = db.summaries.days(DayRange(day, day)).firstOrNull()

    /** Today's energy and the 7-day bars. */
    suspend fun energy(): EnergyWeek {
        val today = clock.today()
        val summaries = db.summaries.days(DayRange(today.plusDays(-6), today)).associateBy { it.day }
        val s = summaries[today]
        // Same goal and food total as the Food screen: the food side is read from the diary itself, so
        // it is current even when the watch service (which rebuilds day summaries) is not running.
        val budget = EnergyBudget(db, clock).today()
        val eatenToday = eatenKcal(today)
        val energy = Energy(budget.goalKcal, eatenToday, s?.activeKcal, s?.restingKcal)
        val week = (0..6).map { i ->
            val d = today.plusDays(i - 6)
            val ds = summaries[d]
            val out = if (ds?.activeKcal == null && ds?.restingKcal == null) -1 else (ds.activeKcal ?: 0) + (ds.restingKcal ?: 0)
            DayEnergy(d.yyyymmdd, (if (d == today) eatenToday else eatenKcal(d)) ?: -1, out)
        }
        return EnergyWeek(energy, week, BodyCardMath.weekBalance(week))
    }

    /** Weight, or null with no weigh-ins. */
    suspend fun weight(): WeightInfo? {
        val today = clock.today()
        val weights = db.body.series(BodyKind.WEIGHT, DayRange(today.plusDays(-LONG_DAYS), today), clock.zone())
        val latest = db.body.latest(BodyKind.WEIGHT) ?: return null
        val recent = weights.filter { it.tSec >= latest.tSec - SPARK_DAYS * DAY_SEC }
        val trend = WeightMath.trend(LongArray(recent.size) { recent[it].tSec }, IntArray(recent.size) { recent[it].valueX100 })
        return WeightInfo(
            latestKgX100 = latest.valueX100,
            latestSec = latest.tSec,
            weekChangeX100 = WeightMath.trendChange(weights, 7),
            longChangeX100 = WeightMath.trendChange(weights, LONG_DAYS),
            goalKgX100 = db.goals.goalOn(GoalKind.WEIGHT_KG_X100, today)?.value,
            spark = trend.map { it.roundToInt() },
        )
    }

    /** Waist, hips and body fat with their 30-day change. */
    suspend fun measures(): List<MeasureTile> {
        val today = clock.today()
        return TILE_KINDS.map { kind ->
            val l = db.body.latest(kind)
            val series = db.body.series(kind, DayRange(today.plusDays(-LONG_DAYS), today), clock.zone())
            MeasureTile(kind, l?.valueX100, l?.let { changeOver(series, it, MEASURE_DAYS) })
        }
    }

    /** Last night's sleep, today's resting heart rate and HRV. */
    suspend fun recovery(): Recovery {
        val today = clock.today()
        val s = summary(today)
        val restingHr = s?.restingHr ?: db.metrics.latest(MetricKind.RESTING_HR)
            ?.takeIf { it.tSec >= today.plusDays(-1).startSec(clock.zone()) }?.value
        return Recovery(s?.sleepMin, restingHr, s?.hrvAvgMs)
    }

    /** Macros eaten on [day] against that day's targets. */
    suspend fun macros(day: Day = clock.today()): MacrosInfo {
        val t = db.diary.dayTotals(day)
        val proteinGoal = db.goals.goalOn(GoalKind.PROTEIN_G, day)?.value
        val autoProtein = if (proteinGoal == null) db.body.latest(BodyKind.WEIGHT)?.valueX100?.let { proteinFromWeight(it) } else null
        return MacrosInfo(
            proteinG = grams(t.proteinX10),
            carbG = grams(t.carbX10),
            fatG = grams(t.fatX10),
            proteinGoalG = proteinGoal ?: autoProtein,
            proteinGoalAuto = autoProtein != null,
            carbGoalG = db.goals.goalOn(GoalKind.CARB_G, day)?.value,
            fatGoalG = db.goals.goalOn(GoalKind.FAT_G, day)?.value,
        )
    }

    /** Vigorous minutes, or null with no activity data this week. */
    suspend fun intensity(): IntensityInfo? {
        val today = clock.today()
        val zone = clock.zone()
        val minutes = db.activity.minutes(TimeRange(today.plusDays(-6).startSec(zone), today.plusDays(1).startSec(zone)))
        if (minutes.isEmpty()) return null
        val todayStart = today.startSec(zone)
        return IntensityInfo(
            todayMin = vigorousMinutes(minutes.filter { it.tSec >= todayStart }),
            weekMin = vigorousMinutes(minutes),
            weekGoalMin = VIGOROUS_WEEK_MIN,
        )
    }

    /** Steps today and active minutes this week. */
    suspend fun steps(): StepsInfo {
        val today = clock.today()
        val range = DayRange(ActivityWeek.start(today), today)
        val summaries = db.summaries.days(range).associateBy { it.day }
        val totals = db.activity.totalsByDay(range, clock.zone()).associateBy { it.day }
        val s = summaries[today]
        var weekActive: Int? = null
        for (d in range.days()) {
            val m = summaries[d]?.activeMin ?: totals[d]?.activeMin ?: continue
            weekActive = (weekActive ?: 0) + m
        }
        return StepsInfo(
            steps = s?.steps ?: totals[today]?.steps?.toInt(),
            stepsGoal = ActivityGoals.steps(db, today),
            activeMin = weekActive,
            activeMinGoal = ActivityGoals.activeMinWeek(db, today),
        )
    }

    /** Blood oxygen, or null with no readings in 30 days. */
    suspend fun spo2(): Spo2Info? {
        val today = clock.today()
        val zone = clock.zone()
        val from = today.plusDays(-(SPARK_DAYS - 1)).startSec(zone) - NIGHT_SHIFT_SEC
        val samples = db.metrics.series(MetricKind.SPO2, TimeRange(from, today.plusDays(1).startSec(zone)))
        val nights = nightlyAverages(samples, zone)
        val last = nights.lastOrNull() ?: return null
        return Spo2Info(last.second, last.first, nights.map { it.second })
    }

    /** Pure helpers. */
    companion object {
        /** The kinds on the Body measurements card. */
        val TILE_KINDS: List<BodyKind> = listOf(BodyKind.WAIST, BodyKind.HIPS, BodyKind.BODY_FAT)

        private const val DAY_SEC = 86_400L
        private const val LONG_DAYS = 90
        private const val SPARK_DAYS = 30
        private const val MEASURE_DAYS = 30

        /** Weekly vigorous minutes recommended for adults. */
        const val VIGOROUS_WEEK_MIN: Int = 75

        /** Intensity of a vigorous minute ([ActivityMinute.intensity]: 0 sedentary … 3 vigorous). */
        private const val VIGOROUS = 3

        /** Readings from 18:00 belong to the next morning's night. */
        private const val NIGHT_SHIFT_SEC = 6 * 3_600L

        /** Protein from body weight: 1.6 g per kg, in tenths. */
        private const val PROTEIN_G_PER_KG_X10 = 16

        /** Grams from tenths of a gram, rounded; null stays null. */
        fun grams(x10: Int?): Int? = x10?.let { (it + 5) / 10 }

        /** 1.6 g of protein per kg of [kgX100], rounded. */
        fun proteinFromWeight(kgX100: Int): Int = (kgX100 * PROTEIN_G_PER_KG_X10 + 500) / 1_000

        /** How many of [minutes] were vigorous. */
        fun vigorousMinutes(minutes: List<ActivityMinute>): Int = minutes.count { it.intensity >= VIGOROUS }

        /**
         * Average of [samples] per night, oldest first. A night is named by the day it ends: readings
         * from 18:00 the evening before until 18:00 that day.
         */
        fun nightlyAverages(samples: List<MetricSample>, zone: ZoneId): List<Pair<Day, Int>> =
            samples.groupBy { Day.of(it.tSec + NIGHT_SHIFT_SEC, zone) }
                .toSortedMap()
                .map { (day, list) -> day to ((list.sumOf { it.value.toLong() } * 2 + list.size) / (2L * list.size)).toInt() }

        /**
         * Change of [latest] against the newest reading at least [days] days older (else the oldest
         * reading in [series]); null when there is nothing older.
         */
        fun changeOver(series: List<BodyMeasure>, latest: BodyMeasure, days: Int): Int? {
            val older = series.filter { it.tSec < latest.tSec }
            if (older.isEmpty()) return null
            val cutoff = latest.tSec - days * DAY_SEC
            val base = older.lastOrNull { it.tSec <= cutoff } ?: older.first()
            return latest.valueX100 - base.valueX100
        }

        /** The chip for [card] (null when no watch is paired). */
        fun chipOf(card: WatchCard?): WatchChip? = card?.let {
            WatchChip(it.name, it.batteryPct, it.lastSyncSec, it.state == ConnectionState.Ready || it.state is ConnectionState.Syncing)
        }
    }
}


/** Body (home) view model for what sits outside the cards: today's date and the watch chip. */
class BodyViewModel(
    db: SelfWatchDb,
    clock: Clock,
    watch: Flow<WatchCard?>,
) : ViewModel() {

    /** Screen state. */
    val state: StateFlow<BodyUiState> = combine(
        db.changes.observe(TABLES).conflate(),
        watch.catch { emit(null) },
    ) { _, card -> BodyUiState(clock.today(), loading = false, watch = BodyLoader.chipOf(card)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), BodyUiState(clock.today()))

    /** Factory with the graph. */
    companion object {
        private const val STOP_MS = 5_000L

        /** Tables whose changes refresh the date (a new day's summary) and the chip. */
        val TABLES: Set<Table> = setOf(Table.DAY_SUMMARY, Table.DEVICE)

        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { BodyViewModel(graph.db, SystemClock, graph.devicesSource.watch) }
        }

        /** A view model with no watch source (tests). */
        fun withoutWatch(db: SelfWatchDb, clock: Clock): BodyViewModel = BodyViewModel(db, clock, flowOf(null))
    }
}
