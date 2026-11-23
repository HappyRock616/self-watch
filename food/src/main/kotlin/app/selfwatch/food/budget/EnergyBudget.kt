/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.budget

import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.food.nutrition.NutrientMath
import app.selfwatch.model.food.Goal
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.food.Sex
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day

/** Resting energy estimates. */
object Bmr {
    /**
     * Mifflin-St Jeor resting energy in kcal/day: `10 × kg + 6.25 × cm − 5 × years + s`, where `s` is
     * +5 for men, −161 for women and −78 (their midpoint) when sex is not given. Whole-number maths,
     * rounded half away from zero; never below 0.
     */
    fun mifflinStJeor(weightKgX100: Int, heightCm: Int, ageYears: Int, sex: Sex): Int {
        val s = when (sex) {
            Sex.MALE -> 5L
            Sex.FEMALE -> -161L
            Sex.UNSPECIFIED -> -78L
        }
        // ×100: 10 × (kgX100/100) × 100 = 10 × kgX100; 6.25 × cm × 100 = 625 × cm.
        val x100 = 10L * weightKgX100 + 625L * heightCm - 500L * ageYears + 100L * s
        return NutrientMath.clampInt(NutrientMath.divRound(x100, 100)).coerceAtLeast(0)
    }
}

/** How the daily calorie goal is set. */
enum class GoalMode {
    /** From resting energy, activity baseline and the chosen weekly rate. */
    AUTO,

    /** A number the user typed. */
    MANUAL,
}

/**
 * Today's energy budget. **remaining = goal − food + activity**, where activity is the watch's
 * active calories; resting calories are already part of the goal.
 *
 * @property floorApplied the automatic goal was raised to the safety floor (show a warning).
 * @property restingEstimated resting energy came from Mifflin-St Jeor or the default, not the watch.
 */
data class Budget(
    val goalKcal: Int,
    val eatenKcal: Int,
    val activeKcal: Int,
    val restingKcal: Int,
    val remainingKcal: Int,
    val mode: GoalMode,
    val floorApplied: Boolean = false,
    val restingEstimated: Boolean = false,
)

/** An automatic goal and whether the floor raised it. */
data class AutoGoal(val kcal: Int, val floorApplied: Boolean)

/**
 * Builds [Budget]s from the database: food from the diary, active and resting calories from today's
 * day summary (the watch), the goal from settings and the goal table.
 *
 * Settings (table `setting`): [KEY_MODE] (`auto` or `manual`, default auto) and [KEY_RATE_G_PER_WEEK]
 * (planned weight change in grams per week; negative to lose, default 0). The manual goal is the
 * `KCAL_IN` goal in force today. Computing today's budget also stores its goal in
 * [SettingKeys.FOOD_GOAL_EFFECTIVE_KCAL] (for the watch's calories-left message).
 */
class EnergyBudget(private val db: SelfWatchDb, private val clock: Clock) {

    /** The budget for today. */
    suspend fun today(): Budget = forDay(clock.today())

    /** The budget for [day] (the profile and weight are today's). */
    suspend fun forDay(day: Day): Budget {
        val summary = db.summaries.day(day)
        val eaten = db.diary.dayTotals(day).kcalX10?.let { NutrientMath.clampInt(NutrientMath.divRound(it.toLong(), 10)) } ?: 0
        val active = summary?.activeKcal ?: 0
        val profile = db.settings.profile()
        val sex = profile.sex
        var estimated = false
        val resting = summary?.restingKcal?.takeIf { it > 0 } ?: run {
            estimated = true
            val weight = db.body.latest(BodyKind.WEIGHT)?.valueX100
            val height = profile.heightCm
            val age = profile.birthYear?.let { day.year - it }
            if (weight != null && height != null && age != null && age in 1..130) {
                Bmr.mifflinStJeor(weight, height, age, sex)
            } else {
                DEFAULT_RESTING_KCAL
            }
        }
        val mode = mode()
        val manual = db.goals.goalOn(GoalKind.KCAL_IN, day)?.value
        val budget = if (mode == GoalMode.MANUAL && manual != null) {
            Budget(manual, eaten, active, resting, remaining(manual, eaten, active), GoalMode.MANUAL, restingEstimated = estimated)
        } else {
            val auto = autoGoal(resting, rateGramsPerWeek(), sex)
            Budget(auto.kcal, eaten, active, resting, remaining(auto.kcal, eaten, active), GoalMode.AUTO, auto.floorApplied, estimated)
        }
        if (day == clock.today()) publishGoal(budget.goalKcal)
        return budget
    }

