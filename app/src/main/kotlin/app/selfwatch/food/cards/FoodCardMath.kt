/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.cards

import app.selfwatch.cards.MainGoal
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.Day
import java.time.DayOfWeek
import kotlin.math.roundToInt

/** Why a protein target is what it is (picks the line under the number). */
enum class ProteinReason {
    /** Losing weight: at least 1.2 g/kg. */
    LOSS,

    /** Losing weight with strength training this week: at least 1.6 g/kg. */
    LOSS_STRENGTH,

    /** Building muscle: 1.6 g/kg. */
    MUSCLE,

    /** Athletic or endurance training: 1.2–2.0 g/kg. */
    TRAINING,

    /** Staying active with age: 1.0–1.2 g/kg. */
    ACTIVE_AGING,

    /** Every other goal: 1.2 g/kg. */
    GENERAL,

    /** The user's own protein goal, turned into g/kg. */
    OWN_GOAL,
}

/** A target in g per kg of body weight: [low] alone is a minimum; with [high] it is a band. */
data class PerKgTarget(val low: Double, val high: Double?)

/** A protein target and why. */
data class ProteinTarget(val target: PerKgTarget, val reason: ProteinReason)

/** The training level behind a carbohydrate band (average training time per day over 7 days). */
enum class CarbLevel {
    /** Under about 45 minutes a day: 3–5 g/kg. */
    LIGHT,

    /** About an hour a day: 5–7 g/kg. */
    MODERATE,

    /** One to three hours a day: 6–10 g/kg. */
    HIGH,

    /** Four hours a day or more: 8–12 g/kg. */
    VERY_HIGH,
}

/** A carbohydrate band and the training level it comes from. */
data class CarbBand(val target: PerKgTarget, val level: CarbLevel)

/**
 * How many of a day's diary entries report a nutrient: [sum] of the known values (null when none is
 * known), [known] entries with a value, out of [entries].
 */
data class Coverage(val sum: Long?, val known: Int, val entries: Int) {
    /** True when some entries have no value for the nutrient (the sum is then a floor). */
    val partial: Boolean get() = known in 1 until entries
}

/**
 * The pure arithmetic behind the Food cards: targets per goal, bands, nutrient coverage and the fuel
 * window. Loaders feed it from the database; tests call it directly.
 */
object FoodCardMath {
    /** Protein per meal that counts as a "protein meal", per kg of body weight. */
    const val MEAL_PROTEIN_G_PER_KG: Double = 0.25

    /** Lower and upper clamp of the per-meal protein threshold (about 20–40 g). */
    const val MEAL_PROTEIN_MIN_G: Int = 20

    /** Upper clamp of the per-meal protein threshold. */
    const val MEAL_PROTEIN_MAX_G: Int = 40

    /** Sodium limit per day. */
    const val SODIUM_LIMIT_MG: Int = 2_300

    /** Fibre per 1,000 kcal eaten. */
    const val FIBRE_G_PER_1000_KCAL: Int = 14

    /** Below this 7-day energy availability (kcal per kg of fat-free mass) the fuel card shows its soft flag. */
    const val LOW_FUEL_KCAL_PER_KG_FFM: Int = 30

    /** Days with diary entries the fuel balance needs out of its 7. */
    const val FUEL_MIN_DAYS: Int = 4

    /** Workouts at least this long get a fuel check. */
    const val LONG_WORKOUT_SEC: Int = 3_600

    /** Beyond this length the fuel band rises to 90 g/h. */
    const val VERY_LONG_WORKOUT_SEC: Int = 9_000

    /** Diary entries this long before a workout starts or after it ends count as its fuel. */
    const val FUEL_WINDOW_SEC: Long = 30 * 60L

    /** FIT `sub_sport` strength_training (FIT SDK profile, public documentation). */
    const val SUB_SPORT_STRENGTH: Int = 20

    /** FIT `sport` training (FIT SDK profile, public documentation). */
    const val SPORT_TRAINING: Int = 10

    /**
     * The protein target for [goal]: losing weight at least 1.2 g/kg (1.6 when [strengthThisWeek]),
     * building muscle 1.6, athletic and endurance 1.2–2.0, staying active 1.0–1.2, everything else 1.2.
     */
    fun proteinTarget(goal: MainGoal, strengthThisWeek: Boolean): ProteinTarget = when (goal) {
        MainGoal.WEIGHT_LOSS ->
            if (strengthThisWeek) ProteinTarget(PerKgTarget(1.6, null), ProteinReason.LOSS_STRENGTH) else ProteinTarget(PerKgTarget(1.2, null), ProteinReason.LOSS)
        MainGoal.BUILD_MUSCLE -> ProteinTarget(PerKgTarget(1.6, null), ProteinReason.MUSCLE)
        MainGoal.ATHLETIC, MainGoal.ENDURANCE -> ProteinTarget(PerKgTarget(1.2, 2.0), ProteinReason.TRAINING)
        MainGoal.ACTIVE_AGING -> ProteinTarget(PerKgTarget(1.0, 1.2), ProteinReason.ACTIVE_AGING)
        MainGoal.WEIGHT_MANAGEMENT, MainGoal.HEART_HEALTH, MainGoal.STRESS_WELLBEING, MainGoal.GENERAL_HEALTH ->
            ProteinTarget(PerKgTarget(1.2, null), ProteinReason.GENERAL)
    }

