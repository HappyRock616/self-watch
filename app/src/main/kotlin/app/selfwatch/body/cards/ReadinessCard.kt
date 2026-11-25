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
import app.selfwatch.activity.ActivityWeek
import app.selfwatch.body.BodyTrendLoader
import app.selfwatch.body.HeartDetailFragment
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.TrendCard
import app.selfwatch.cards.TrendData
import app.selfwatch.db.Table
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import kotlin.math.roundToInt

/** What lowered (or set) the readiness score most. */
enum class ReadinessFactor { SLEEP, HRV, RESTING_HR, LOAD }

/**
 * Readiness arithmetic: four factors, each scored 0–100, averaged with weights (sleep 35, HRV 35,
 * resting heart rate 15, recent load 15). Missing factors are left out and the weights of the rest
 * scaled up; without sleep and HRV there is no score. Self Watch works it out itself, so every device
 * with sleep and HRV gets one.
 */
object ReadinessMath {
    private const val W_SLEEP = 35
    private const val W_HRV = 35
    private const val W_RHR = 15
    private const val W_LOAD = 15
    private const val FULL = 100.0

    /** Score at the edge of "too far off" for HRV (share below the low end of usual), resting HR and load. */
    private const val HRV_ZERO_SHARE = 0.3
    private const val RHR_ZERO_BPM = 10.0
    private const val LOAD_ZERO_RATIO = 2.0

    /** Score from which you are ready, and from which "take it steady". */
    const val READY: Int = 70
    const val STEADY: Int = 40

    /** Sleep factor: [sleepMin] of [goalMin], full at the goal. */
    fun sleep(sleepMin: Int, goalMin: Int): Double = if (goalMin <= 0) FULL else (sleepMin.toDouble() / goalMin * FULL).coerceIn(0.0, FULL)

    /** HRV factor: full at or above the low end of the usual range [lowMs], zero 30% below it. */
    fun hrv(ms: Int, lowMs: Int): Double {
        if (lowMs <= 0 || ms >= lowMs) return FULL
        val short = (lowMs - ms).toDouble() / lowMs
        return (FULL * (1 - short / HRV_ZERO_SHARE)).coerceIn(0.0, FULL)
    }

    /** Resting heart rate factor: full at or below the usual [usualBpm], zero 10 bpm above it. */
    fun restingHr(bpm: Int, usualBpm: Int): Double {
        val over = (bpm - usualBpm).toDouble()
        return if (over <= 0) FULL else (FULL * (1 - over / RHR_ZERO_BPM)).coerceIn(0.0, FULL)
    }

    /** Load factor: full up to the recent weekly average [usual] (workout minutes), zero at twice it. */
    fun load(thisWeek: Int, usual: Int): Double {
        if (usual <= 0 || thisWeek <= usual) return FULL
        val ratio = thisWeek.toDouble() / usual
        return (FULL * (LOAD_ZERO_RATIO - ratio) / (LOAD_ZERO_RATIO - 1)).coerceIn(0.0, FULL)
    }

    /** The score (0–100) and the weakest factor, or null without sleep and HRV. */
    fun score(sleep: Double?, hrv: Double?, restingHr: Double?, load: Double?): Pair<Int, ReadinessFactor>? {
        if (sleep == null && hrv == null) return null
        val parts = listOfNotNull(
            sleep?.let { Triple(ReadinessFactor.SLEEP, it, W_SLEEP) },
            hrv?.let { Triple(ReadinessFactor.HRV, it, W_HRV) },
            restingHr?.let { Triple(ReadinessFactor.RESTING_HR, it, W_RHR) },
            load?.let { Triple(ReadinessFactor.LOAD, it, W_LOAD) },
        )
        val total = parts.sumOf { it.third }
        val score = parts.sumOf { it.second * it.third } / total
        return score.roundToInt() to parts.minBy { it.second }.first
    }
}

/**
 * Readiness: ready to train today, 0–100, from last night's sleep against the sleep goal, last night's
 * HRV against your usual range, resting heart rate against your usual, and this week's workout time
 * against the weeks before. Opens Heart.
 */
