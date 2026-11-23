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
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.db.dao.MeasureKind
import app.selfwatch.model.food.Goal
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.SystemClock
import app.selfwatch.shell.DisplayUnits
import app.selfwatch.ui.format.Units
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/** The first day the "All" ranges read from. */
internal val EPOCH_DAY: Day = Day(19700101)

/** Weight screen ranges. */
enum class WeightRange(
    /** Days shown, or null for everything. */
    val days: Int?,
) {
    D30(30), D90(90), Y1(365), ALL(null)
}

/**
 * Weight detail state.
 *
 * @property entries weigh-ins in the range, oldest first.
 * @property trendKgX100 the smoothed weight at the newest weigh-in.
 * @property bmi body-mass index from the latest weight and the profile height, or null.
 * @property trend the smoothed weight at each of [entries] (kg ×100).
 * @property firstDay the chart's first day (for "All", the first weigh-in's day; null without weigh-ins).
 * @property overlay calories and activity over [firstDay]..[lastDay], or null without weigh-ins.
 * @property hiddenLayers chart layers the user switched off ([WeightLayers]).
 */
data class WeightUiState(
    val range: WeightRange = WeightRange.D90,
    val units: Units = Units.METRIC,
    val entries: List<BodyMeasure> = emptyList(),
    val trendKgX100: Int? = null,
    val goal: Goal? = null,
    val bmi: Double? = null,
    val firstDay: Day? = null,
    val lastDay: Day? = null,
    val loading: Boolean = true,
    val trend: List<Int> = emptyList(),
    val overlay: WeightOverlay? = null,
    val hiddenLayers: Set<String> = WeightLayers.DEFAULT_HIDDEN,
)

/** Pure weight-screen rules. */
object WeightRules {
    /** BMI from [kgX100] and [heightCm], or null when either is missing. */
    fun bmi(kgX100: Int?, heightCm: Int?): Double? {
        if (kgX100 == null || heightCm == null || heightCm <= 0) return null
        val m = heightCm / 100.0
        return (kgX100 / 100.0) / (m * m)
    }
}

/** Weight detail view model: range, weigh-ins, trend, goal and BMI; log, edit, delete and goal changes. */
class WeightViewModel(private val db: SelfWatchDb, private val clock: Clock) : ViewModel() {
    private val rangeFlow = MutableStateFlow(WeightRange.D90)

    /** Screen state. */
    val state: StateFlow<WeightUiState> = combine(
        db.changes.observe(setOf(Table.BODY, Table.GOAL, Table.SETTING, Table.DIARY, Table.DAY_SUMMARY)).conflate(),
        rangeFlow,
        DisplayUnits.observe(db),
    ) { _, r, u -> r to u }
        .conflate()
        .map { (r, u) -> safeLoad(r, u) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), WeightUiState())

    private suspend fun safeLoad(range: WeightRange, units: Units): WeightUiState = try {
        load(range, units)
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        SwLog.e(TAG, "weight load failed", e)
        WeightUiState(range, units, loading = false)
    }

    /** Loads [range] in [units]. */
    suspend fun load(range: WeightRange, units: Units): WeightUiState {
        val today = clock.today()
        val first = range.days?.let { today.plusDays(1 - it) } ?: EPOCH_DAY
        val all = db.body.series(BodyKind.WEIGHT, DayRange(first, today), clock.zone())
        val trend = WeightMath.trend(LongArray(all.size) { all[it].tSec }, IntArray(all.size) { all[it].valueX100 })
        val latest = db.body.latest(BodyKind.WEIGHT)
        val chartFirst = when {
            all.isEmpty() -> null
            range.days != null -> first
            else -> Day.of(all.first().tSec, clock.zone())
        }
        return WeightUiState(
            range = range,
            units = units,
            entries = all,
            trendKgX100 = trend.lastOrNull()?.roundToInt(),
            goal = db.goals.goalOn(GoalKind.WEIGHT_KG_X100, today),
            bmi = WeightRules.bmi(latest?.valueX100, db.settings.profile().heightCm),
            firstDay = chartFirst,
            lastDay = today,
            loading = false,
            trend = trend.map { it.roundToInt() },
            overlay = chartFirst?.let { WeightOverlay.load(db, it, today) },
            hiddenLayers = WeightLayers.load(db),
        )
    }

    /** Remembers which chart layers are switched off. */
    fun saveHiddenLayers(hidden: Set<String>) {
        viewModelScope.launch { WeightLayers.save(db, hidden) }
    }

    /** Switches the range. */
    fun setRange(r: WeightRange) {
        rangeFlow.value = r
    }

    /** Sets the weight goal from today (a dated goal row; earlier days keep their goal). */
    fun setGoal(kgX100: Int) {
        viewModelScope.launch { db.goals.set(Goal(GoalKind.WEIGHT_KG_X100, kgX100, clock.today())) }
    }

    /** Factory. */
    companion object {
        private const val TAG = "WeightViewModel"
        private const val STOP_MS = 5_000L

        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { WeightViewModel(graph.db, SystemClock) }
        }
    }
}

