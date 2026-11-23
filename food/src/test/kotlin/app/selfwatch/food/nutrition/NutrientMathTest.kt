/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.nutrition

import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Serving
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NutrientMathTest {
    private val per = Nutrients(2570, 118, 150, 50, 204, 40, 16, 460, null)

    @Test
    fun roundingIsHalfAwayFromZero() {
        assertEquals(3, NutrientMath.divRound(5, 2))
        assertEquals(-3, NutrientMath.divRound(-5, 2))
        assertEquals(-3, NutrientMath.divRound(5, -2))
        assertEquals(3, NutrientMath.divRound(-5, -2))
        assertEquals(2, NutrientMath.divRound(7, 3)) // 2.33
        assertEquals(3, NutrientMath.divRound(8, 3)) // 2.67
        assertEquals(0, NutrientMath.divRound(0, 7))
        assertEquals(1, NutrientMath.divRound(1, 2))
        assertEquals(0, NutrientMath.divRound(1, 3))
        assertEquals(Long.MAX_VALUE / 2 + 1, NutrientMath.divRound(Long.MAX_VALUE, 2))
        assertEquals(Long.MIN_VALUE / 2, NutrientMath.divRound(Long.MIN_VALUE, 2))
    }

    @Test
    fun divRoundMatchesBigDecimalOverManyValues() {
        val r = Random(11)
        repeat(20_000) {
            val num = when (it % 3) {
                0 -> r.nextLong(-1_000_000, 1_000_000)
                1 -> r.nextLong()
                else -> r.nextLong(-100, 100)
            }
            val den = r.nextLong(-100_000, 100_000).takeIf { d -> d != 0L } ?: 7L
            assertEquals("$num / $den", reference(num.toBigDecimal(), den.toBigDecimal()), NutrientMath.divRound(num, den))
        }
    }

    @Test
    fun mulDivRoundIsExactEvenWhenTheProductOverflows() {
        val r = Random(12)
        repeat(20_000) {
            val a = if (it % 2 == 0) r.nextLong() else r.nextLong(-1_000_000, 1_000_000)
            val b = if (it % 3 == 0) r.nextLong() else r.nextLong(-1_000_000, 1_000_000)
            val den = r.nextLong(1, Long.MAX_VALUE).let { d -> if (it % 5 == 0) -d else d }
            val big = a.toBigDecimal().multiply(b.toBigDecimal()).divide(den.toBigDecimal(), 0, RoundingMode.HALF_UP)
            val clamped = when {
                big > Long.MAX_VALUE.toBigDecimal() -> Long.MAX_VALUE
                big < Long.MIN_VALUE.toBigDecimal() -> Long.MIN_VALUE
                else -> big.toLong()
            }
            assertEquals("$a × $b / $den", clamped, NutrientMath.mulDivRound(a, b, den))
        }
    }

    @Test
    fun per100gUsesServingWeightTimesAmount() {
        val sandwich = Serving(0, "1 sandwich", 2190) // 219 g
        val n = NutrientMath.forServing(Basis.PER_100G, per, sandwich, 100)!!
        assertEquals(5628, n.kcalX10) // 257 × 2.19 = 562.83
        assertEquals(258, n.proteinX10) // 11.8 × 2.19 = 25.842
        assertEquals(1007, n.sodiumMg) // 460 × 2.19 = 1007.4
        assertNull(n.cholesterolMg)
        val half = NutrientMath.forServing(Basis.PER_100G, per, sandwich, 50)!!
        assertEquals(2814, half.kcalX10) // 281.415 → 281.4
        assertEquals(2190, NutrientMath.gramsX10(sandwich, 100))
        assertEquals(3285, NutrientMath.gramsX10(sandwich, 150))
        // Per 100 ml reads the serving weight as millilitres.
        assertEquals(n, NutrientMath.forServing(Basis.PER_100ML, per, sandwich, 100))
    }

    @Test
    fun gramServingLogsByWeight() {
        val n = NutrientMath.forServing(Basis.PER_100G, per, NutrientMath.GRAM, 15_000)!! // 150 g
        assertEquals(3855, n.kcalX10)
        assertEquals(1500, NutrientMath.gramsX10(NutrientMath.GRAM, 15_000))
        assertEquals(per, NutrientMath.forServing(Basis.PER_100G, per, NutrientMath.GRAM, 10_000))
    }

    @Test
    fun perItemIgnoresWeight() {
        val item = Serving(0, "1 sandwich", null)
        assertEquals(per, NutrientMath.forServing(Basis.PER_ITEM, per, item, 100))
        assertEquals(5140, NutrientMath.forServing(Basis.PER_ITEM, per, item, 200)!!.kcalX10)
        assertEquals(1285, NutrientMath.forServing(Basis.PER_ITEM, per, item, 50)!!.kcalX10)
        assertEquals(2, NutrientMath.forServing(Basis.PER_ITEM, Nutrients(3, null, null, null, null, null, null, null, null), item, 50)!!.kcalX10) // 1.5 → 2
        assertNull(NutrientMath.gramsX10(item, 100))
    }

    @Test
    fun unknownWeightForPerWeightFoodIsNull() {
        assertNull(NutrientMath.forServing(Basis.PER_100G, per, Serving(0, "1 piece", null), 100))
        assertNull(NutrientMath.forGrams(Basis.PER_ITEM, per, 1000))
        assertEquals(per, NutrientMath.forGrams(Basis.PER_100G, per, 1000))
    }

    @Test
    fun propertiesOverManyServings() {
        val r = Random(13)
        repeat(5_000) {
            val v = r.nextInt(0, 9001)
            val grams = r.nextInt(1, 20_000) // up to 2 kg
            val amount = r.nextInt(1, 2_000) // up to 20 servings
            val n = Nutrients(v, null, null, null, null, null, null, null, null)
            val got = NutrientMath.forServing(Basis.PER_100G, n, Serving(0, "s", grams), amount)!!.kcalX10!!
            // Exact reference: v × grams/10 × amount/100 / 100.
            val exact = reference(v.toBigDecimal() * grams.toBigDecimal() * amount.toBigDecimal(), BigDecimal(100_000))
            assertEquals(exact.toInt(), got)
            // Same total weight, same answer: 2 × (g) equals 1 × (2g).
            if (grams <= 10_000) {
                val twice = NutrientMath.forServing(Basis.PER_100G, n, Serving(0, "s", grams * 2), amount)!!.kcalX10
                val doubleAmount = NutrientMath.forServing(Basis.PER_100G, n, Serving(0, "s", grams), amount * 2)!!.kcalX10
                assertEquals(twice, doubleAmount)
            }
            // Monotonic in amount.
            val more = NutrientMath.forServing(Basis.PER_100G, n, Serving(0, "s", grams), amount + 1)!!.kcalX10!!
            assert(more >= got)
            // Per item: exactly v × amount / 100.
            val item = NutrientMath.forServing(Basis.PER_ITEM, n, Serving(0, "s", null), amount)!!.kcalX10!!
            assertEquals(reference(v.toBigDecimal() * amount.toBigDecimal(), BigDecimal(100)).toInt(), item)
        }
    }

    @Test
    fun scaleClampsToIntAndKeepsNulls() {
        val big = Nutrients(Int.MAX_VALUE, Int.MIN_VALUE, null, 1, 1, 1, 1, 1, 1)
        val s = NutrientMath.scale(big, 10, 1)
        assertEquals(Int.MAX_VALUE, s.kcalX10)
        assertEquals(Int.MIN_VALUE, s.proteinX10)
        assertNull(s.fatX10)
        assertEquals(10, s.satfatX10)
        assertEquals(Nutrients.UNKNOWN, NutrientMath.scale(Nutrients.UNKNOWN, 3, 7))
    }

    private fun reference(num: BigDecimal, den: BigDecimal): Long = num.divide(den, 0, RoundingMode.HALF_UP).toLong()
}
