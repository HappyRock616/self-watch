/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.cards

import app.selfwatch.cards.MainGoal
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.food.budget.EnergyBudget
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.roundToInt

/** The week holding the day shown: its 7 [days], which have diary entries, and which are still ahead. */
data class LoggingWeek(val days: List<Day>, val logged: List<Boolean>, val ahead: List<Boolean>) {
    /** Days logged so far this week. */
    val count: Int get() = logged.count { it }
}

/**
 * Protein for the day shown per kg of body weight against [target].
 *
 * @property proteinG protein logged (null when no entry reports it).
 * @property kgX100 the latest weight on or before the day (null without a weigh-in).
 * @property perKg protein ÷ weight, or null when either is missing.
 */
data class ProteinPerKg(val proteinG: Int?, val kgX100: Int?, val perKg: Double?, val target: ProteinTarget, val goalG: Int?)

/** One meal's protein: the slot [name], grams logged (null when nothing or no protein value), and whether it was eaten. */
data class MealProtein(val name: String, val proteinG: Int?, val eaten: Boolean)

/** Meals of the day shown and the protein a meal needs to count ([thresholdG]; [fromWeight] when from body weight). */
data class ProteinMeals(val meals: List<MealProtein>, val thresholdG: Int, val fromWeight: Boolean) {
    /** Meals that reached the threshold. */
    val reached: Int get() = meals.count { (it.proteinG ?: 0) >= thresholdG }

    /** Meals with something logged. */
    val eaten: Int get() = meals.count { it.eaten }
}

/** Carbs for the day shown per kg against the [band] from the last 7 days' training ([avgTrainingMin] a day). */
data class CarbsPerKg(val carbsG: Int?, val kgX100: Int?, val perKg: Double?, val band: CarbBand, val avgTrainingMin: Int)

/**
 * Fuel around the latest workout of an hour or more today or the day before ([workout] null: none).
 *
 * @property carbsG carbs logged from 30 minutes before to 30 minutes after it.
 * @property gPerHour [carbsG] per hour of the workout.
 * @property entries diary entries in that window; [unknown] of them have no carb value.
 */
data class WorkoutFuel(
    val workout: Workout?,
    val carbsG: Int,
    val gPerHour: Int,
    val band: IntRange,
    val entries: Int,
    val unknown: Int,
)

/** What the fuel balance still needs before it can show a number. */
enum class FuelMissing { NONE, WEIGHT, BODY_FAT, DAYS }

/**
 * Seven-day fuel balance: average (kcal eaten − active kcal) per kg of fat-free mass.
 *
 * @property kcalPerKgFfm the estimate, or null when [missing] is not [FuelMissing.NONE].
 * @property loggedDays days with diary entries among the 7.
 */
data class FuelBalance(
    val kcalPerKgFfm: Int?,
    val avgEatenKcal: Int?,
    val avgActiveKcal: Int?,
    val ffmKg: Double?,
    val loggedDays: Int,
    val missing: FuelMissing,
) {
    /** True when the estimate is under 30 kcal/kg FFM (the soft flag). */
    val low: Boolean get() = kcalPerKgFfm != null && kcalPerKgFfm < FoodCardMath.LOW_FUEL_KCAL_PER_KG_FFM
}

/** Sodium for the day shown against the limit, with how many entries report sodium. */
data class SodiumDay(val coverage: Coverage, val limitMg: Int)

/** Fibre for the day shown, with the target from calories eaten (null with nothing eaten). */
data class FibreDay(val coverage: Coverage, val kcal: Int?, val targetG: Int?)

/** Total sugar for the day shown (natural and added together) and the calories eaten. */
data class SugarDay(val coverage: Coverage, val kcal: Int?)

/**
 * Reads the Food cards' numbers. Food always comes from the diary itself (never from day summaries,
 * which can lag behind it); activity and body values come from their own tables.
 */
class FoodCardLoader(private val db: SelfWatchDb, private val clock: Clock) {

    /** The latest weight on or before [day], else the latest at all. */
    suspend fun weightKgX100(day: Day): Int? = latestOnOrBefore(BodyKind.WEIGHT, day)

    private suspend fun latestOnOrBefore(kind: BodyKind, day: Day): Int? =
        db.body.series(kind, DayRange(day.plusDays(-LOOKBACK_DAYS), day), clock.zone()).lastOrNull()?.valueX100
            ?: db.body.latest(kind)?.valueX100

    private suspend fun mainGoal(): MainGoal = MainGoal.byId(db.settings.get(SettingKeys.MAIN_GOAL))

    private suspend fun workouts(range: DayRange): List<Workout> = db.workouts.forExport(range, clock.zone())

    /** Days with diary entries in the week holding [day] (the week starts on [firstDay]). */
    suspend fun loggingWeek(day: Day, firstDay: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek): LoggingWeek {
        val days = FoodCardMath.weekOf(day, firstDay)
        val logged = db.diary.dailyKcalX10(DayRange(days.first(), days.last())).keys
        val today = clock.today()
        return LoggingWeek(days, days.map { it in logged }, days.map { it > today })
    }

    /** Protein per kg for [day], against the target for the main goal or the user's own protein goal. */
    suspend fun proteinPerKg(day: Day): ProteinPerKg {
        val proteinX10 = db.diary.dayTotals(day).proteinX10
        val kg = weightKgX100(day)
        val goalG = db.goals.goalOn(GoalKind.PROTEIN_G, day)?.value
        val target = if (goalG != null && kg != null) {
            FoodCardMath.ownProteinTarget(goalG, kg)
        } else {
            val strength = workouts(DayRange(day.plusDays(-6), day)).any(FoodCardMath::isStrength)
            FoodCardMath.proteinTarget(mainGoal(), strength)
        }
        val perKg = if (proteinX10 != null && kg != null) FoodCardMath.perKg(proteinX10.toLong(), kg) else null
        return ProteinPerKg(grams(proteinX10?.toLong()), kg, perKg, target, goalG)
    }

