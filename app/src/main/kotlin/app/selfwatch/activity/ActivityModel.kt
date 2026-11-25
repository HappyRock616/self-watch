/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.AppGraph
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.db.dao.PeriodSummary
import app.selfwatch.me.GoalField
import app.selfwatch.model.food.Goal
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.health.DaySummary
import app.selfwatch.model.health.Workout
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.SystemClock
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.coroutines.cancellation.CancellationException

/**
 * The rings: today's steps and active kcal against their daily goals, and this week's active minutes
 * (Monday to today) against the weekly active-minutes goal.
 */
data class Rings(
    val steps: Int = 0,
    val stepGoal: Int = ActivityRules.DEFAULT_STEPS,
    val activeMin: Int = 0,
    val activeMinGoal: Int = ActivityRules.DEFAULT_ACTIVE_MIN,
    val activeKcal: Int = 0,
    val activeKcalGoal: Int = ActivityRules.DEFAULT_ACTIVE_KCAL,
) {
    /** Progress toward each goal (0..∞; 1 is the goal reached). */
    fun progress(value: Int, goal: Int): Float = if (goal > 0) value.toFloat() / goal else 0f
}

/** Long-trend range: last 7 days, last 30 days (daily bars), last 12 months or everything (monthly). */
enum class TrendRange {
    WEEK,
    MONTH,
    YEAR,
    ALL,
    ;

    /** Stored value in [KEY]. */
    val id: String get() = name.lowercase()

    /** Lookup and the setting. */
    companion object {
        /** Setting: the range chosen on the Trends card (`week`, `month`, `year`, `all`; no default: month). */
        const val KEY: String = "activity.trend_range"

        /** The range stored as [id], else [MONTH]. */
        fun byId(id: String?): TrendRange = entries.firstOrNull { it.id == id } ?: MONTH
    }
}

/** Trend series the chips switch on and off. [id] keys the chip. */
enum class TrendMetric(val id: String) { DISTANCE("distance"), ACTIVE_MIN("active_min"), STEPS("steps"), ACTIVE_KCAL("active_kcal") }

/**
 * The trend chart data.
 *
 * @property daily true for one bar per day; false for one bar per month showing the daily average.
 * @property starts the first day of each bar.
 * @property values per metric, one raw value per bar (distance in metres); -1 for no data.
 * @property goals per metric, the goal line per bar (null for none). Every bar is a day or a daily average,
 *   so the active-minutes line is a seventh of the weekly goal.
 */
data class Trend(
    val range: TrendRange,
    val daily: Boolean,
    val starts: List<Day>,
    val values: Map<TrendMetric, List<Int>>,
    val goals: Map<TrendMetric, Int?>,
) {
    /** True when no bar has data. */
    val isEmpty: Boolean get() = values.values.all { list -> list.all { it < 0 } }
}

/** Activity screen state outside the cards: the step-goal streak in the toolbar. */
data class ActivityUiState(
    val today: Day,
    /** Consecutive days meeting the step goal. */
    val streak: Int = 0,
    val loading: Boolean = true,
)

/** Pure activity rules: goals over time, the streak and the trend bars. */
object ActivityRules {
    /** Step goal per day before one is set. */
    const val DEFAULT_STEPS: Int = ActivityGoals.DEFAULT_STEPS

    /** Active minutes goal per week before one is set. */
    const val DEFAULT_ACTIVE_MIN: Int = ActivityGoals.DEFAULT_ACTIVE_MIN_WEEK

    /** Active kcal goal before one is set. */
    const val DEFAULT_ACTIVE_KCAL: Int = 400

    /** The goal of [history] (oldest first) in force on [day], else [default]. */
    fun goalOn(history: List<Goal>, day: Day, default: Int): Int = history.lastOrNull { it.fromDay <= day }?.value ?: default

    /**
     * The streak of days meeting the step goal: consecutive days back from yesterday where steps reached
     * that day's goal, plus today once today's goal is reached (today is never held against the streak
     * while it is still going). [steps] maps days to step totals (missing days break the streak).
     */
    fun streak(today: Day, steps: Map<Day, Int>, goal: (Day) -> Int): Int {
        var n = 0
        val todaySteps = steps[today]
        if (todaySteps != null && todaySteps >= goal(today)) n++
        var d = today.plusDays(-1)
        while (true) {
            val s = steps[d] ?: break
            if (s < goal(d)) break
            n++
            d = d.plusDays(-1)
        }
        return n
    }

    /** Daily bars for [days] from [summaries]. */
    fun daily(range: TrendRange, days: DayRange, summaries: List<DaySummary>, goals: Map<TrendMetric, Int?>): Trend {
        val by = summaries.associateBy { it.day }
        val list = days.days()
        fun col(f: (DaySummary) -> Int?): List<Int> = list.map { d -> by[d]?.let(f) ?: -1 }
        return Trend(
            range,
            daily = true,
            starts = list,
            values = mapOf(
                TrendMetric.DISTANCE to col { it.distanceM },
                TrendMetric.ACTIVE_MIN to col { it.activeMin },
                TrendMetric.STEPS to col { it.steps },
                TrendMetric.ACTIVE_KCAL to col { it.activeKcal },
            ),
            goals = goals,
        )
    }

