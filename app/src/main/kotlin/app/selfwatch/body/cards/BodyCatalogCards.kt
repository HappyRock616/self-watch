/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body.cards

import android.content.Context
import app.selfwatch.R
import app.selfwatch.body.BodyTrendLoader
import app.selfwatch.body.BodyUnits
import app.selfwatch.body.HeartCardLoader
import app.selfwatch.body.MeasureDetailFragment
import app.selfwatch.body.SignalKind
import app.selfwatch.body.SleepDetailFragment
import app.selfwatch.body.WeightDetailFragment
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.TrendCard
import app.selfwatch.cards.TrendData
import app.selfwatch.cards.WaitingCard
import app.selfwatch.db.Table
import app.selfwatch.food.budget.Bmr
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.TimeRange
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import app.selfwatch.ui.format.Units
import java.util.Locale
import kotlin.math.abs

private const val HISTORY_DAYS = 90
private const val CHANGE_DAYS = 30
private const val DAY_SEC = 86_400L

/** The values of [kind] over the last 90 days (oldest first) and their change over 30 days, from the body log. */
private suspend fun bodySeries(env: CardEnv, kind: BodyKind): TrendData {
    val today = env.clock.today()
    val list = env.db.body.series(kind, DayRange(today.plusDays(-HISTORY_DAYS), today), env.clock.zone()).sortedBy { it.tSec }
    val latest = env.db.body.latest(kind) ?: return TrendData(null, units = env.units)
    val base = list.lastOrNull { it.tSec <= latest.tSec - CHANGE_DAYS * DAY_SEC }
    val change = base?.let { (latest.valueX100 - it.valueX100) / 100.0 }
    return TrendData(latest.valueX100 / 100.0, list.map { it.valueX100 / 100.0 }, change, env.units)
}

/**
 * A scale or body-log number (muscle, bone, body water): the latest value, its 30-day change and 90 days
 * of entries. Opens that measurement's screen.
 */
open class BodyMeasureCard(
    id: String,
    title: Int,
    summary: Int,
    private val kind: BodyKind,
) : TrendCard(id, CardTab.BODY, title, summary) {
    override val tables: Set<Table> = setOf(Table.BODY)

    override val needs: Set<DataKind> = setOf(DataKind.BODY_COMPOSITION)

    override suspend fun load(env: CardEnv): TrendData = bodySeries(env, kind)

    override fun format(ctx: Context, value: Double, units: Units): String = BodyUnits.number(kind, (value * 100).toInt(), units)

    override fun unit(ctx: Context, units: Units): String = BodyUnits.unit(kind, units)

    override fun label(ctx: Context, data: TrendData): String = ctx.getString(R.string.trend_label_latest)

    override fun open(host: CardHost): Boolean {
        host.open(MeasureDetailFragment.newInstance(kind, null))
        return true
    }
}

/** Muscle mass from a scale (or typed in). */
object MuscleMassCard : BodyMeasureCard("muscle_mass", R.string.card_muscle_mass, R.string.card_muscle_mass_summary, BodyKind.MUSCLE_MASS)

/** Body water from a scale. */
object BodyWaterCard : BodyMeasureCard("body_water", R.string.card_body_water, R.string.card_body_water_summary, BodyKind.BODY_WATER)

/** Bone mass from a scale. */
object BoneMassCard : BodyMeasureCard("bone_mass", R.string.card_bone_mass, R.string.card_bone_mass_summary, BodyKind.BONE_MASS)

/** Lean (fat-free) mass: each body-fat reading with the weight nearest to it, weight × (1 − fat). */
object LeanMassCard : TrendCard("lean_mass", CardTab.BODY, R.string.card_lean_mass, R.string.card_lean_mass_summary) {
    override val tables: Set<Table> = setOf(Table.BODY)

    override val needs: Set<DataKind> = setOf(DataKind.BODY_FAT)

