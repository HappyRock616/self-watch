/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.nutrition

import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Serving
import java.math.BigInteger

/**
 * Nutrient maths for diary lines and recipes (WP-11 "Nutrient maths"). Everything is whole-number
 * `Long` arithmetic with a single rounding at the very end, half away from zero, so there is no float
 * drift and the same inputs always give the same diary numbers.
 *
 * - `grams = serving.gramsX10 × amount`.
 * - Per 100 g (and per 100 ml, reading the serving's weight as millilitres): `factor = grams / 100`.
 * - Per item: `factor = amount` (the number of servings; the serving weight is not needed).
 *
 * Amounts are servings ×100 (`amountX100`, 1.5 servings = 150), as in `DiaryEntry`.
 */
object NutrientMath {

    /** A "1 g" serving, so any per-100 g food can be logged by weight (100 g = amount 100, `amountX100` 10000). */
    val GRAM: Serving = Serving(seq = -1, label = "g", gramsX10 = 10)

    /** Total weight ×10 of [amountX100] servings of [serving], or null when the serving's weight is unknown. */
    fun gramsX10(serving: Serving, amountX100: Int): Int? =
        serving.gramsX10?.let { clampInt(mulDivRound(it.toLong(), amountX100.toLong(), 100L)) }

    /**
     * Nutrients of [amountX100] servings of [serving] for a food whose nutrients [per] are given per
     * [basis]. Null when the basis is per 100 g or 100 ml and the serving's weight is unknown (the
     * caller should offer a serving with a weight, or [GRAM]). Unknown nutrients stay null.
     */
    fun forServing(basis: Basis, per: Nutrients, serving: Serving, amountX100: Int): Nutrients? = when (basis) {
        Basis.PER_100G, Basis.PER_100ML -> serving.gramsX10?.let { g ->
            // per × (g/10 × amount/100) / 100  =  per × g × amountX100 / 100 000
            scale(per, g.toLong() * amountX100.toLong(), 100_000L)
        }
        Basis.PER_ITEM -> scale(per, amountX100.toLong(), 100L)
    }

    /** [forServing] for [food]. */
    fun forServing(food: Food, serving: Serving, amountX100: Int): Nutrients? =
        forServing(food.basis, food.per, serving, amountX100)

    /**
     * Nutrients of [gramsX10] grams ×10 of a food with nutrients [per] per 100 g (or 100 ml). Null for
     * a per-item basis, whose weight is unknown.
     */
    fun forGrams(basis: Basis, per: Nutrients, gramsX10: Long): Nutrients? =
        if (basis == Basis.PER_ITEM) null else scale(per, gramsX10, 1000L)

    /**
     * Every known field of [n] multiplied by `num / den`, rounded half away from zero and clamped to
     * the `Int` range. [den] must not be 0. Null fields stay null.
     */
    fun scale(n: Nutrients, num: Long, den: Long): Nutrients {
        require(den != 0L) { "den must not be 0" }
        return n.map { v -> v?.let { clampInt(mulDivRound(it.toLong(), num, den)) } }
    }

    /** `a × b / den` rounded half away from zero, exact for any inputs (falls back to BigInteger on overflow). */
    fun mulDivRound(a: Long, b: Long, den: Long): Long {
        require(den != 0L) { "den must not be 0" }
        val product = try {
            Math.multiplyExact(a, b)
        } catch (_: ArithmeticException) {
            return bigMulDivRound(a, b, den)
        }
        if (product == Long.MIN_VALUE || den == Long.MIN_VALUE) return bigMulDivRound(a, b, den)
        return divRound(product, den)
    }

    /** `num / den` rounded half away from zero (2.5 → 3, −2.5 → −3). [den] must not be 0. */
    fun divRound(num: Long, den: Long): Long {
        require(den != 0L) { "den must not be 0" }
        if (num == Long.MIN_VALUE || den == Long.MIN_VALUE) {
            return bigMulDivRound(num, 1L, den)
        }
        val negative = (num < 0) != (den < 0)
        val n = kotlin.math.abs(num)
        val d = kotlin.math.abs(den)
        val q = n / d
        val r = n % d
        // r ≥ d − r  ⇔  2r ≥ d, written without overflow.
        val rounded = if (r >= d - r) q + 1 else q
        return if (negative) -rounded else rounded
    }

    /** [v] clamped into the `Int` range. */
    fun clampInt(v: Long): Int = v.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    private fun bigMulDivRound(a: Long, b: Long, den: Long): Long {
        val p = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b))
        val d = BigInteger.valueOf(den)
        val negative = p.signum() * d.signum() < 0
        val qr = p.abs().divideAndRemainder(d.abs())
        var q = qr[0]
        if (qr[1].shiftLeft(1) >= d.abs()) q = q.add(BigInteger.ONE)
        if (negative) q = q.negate()
        return when {
            q > BigInteger.valueOf(Long.MAX_VALUE) -> Long.MAX_VALUE
            q < BigInteger.valueOf(Long.MIN_VALUE) -> Long.MIN_VALUE
            else -> q.toLong()
        }
    }
}
