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
 * Nutrient amounts. `X10` fields are the unit ×10 (grams for most, kcal for [kcalX10], milligrams for
 * [ironX10] and [vitcX10], micrograms for [vitdX10]); `Mg` fields are milligrams, `Ug` micrograms.
 * Null means unknown; 0 means a known zero.
 *
 * The first nine fields are the original set. The ten after [cholesterolMg] came with food database
 * schema 2 and default to unknown, so code that names only the first nine keeps working.
 *
 * **Rounding rule:** [scaled] rounds each field to the nearest integer, halves away from zero
 * (2.5 → 3, −2.5 → −3).
 *
 * **Null rule:** [scaled] keeps nulls null. In [plus], known values are summed and unknown ones are
 * skipped: `value + value = sum`, `null + value = value`, `null + null = null`. So a day total of, say,
 * sugar is the sum of the foods that report sugar; screens that need to say a total is partial count
 * the unknown parts themselves.
 *
 * @property transfatX10 trans fat, g ×10.
 * @property monofatX10 monounsaturated fat, g ×10.
 * @property polyfatX10 polyunsaturated fat, g ×10.
 * @property addsugarX10 added sugars, g ×10.
 * @property potassiumMg potassium, mg.
 * @property calciumMg calcium, mg.
 * @property ironX10 iron, mg ×10.
 * @property vitaUg vitamin A (RAE), µg.
 * @property vitcX10 vitamin C, mg ×10.
 * @property vitdX10 vitamin D, µg ×10.
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
    val transfatX10: Int? = null,
    val monofatX10: Int? = null,
    val polyfatX10: Int? = null,
    val addsugarX10: Int? = null,
    val potassiumMg: Int? = null,
    val calciumMg: Int? = null,
    val ironX10: Int? = null,
    val vitaUg: Int? = null,
    val vitcX10: Int? = null,
    val vitdX10: Int? = null,
) {
    /** Every field in declaration order (the order of [Field]). */
    public fun toList(): List<Int?> = listOf(
        kcalX10, proteinX10, fatX10, satfatX10, carbX10, sugarX10, fibreX10, sodiumMg, cholesterolMg,
        transfatX10, monofatX10, polyfatX10, addsugarX10, potassiumMg, calciumMg, ironX10, vitaUg, vitcX10, vitdX10,
    )

    /** The stored value of [field]. */
    public operator fun get(field: Field): Int? = toList()[field.ordinal]

    /** Every field passed through [f], in [Field] order. */
    public fun map(f: (Int?) -> Int?): Nutrients = fromList(toList().map(f))

    /** Fields combined pairwise with [o] through [f]. */
    public fun zip(o: Nutrients, f: (Int?, Int?) -> Int?): Nutrients {
        val a = toList()
        val b = o.toList()
        return fromList(a.indices.map { f(a[it], b[it]) })
    }

    /** How many fields are known (not null). */
    public val knownCount: Int get() = toList().count { it != null }

    /**
     * Every field multiplied by `factorX1000 / 1000` (1000 = ×1, 1500 = ×1.5), rounded half away
     * from zero and clamped to the `Int` range. Null fields stay null.
     */
    public fun scaled(factorX1000: Int): Nutrients = map { scale(it, factorX1000) }

    /** Field-by-field sum of known values; null only when both sides are unknown (see the class KDoc). */
    public operator fun plus(o: Nutrients): Nutrients = zip(o, ::add)

    /**
     * The nutrient fields in [Nutrients] declaration order. [unit] is the display unit, [scale] the
     * divisor from the stored value to [unit] (10 for `X10` fields), [isV2] marks the fields that came
     * with food database schema 2.
     */
    public enum class Field(public val unit: String, public val scale: Int, public val isV2: Boolean) {
        KCAL("kcal", 10, false),
        PROTEIN("g", 10, false),
        FAT("g", 10, false),
        SATFAT("g", 10, false),
        CARB("g", 10, false),
        SUGAR("g", 10, false),
        FIBER("g", 10, false),
        SODIUM("mg", 1, false),
        CHOLESTEROL("mg", 1, false),
        TRANSFAT("g", 10, true),
        MONOFAT("g", 10, true),
        POLYFAT("g", 10, true),
        ADDSUGAR("g", 10, true),
        POTASSIUM("mg", 1, true),
        CALCIUM("mg", 1, true),
        IRON("mg", 10, true),
        VITA("µg", 1, true),
        VITC("mg", 10, true),
        VITD("µg", 10, true),
    }

    /** Constants. */
    public companion object {
        /** All fields known and zero: the identity for [plus]. */
        public val ZERO: Nutrients = fromList(List(Field.entries.size) { 0 })

        /** All fields unknown. */
        public val UNKNOWN: Nutrients = Nutrients(null, null, null, null, null, null, null, null, null)

        /** Nutrients from [v], one value per [Field] in order. */
        public fun fromList(v: List<Int?>): Nutrients {
            require(v.size == Field.entries.size) { "need ${Field.entries.size} values, got ${v.size}" }
            return Nutrients(
                v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8],
                v[9], v[10], v[11], v[12], v[13], v[14], v[15], v[16], v[17], v[18],
            )
        }

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

/**
 * Diet flags and allergens (food database schema 2, or ticked by the user for a custom food). Bits
 * follow foods.db: [diet] 1 vegan, 2 vegetarian, 4 gluten-free (label claim), 8 gluten-free (naturally,
 * one ingredient); [dietKnown] 1 vegan decided, 2 vegetarian decided (decided but not set in [diet]
 * means "no"); [allergens] see [Allergen]; [allergensFull] true when a full ingredient list was checked,
 * so a missing allergen bit means "not listed". Unknown is never reported as "free".
 */
public data class DietInfo(val diet: Int, val dietKnown: Int, val allergens: Int, val allergensFull: Boolean) {
    /** True vegan, false not vegan, null unknown. */
    public val vegan: Boolean? get() = decided(VEGAN)

    /** True vegetarian, false not vegetarian, null unknown. A vegan food is vegetarian. */
    public val vegetarian: Boolean? get() = decided(VEGETARIAN) ?: if (vegan == true) true else null

    /** Gluten-free by a label claim. */
    public val glutenFreeLabel: Boolean get() = diet and GLUTEN_FREE_LABEL != 0

    /** Gluten-free by nature (a one-ingredient food). */
    public val glutenFreeNatural: Boolean get() = diet and GLUTEN_FREE_NATURAL != 0

    /** Gluten-free by label or by nature. */
    public val glutenFree: Boolean get() = glutenFreeLabel || glutenFreeNatural

    /** True when nothing at all is known. */
    public val isUnknown: Boolean get() = diet == 0 && dietKnown == 0 && allergens == 0

    /** The allergens listed for this food, in [Allergen] order. */
    public val contains: List<Allergen> get() = Allergen.entries.filter { allergens and it.bit != 0 }

    /** True when [a] is listed, false when it is known to be absent, null when unknown. */
    public fun has(a: Allergen): Boolean? = when {
        allergens and a.bit != 0 -> true
        a == Allergen.GLUTEN && glutenFree -> false
        allergensFull -> false
        else -> null
    }

    private fun decided(bit: Int): Boolean? = if (dietKnown and bit != 0) diet and bit != 0 else null

    /** Bits and constants. */
    public companion object {
        /** Diet bit: vegan. */
        public const val VEGAN: Int = 1

        /** Diet bit: vegetarian. */
        public const val VEGETARIAN: Int = 2

        /** Diet bit: gluten-free by label claim. */
        public const val GLUTEN_FREE_LABEL: Int = 4

        /** Diet bit: naturally gluten-free. */
        public const val GLUTEN_FREE_NATURAL: Int = 8

        /** Nothing known. */
        public val UNKNOWN: DietInfo = DietInfo(0, 0, 0, false)
    }
}

/** The nine allergens foods.db tracks, with their bit in `allergens`. */
public enum class Allergen(public val bit: Int) {
    GLUTEN(1), MILK(2), EGG(4), PEANUT(8), TREE_NUT(16), SOY(32), FISH(64), SHELLFISH(128), SESAME(256);

    /** Lookup. */
    public companion object {
        /** The allergens whose bits are set in [mask]. */
        public fun of(mask: Int): Set<Allergen> = entries.filterTo(LinkedHashSet()) { mask and it.bit != 0 }

        /** The bit mask of [set]. */
        public fun maskOf(set: Collection<Allergen>): Int = set.fold(0) { m, a -> m or a.bit }
    }
}

/** A food category from foods.db's `category` table (42 categories in 9 groups). */
public data class FoodCategory(val id: Int, val name: String, val group: String)

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
 * @property category food category (foods.db schema 2, or the user's pick for a custom food), if known.
 * @property dietInfo diet flags and allergens (unknown by default).
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
    val category: FoodCategory? = null,
    val dietInfo: DietInfo = DietInfo.UNKNOWN,
)

/** A diary meal slot (breakfast, lunch, …), in display [order]. */
public data class MealSlot(val id: Long, val name: String, val order: Int, val hidden: Boolean)

/**
 * One food diary line. [nutrients] are copied in as the totals for this entry, so later changes
 * to the food do not change the diary.
 *
 * @property amountX100 number of servings ×100.
 * @property gramsX10 total grams ×10, if known.
 * @property categoryId the food's category ([FoodCategory.id]) when it was logged, if known.
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
    val categoryId: Int? = null,
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