    override suspend fun load(env: CardEnv): TrendData {
        val today = env.clock.today()
        val range = DayRange(today.plusDays(-HISTORY_DAYS), today)
        val weights = env.db.body.series(BodyKind.WEIGHT, range, env.clock.zone()).sortedBy { it.tSec }
        val fats = env.db.body.series(BodyKind.BODY_FAT, range, env.clock.zone()).sortedBy { it.tSec }
        val lean = fats.mapNotNull { f ->
            val w = weights.minByOrNull { abs(it.tSec - f.tSec) }?.takeIf { abs(it.tSec - f.tSec) <= NEAR_SEC } ?: return@mapNotNull null
            f.tSec to leanKg(w.valueX100, f.valueX100)
        }
        val latest = lean.lastOrNull() ?: return TrendData(null, units = env.units)
        val base = lean.lastOrNull { it.first <= latest.first - CHANGE_DAYS * DAY_SEC }
        return TrendData(latest.second, lean.map { it.second }, base?.let { latest.second - it.second }, env.units)
    }

    /** Lean mass in kg from a weight (kg ×100) and a body fat share (% ×100). */
    fun leanKg(weightKgX100: Int, fatPctX100: Int): Double = weightKgX100 / 100.0 * (1 - fatPctX100 / 10_000.0)

    override fun format(ctx: Context, value: Double, units: Units): String = BodyUnits.number(BodyKind.WEIGHT, (value * 100).toInt(), units)

    override fun unit(ctx: Context, units: Units): String = BodyUnits.unit(BodyKind.WEIGHT, units)

    override fun label(ctx: Context, data: TrendData): String = ctx.getString(R.string.trend_label_latest)

    override fun emptyMessage(ctx: Context): String = ctx.getString(R.string.lean_mass_empty)

    private const val NEAR_SEC = 2 * DAY_SEC
}

/**
 * BMI: the scale's own number when it sends one, else the latest weight and the profile height. The
 * usual healthy range (18.5–24.9) is shown as a note, without a judgment color: BMI says little about
 * one person.
 */
object BmiCard : TrendCard("bmi", CardTab.BODY, R.string.card_bmi, R.string.card_bmi_summary) {
    override val tables: Set<Table> = setOf(Table.BODY, Table.SETTING)

    override val needs: Set<DataKind> = setOf(DataKind.WEIGHT)

    override suspend fun load(env: CardEnv): TrendData {
        val own = bodySeries(env, BodyKind.BMI)
        if (own.latest != null) return own
        val height = env.db.settings.profile().heightCm ?: return TrendData(null, units = env.units)
        val w = bodySeries(env, BodyKind.WEIGHT)
        return TrendData(w.latest?.let { bmi(it, height) }, w.series.map { bmi(it, height) }, w.change?.let { bmi(it, height) }, env.units)
    }

    /** BMI of [kg] at [heightCm]. */
    fun bmi(kg: Double, heightCm: Int): Double = if (heightCm <= 0) 0.0 else kg / ((heightCm / 100.0) * (heightCm / 100.0))

    override fun format(ctx: Context, value: Double, units: Units): String = Fmt.number(value, 1)

    override fun unit(ctx: Context, units: Units): String? = null

    override fun label(ctx: Context, data: TrendData): String = ctx.getString(R.string.trend_label_latest)

    override fun note(ctx: Context, data: TrendData): String = ctx.getString(R.string.bmi_note)

    override fun emptyMessage(ctx: Context): String = ctx.getString(R.string.bmi_empty)

    override fun open(host: CardHost): Boolean {
        host.open(WeightDetailFragment())
        return true
    }
}

/**
 * Resting calories (BMR): what the devices measure at rest per day (30-day average) or, without that,
 * the Mifflin-St Jeor estimate from weight, height, age and sex.
 */
object BmrCard : TrendCard("bmr", CardTab.BODY, R.string.card_bmr, R.string.card_bmr_summary) {
    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY, Table.BODY, Table.SETTING)

    override suspend fun load(env: CardEnv): TrendData {
        val today = env.clock.today()
        val days = env.db.summaries.days(DayRange(today.plusDays(-CHANGE_DAYS), today.plusDays(-1))).mapNotNull { it.restingKcal?.takeIf { k -> k > 0 } }
        if (days.isNotEmpty()) return TrendData(days.average(), days.map { it.toDouble() }, units = env.units, extras = listOf(MEASURED))
        val p = env.db.settings.profile()
        val w = env.db.body.latest(BodyKind.WEIGHT)?.valueX100
        val age = p.birthYear?.let { today.year - it }
        if (w == null || p.heightCm == null || age == null) return TrendData(null, units = env.units)
        return TrendData(Bmr.mifflinStJeor(w, p.heightCm ?: 0, age, p.sex).toDouble(), units = env.units, extras = listOf(ESTIMATED))
    }

    override fun format(ctx: Context, value: Double, units: Units): String = Fmt.number(value, 0)

    override fun unit(ctx: Context, units: Units): String = ctx.getString(R.string.unit_kcal)

    override fun label(ctx: Context, data: TrendData): String =
        ctx.getString(if (data.extras.firstOrNull() == MEASURED) R.string.bmr_label_measured else R.string.bmr_label_estimated)

    override fun note(ctx: Context, data: TrendData): String? = null

    override fun emptyMessage(ctx: Context): String = ctx.getString(R.string.bmr_empty)

    private const val MEASURED = 1.0
    private const val ESTIMATED = 0.0
}

