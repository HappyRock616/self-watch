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
import app.selfwatch.cards.ChartChoice
import app.selfwatch.cards.ChartId
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.db.dao.SleepNight
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.health.SleepStageSample
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.SystemClock
import app.selfwatch.model.time.TimeRange
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

/** A time series: epoch seconds and raw values (scaled as the metric kind says). */
class Series(val t: LongArray, val v: IntArray) {
    /** Number of samples. */
    val size: Int get() = t.size

    /** Rounded mean of the values, or null when empty. */
    fun average(): Int? = if (v.isEmpty()) null else (v.sumOf { it.toLong() }.toDouble() / v.size).roundToInt()

    override fun equals(other: Any?): Boolean = other is Series && other.t.contentEquals(t) && other.v.contentEquals(v)

    override fun hashCode(): Int = t.contentHashCode() * 31 + v.contentHashCode()

    /** Builders. */
    companion object {
        /** No samples. */
        val EMPTY: Series = Series(LongArray(0), IntArray(0))

        /** From metric samples (assumed sorted by time). */
        fun of(samples: List<MetricSample>): Series =
            Series(LongArray(samples.size) { samples[it].tSec }, IntArray(samples.size) { samples[it].value })
    }
}

/** Chart range of the Sleep and Heart screens: one night (or day), the last 7 days, or the last 30. */
enum class PeriodRange(
    /** Days covered by the period views (1 for the night or day view). */
    val days: Int,
) {
    /** One night or one day. */
    SINGLE(1),

    /** The last 7 days. */
    WEEK(7),

    /** The last 30 days. */
    MONTH(30),
}

/**
 * One night assembled for the Sleep screen: the session, stage blocks, minutes per stage and the
 * overnight series (heart rate, HRV, breathing ×10, SpO2, skin temperature delta ×100).
 */
data class SleepNightData(
    val day: Day,
    val startSec: Long,
    val endSec: Long,
    val score: Int?,
    val stages: List<SleepStageSample>,
    val stageMin: Map<SleepStage, Int>,
    val asleepMin: Int,
    val hr: Series,
    val hrv: Series,
    val breathing: Series,
    val spo2: Series,
    val skinTemp: Series,
)

/** One day of the week and month sleep views. */
data class SleepDay(val day: Day, val sleepMin: Int?, val restingHr: Int?, val hrvMs: Int?)

/** Sleep detail state. */
data class SleepUiState(
    val range: PeriodRange = PeriodRange.SINGLE,
    /** The wake-up day of the night shown. */
    val nightDay: Day,
    /** False when [nightDay] is today (no later night). */
    val canGoNext: Boolean = false,
    val night: SleepNightData? = null,
    val days: List<SleepDay> = emptyList(),
    val loading: Boolean = true,
    /** Chart layers switched off for this view ([SleepLayers.NIGHT_CHOICE] or [SleepLayers.PERIOD_CHOICE]). */
    val hidden: Set<String> = emptySet(),
)

/** The Sleep chart's layer ids, per view, and the stored choices. */
object SleepLayers {
    /** Night view: the hypnogram. */
    const val STAGES: String = "stages"

    /** Night view: heart rate. */
    const val HR: String = "hr"

    /** Night and period views: HRV. */
    const val HRV: String = "hrv"

    /** Night view: breathing rate. */
    const val BREATHING: String = "breathing"

    /** Night view: blood oxygen. */
    const val SPO2: String = "spo2"

    /** Night view: skin temperature change. */
    const val TEMP: String = "temp"

    /** Period views: sleep time per night. */
    const val SLEEP: String = "sleep"

    /** Period views: resting heart rate. */
    const val RESTING_HR: String = "rhr"

    /** Night view layers, in chip order. */
    val NIGHT: List<String> = listOf(STAGES, HR, HRV, BREATHING, SPO2, TEMP)

    /** Week and month view layers, in chip order. */
    val PERIOD: List<String> = listOf(SLEEP, RESTING_HR, HRV)

    /** The night view's stored choice. */
    val NIGHT_CHOICE: ChartChoice by lazy { ChartChoice(ChartId.SLEEP_NIGHT) }

    /** The week and month views' stored choice. */
    val PERIOD_CHOICE: ChartChoice by lazy { ChartChoice(ChartId.SLEEP_PERIOD) }
}

