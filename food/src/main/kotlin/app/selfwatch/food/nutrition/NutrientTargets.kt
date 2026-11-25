/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.nutrition

import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Nutrients.Field
import app.selfwatch.model.food.Sex
import kotlin.math.roundToInt

/**
 * A daily target for one nutrient, in the stored unit of [field] (×10 for `X10` fields).
 *
 * @property low the amount to reach (a goal), if any.
 * @property high the top of a recommended range (high protein: 2.0 g/kg), if any; not a limit.
 * @property limit the most to have (sodium, saturated fat, added sugar); above it is "over".
 * @property caution above this (but not over [limit]) is "near the limit"; null means 90% of [limit].
 */
data class Target(val field: Field, val low: Int? = null, val high: Int? = null, val limit: Int? = null, val caution: Int? = null) {
    /** The number a bar is drawn against: the limit for limits, else the goal. */
    val reference: Int? get() = limit ?: low

    /** Where [value] stands against this target. */
    fun status(value: Int?): TargetStatus {
        if (value == null) return TargetStatus.UNKNOWN
        val lim = limit
        if (lim != null) {
            val soft = caution ?: (lim * CAUTION_SHARE).roundToInt()
            return when {
                value > lim -> TargetStatus.OVER
                value > soft -> TargetStatus.NEAR_LIMIT
                else -> TargetStatus.UNDER_LIMIT
            }
        }
        val lo = low ?: return TargetStatus.NONE
        return if (value >= lo) TargetStatus.MET else TargetStatus.BELOW
    }

    /** [value] as a share of [reference] in percent, or null without one. */
    fun percent(value: Int?): Int? {
        val ref = reference ?: return null
        if (value == null || ref <= 0) return null
        return (value * 100.0 / ref).roundToInt()
    }

    private companion object {
        const val CAUTION_SHARE = 0.9
    }
}

/** Where an amount stands against its [Target]. */
enum class TargetStatus {
    /** No target for this nutrient. */
    NONE,

    /** The amount is unknown. */
    UNKNOWN,

    /** Below a goal. */
    BELOW,

    /** A goal reached. */
    MET,

    /** Within a limit, not close to it. */
    UNDER_LIMIT,

    /** Close to a limit (amber). */
    NEAR_LIMIT,

    /** Over a limit (red). */
    OVER,
}

/**
 * What targets are worked out from: the profile ([age], [sex]), the latest weight, the user's goals
 * (calories and macros, in kcal and grams; null when not set) and the diet.
 */
data class TargetInputs(
    val age: Int? = null,
    val sex: Sex = Sex.UNSPECIFIED,
    val weightKgX100: Int? = null,
    val kcalGoal: Int? = null,
    val proteinGoalG: Int? = null,
    val fatGoalG: Int? = null,
    val carbGoalG: Int? = null,
    val prefs: DietPrefs = DietPrefs.NONE,
    val overrides: Map<Field, Int> = emptyMap(),
)

/**
 * Targets the user typed by hand (Diet and allergens → Your daily targets), by field, in the stored unit
 * of the field. Kept in settings as a comma list of `FIELD=value` (for example `SODIUM=1500,IRON=180`).
 */
object TargetOverrides {
    /** The stored list items for [map], in [Field] order; values of 0 or less are dropped. */
    fun encode(map: Map<Field, Int>): List<String> =
        Field.entries.mapNotNull { f -> map[f]?.takeIf { it > 0 }?.let { "${f.name}=$it" } }

    /** The overrides in [items]; unknown fields and bad numbers are skipped. */
    fun decode(items: List<String>): Map<Field, Int> {
        val out = LinkedHashMap<Field, Int>()
        for (item in items) {
            val name = item.substringBefore('=', "").trim()
            val value = item.substringAfter('=', "").trim().toIntOrNull() ?: continue
            val f = Field.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: continue
            if (value > 0) out[f] = value
        }
        return out
    }
}