/**
 * Temperature: skin (or body) temperature overnight against your usual, as the change in °C (or °F)
 * from the device's own baseline, the last 30 nights.
 */
object TemperatureCard : TrendCard("temperature", CardTab.BODY, R.string.card_temperature, R.string.card_temperature_summary) {
    override val tables: Set<Table> = setOf(Table.METRIC, Table.SLEEP_SESSION)

    override val needs: Set<DataKind> = setOf(DataKind.SKIN_TEMP)

    override suspend fun load(env: CardEnv): TrendData {
        val today = env.clock.today()
        val nights = BodyTrendLoader(env.db, env.clock).signalNights(DayRange(today.plusDays(-CHANGE_DAYS), today))[SignalKind.SKIN_TEMP].orEmpty()
        val sorted = nights.toSortedMap()
        val last = sorted.values.lastOrNull() ?: return TrendData(null, units = env.units)
        return TrendData(last / 100.0, sorted.values.map { it / 100.0 }, units = env.units)
    }

    override fun format(ctx: Context, value: Double, units: Units): String {
        val v = if (units == Units.METRIC) value else value * F_PER_C
        return (if (v > 0) "+" else "") + Fmt.number(v, 1)
    }

    override fun unit(ctx: Context, units: Units): String = ctx.getString(if (units == Units.METRIC) R.string.unit_celsius_delta else R.string.unit_fahrenheit_delta)

    override fun label(ctx: Context, data: TrendData): String = ctx.getString(R.string.temperature_label)

    override fun note(ctx: Context, data: TrendData): String? = null

    override fun status(data: TrendData): CardStatus = when {
        data.latest == null -> CardStatus.NONE
        abs(data.latest) < NEAR_C -> CardStatus.GOOD
        abs(data.latest) < FAR_C -> CardStatus.CLOSE
        else -> CardStatus.OFF
    }

    override fun open(host: CardHost): Boolean {
        host.open(app.selfwatch.body.BodySignalsDetailFragment())
        return true
    }

    private const val F_PER_C = 1.8
    private const val NEAR_C = 0.5
    private const val FAR_C = 1.0
}

/** Blood pressure: the latest reading (systolic / diastolic, mmHg) from a monitor, and the systolic trend. */
object BloodPressureCard : TrendCard("blood_pressure", CardTab.BODY, R.string.card_blood_pressure, R.string.card_blood_pressure_summary) {
    override val tables: Set<Table> = setOf(Table.METRIC)

    override val needs: Set<DataKind> = setOf(DataKind.BLOOD_PRESSURE)

    override suspend fun load(env: CardEnv): TrendData {
        val sys = env.db.metrics.latest(MetricKind.BP_SYSTOLIC) ?: return TrendData(null, units = env.units)
        val dia = env.db.metrics.series(MetricKind.BP_DIASTOLIC, TimeRange(sys.tSec, sys.tSec + 1)).firstOrNull()
            ?: env.db.metrics.latest(MetricKind.BP_DIASTOLIC)
        val start = sys.tSec - HISTORY_DAYS * DAY_SEC
        val series = env.db.metrics.series(MetricKind.BP_SYSTOLIC, TimeRange(start, sys.tSec + 1)).map { it.value.toDouble() }
        return TrendData(sys.value.toDouble(), series, units = env.units, extras = listOf(dia?.value?.toDouble()))
    }

    override fun format(ctx: Context, value: Double, units: Units): String = Fmt.number(value, 0)

