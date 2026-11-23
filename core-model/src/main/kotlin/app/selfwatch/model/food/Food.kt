/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.model.food

import app.selfwatch.model.time.Day

/** Where a food lives. [code] is stored in the `*_origin` columns. */
public enum class FoodOrigin(public val code: Int) {
    MY_FOOD(1), FOODS_DB(2), OPEN_FOOD_FACTS(3), RECIPE(4);

    /** Lookup by stored code. */
    public companion object {
        /** The origin with [code], or null. */
        public fun fromCode(code: Int): FoodOrigin? = entries.firstOrNull { it.code == code }
    }
}

/** A pointer to a food: its [origin] and the [id] within that origin. */
public data class FoodRef(val origin: FoodOrigin, val id: Long)

/**
 * Nutrient amounts. `X10` fields are grams ×10 (kcal ×10 for [kcalX10]); `Mg` fields are milligrams.
 * Null means unknown; 0 means a known zero.
 *
 * **Rounding rule:** [scaled] rounds each field to the nearest integer, halves away from zero
 * (2.5 → 3, −2.5 → −3).
 *
 * **Null rule:** [scaled] keeps nulls null. In [plus], known values are summed and unknown ones are
 * skipped: `value + value = sum`, `null + value = value`, `null + null = null`. So a day total of, say,
 * sugar is the sum of the foods that report sugar; screens that need to say a total is partial count
 * the unknown parts themselves.
 */
public data class Nutrients(
    val kcalX10: Int?,
    val proteinX10: Int?,
    val fatX10: Int?,
    val satfatX10: Int?,
    val carbX10: Int?,
    val sugarX10: Int?,
    val fibreX10: Int?,
    val sodiumMg: Int?,
    val cholesterolMg: Int?,
) {
    /**
     * Every field multiplied by `factorX1000 / 1000` (1000 = ×1, 1500 = ×1.5), rounded half away
     * from zero and clamped to the `Int` range. Null fields stay null.
     */
    public fun scaled(factorX1000: Int): Nutrients = Nutrients(
        kcalX10 = scale(kcalX10, factorX1000),
        proteinX10 = scale(proteinX10, factorX1000),
        fatX10 = scale(fatX10, factorX1000),
        satfatX10 = scale(satfatX10, factorX1000),
        carbX10 = scale(carbX10, factorX1000),
        sugarX10 = scale(sugarX10, factorX1000),
        fibreX10 = scale(fibreX10, factorX1000),
        sodiumMg = scale(sodiumMg, factorX1000),
        cholesterolMg = scale(cholesterolMg, factorX1000),
    )

    /** Field-by-field sum of known values; null only when both sides are unknown (see the class KDoc). */
    public operator fun plus(o: Nutrients): Nutrients = Nutrients(
        kcalX10 = add(kcalX10, o.kcalX10),
        proteinX10 = add(proteinX10, o.proteinX10),
        fatX10 = add(fatX10, o.fatX10),
        satfatX10 = add(satfatX10, o.satfatX10),
        carbX10 = add(carbX10, o.carbX10),
        sugarX10 = add(sugarX10, o.sugarX10),
        fibreX10 = add(fibreX10, o.fibreX10),
        sodiumMg = add(sodiumMg, o.sodiumMg),
        cholesterolMg = add(cholesterolMg, o.cholesterolMg),
    )

    /** Constants. */
    public companion object {
        /** All fields known and zero: the identity for [plus]. */
        public val ZERO: Nutrients = Nutrients(0, 0, 0, 0, 0, 0, 0, 0, 0)

        /** All fields unknown. */
        public val UNKNOWN: Nutrients = Nutrients(null, null, null, null, null, null, null, null, null)

        private fun scale(v: Int?, factorX1000: Int): Int? {
            if (v == null) return null
            val n = v.toLong() * factorX1000
            val q = (kotlin.math.abs(n) + 500) / 1000
            return clamp(if (n < 0) -q else q)
        }

        private fun add(a: Int?, b: Int?): Int? =
            when {
                a == null -> b
                b == null -> a
                else -> clamp(a.toLong() + b.toLong())
            }

        private fun clamp(v: Long): Int = v.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }
}

/** What a food's [Nutrients] are measured per. [code] is stored in the database. */
public enum class Basis(public val code: Int) {
    PER_100G(0), PER_100ML(1), PER_ITEM(2);

    /** Lookup by stored code. */
    public companion object {
        /** The basis with [code], or null. */
        public fun fromCode(code: Int): Basis? = entries.firstOrNull { it.code == code }
    }
}