/**
 * Daily nutrient targets: the official intake guidelines (US Dietary Reference Intakes and the 2020–2025
 * Dietary Guidelines) by age and sex, the user's own calorie and macro goals, and the diet presets:
 *
 * - Heart-healthy: sodium amber above 1,500 mg and red above 2,300 mg; saturated fat amber above 6% and
 *   red above 10% of calories; added sugar under 10% of calories; fiber 14 g per 1,000 kcal.
 * - High protein: 1.2–2.0 g of protein per kg of body weight (needs a weigh-in).
 * - Everyone else: sodium under 2,300 mg, saturated fat and added sugar under 10% of calories, fiber
 *   14 g per 1,000 kcal (or the age and sex value without a calorie goal), protein 0.8 g/kg.
 *
 * Without a sex in the profile the female and male values are averaged; without an age, adult 19–50
 * values are used. Without a calorie goal, shares of calories are taken of 2,000 kcal.
 */
object NutrientTargets {

    /** Calories the "% of calories" limits use without a goal. */
    const val DEFAULT_KCAL: Int = 2000

    /** Every target for [inp], by field. Fields without a target are absent. */
    fun of(inp: TargetInputs): Map<Field, Target> {
        val out = LinkedHashMap<Field, Target>()
        val heart = Diet.HEART_HEALTHY in inp.prefs.diets
        val kcal = inp.kcalGoal?.takeIf { it > 0 }
        val kcalBase = kcal ?: DEFAULT_KCAL
        val age = inp.age ?: ADULT
        kcal?.let { out[Field.KCAL] = Target(Field.KCAL, low = it * 10) }

        // Protein: the user's goal, else high protein 1.2–2.0 g/kg, else 0.8 g/kg, else by sex.
        val kg = inp.weightKgX100?.takeIf { it > 0 }?.let { it / 100.0 }
        out[Field.PROTEIN] = when {
            inp.proteinGoalG != null -> Target(Field.PROTEIN, low = inp.proteinGoalG * 10)
            Diet.HIGH_PROTEIN in inp.prefs.diets && kg != null ->
                Target(Field.PROTEIN, low = (kg * HIGH_PROTEIN_LOW * 10).roundToInt(), high = (kg * HIGH_PROTEIN_HIGH * 10).roundToInt())
            kg != null -> Target(Field.PROTEIN, low = (kg * RDA_PROTEIN_PER_KG * 10).roundToInt())
            else -> Target(Field.PROTEIN, low = bySex(inp.sex, 56, 46) * 10)
        }
        inp.fatGoalG?.let { out[Field.FAT] = Target(Field.FAT, low = it * 10) }
        inp.carbGoalG?.let { out[Field.CARB] = Target(Field.CARB, low = it * 10) }

        // Limits as shares of calories: grams ×10 = kcal × share ÷ kcal per gram × 10.
        out[Field.SATFAT] = Target(
            Field.SATFAT,
            limit = gramsX10OfShare(kcalBase, SATFAT_SHARE, KCAL_PER_G_FAT),
            caution = if (heart) gramsX10OfShare(kcalBase, HEART_SATFAT_CAUTION, KCAL_PER_G_FAT) else null,
        )
        out[Field.ADDSUGAR] = Target(Field.ADDSUGAR, limit = gramsX10OfShare(kcalBase, ADDED_SUGAR_SHARE, KCAL_PER_G_CARB))
        out[Field.SODIUM] = Target(Field.SODIUM, limit = SODIUM_LIMIT_MG, caution = if (heart) HEART_SODIUM_CAUTION_MG else null)

        // Fiber: 14 g per 1,000 kcal of the goal, else the age and sex value.
        out[Field.FIBER] = Target(
            Field.FIBER,
            low = if (kcal != null) (kcal * FIBER_G_PER_1000_KCAL / 100.0).roundToInt() else
                bySex(inp.sex, if (age > 50) 30 else 38, if (age > 50) 21 else 25) * 10,
        )
        out[Field.POTASSIUM] = Target(Field.POTASSIUM, low = bySex(inp.sex, 3400, 2600))
        out[Field.CALCIUM] = Target(Field.CALCIUM, low = bySex(inp.sex, if (age > 70) 1200 else 1000, if (age > 50) 1200 else 1000))
        out[Field.IRON] = Target(Field.IRON, low = bySex(inp.sex, 8, if (age > 50) 8 else 18) * 10)
        out[Field.VITA] = Target(Field.VITA, low = bySex(inp.sex, 900, 700))
        out[Field.VITC] = Target(Field.VITC, low = bySex(inp.sex, 90, 75) * 10)
        out[Field.VITD] = Target(Field.VITD, low = (if (age > 70) 20 else 15) * 10)

        // The user's own numbers win: a limit stays a limit, anything else becomes a goal.
        for ((f, v) in inp.overrides) {
            if (v <= 0 || f !in EDITABLE) continue
            val base = out[f]
            out[f] = if (f in LIMITS || base?.limit != null) {
                Target(f, limit = v, caution = base?.caution?.takeIf { it < v })
            } else {
                Target(f, low = v)
            }
        }
        return out
    }