    override fun unit(ctx: Context, units: Units): String = ctx.getString(R.string.unit_mmhg)

    override fun label(ctx: Context, data: TrendData): String =
        data.extras.firstOrNull()?.let { ctx.getString(R.string.bp_label_diastolic, Fmt.number(it, 0)) } ?: ctx.getString(R.string.bp_label)

    override fun note(ctx: Context, data: TrendData): String? = null

    override fun status(data: TrendData): CardStatus {
        val s = data.latest ?: return CardStatus.NONE
        val d = data.extras.firstOrNull() ?: 0.0
        return when {
            s < SYS_ELEVATED && d < DIA_HIGH -> CardStatus.GOOD
            s < SYS_HIGH && d < DIA_HIGH -> CardStatus.CLOSE
            else -> CardStatus.OFF
        }
    }

    // American Heart Association categories: normal < 120/80, elevated 120–129, high from 130/80.
    private const val SYS_ELEVATED = 120
    private const val SYS_HIGH = 130
    private const val DIA_HIGH = 80
}

/** Sleep score: last night's score from the device (0–100) and the last 30 nights. */
object SleepScoreCard : TrendCard("sleep_score", CardTab.BODY, R.string.card_sleep_score, R.string.card_sleep_score_summary) {
    override val tables: Set<Table> = setOf(Table.SLEEP_SESSION)

    override val needs: Set<DataKind> = setOf(DataKind.SLEEP_SCORE)

    override suspend fun load(env: CardEnv): TrendData {
        val today = env.clock.today()
        val scores = env.db.sleep.sessions(DayRange(today.plusDays(-CHANGE_DAYS), today), env.clock.zone()).sortedBy { it.endSec }.mapNotNull { it.score }
        val last = scores.lastOrNull() ?: return TrendData(null, units = env.units)
        val usual = scores.dropLast(1).takeIf { it.size >= MIN_NIGHTS }?.average()
        return TrendData(last.toDouble(), scores.map { it.toDouble() }, usual?.let { last - it }, env.units)
    }

    override fun format(ctx: Context, value: Double, units: Units): String = Fmt.number(value, 0)

    override fun unit(ctx: Context, units: Units): String = ctx.getString(R.string.sleep_score_unit)

    override fun label(ctx: Context, data: TrendData): String = ctx.getString(R.string.sleep_score_label)

    override fun status(data: TrendData): CardStatus {
        val v = data.latest ?: return CardStatus.NONE
        return when {
            v >= GOOD_SCORE -> CardStatus.GOOD
            v >= FAIR_SCORE -> CardStatus.CLOSE
            else -> CardStatus.OFF
        }
    }

    override fun open(host: CardHost): Boolean {
        host.open(SleepDetailFragment())
        return true
    }

    private const val MIN_NIGHTS = 3
    private const val GOOD_SCORE = 80
    private const val FAIR_SCORE = 60
}

/** Naps: today's sleep outside the night (sessions starting between 9:00 and 20:00), in minutes. */
object NapsCard : TrendCard("naps", CardTab.BODY, R.string.card_naps, R.string.card_naps_summary) {
    override val tables: Set<Table> = setOf(Table.SLEEP_SESSION)

    override val needs: Set<DataKind> = setOf(DataKind.SLEEP)

    override suspend fun load(env: CardEnv): TrendData {
        val zone = env.clock.zone()
        val days = (NAP_DAYS - 1 downTo 0).map { env.day.plusDays(-it) }
        val sessions = env.db.sleep.sessions(DayRange(days.first(), days.last()), zone)
        val perDay = days.map { d -> napMinutes(d, sessions.map { it.startSec to it.endSec }, zone) }
        return TrendData(perDay.last().toDouble(), perDay.map { it.toDouble() }, units = env.units, extras = listOf(perDay.last().toDouble()))
    }

    /** Minutes of [sessions] (start, end) on [day] that started between 9:00 and 20:00 local time. */
    fun napMinutes(day: Day, sessions: List<Pair<Long, Long>>, zone: java.time.ZoneId): Int {
        val start = day.startSec(zone)
        return sessions.filter { (s, _) -> s >= start + NAP_FROM_H * HOUR_SEC && s < start + NAP_TO_H * HOUR_SEC }
            .sumOf { (s, e) -> ((e - s) / MINUTE_SEC).toInt().coerceAtLeast(0) }
    }