    /**
     * Stores today's effective goal in [SettingKeys.FOOD_GOAL_EFFECTIVE_KCAL] for the service (which
     * never reads the food module), only when it changed, so observers of the setting table don't loop.
     */
    private suspend fun publishGoal(kcal: Int) {
        if (db.settings.getInt(SettingKeys.FOOD_GOAL_EFFECTIVE_KCAL) != kcal) db.settings.setInt(SettingKeys.FOOD_GOAL_EFFECTIVE_KCAL, kcal)
    }

    /**
     * The goal mode setting. With no setting stored yet, a calorie goal the user typed (onboarding,
     * Me > Goals) means manual, so the number they chose is the one every screen uses.
     */
    suspend fun mode(): GoalMode = when (db.settings.get(KEY_MODE)) {
        MODE_MANUAL -> GoalMode.MANUAL
        MODE_AUTO -> GoalMode.AUTO
        else -> if (db.goals.goalOn(GoalKind.KCAL_IN, clock.today()) != null) GoalMode.MANUAL else GoalMode.AUTO
    }

    /** The planned weekly change in grams (negative to lose). */
    suspend fun rateGramsPerWeek(): Int = db.settings.getInt(KEY_RATE_G_PER_WEEK) ?: 0

    /** Switches to an automatic goal with [rateGramsPerWeek] (negative to lose weight). */
    suspend fun setAuto(rateGramsPerWeek: Int) {
        db.settings.setAll(mapOf(KEY_MODE to MODE_AUTO, KEY_RATE_G_PER_WEEK to rateGramsPerWeek.toString()))
    }

    /** Switches to a manual goal of [kcal] from today on. */
    suspend fun setManual(kcal: Int) {
        require(kcal > 0) { "goal must be positive" }
        db.goals.set(Goal(GoalKind.KCAL_IN, kcal, clock.today()))
        db.settings.set(KEY_MODE, MODE_MANUAL)
    }

    /** Setting keys, constants and the pure rules. */
    companion object {
        /** Setting: `auto` or `manual`. */
        const val KEY_MODE: String = SettingKeys.FOOD_GOAL_MODE

        /** Setting: planned weight change, grams per week (int, negative to lose). */
        const val KEY_RATE_G_PER_WEEK: String = SettingKeys.FOOD_GOAL_RATE_G_PER_WEEK

        private const val MODE_AUTO = "auto"
        private const val MODE_MANUAL = "manual"

        /** Resting energy used when neither the watch nor the profile can give one. */
        const val DEFAULT_RESTING_KCAL: Int = 1600

        /** Energy in one kilogram of body weight change. */
        const val KCAL_PER_KG: Int = 7700

        /** Activity baseline: sedentary × 1.2 (as ×10). */
        const val SEDENTARY_X10: Int = 12

        /** The lowest automatic goal for [sex]: 1,200 women, 1,500 men, 1,350 unspecified. */
        fun floorFor(sex: Sex): Int = when (sex) {
            Sex.FEMALE -> 1200
            Sex.MALE -> 1500
            Sex.UNSPECIFIED -> 1350
        }

        /**
         * The automatic goal: `resting × 1.2 + rate × 7,700 / 7,000` (rate in g/week, so a 500 g/week
         * loss is −550 kcal/day), raised to [floorFor] when below it.
         */
        fun autoGoal(restingKcal: Int, rateGramsPerWeek: Int, sex: Sex): AutoGoal {
            val base = NutrientMath.divRound(restingKcal.toLong() * SEDENTARY_X10, 10)
            // g/week × 7.7 kcal/g ÷ 7 days
            val perDay = NutrientMath.divRound(rateGramsPerWeek.toLong() * KCAL_PER_KG, 7L * 1000)
            val goal = base + perDay
            val floor = floorFor(sex)
            return if (goal < floor) AutoGoal(floor, true) else AutoGoal(NutrientMath.clampInt(goal), false)
        }

        /** goal − food + activity. */
        fun remaining(goalKcal: Int, eatenKcal: Int, activeKcal: Int): Int =
            NutrientMath.clampInt(goalKcal.toLong() - eatenKcal + activeKcal)
    }
}