    /**
     * Monthly bars (daily averages) for the months starting at [firstMonth] up to the month of [today];
     * months without data get -1.
     */
    fun monthly(range: TrendRange, firstMonth: Day, today: Day, months: List<PeriodSummary>, goals: Map<TrendMetric, Int?>): Trend {
        val by = months.associateBy { it.first }
        val starts = ArrayList<Day>()
        var m = Day(firstMonth.year * 10_000 + firstMonth.month * 100 + 1)
        while (m <= today) {
            starts += m
            m = Day.of(m.toLocalDate().plusMonths(1))
        }
        fun col(f: (PeriodSummary) -> Long?): List<Int> = starts.map { s -> by[s]?.let(f)?.toInt() ?: -1 }
        return Trend(
            range,
            daily = false,
            starts = starts,
            values = mapOf(
                TrendMetric.DISTANCE to col { it.avg.distanceM },
                TrendMetric.ACTIVE_MIN to col { it.avg.activeMin },
                TrendMetric.STEPS to col { it.avg.steps },
                TrendMetric.ACTIVE_KCAL to col { it.avg.activeKcal },
            ),
            goals = goals,
        )
    }
}

/** Reads the Activity cards and the streak from the database. */
class ActivityLoader(private val db: SelfWatchDb, private val clock: Clock) {

    /** Today's rings against the goals in force today; active minutes are this week's (Monday to today). */
    suspend fun rings(): Rings {
        val today = clock.today()
        val g = goals(today)
        val week = db.summaries.days(DayRange(ActivityWeek.start(today), today))
        val s = week.firstOrNull { it.day == today }
        val weekActive = week.sumOf { it.activeMin ?: 0 }
        return Rings(s?.steps ?: 0, g.steps, weekActive, g.activeMinWeek, s?.activeKcal ?: 0, g.activeKcal)
    }

    /** Consecutive days meeting the step goal (see [ActivityRules.streak]). */
    suspend fun streak(): Int {
        val today = clock.today()
        val stepHistory = db.goals.history(GoalKind.STEPS)
        val year = db.summaries.days(DayRange(today.plusDays(-STREAK_DAYS), today))
        val steps = year.mapNotNull { d -> d.steps?.let { d.day to it } }.toMap()
        return ActivityRules.streak(today, steps) { ActivityRules.goalOn(stepHistory, it, ActivityRules.DEFAULT_STEPS) }
    }

    /** The trend bars for [range]. */
    suspend fun trend(range: TrendRange): Trend {
        val today = clock.today()
        val g = goals(today)
        val goals = mapOf(
            TrendMetric.DISTANCE to null,
            TrendMetric.ACTIVE_MIN to ActivityGoals.dailyShare(g.activeMinWeek),
            TrendMetric.STEPS to g.steps,
            TrendMetric.ACTIVE_KCAL to g.activeKcal,
        )
        return when (range) {
            TrendRange.WEEK, TrendRange.MONTH -> {
                val days = DayRange(today.plusDays(if (range == TrendRange.WEEK) -6 else -29), today)
                ActivityRules.daily(range, days, db.summaries.days(days), goals)
            }
            TrendRange.YEAR -> {
                val first = Day.of(today.toLocalDate().withDayOfMonth(1).minusMonths(11))
                ActivityRules.monthly(range, first, today, db.summaries.months(DayRange(first, today)), goals)
            }
            TrendRange.ALL -> {
                val months = db.summaries.months(DayRange(EPOCH, today))
                val first = months.firstOrNull { it.sum.steps != null || it.sum.distanceM != null }?.first ?: Day.of(today.toLocalDate().withDayOfMonth(1))
                ActivityRules.monthly(range, first, today, months, goals)
            }
        }
    }

    /** The latest workouts, newest first. */
    suspend fun workouts(): List<Workout> = db.workouts.list(RECENT)

    /** The trend range chosen on the Trends card (Month until one is chosen). */
    suspend fun trendRange(): TrendRange = TrendRange.byId(db.settings.get(TrendRange.KEY))

    private suspend fun goals(today: Day): DayGoals = DayGoals(
        steps = ActivityRules.goalOn(db.goals.history(GoalKind.STEPS), today, ActivityRules.DEFAULT_STEPS),
        activeMinWeek = ActivityGoals.activeMinWeek(db, today),
        activeKcal = db.settings.getInt(GoalField.ACTIVE_KCAL_KEY) ?: ActivityRules.DEFAULT_ACTIVE_KCAL,
    )

    private class DayGoals(val steps: Int, val activeMinWeek: Int, val activeKcal: Int)

    private companion object {
        val EPOCH = Day(19700101)
        const val STREAK_DAYS = 400
        const val RECENT = 5
    }
}

/** Activity view model for the toolbar's streak chip (the cards load themselves). */
class ActivityViewModel(db: SelfWatchDb, clock: Clock) : ViewModel() {
    private val loader = ActivityLoader(db, clock)

    /** Screen state. */
    val state: StateFlow<ActivityUiState> = db.changes.observe(TABLES).conflate()
        .map {
            try {
                ActivityUiState(clock.today(), loader.streak(), loading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                SwLog.e(TAG, "activity load failed", e)
                ActivityUiState(clock.today(), loading = false)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), ActivityUiState(clock.today()))

    /** Factory. */
    companion object {
        private const val TAG = "ActivityViewModel"
        private const val STOP_MS = 5_000L

        /** Tables that refresh the streak. */
        val TABLES: Set<Table> = setOf(Table.DAY_SUMMARY, Table.GOAL)

        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { ActivityViewModel(graph.db, SystemClock) }
        }
    }
}