    /** Protein per meal for [day]: every shown meal slot and any hidden one with entries, in order. */
    suspend fun proteinMeals(day: Day): ProteinMeals {
        val totals = db.diary.slotTotals(day)
        val slots = db.diary.mealSlots(includeHidden = true).filter { !it.hidden || it.id in totals }
        val kg = weightKgX100(day)
        val meals = slots.map { s ->
            val t = totals[s.id]
            MealProtein(s.name, grams(t?.proteinX10?.toLong()), t != null)
        }
        return ProteinMeals(meals, FoodCardMath.mealProteinThresholdG(kg), kg != null)
    }

    /** Carbs per kg for [day] against the band from the 7 days up to it. */
    suspend fun carbsPerKg(day: Day): CarbsPerKg {
        val carbX10 = db.diary.dayTotals(day).carbX10
        val kg = weightKgX100(day)
        val avgMin = FoodCardMath.avgTrainingMin(workouts(DayRange(day.plusDays(-(WEEK - 1)), day)), WEEK)
        val perKg = if (carbX10 != null && kg != null) FoodCardMath.perKg(carbX10.toLong(), kg) else null
        return CarbsPerKg(grams(carbX10?.toLong()), kg, perKg, FoodCardMath.carbBand(avgMin), avgMin)
    }

    /** Fuel around the latest workout of an hour or more on [day] or the day before. */
    suspend fun workoutFuel(day: Day): WorkoutFuel {
        val w = FoodCardMath.longWorkout(workouts(DayRange(day.plusDays(-1), day)))
            ?: return WorkoutFuel(null, 0, 0, FoodCardMath.fuelBandGPerH(0), 0, 0)
        val inWindow = FoodCardMath.fuelEntries(w, db.diary.range(DayRange(day.plusDays(-2), day.plusDays(1))))
        val carbs = FoodCardMath.coverage(inWindow) { it.nutrients.carbX10 }
        val x10 = carbs.sum ?: 0L
        return WorkoutFuel(
            workout = w,
            carbsG = grams(x10) ?: 0,
            gPerHour = FoodCardMath.gramsPerHour(x10, w.durationSec),
            band = FoodCardMath.fuelBandGPerH(w.durationSec),
            entries = inWindow.size,
            unknown = inWindow.size - carbs.known,
        )
    }

    /**
     * The 7-day fuel balance ending the day before [day] when [day] is today (today is not over yet),
     * else ending on [day].
     */
    suspend fun fuelBalance(day: Day): FuelBalance {
        val last = if (day >= clock.today()) day.plusDays(-1) else day
        val range = DayRange(last.plusDays(-(WEEK - 1)), last)
        val eaten = db.diary.dailyKcalX10(range)
        val active = db.summaries.days(range).associate { it.day to (it.activeKcal ?: 0) }
        val days = eaten.size
        val avgEaten = if (days == 0) null else (eaten.values.sum() / 10.0 / days).roundToInt()
        val avgActive = if (days == 0) null else (eaten.keys.sumOf { active[it] ?: 0 }.toDouble() / days).roundToInt()
        val kg = weightKgX100(day)
        val fat = latestOnOrBefore(BodyKind.BODY_FAT, day)
        val missing = when {
            kg == null -> FuelMissing.WEIGHT
            fat == null -> FuelMissing.BODY_FAT
            days < FoodCardMath.FUEL_MIN_DAYS -> FuelMissing.DAYS
            else -> FuelMissing.NONE
        }
        val ffm = if (kg != null && fat != null) FoodCardMath.fatFreeKg(kg, fat) else null
        val value = if (missing == FuelMissing.NONE && ffm != null && ffm > 0 && avgEaten != null && avgActive != null) {
            ((avgEaten - avgActive) / ffm).roundToInt()
        } else {
            null
        }
        return FuelBalance(value, avgEaten, avgActive, ffm, days, missing)
    }

    /** Sodium for [day]. */
    suspend fun sodium(day: Day): SodiumDay =
        SodiumDay(FoodCardMath.coverage(db.diary.day(day)) { it.nutrients.sodiumMg }, FoodCardMath.SODIUM_LIMIT_MG)

    /**
     * Fibre for [day]. The target is 14 g per 1,000 kcal of the day's calorie goal (or of what was eaten,
     * when that is more), so it doesn't start tiny in the morning before most meals are logged.
     */
    suspend fun fibre(day: Day): FibreDay {
        val entries = db.diary.day(day)
        val kcal = kcal(FoodCardMath.coverage(entries) { it.nutrients.kcalX10 })
        val goal = EnergyBudget(db, clock).forDay(day).goalKcal
        return FibreDay(FoodCardMath.coverage(entries) { it.nutrients.fibreX10 }, kcal, FoodCardMath.fibreTargetG(maxOf(goal, kcal ?: 0)))
    }

    /** Total sugar for [day]. */
    suspend fun sugar(day: Day): SugarDay {
        val entries = db.diary.day(day)
        return SugarDay(FoodCardMath.coverage(entries) { it.nutrients.sugarX10 }, kcal(FoodCardMath.coverage(entries) { it.nutrients.kcalX10 }))
    }

    /** Constants and small helpers. */
    companion object {
        private const val WEEK = 7
        private const val LOOKBACK_DAYS = 365

        /** Whole grams from tenths, rounded; null stays null. */
        fun grams(x10: Long?): Int? = x10?.let { ((it + 5) / 10).toInt() }

        private fun kcal(c: Coverage): Int? = c.sum?.let { ((it + 5) / 10).toInt() }
    }
}
