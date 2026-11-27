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
import app.selfwatch.body.GarminBands
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
import app.selfwatch.cards.CardVisuals
import app.selfwatch.cards.TrendCard
import app.selfwatch.cards.TrendData
import app.selfwatch.cards.TrendPicture
import app.selfwatch.cards.TrendPictures
import app.selfwatch.cards.WaitingCard
import app.selfwatch.db.Table
import app.selfwatch.food.budget.Bmr
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.TimeRange
import app.selfwatch.ui.charts.LevelColors
import app.selfwatch.ui.components.Band
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
    val zone = env.clock.zone()
    return TrendData(
        latest.valueX100 / 100.0, list.map { it.valueX100 / 100.0 }, change, env.units,
        startDay = list.firstOrNull()?.let { Day.of(it.tSec, zone) }, endDay = Day.of(latest.tSec, zone),
    )
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
        val zone = env.clock.zone()
        return TrendData(
            latest.second, lean.map { it.second }, base?.let { latest.second - it.second }, env.units,
            startDay = Day.of(lean.first().first, zone), endDay = Day.of(latest.first, zone),
        )
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
 * BMI: the scale's own number when it sends one, else the latest weight and the profile height, named
 * with the WHO band as Garmin Index scales do ([GarminBands.bmi]).
 */
object BmiCard : TrendCard("bmi", CardTab.BODY, R.string.card_bmi, R.string.card_bmi_summary) {
    override val tables: Set<Table> = setOf(Table.BODY, Table.SETTING)

    override val needs: Set<DataKind> = setOf(DataKind.WEIGHT)

    override suspend fun load(env: CardEnv): TrendData {
        val own = bodySeries(env, BodyKind.BMI)
        if (own.latest != null) return own
        val height = env.db.settings.profile().heightCm ?: return TrendData(null, units = env.units)
        val w = bodySeries(env, BodyKind.WEIGHT)
        return w.copy(latest = w.latest?.let { bmi(it, height) }, series = w.series.map { bmi(it, height) }, change = w.change?.let { bmi(it, height) })
    }

    /** BMI of [kg] at [heightCm]. */
    fun bmi(kg: Double, heightCm: Int): Double = if (heightCm <= 0) 0.0 else kg / ((heightCm / 100.0) * (heightCm / 100.0))

    override fun format(ctx: Context, value: Double, units: Units): String = Fmt.number(value, 1)

    override fun unit(ctx: Context, units: Units): String? = null

    // The WHO band is the tag ("Overweight").
    override fun label(ctx: Context, data: TrendData): String =
        data.latest?.let { ctx.getString(GarminBands.bmi(it).name) } ?: ctx.getString(R.string.trend_label_latest)

    override fun note(ctx: Context, data: TrendData): String? = null

    /** WHO bands: under 18.5, 18.5–24.9, 25–29.9, 30–34.9, 35 and over. */
    override fun picture(ctx: Context, data: TrendData, small: Boolean): TrendPicture? {
        val v = data.latest ?: return null
        val c = LevelColors.of(ctx)
        return TrendPicture.Range(
            listOf(Band(18.5f, c.scaleClose), Band(25f, c.scaleGood), Band(30f, c.scaleClose), Band(35f, c.scaleOff), Band(BMI_MAX, c.stressHigh)),
            BMI_MIN, BMI_MAX, v.toFloat(),
            listOf(18.5f to "18.5", 25f to "25", 30f to "30", 35f to "35"),
        )
    }

    private const val BMI_MIN = 15f
    private const val BMI_MAX = 42f