    /** The targets for [inp] without the user's own numbers (what "Reset to recommended" goes back to). */
    fun recommended(inp: TargetInputs): Map<Field, Target> = of(inp.copy(overrides = emptyMap()))

    /** The number a user edits for [t]: its limit for limits, else its goal. */
    fun editValue(t: Target): Int? = t.limit ?: t.low

    /**
     * Fields whose target can be typed by hand. Calories are left out: they follow the calorie goal
     * (Me → Goals), which also drives the energy budget.
     */
    val EDITABLE: List<Field> = listOf(
        Field.PROTEIN, Field.FAT, Field.CARB, Field.FIBER, Field.SODIUM, Field.SATFAT, Field.ADDSUGAR,
        Field.POTASSIUM, Field.CALCIUM, Field.IRON, Field.VITA, Field.VITC, Field.VITD,
    )

    /**
     * The nutrients the diets in [prefs] care about most, in display order ("Your targets"): heart-healthy
     * sodium, saturated fat, fiber, potassium and added sugar; high protein protein; vegetarian and vegan
     * iron, calcium and vitamin D (the database has no B12).
     */
    fun focus(prefs: DietPrefs): List<Field> {
        val out = LinkedHashSet<Field>()
        if (Diet.HEART_HEALTHY in prefs.diets) out += listOf(Field.SODIUM, Field.SATFAT, Field.FIBER, Field.POTASSIUM, Field.ADDSUGAR)
        if (Diet.HIGH_PROTEIN in prefs.diets) out += Field.PROTEIN
        if (Diet.VEGAN in prefs.diets || Diet.VEGETARIAN in prefs.diets) out += listOf(Field.IRON, Field.CALCIUM, Field.VITD)
        return out.toList()
    }

    /** Fields a limit applies to (shown amber or red when high, never green). */
    val LIMITS: Set<Field> = setOf(Field.SODIUM, Field.SATFAT, Field.ADDSUGAR)

    /** Display groups for the full nutrient list (Energy, Fats, Carbs, Protein, Minerals, Vitamins). */
    val GROUPS: List<Pair<String, List<Field>>> = listOf(
        "energy" to listOf(Field.KCAL),
        "fats" to listOf(Field.FAT, Field.SATFAT, Field.TRANSFAT, Field.MONOFAT, Field.POLYFAT, Field.CHOLESTEROL),
        "carbs" to listOf(Field.CARB, Field.FIBER, Field.SUGAR, Field.ADDSUGAR),
        "protein" to listOf(Field.PROTEIN),
        "minerals" to listOf(Field.SODIUM, Field.POTASSIUM, Field.CALCIUM, Field.IRON),
        "vitamins" to listOf(Field.VITA, Field.VITC, Field.VITD),
    )

    /** How many of [entries] know [field] (for "Potassium known for 6 of 9 foods"). */
    fun known(entries: List<Nutrients>, field: Field): Int = entries.count { it[field] != null }

    private fun gramsX10OfShare(kcal: Int, share: Double, kcalPerGram: Int): Int = (kcal * share / kcalPerGram * 10).roundToInt()

    private fun bySex(sex: Sex, male: Int, female: Int): Int = when (sex) {
        Sex.MALE -> male
        Sex.FEMALE -> female
        Sex.UNSPECIFIED -> ((male + female) / 2.0).roundToInt()
    }

    private const val ADULT = 30
    private const val HIGH_PROTEIN_LOW = 1.2
    private const val HIGH_PROTEIN_HIGH = 2.0
    private const val RDA_PROTEIN_PER_KG = 0.8
    private const val SATFAT_SHARE = 0.10
    private const val HEART_SATFAT_CAUTION = 0.06
    private const val ADDED_SUGAR_SHARE = 0.10
    private const val KCAL_PER_G_FAT = 9
    private const val KCAL_PER_G_CARB = 4
    private const val SODIUM_LIMIT_MG = 2300
    private const val HEART_SODIUM_CAUTION_MG = 1500
    private const val FIBER_G_PER_1000_KCAL = 14
}