/** Pure sleep arithmetic. */
object SleepNights {
    /**
     * Minutes per stage: each sample lasts until the next one, the last until [endSec]. Samples before
     * the previous one (unsorted input) are sorted first.
     */
    fun stageMinutes(stages: List<SleepStageSample>, endSec: Long): Map<SleepStage, Int> {
        val sorted = stages.sortedBy { it.tSec }
        val sec = HashMap<SleepStage, Long>()
        for (i in sorted.indices) {
            val end = if (i + 1 < sorted.size) sorted[i + 1].tSec else endSec
            val len = (end - sorted[i].tSec).coerceAtLeast(0)
            sec[sorted[i].stage] = (sec[sorted[i].stage] ?: 0) + len
        }
        return SleepStage.entries.associateWith { ((sec[it] ?: 0) / 60).toInt() }
    }

    /** Asleep minutes: light, deep and REM. */
    fun asleep(stageMin: Map<SleepStage, Int>): Int =
        (stageMin[SleepStage.LIGHT] ?: 0) + (stageMin[SleepStage.DEEP] ?: 0) + (stageMin[SleepStage.REM] ?: 0)

    /** Assembles [night] (ending on [day]) with its overnight [metrics]. */
    fun assemble(day: Day, night: SleepNight, metrics: Map<MetricKind, List<MetricSample>>): SleepNightData {
        val s = night.session
        val inside = { kind: MetricKind ->
            Series.of(metrics[kind].orEmpty().filter { it.tSec >= s.startSec && it.tSec < s.endSec }.sortedBy { it.tSec })
        }
        val stageMin = stageMinutes(night.stages, s.endSec)
        val asleep = if (night.stages.isEmpty()) ((s.endSec - s.startSec) / 60).toInt() else asleep(stageMin)
        return SleepNightData(
            day = day,
            startSec = s.startSec,
            endSec = s.endSec,
            score = s.score,
            stages = night.stages,
            stageMin = stageMin,
            asleepMin = asleep,
            hr = inside(MetricKind.HEART_RATE),
            hrv = inside(MetricKind.HRV_RMSSD),
            breathing = inside(MetricKind.RESPIRATION),
            spo2 = inside(MetricKind.SPO2),
            skinTemp = inside(MetricKind.SKIN_TEMP_DELTA),
        )
    }

    /** The overnight metric kinds the Sleep chart shows. */
    val NIGHT_KINDS: List<MetricKind> =
        listOf(MetricKind.HEART_RATE, MetricKind.HRV_RMSSD, MetricKind.RESPIRATION, MetricKind.SPO2, MetricKind.SKIN_TEMP_DELTA)
}