    override fun status(data: TrendData): CardStatus = data.latest?.let { GarminBands.bmi(it).status } ?: CardStatus.NONE

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
        val range = DayRange(today.plusDays(-CHANGE_DAYS), today.plusDays(-1))
        val summaries = env.db.summaries.days(range)
        val days = summaries.mapNotNull { it.restingKcal?.takeIf { k -> k > 0 } }
        // The week bars: the last 7 complete days (0 for none).
        val byDay = summaries.associate { it.day to (it.restingKcal ?: 0).coerceAtLeast(0) }
        val week = (WEEK - 1 downTo 0).map { (byDay[today.plusDays(-1 - it)] ?: 0).toDouble() }
        // "Measured" only when the watch reported its resting metabolic rate; otherwise the days used the formula.
        val zone = env.clock.zone()
        val watch = env.db.metrics.series(MetricKind.RESTING_KCAL, TimeRange(range.first.startSec(zone), today.startSec(zone))).isNotEmpty()
        if (days.isNotEmpty()) {
            return TrendData(days.average(), week, units = env.units, extras = listOf(if (watch) MEASURED else ESTIMATED), endDay = today.plusDays(-1))
        }
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

    /** The last 7 days as bars with day letters and the average as the line (none for a formula estimate). */
    override fun picture(ctx: Context, data: TrendData, small: Boolean): TrendPicture? {
        val end = data.endDay ?: return null
        val avg = data.latest ?: return null
        if (data.series.none { it > 0 }) return null
        return TrendPicture.Bars(
            TrendPictures.dayLetters(end, data.series.size),
            data.series.map { it.toInt() }.toIntArray(),
            goal = avg.toFloat(),
        )
    }

    override fun emptyMessage(ctx: Context): String = ctx.getString(R.string.bmr_empty)

    private const val WEEK = 7

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
        return TrendData(last / 100.0, sorted.values.map { it / 100.0 }, units = env.units, startDay = sorted.firstKey(), endDay = sorted.lastKey())
    }

    override fun format(ctx: Context, value: Double, units: Units): String {
        val v = if (units == Units.METRIC) value else value * F_PER_C
        return (if (v > 0) "+" else "") + Fmt.number(v, 1)
    }

    override fun unit(ctx: Context, units: Units): String = ctx.getString(if (units == Units.METRIC) R.string.unit_celsius_delta else R.string.unit_fahrenheit_delta)

    override fun label(ctx: Context, data: TrendData): String = ctx.getString(R.string.d2_tag_vs_usual)

    override fun note(ctx: Context, data: TrendData): String? = ctx.getString(R.string.temperature_label)

    /** Your usual: within half a degree of the baseline, shaded. */
    override fun lineBand(data: TrendData): Pair<Float, Float> = -NEAR_C.toFloat() to NEAR_C.toFloat()

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

    /** "128/79" (systolic over diastolic), or the systolic alone. */
    override fun valueText(ctx: Context, data: TrendData): String {
        val sys = data.latest?.let { Fmt.number(it, 0) } ?: return ctx.getString(R.string.dash)
        return data.extras.firstOrNull()?.let { ctx.getString(R.string.d2_bp_value, sys, Fmt.number(it, 0)) } ?: sys
    }

    override fun unit(ctx: Context, units: Units): String = ctx.getString(R.string.unit_mmhg)

    // The American Heart Association category is the tag.
    override fun label(ctx: Context, data: TrendData): String = band(data)?.let { ctx.getString(it.name) } ?: ctx.getString(R.string.bp_label)

    override fun note(ctx: Context, data: TrendData): String? = null

    /** The systolic reading on the AHA ranges: normal, elevated, stage 1, stage 2, crisis. */
    override fun picture(ctx: Context, data: TrendData, small: Boolean): TrendPicture? {
        val sys = data.latest ?: return null
        val c = LevelColors.of(ctx)
        return TrendPicture.Range(
            listOf(Band(120f, c.scaleGood), Band(130f, c.scaleClose), Band(140f, c.stressMedium), Band(180f, c.stressHigh), Band(BP_MAX, c.scaleOff)),
            BP_MIN, BP_MAX, sys.toFloat(),
            listOf(120f to "120", 130f to "130", 140f to "140", 180f to "180"),
        )
    }

    private const val BP_MIN = 90f
    private const val BP_MAX = 200f

    override fun status(data: TrendData): CardStatus = band(data)?.status ?: CardStatus.NONE

    /** The American Heart Association category of the latest reading ([GarminBands.bloodPressure]). */
    private fun band(data: TrendData) = data.latest?.let { s -> GarminBands.bloodPressure(s.toInt(), (data.extras.firstOrNull() ?: 0.0).toInt()) }
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

    override fun label(ctx: Context, data: TrendData): String =
        data.latest?.let { ctx.getString(R.string.sleep_score_label_band, ctx.getString(GarminBands.sleepScore(it.toInt()).name)) }
            ?: ctx.getString(R.string.sleep_score_label)

    // Garmin's bands: Excellent 90+, Good 80–89, Fair 60–79, Poor under 60.
    override fun status(data: TrendData): CardStatus = data.latest?.let { GarminBands.sleepScore(it.toInt()).status } ?: CardStatus.NONE

    override val pictureShowsValue: Boolean = true

    /** An arc gauge in Garmin's bands (Poor, Fair, Good, Excellent) with the score and its name inside. */
    override fun picture(ctx: Context, data: TrendData, small: Boolean): TrendPicture? {
        val v = data.latest ?: return null
        return TrendPicture.Gauge(
            CardVisuals.scoreBands(ctx, 60f, 80f, 90f),
            v.toFloat(),
            Fmt.number(v, 0),
            ctx.getString(GarminBands.sleepScore(v.toInt()).name),
        )
    }

    override fun open(host: CardHost): Boolean {
        host.open(SleepDetailFragment())
        return true
    }

    private const val MIN_NIGHTS = 3
}