object ReadinessCard : TrendCard("readiness", CardTab.BODY, R.string.card_readiness, R.string.card_readiness_summary) {
    override val tables: Set<Table> = setOf(Table.SLEEP_SESSION, Table.DAY_SUMMARY, Table.WORKOUT, Table.GOAL)

    override val needs: Set<DataKind> = setOf(DataKind.SLEEP, DataKind.HRV)

    override suspend fun load(env: CardEnv): TrendData {
        val trends = BodyTrendLoader(env.db, env.clock)
        val today = env.clock.today()
        val goal = env.db.goals.goalOn(GoalKind.SLEEP_MIN, today)?.value ?: BodyTodayCard.DEFAULT_SLEEP_MIN
        val summary = env.db.summaries.day(today)
        val sleep = trends.sleep().lastMin?.let { ReadinessMath.sleep(it, goal) }
        val hrvBase = trends.hrv().result
        val hrv = summary?.hrvAvgMs?.let { ms -> hrvBase?.let { ReadinessMath.hrv(ms, it.lowMs) } }
        val rhrTrend = trends.restingHr()
        val rhr = summary?.restingHr?.let { bpm -> rhrTrend?.let { ReadinessMath.restingHr(bpm, it.baselineBpm ?: it.currentBpm) } }
        val load = recentLoad(env)
        val (score, weakest) = ReadinessMath.score(sleep, hrv, rhr, load) ?: return TrendData(null, units = env.units)
        return TrendData(score.toDouble(), units = env.units, extras = listOf(weakest.ordinal.toDouble()))
    }

    /**
     * The load factor: workout minutes this week so far against the average full week of the 4 before
     * (cheap; Training load's zone time needs the workouts' files). Null without earlier workouts.
     */
    private suspend fun recentLoad(env: CardEnv): Double? {
        val monday = ActivityWeek.start(env.clock.today())
        val workouts = env.db.workouts.forExport(DayRange(monday.plusDays(-LOAD_WEEKS * ActivityWeek.DAYS), env.clock.today()), env.clock.zone())
        val zone = env.clock.zone()
        val thisWeek = workouts.filter { Day.of(it.startSec, zone) >= monday }.sumOf { it.durationSec } / MINUTE
        val before = workouts.filter { Day.of(it.startSec, zone) < monday }.sumOf { it.durationSec } / MINUTE / LOAD_WEEKS
        return if (before <= 0) null else ReadinessMath.load(thisWeek, before)
    }

    private const val LOAD_WEEKS = 4
    private const val MINUTE = 60

    override fun format(ctx: Context, value: Double, units: Units): String = Fmt.number(value, 0)

    override fun unit(ctx: Context, units: Units): String = ctx.getString(R.string.readiness_unit)

    override fun label(ctx: Context, data: TrendData): String {
        val v = data.latest ?: 0.0
        return ctx.getString(
            when {
                v >= ReadinessMath.READY -> R.string.readiness_ready
                v >= ReadinessMath.STEADY -> R.string.readiness_steady
                else -> R.string.readiness_rest
            },
        )
    }

    override fun note(ctx: Context, data: TrendData): String? {
        val factor = data.extras.firstOrNull()?.toInt()?.let { ReadinessFactor.entries.getOrNull(it) } ?: return null
        if ((data.latest ?: 0.0) >= ReadinessMath.READY) return ctx.getString(R.string.readiness_note_good)
        return ctx.getString(
            when (factor) {
                ReadinessFactor.SLEEP -> R.string.readiness_note_sleep
                ReadinessFactor.HRV -> R.string.readiness_note_hrv
                ReadinessFactor.RESTING_HR -> R.string.readiness_note_rhr
                ReadinessFactor.LOAD -> R.string.readiness_note_load
            },
        )
    }

    override fun status(data: TrendData): CardStatus {
        val v = data.latest ?: return CardStatus.NONE
        return when {
            v >= ReadinessMath.READY -> CardStatus.GOOD
            v >= ReadinessMath.STEADY -> CardStatus.CLOSE
            else -> CardStatus.OFF
        }
    }

    override fun emptyMessage(ctx: Context): String = ctx.getString(R.string.readiness_empty)

    override fun open(host: CardHost): Boolean {
        host.open(HeartDetailFragment())
        return true
    }
}