/** A named serving size; [gramsX10] is its weight in grams ×10, if known. */
public data class Serving(val seq: Int, val label: String, val gramsX10: Int?)

/**
 * A food from any origin.
 *
 * @property kind food kind code from the food database (see FOODDB_SPEC).
 * @property per nutrients per [basis].
 * @property dataYear year the nutrient data was published, if known.
 * @property sourceLabel short source credit for the UI.
 * @property barcode EAN/UPC as a number, if known.
 */
public data class Food(
    val ref: FoodRef,
    val name: String,
    val brand: String?,
    val kind: Int,
    val basis: Basis,
    val per: Nutrients,
    val servings: List<Serving>,
    val dataYear: Int?,
    val sourceLabel: String,
    val barcode: Long?,
)

/** A diary meal slot (breakfast, lunch, …), in display [order]. */
public data class MealSlot(val id: Long, val name: String, val order: Int, val hidden: Boolean)

/**
 * One food diary line. [nutrients] are copied in as the totals for this entry, so later changes
 * to the food do not change the diary.
 *
 * @property amountX100 number of servings ×100.
 * @property gramsX10 total grams ×10, if known.
 */
public data class DiaryEntry(
    val id: Long,
    val day: Day,
    val mealSlotId: Long,
    val tSec: Long,
    val food: FoodRef?,
    val name: String,
    val brand: String?,
    val amountX100: Int,
    val servingLabel: String,
    val gramsX10: Int?,
    val nutrients: Nutrients,
    val importId: Long?,
)

/** Whether a recipe-book entry is a recipe or a saved meal. [code] is stored in the database. */
public enum class RecipeKind(public val code: Int) {
    RECIPE(1), SAVED_MEAL(2);

    /** Lookup by stored code. */
    public companion object {
        /** The kind with [code], or null. */
        public fun fromCode(code: Int): RecipeKind? = entries.firstOrNull { it.code == code }
    }
}

/** How far a recipe ingredient line has been matched to a food. [code] is stored in the database. */
public enum class MatchState(public val code: Int) {
    UNMATCHED(0), SUGGESTED(1), CONFIRMED(2), SKIPPED(3);

    /** Lookup by stored code. */
    public companion object {
        /** The state with [code], or null. */
        public fun fromCode(code: Int): MatchState? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One ingredient line of a recipe.
 *
 * @property line the original text, for example "2 cups flour".
 * @property amountX100 parsed amount ×100, if any.
 * @property unit parsed unit, if any.
 */
public data class RecipeItem(
    val seq: Int,
    val line: String,
    val food: FoodRef?,
    val amountX100: Int?,
    val unit: String?,
    val gramsX10: Int?,
    val match: MatchState,
)

/**
 * A recipe or saved meal.
 *
 * @property servingsX10 number of servings the recipe makes ×10.
 * @property statedPerServing nutrients per serving as the source stated them, if any.
 */
public data class Recipe(
    val id: Long,
    val kind: RecipeKind,
    val name: String,
    val servingsX10: Int,
    val servingLabel: String?,
    val prepMin: Int?,
    val cookMin: Int?,
    val steps: String?,
    val notes: String?,
    val sourceName: String?,
    val sourceUrl: String?,
    val statedPerServing: Nutrients?,
    val tags: Set<String>,
    val items: List<RecipeItem>,
    val importId: Long?,
)

/** Goal kinds; the unit is in the name. [code] is stored in the database. */
public enum class GoalKind(public val code: Int) {
    KCAL_IN(1), PROTEIN_G(2), FAT_G(3), CARB_G(4), STEPS(5), WEIGHT_KG_X100(6),
    WATER_ML(7), FAST_MIN(8), ACTIVE_MIN(9), SLEEP_MIN(10);

    /** Lookup by stored code. */
    public companion object {
        /** The kind with [code], or null. */
        public fun fromCode(code: Int): GoalKind? = entries.firstOrNull { it.code == code }
    }
}

/** A goal of [value] (in the unit of [kind]) that applies from [fromDay] until the next goal of that kind. */
public data class Goal(val kind: GoalKind, val value: Int, val fromDay: Day)

/** Sex for calorie estimates. */
public enum class Sex { FEMALE, MALE, UNSPECIFIED }

/** Display units. */
public enum class UnitSystem { METRIC, IMPERIAL, FOLLOW_PHONE }

/** The user's profile. Every field is optional. */
public data class Profile(val heightCm: Int?, val birthYear: Int?, val sex: Sex, val units: UnitSystem)