/**
 * Naps: today's naps in minutes, from the watch's nap events (Garmin: under 3 hours, outside the sleep
 * window). Without nap events, sleep sessions starting between 9:00 and 20:00 count.
 */
object NapsCard : TrendCard("naps", CardTab.BODY, R.string.card_naps, R.string.card_naps_summary) {
    override val tables: Set<Table> = setOf(Table.SLEEP_SESSION, Table.METRIC)

    override val needs: Set<DataKind> = setOf(DataKind.SLEEP)

    override suspend fun load(env: CardEnv): TrendData {
        val zone = env.clock.zone()
        val days = (NAP_DAYS - 1 downTo 0).map { env.day.plusDays(-it) }
        val naps = env.db.metrics.series(MetricKind.NAP_MIN, TimeRange(days.first().startSec(zone), days.last().plusDays(1).startSec(zone)))
        val perDay = if (naps.isNotEmpty()) {
            days.map { d -> naps.filter { Day.of(it.tSec, zone) == d }.sumOf { it.value } }
        } else {
            val sessions = env.db.sleep.sessions(DayRange(days.first(), days.last()), zone)
            days.map { d -> napMinutes(d, sessions.map { it.startSec to it.endSec }, zone) }
        }
        return TrendData(perDay.last().toDouble(), perDay.map { it.toDouble() }, units = env.units, extras = listOf(perDay.last().toDouble()), endDay = days.last())
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

    /** Minutes napped each of the last 7 days, with day letters. */
    override fun picture(ctx: Context, data: TrendData, small: Boolean): TrendPicture? {
        val end = data.endDay ?: return null
        if (data.latest == null || data.series.isEmpty()) return null
        return TrendPicture.Bars(TrendPictures.dayLetters(end, data.series.size), data.series.map { it.toInt() }.toIntArray())
    }

    override fun open(host: CardHost): Boolean {
        host.open(SleepDetailFragment())
        return true
    }

    private const val NAP_DAYS = 7
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

    override val pictureShowsValue: Boolean = true

    /** Three numbers with icons: sleep, HRV, energy (those known). */
    override fun picture(ctx: Context, data: TrendData, small: Boolean): TrendPicture? {
        val sleep = data.latest ?: return null
        val tiles = listOfNotNull(
            TrendPicture.Tile(R.drawable.ic_morning_sleep, format(ctx, sleep, data.units), ctx.getString(R.string.d2_morning_sleep)),
            data.extras.getOrNull(0)?.let {
                TrendPicture.Tile(R.drawable.ic_morning_hrv, Fmt.number(it, 0) + " " + ctx.getString(R.string.unit_ms), ctx.getString(R.string.d2_morning_hrv))
            },
            data.extras.getOrNull(1)?.let { TrendPicture.Tile(R.drawable.ic_morning_energy, Fmt.number(it, 0), ctx.getString(R.string.d2_morning_energy)) },
        )
        return TrendPicture.Tiles(tiles)
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

/**
 * Fitness age (Body), as Garmin derives it from VO2 max ([GarminBands.fitnessAge]): the latest running VO2
 * max (else cycling), with the user's age for comparison. Lower than your age is good.
 */
object FitnessAgeCard : TrendCard("fitness_age", CardTab.BODY, R.string.card_fitness_age, R.string.card_fitness_age_summary) {
    override val tables: Set<Table> = setOf(Table.METRIC, Table.SETTING)

    override val needs: Set<DataKind> = setOf(DataKind.VO2_MAX)

    override suspend fun load(env: CardEnv): TrendData {
        val profile = env.db.settings.profile()
        val age = profile.birthYear?.let { env.clock.today().year - it }?.takeIf { it in 10..100 } ?: return TrendData(null, units = env.units)
        val vo2 = (env.db.metrics.latest(MetricKind.VO2_MAX_RUNNING) ?: env.db.metrics.latest(MetricKind.VO2_MAX_CYCLING))?.value
            ?: return TrendData(null, units = env.units)
        return TrendData(GarminBands.fitnessAge(vo2 / 10.0, profile.sex).toDouble(), units = env.units, extras = listOf(age.toDouble()))
    }

    override fun format(ctx: Context, value: Double, units: Units): String = Fmt.number(value, 0)

    override fun unit(ctx: Context, units: Units): String = ctx.getString(R.string.fitness_age_unit)

    override fun label(ctx: Context, data: TrendData): String =
        ctx.getString(R.string.fitness_age_label, Fmt.number(data.extras.firstOrNull() ?: 0.0, 0))

    override fun note(ctx: Context, data: TrendData): String? = null

    /** Two bars: fitness age (good when at or under your age) and your age. */
    override fun picture(ctx: Context, data: TrendData, small: Boolean): TrendPicture? {
        val f = data.latest ?: return null
        val age = data.extras.firstOrNull() ?: return null
        val c = LevelColors.of(ctx)
        val fitColor = if (f <= age) c.good else c.close
        return TrendPicture.Bars(
            listOf(ctx.getString(R.string.d2_fitness_bar), ctx.getString(R.string.d2_age_bar)),
            intArrayOf(f.toInt(), age.toInt()),
            colorFor = { g, _ -> if (g == 0) fitColor else c.track },
            description = ctx.getString(R.string.d2_fitness_description, Fmt.number(f, 0), Fmt.number(age, 0)),
        )
    }

    override fun status(data: TrendData): CardStatus {
        val f = data.latest ?: return CardStatus.NONE
        val age = data.extras.firstOrNull() ?: return CardStatus.NONE
        return if (f <= age) CardStatus.GOOD else CardStatus.CLOSE
    }

    override fun emptyMessage(ctx: Context): String = ctx.getString(R.string.fitness_age_empty)
}

/** The last ECG recording. */
val EcgCard = WaitingCard("ecg", CardTab.BODY, R.string.card_ecg, R.string.card_ecg_summary, DataKind.ECG, CardSize.LARGE)

/** Cycle tracking. */
val CycleTrackingCard = WaitingCard("cycle_tracking", CardTab.BODY, R.string.card_cycle_tracking, R.string.card_cycle_tracking_summary, DataKind.CYCLE_TRACKING)

/** Body clock vs destination time. */
val JetLagCard = WaitingCard("jet_lag", CardTab.BODY, R.string.card_jet_lag, R.string.card_jet_lag_summary, DataKind.JET_LAG)

/** Blood glucose readings. */
val BloodGlucoseCard = WaitingCard("blood_glucose", CardTab.BODY, R.string.card_blood_glucose, R.string.card_blood_glucose_summary, DataKind.BLOOD_GLUCOSE)