/** Base for the Sleep and Heart view models: a range, a day offset and a reload on database changes. */
abstract class PeriodViewModel<S>(
    protected val db: SelfWatchDb,
    protected val clock: Clock,
    tables: Set<Table>,
    initial: S,
) : ViewModel() {
    private val rangeFlow = MutableStateFlow(PeriodRange.SINGLE)
    private val offsetFlow = MutableStateFlow(0)

    /** Screen state. */
    val state: StateFlow<S> = combine(db.changes.observe(tables).conflate(), rangeFlow, offsetFlow) { _, r, o -> r to o }
        .conflate()
        .map { (r, o) ->
            try {
                load(r, clock.today().plusDays(-o), o > 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                SwLog.e("PeriodViewModel", "load failed", e)
                failed(r, clock.today().plusDays(-o))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), initial)

    /** Loads [range] for [day] ([canGoNext] when a later day exists). */
    protected abstract suspend fun load(range: PeriodRange, day: Day, canGoNext: Boolean): S

    /** The state shown when loading failed. */
    protected abstract fun failed(range: PeriodRange, day: Day): S

    /** Switches the range. */
    fun setRange(range: PeriodRange) {
        rangeFlow.value = range
    }

    /** The current range. */
    val range: PeriodRange get() = rangeFlow.value

    /** One night or day earlier. */
    fun previous() {
        offsetFlow.value += 1
    }

    /** One night or day later (never past today). */
    fun next() {
        if (offsetFlow.value > 0) offsetFlow.value -= 1
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

/** Sleep detail view model. */
class SleepViewModel(db: SelfWatchDb, clock: Clock) :
    PeriodViewModel<SleepUiState>(db, clock, TABLES, SleepUiState(nightDay = clock.today())) {

    override suspend fun load(range: PeriodRange, day: Day, canGoNext: Boolean): SleepUiState {
        val zone = clock.zone()
        if (range == PeriodRange.SINGLE) {
            val night = db.sleep.night(day, zone)
            val data = night?.let { n ->
                val r = TimeRange(n.session.startSec, n.session.endSec)
                val metrics = SleepNights.NIGHT_KINDS.associateWith { db.metrics.series(it, r, n.session.deviceId) }
                SleepNights.assemble(day, n, metrics)
            }
            return SleepUiState(range, day, canGoNext, data, emptyList(), loading = false, hidden = SleepLayers.NIGHT_CHOICE.load(db))
        }
        val today = clock.today()
        val first = today.plusDays(1 - range.days)
        val byDay = db.summaries.days(DayRange(first, today)).associateBy { it.day }
        val days = DayRange(first, today).days().map { d ->
            val s = byDay[d]
            SleepDay(d, s?.sleepMin, s?.restingHr, s?.hrvAvgMs)
        }
        return SleepUiState(range, day, canGoNext, null, days, loading = false, hidden = SleepLayers.PERIOD_CHOICE.load(db))
    }

    override fun failed(range: PeriodRange, day: Day): SleepUiState = SleepUiState(range, day, loading = false)

    /** Saves the layers switched off on the night view ([night]) or the week and month views. */
    fun saveHidden(night: Boolean, hidden: Set<String>) {
        val choice = if (night) SleepLayers.NIGHT_CHOICE else SleepLayers.PERIOD_CHOICE
        viewModelScope.launch { choice.save(db, hidden) }
    }

    /** Factory. */
    companion object {
        /** Tables that refresh the screen. */
        val TABLES: Set<Table> = setOf(Table.SLEEP_SESSION, Table.SLEEP_STAGE, Table.METRIC, Table.DAY_SUMMARY)

        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { SleepViewModel(graph.db, SystemClock) }
        }
    }
}

/** One day of heart data in the week and month views. */
data class HeartDay(
    val day: Day,
    val minHr: Int?,
    val maxHr: Int?,
    val restingHr: Int?,
    val hrvMs: Int?,
    val stress: Int?,
)

/** Heart detail state. */
data class HeartUiState(
    val range: PeriodRange = PeriodRange.SINGLE,
    val day: Day,
    val canGoNext: Boolean = false,
    /** The day's series (single-day view). */
    val hr: Series = Series.EMPTY,
    val hrv: Series = Series.EMPTY,
    val stress: Series = Series.EMPTY,
    val restingHr: Int? = null,
    /** Per day (week and month views). */
    val days: List<HeartDay> = emptyList(),
    val loading: Boolean = true,
) {
    /** True when there is nothing to draw. */
    val isEmpty: Boolean get() = if (range == PeriodRange.SINGLE) {
        hr.size == 0 && hrv.size == 0 && stress.size == 0
    } else {
        days.none { it.maxHr != null || it.restingHr != null || it.hrvMs != null || it.stress != null }
    }
}

/** Heart detail view model: heart rate, resting heart rate, HRV and stress. */
class HeartViewModel(db: SelfWatchDb, clock: Clock) :
    PeriodViewModel<HeartUiState>(db, clock, TABLES, HeartUiState(day = clock.today())) {

    override suspend fun load(range: PeriodRange, day: Day, canGoNext: Boolean): HeartUiState {
        val zone = clock.zone()
        if (range == PeriodRange.SINGLE) {
            val r = day.toTimeRange(zone)
            return HeartUiState(
                range = range,
                day = day,
                canGoNext = canGoNext,
                hr = Series.of(db.metrics.series(MetricKind.HEART_RATE, r).filter { it.value > 0 }),
                hrv = Series.of(db.metrics.series(MetricKind.HRV_RMSSD, TimeRange(r.startSec - NIGHT_LEAD_SEC, r.endSec))),
                stress = Series.of(db.metrics.series(MetricKind.STRESS, r).filter { it.value in 0..100 }),
                restingHr = db.summaries.day(day)?.restingHr,
                loading = false,
            )
        }
        val today = clock.today()
        val days = DayRange(today.plusDays(1 - range.days), today)
        val hr = db.metrics.dailyStats(MetricKind.HEART_RATE, days, zone).associateBy { it.day }
        val stress = db.metrics.dailyStats(MetricKind.STRESS, days, zone).associateBy { it.day }
        val sums = db.summaries.days(days).associateBy { it.day }
        val list = days.days().map { d ->
            HeartDay(d, hr[d]?.min, hr[d]?.max, sums[d]?.restingHr, sums[d]?.hrvAvgMs, stress[d]?.avg)
        }
        return HeartUiState(range, day, canGoNext, days = list, loading = false)
    }

    override fun failed(range: PeriodRange, day: Day): HeartUiState = HeartUiState(range, day, loading = false)

    /** Factory. */
    companion object {
        // HRV is measured overnight: the day view includes the night before it.
        private const val NIGHT_LEAD_SEC = 6 * 3_600L

        /** Tables that refresh the screen. */
        val TABLES: Set<Table> = setOf(Table.METRIC, Table.DAY_SUMMARY)

        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { HeartViewModel(graph.db, SystemClock) }
        }
    }
}