    /** The user's protein goal of [goalG] grams as g/kg of [kgX100]. */
    fun ownProteinTarget(goalG: Int, kgX100: Int): ProteinTarget =
        ProteinTarget(PerKgTarget(perKg(goalG * 10L, kgX100), null), ProteinReason.OWN_GOAL)

    /** Tenths of a gram per kg of [kgX100] (g/kg). */
    fun perKg(gramsX10: Long, kgX100: Int): Double = if (kgX100 <= 0) 0.0 else gramsX10 * 10.0 / kgX100

    /** True for a strength workout: FIT sub-sport strength training, or training without a sub-sport. */
    fun isStrength(w: Workout): Boolean = w.subSport == SUB_SPORT_STRENGTH || (w.sport == SPORT_TRAINING && w.subSport == null)

    /** Protein a meal needs to count, in grams: 0.25 g/kg of [kgX100], clamped to 20–40 g; 20 g without a weight. */
    fun mealProteinThresholdG(kgX100: Int?): Int {
        if (kgX100 == null || kgX100 <= 0) return MEAL_PROTEIN_MIN_G
        return (kgX100 / 100.0 * MEAL_PROTEIN_G_PER_KG).roundToInt().coerceIn(MEAL_PROTEIN_MIN_G, MEAL_PROTEIN_MAX_G)
    }

    /**
     * The carbohydrate band for an average of [avgTrainingMinPerDay] minutes of training a day: under
     * 45 → 3–5 g/kg, under 75 (about an hour) → 5–7, under 3.5 h (1–3 h) → 6–10, from 3.5 h (towards
     * 4 h and more) → 8–12.
     */
    fun carbBand(avgTrainingMinPerDay: Int): CarbBand = when {
        avgTrainingMinPerDay < 45 -> CarbBand(PerKgTarget(3.0, 5.0), CarbLevel.LIGHT)
        avgTrainingMinPerDay < 75 -> CarbBand(PerKgTarget(5.0, 7.0), CarbLevel.MODERATE)
        avgTrainingMinPerDay < 210 -> CarbBand(PerKgTarget(6.0, 10.0), CarbLevel.HIGH)
        else -> CarbBand(PerKgTarget(8.0, 12.0), CarbLevel.VERY_HIGH)
    }

    /** Average training minutes per day: the workouts' total time over [days] days, rounded. */
    fun avgTrainingMin(workouts: List<Workout>, days: Int): Int =
        if (days <= 0) 0 else (workouts.sumOf { it.durationSec.toLong() } / 60.0 / days).roundToInt()

    /** The sum and count of [entries] reporting the nutrient [value] reads (null is unknown). */
    fun coverage(entries: List<DiaryEntry>, value: (DiaryEntry) -> Int?): Coverage {
        var sum = 0L
        var known = 0
        for (e in entries) {
            val v = value(e) ?: continue
            sum += v
            known++
        }
        return Coverage(if (known == 0) null else sum, known, entries.size)
    }

    /** Fibre target in grams for [kcal] eaten: 14 g per 1,000 kcal, rounded; null with nothing eaten. */
    fun fibreTargetG(kcal: Int?): Int? = kcal?.takeIf { it > 0 }?.let { (it * FIBRE_G_PER_1000_KCAL / 1000.0).roundToInt() }

    /** The carbohydrate band during a workout of [durationSec]: 30–60 g/h, up to 90 g/h beyond 2.5 h. */
    fun fuelBandGPerH(durationSec: Int): IntRange = if (durationSec > VERY_LONG_WORKOUT_SEC) 30..90 else 30..60

    /** The diary entries logged from 30 minutes before [w] starts to 30 minutes after it ends. */
    fun fuelEntries(w: Workout, entries: List<DiaryEntry>): List<DiaryEntry> {
        val from = w.startSec - FUEL_WINDOW_SEC
        val to = w.startSec + w.durationSec + FUEL_WINDOW_SEC
        return entries.filter { it.tSec in from..to }
    }

    /** The workout to check: the latest one of at least an hour among [workouts], or null. */
    fun longWorkout(workouts: List<Workout>): Workout? =
        workouts.filter { it.durationSec >= LONG_WORKOUT_SEC }.maxByOrNull { it.startSec }

    /** Carbs per hour of [durationSec] for [carbsX10] tenths of a gram, rounded. */
    fun gramsPerHour(carbsX10: Long, durationSec: Int): Int = if (durationSec <= 0) 0 else (carbsX10 / 10.0 / (durationSec / 3_600.0)).roundToInt()

    /** Fat-free mass in kg from [kgX100] and [bodyFatX100] (percent ×100). */
    fun fatFreeKg(kgX100: Int, bodyFatX100: Int): Double = kgX100 / 100.0 * (1 - bodyFatX100 / 10_000.0)

    /** The 7 days of the week holding [day], starting on [firstDay]. */
    fun weekOf(day: Day, firstDay: DayOfWeek): List<Day> {
        val back = (day.toLocalDate().dayOfWeek.value - firstDay.value + 7) % 7
        val start = day.plusDays(-back)
        return (0 until 7).map { start.plusDays(it) }
    }
}
