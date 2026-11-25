/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.cards

import app.selfwatch.model.time.Day
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sign

/** Where the daily burn of a weight plan comes from. */
enum class BurnSource {
    /** Intake and the weight trend over recent weeks (what really happened; re-adjusted weekly). */
    ADAPTIVE,

    /** The devices' measured resting + active calories. */
    MEASURED,

    /** The BMR formula (Mifflin-St Jeor) for a lightly active day: no device data yet. */
    ESTIMATED,
}

/**
 * A weight plan: eat [targetKcal] a day to change weight by [ratePerWeekX100] (kg ×100 a week, negative
 * to lose) from a daily burn of [burnKcal]. [capped]: the goal date asked for a faster rate than the safe
 * one, so the plan uses the safe one; [floorApplied]: the target was raised to the safe minimum.
 * [reachDay] is when the goal weight is reached at this rate (null when already there).
 */
data class WeightPlan(
    val targetKcal: Int,
    val burnKcal: Int,
    val source: BurnSource,
    val ratePerWeekX100: Int,
    val capped: Boolean,
    val floorApplied: Boolean,
    val reachDay: Day?,
) {
    /** True when the plan reaches the goal by [goalDate] (always true without a date). */
    fun onTime(goalDate: Day?): Boolean = goalDate == null || reachDay == null || reachDay <= goalDate
}

/**
 * The calorie recommendation behind the Weight goal card (pure):
 *
 * - **Burn**: from the weight trend and intake when there is enough of both ([adaptiveBurn]: what you
 *   ate minus what the scale says you stored), else the devices' measured resting + active calories,
 *   else the BMR formula × 1.2.
 * - **Rate**: what reaches the goal weight by the goal date, capped at 1% of body weight a week for
 *   losing and 0.5% for gaining; without a date, a gentle 0.5% a week.
 * - **Target**: burn ± the rate's daily calories (7,700 kcal per kg), never below the safe minimum
 *   (1,200 / 1,500 / 1,350 kcal for women / men / unspecified).
 */
object WeightGoalMath {
    /** Setting: the goal date, `yyyymmdd`. */
    const val DATE_KEY: String = "goal.weight_date"

    /** Energy in one kg of body weight, kcal. */
    const val KCAL_PER_KG: Int = 7_700

    /** Fastest safe loss: share of body weight a week. */
    const val MAX_LOSS_SHARE: Double = 0.01

    /** Fastest sensible gain (mostly lean): share of body weight a week. */
    const val MAX_GAIN_SHARE: Double = 0.005

    /** The rate used without a goal date: share of body weight a week. */
    const val GENTLE_SHARE: Double = 0.005

    /** Within this of the goal (kg ×100) counts as there: keep weight steady. */
    const val THERE_X100: Int = 50

    /** The BMR multiplier for a lightly active day (used only without device data). */
    const val LIGHT_ACTIVITY: Double = 1.2

    private const val DAYS_PER_WEEK = 7.0
    private const val X100 = 100.0

    /**
     * The daily burn from intake and the trend: the average intake [avgIntakeKcal] minus the energy the
     * trend change [trendChangeX100] (kg ×100 over [days] days) says was stored.
     */
    fun adaptiveBurn(avgIntakeKcal: Double, trendChangeX100: Int, days: Int): Int =
        // Energy balance: what was eaten = what was burned + what was stored. A falling trend (negative
        // change) means stored energy went out, so the burn is above the intake.
        (avgIntakeKcal - trendChangeX100 / X100 * KCAL_PER_KG / days).roundToInt()

    /** The weekly rate (kg ×100, signed) to go from [currentX100] to [goalX100] by [goalDate] from [today], capped. */
    fun rate(currentX100: Int, goalX100: Int, today: Day, goalDate: Day?): Pair<Int, Boolean> {
        val delta = goalX100 - currentX100
        if (abs(delta) <= THERE_X100) return 0 to false
        // The cap is a share of today's weight, so it shrinks as the weight comes down.
        val max = currentX100 * (if (delta < 0) MAX_LOSS_SHARE else MAX_GAIN_SHARE)
        // A goal date today or in the past gives no weeks: treated like no date (the gentle rate).
        val weeks = goalDate?.let { ChronoUnit.DAYS.between(today.toLocalDate(), it.toLocalDate()) / DAYS_PER_WEEK }?.takeIf { it > 0 }
        val needed = if (weeks == null) currentX100 * GENTLE_SHARE else abs(delta) / weeks
        // Only a chosen date can be "too soon"; the gentle default is never reported as capped.
        val capped = weeks != null && needed > max
        val r = minOf(needed, max)
        return (sign(delta.toDouble()) * r).roundToInt() to capped
    }

    /** The plan for the inputs; [floorKcal] is the safe minimum to eat. */
    fun plan(
        currentX100: Int,
        goalX100: Int,
        today: Day,
        goalDate: Day?,
        burnKcal: Int,
        source: BurnSource,
        floorKcal: Int,
    ): WeightPlan {
        val (rate, capped) = rate(currentX100, goalX100, today, goalDate)
        val raw = burnKcal + (rate / X100 * KCAL_PER_KG / DAYS_PER_WEEK).roundToInt()
        val floorApplied = raw < floorKcal
        val target = maxOf(raw, floorKcal)
        // The rate the target really gives (the floor can slow a loss down).
        val actual = if (rate == 0) 0 else ((target - burnKcal) * DAYS_PER_WEEK / KCAL_PER_KG * X100).roundToInt()
        val delta = goalX100 - currentX100
        val reach = when {
            abs(delta) <= THERE_X100 -> null
            // At the floor with a burn below it the plan gains (or holds) instead of losing: never reached.
            actual == 0 || sign(actual.toDouble()) != sign(delta.toDouble()) -> null
            else -> today.plusDays(ceil(abs(delta).toDouble() / abs(actual) * DAYS_PER_WEEK).toInt())
        }
        return WeightPlan(target, burnKcal, source, actual, capped, floorApplied, reach)
    }

    /** Weekly projected weights (kg ×100) from [currentX100] at [ratePerWeekX100] until [goalX100], at most [maxWeeks]. */
    fun projection(currentX100: Int, ratePerWeekX100: Int, goalX100: Int, maxWeeks: Int): List<Int> {
        if (ratePerWeekX100 == 0) return listOf(currentX100)
        val out = arrayListOf(currentX100)
        var w = currentX100
        while (out.size <= maxWeeks) {
            w += ratePerWeekX100
            val past = if (ratePerWeekX100 < 0) w <= goalX100 else w >= goalX100
            // The last week lands on the goal, not past it, so the chart line ends at the goal.
            out += if (past) goalX100 else w
            if (past) break
        }
        return out
    }

    /** BMI of [kgX100] at [heightCm], or null without a height. */
    fun bmi(kgX100: Int, heightCm: Int?): Double? {
        val h = heightCm?.takeIf { it > 0 } ?: return null
        val m = h / X100
        return kgX100 / X100 / (m * m)
    }
}
