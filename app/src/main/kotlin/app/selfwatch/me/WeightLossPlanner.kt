/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.me

import android.content.Context
import app.selfwatch.R
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.food.budget.Bmr
import app.selfwatch.food.budget.EnergyBudget
import app.selfwatch.food.cards.BurnSource
import app.selfwatch.food.cards.WeightGoalMath
import app.selfwatch.model.food.Profile
import app.selfwatch.model.food.Sex
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A daily calorie goal worked out from a goal weight and date ([WeightLossPlanner.advise]).
 *
 * @property kcal the daily calorie goal to fill in.
 * @property burnKcal the estimated daily burn it starts from.
 * @property ratePerWeekX100 the weekly change it gives, kg ×100 (negative to lose).
 * @property floorKcal the safe minimum for the user's sex; [floorApplied] when the goal was raised to it.
 * @property tooSoon the goal date asks for more than the safe pace; [earliestSafe] is the earliest safe date.
 * @property stalled at the safe minimum the weight would not change (the burn is at or below it).
 */
data class CalorieAdvice(
    val kcal: Int,
    val burnKcal: Int,
    val source: BurnSource,
    val ratePerWeekX100: Int,
    val floorKcal: Int,
    val floorApplied: Boolean,
    val tooSoon: Boolean,
    val earliestSafe: Day?,
    val stalled: Boolean,
) {
    /** Calories a day below (negative) or above the burn. */
    val deltaKcal: Int get() = kcal - burnKcal
}

/**
 * The weight loss goal flow (Goals screen and onboarding): goal weight, then goal date, then a daily
 * calorie goal from [WeightGoalMath] (at most 1% of body weight a week to lose, 0.5% to gain, never below
 * 1,200 / 1,500 / 1,350 kcal for women / men / unspecified). The user can still change the result.
 */
object WeightLossPlanner {

    /** Days of device data needed before the measured burn is used. */
    const val MIN_MEASURED_DAYS: Int = 7

    /** Days looked back for the measured burn. */
    const val BURN_DAYS: Int = 21

    /** The advice for going from [currentX100] to [goalX100] (kg ×100) by [goalDate]. */
    fun advise(currentX100: Int, goalX100: Int, today: Day, goalDate: Day, burnKcal: Int, source: BurnSource, sex: Sex): CalorieAdvice {
        val floor = EnergyBudget.floorFor(sex)
        val plan = WeightGoalMath.plan(currentX100, goalX100, today, goalDate, burnKcal, source, floor)
        val moving = abs(goalX100 - currentX100) > WeightGoalMath.THERE_X100
        // Stalled: a goal to reach, but the safe floor leaves no deficit (the burn is at or below it).
        val stalled = moving && plan.ratePerWeekX100 == 0
        // Too soon is only worth saying when the plan moves at all; a stalled plan has its own message.
        val tooSoon = moving && !stalled && !plan.onTime(goalDate)
        return CalorieAdvice(
            kcal = plan.targetKcal,
            burnKcal = burnKcal,
            source = source,
            ratePerWeekX100 = plan.ratePerWeekX100,
            floorKcal = floor,
            floorApplied = plan.floorApplied,
            tooSoon = tooSoon,
            earliestSafe = if (tooSoon) plan.reachDay else null,
            stalled = stalled,
        )
    }

    /**
     * The daily burn from the profile: Mifflin-St Jeor resting energy × 1.2 (a lightly active day) when
     * weight, height and birth year are known, else the app's default resting energy × 1.2.
     */
    fun estimatedBurn(weightX100: Int?, profile: Profile, today: Day): Int {
        val age = profile.birthYear?.let { today.year - it }
        val height = profile.heightCm
        val resting = if (weightX100 != null && height != null && age != null && age in 1..130) {
            Bmr.mifflinStJeor(weightX100, height, age, profile.sex)
        } else {
            EnergyBudget.DEFAULT_RESTING_KCAL
        }
        return (resting * WeightGoalMath.LIGHT_ACTIVITY).roundToInt()
    }

    /** The burn: the devices' measured resting + active calories over recent weeks when there are enough days, else [estimatedBurn]. */
    suspend fun burn(db: SelfWatchDb, today: Day, weightX100: Int?): Pair<Int, BurnSource> {
        // Whole days only (today is still running), and only days with a resting figure: a day with only
        // active calories would make the burn look far too low.
        val window = DayRange(today.plusDays(-BURN_DAYS), today.plusDays(-1))
        val measured = db.summaries.days(window).mapNotNull { s -> s.restingKcal?.takeIf { it > 0 }?.let { it + (s.activeKcal ?: 0) } }
        if (measured.size >= MIN_MEASURED_DAYS) return measured.average().roundToInt() to BurnSource.MEASURED
        return estimatedBurn(weightX100, db.settings.profile(), today) to BurnSource.ESTIMATED
    }

    /** The one-line explanation of how [a] was worked out, weights in [units]. */
    fun explain(ctx: Context, a: CalorieAdvice, units: Units): String {
        val burn = Fmt.number(a.burnKcal.toLong())
        val target = Fmt.number(a.kcal.toLong())
        val from = ctx.getString(if (a.source == BurnSource.MEASURED) R.string.wl_burn_measured else R.string.wl_burn_estimated)
        val base = when {
            a.stalled -> ctx.getString(R.string.wl_explain_stalled, target, burn, from)
            a.ratePerWeekX100 == 0 -> ctx.getString(R.string.wl_explain_steady, burn, from)
            else -> ctx.getString(
                if (a.ratePerWeekX100 < 0) R.string.wl_explain_lose else R.string.wl_explain_gain,
                burn, from, Fmt.number(abs(a.deltaKcal).toLong()), weightText(ctx, abs(a.ratePerWeekX100), units), target,
            )
        }
        val floor = if (a.floorApplied && !a.stalled) " " + ctx.getString(R.string.wl_explain_floor, Fmt.number(a.floorKcal.toLong())) else ""
        return base + floor
    }

    /** "0.5 kg" or "1.1 lb". */
    fun weightText(ctx: Context, kgX100: Int, units: Units): String =
        if (units == Units.METRIC) {
            ctx.getString(R.string.wl_kg, Fmt.x100(kgX100.toLong(), 1))
        } else {
            ctx.getString(R.string.wl_lb, Fmt.x100((kgX100 / KG_PER_LB).roundToInt().toLong(), 1))
        }

    private const val KG_PER_LB = 0.45359237
}