    override fun format(ctx: Context, value: Double, units: Units): String = Fmt.duration(value.toLong() * MINUTE_SEC, Locale.getDefault(), FmtLabels.from(ctx))

    override fun unit(ctx: Context, units: Units): String? = null

    override fun label(ctx: Context, data: TrendData): String = ctx.getString(R.string.naps_label)

    override fun note(ctx: Context, data: TrendData): String? = null

    override fun open(host: CardHost): Boolean {
        host.open(SleepDetailFragment())
        return true
    }

    private const val NAP_DAYS = 14
    private const val NAP_FROM_H = 9
    private const val NAP_TO_H = 20
    private const val HOUR_SEC = 3_600L
    private const val MINUTE_SEC = 60L
}

/**
 * Morning summary: last night's sleep, HRV and the energy level in one line, for a quick look each
 * morning.
 */
object MorningSummaryCard : TrendCard("morning_summary", CardTab.BODY, R.string.card_morning_summary, R.string.card_morning_summary_summary) {
    override val tables: Set<Table> = setOf(Table.SLEEP_SESSION, Table.DAY_SUMMARY, Table.METRIC)

    override val needs: Set<DataKind> = setOf(DataKind.SLEEP)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override suspend fun load(env: CardEnv): TrendData {
        val sleep = BodyTrendLoader(env.db, env.clock).sleep()
        val hrv = env.db.summaries.day(env.clock.today())?.hrvAvgMs
        val energy = HeartCardLoader(env.db, env.clock).energy()?.latest
        return TrendData(sleep.lastMin?.toDouble(), units = env.units, extras = listOf(hrv?.toDouble(), energy?.toDouble()))
    }

    override fun format(ctx: Context, value: Double, units: Units): String = Fmt.duration(value.toLong() * 60, Locale.getDefault(), FmtLabels.from(ctx))

    override fun unit(ctx: Context, units: Units): String? = null

    override fun label(ctx: Context, data: TrendData): String = ctx.getString(R.string.morning_label)

    override fun note(ctx: Context, data: TrendData): String? {
        val hrv = data.extras.getOrNull(0)?.let { ctx.getString(R.string.morning_hrv, Fmt.number(it, 0)) }
        val energy = data.extras.getOrNull(1)?.let { ctx.getString(R.string.morning_energy, Fmt.number(it, 0)) }
        return listOfNotNull(hrv, energy).joinToString(" · ").ifEmpty { null }
    }

    override fun open(host: CardHost): Boolean {
        host.open(SleepDetailFragment())
        return true
    }
}

// Catalog cards for data no device sends to Self Watch yet: each says "Your devices don't record this".

/** Tonight's suggested sleep. */
val SleepNeedCard = WaitingCard("sleep_need", CardTab.BODY, R.string.card_sleep_need, R.string.card_sleep_need_summary, DataKind.SLEEP_NEED)

/** The last 2-minute health check. */
val HealthCheckCard = WaitingCard("health_check", CardTab.BODY, R.string.card_health_check, R.string.card_health_check_summary, DataKind.HEALTH_CHECK)

/** Fitness age. */
val FitnessAgeCard = WaitingCard("fitness_age", CardTab.BODY, R.string.card_fitness_age, R.string.card_fitness_age_summary, DataKind.FITNESS_AGE)

/** The last ECG recording. */
val EcgCard = WaitingCard("ecg", CardTab.BODY, R.string.card_ecg, R.string.card_ecg_summary, DataKind.ECG, CardSize.LARGE)

/** Cycle tracking. */
val CycleTrackingCard = WaitingCard("cycle_tracking", CardTab.BODY, R.string.card_cycle_tracking, R.string.card_cycle_tracking_summary, DataKind.CYCLE_TRACKING)

/** Body clock vs destination time. */
val JetLagCard = WaitingCard("jet_lag", CardTab.BODY, R.string.card_jet_lag, R.string.card_jet_lag_summary, DataKind.JET_LAG)

/** Blood glucose readings. */
val BloodGlucoseCard = WaitingCard("blood_glucose", CardTab.BODY, R.string.card_blood_glucose, R.string.card_blood_glucose_summary, DataKind.BLOOD_GLUCOSE)