/**
 * One row of the Measurements list.
 *
 * @property custom the custom kind, for [BodyKind.CUSTOM] rows.
 * @property recent values of the last 90 days, oldest first, for the sparkline.
 */
data class MeasureRow(val kind: BodyKind, val custom: MeasureKind?, val latest: BodyMeasure?, val recent: List<Int>)

/** Measurements list state. */
data class MeasurementsUiState(val units: Units = Units.METRIC, val rows: List<MeasureRow> = emptyList(), val loading: Boolean = true)

/** Measurements view model: every listed body kind plus visible custom kinds. */
class MeasurementsViewModel(private val db: SelfWatchDb, private val clock: Clock) : ViewModel() {

    /** Screen state. */
    val state: StateFlow<MeasurementsUiState> = combine(
        db.changes.observe(setOf(Table.BODY, Table.MEASURE_KIND)).conflate(),
        DisplayUnits.observe(db),
    ) { _, u -> u }
        .conflate()
        .map { u ->
            try {
                load(u)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                SwLog.e(TAG, "measurements load failed", e)
                MeasurementsUiState(u, loading = false)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), MeasurementsUiState())

    /** Loads the rows in [units]. */
    suspend fun load(units: Units): MeasurementsUiState {
        val today = clock.today()
        val range = DayRange(today.plusDays(-RECENT_DAYS), today)
        val rows = ArrayList<MeasureRow>()
        for (k in BodyUnits.LISTED) {
            rows += MeasureRow(k, null, db.body.latest(k), db.body.series(k, range, clock.zone()).map { it.valueX100 })
        }
        for (c in db.body.customKinds().filter { !it.hidden }) {
            rows += MeasureRow(
                BodyKind.CUSTOM,
                c,
                db.body.latest(BodyKind.CUSTOM, c.id),
                db.body.series(BodyKind.CUSTOM, range, clock.zone(), c.id).map { it.valueX100 },
            )
        }
        return MeasurementsUiState(units, rows, loading = false)
    }

    /** Adds a custom measurement kind. */
    fun addCustomKind(name: String, unit: String) {
        viewModelScope.launch { db.body.addCustomKind(name.trim(), unit.trim()) }
    }

    /** Factory. */
    companion object {
        private const val TAG = "MeasurementsViewModel"
        private const val STOP_MS = 5_000L
        private const val RECENT_DAYS = 90

        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { MeasurementsViewModel(graph.db, SystemClock) }
        }
    }
}

/** One measurement kind's history. */
data class MeasureDetailUiState(
    val units: Units = Units.METRIC,
    val custom: MeasureKind? = null,
    val entries: List<BodyMeasure> = emptyList(),
    val loading: Boolean = true,
)

/** History of one kind (all time): chart, entries, log, edit, delete. */
class MeasureDetailViewModel(
    private val db: SelfWatchDb,
    private val clock: Clock,
    /** The kind shown. */
    val kind: BodyKind,
    /** The custom kind id, for [BodyKind.CUSTOM]. */
    val customKindId: Long?,
) : ViewModel() {

    /** Screen state. */
    val state: StateFlow<MeasureDetailUiState> = combine(
        db.changes.observe(setOf(Table.BODY, Table.MEASURE_KIND)).conflate(),
        DisplayUnits.observe(db),
    ) { _, u -> u }
        .conflate()
        .map { u ->
            try {
                MeasureDetailUiState(
                    units = u,
                    custom = customKindId?.let { id -> db.body.customKinds().firstOrNull { it.id == id } },
                    entries = db.body.series(kind, DayRange(EPOCH_DAY, clock.today()), clock.zone(), customKindId),
                    loading = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                SwLog.e("MeasureDetailViewModel", "load failed", e)
                MeasureDetailUiState(u, loading = false)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), MeasureDetailUiState())

    /** Factory. */
    companion object {
        /** Builds the view model from [graph] for [kind] / [customKindId]. */
        fun factory(graph: AppGraph, kind: BodyKind, customKindId: Long?): ViewModelProvider.Factory = viewModelFactory {
            initializer { MeasureDetailViewModel(graph.db, SystemClock, kind, customKindId) }
        }
    }
}
