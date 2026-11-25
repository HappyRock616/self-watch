/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.model.food

import org.junit.Assert.assertEquals
import org.junit.Test

class NutrientsTest {
    private fun kcal(v: Int?) = Nutrients.UNKNOWN.copy(kcalX10 = v)

    @Test fun scaledIdentityAndSimple() {
        val n = Nutrients(1000, 50, 30, 10, 200, 100, 20, 400, 5)
        assertEquals(n, n.scaled(1000))
        assertEquals(Nutrients(1500, 75, 45, 15, 300, 150, 30, 600, 8), n.scaled(1500)) // 7.5 -> 8
        assertEquals(Nutrients(0, 0, 0, 0, 0, 0, 0, 0, 0), n.scaled(0))
    }

    @Test fun scaledRoundsHalfAwayFromZero() {
        assertEquals(3, kcal(5).scaled(500).kcalX10) // 2.5 -> 3
        assertEquals(-3, kcal(-5).scaled(500).kcalX10) // -2.5 -> -3
        assertEquals(2, kcal(5).scaled(499).kcalX10) // 2.495 -> 2
        assertEquals(3, kcal(5).scaled(501).kcalX10) // 2.505 -> 3
        assertEquals(-3, kcal(5).scaled(-500).kcalX10)
        assertEquals(1, kcal(1).scaled(500).kcalX10) // 0.5 -> 1
        assertEquals(0, kcal(1).scaled(499).kcalX10) // 0.499 -> 0
        assertEquals(1, kcal(3).scaled(333).kcalX10) // 0.999 -> 1
    }

    @Test fun scaledKeepsNullsAndClamps() {
        assertEquals(Nutrients.UNKNOWN, Nutrients.UNKNOWN.scaled(2500))
        val n = Nutrients(10, null, 20, null, null, null, null, 7, null)
        assertEquals(Nutrients(20, null, 40, null, null, null, null, 14, null), n.scaled(2000))
        assertEquals(Int.MAX_VALUE, kcal(Int.MAX_VALUE).scaled(2000).kcalX10)
        assertEquals(Int.MIN_VALUE, kcal(Int.MAX_VALUE).scaled(-3000).kcalX10)
    }

    @Test fun plusBothKnownSums() {
        val a = Nutrients(100, 10, 5, 1, 20, 4, 2, 300, 0)
        val b = Nutrients(50, 1, 2, 0, 3, 1, 1, 100, 10)
        assertEquals(Nutrients(150, 11, 7, 1, 23, 5, 3, 400, 10), a + b)
        assertEquals(Nutrients.ZERO.copy(kcalX10 = 100, proteinX10 = 10, fatX10 = 5, satfatX10 = 1, carbX10 = 20, sugarX10 = 4, fibreX10 = 2, sodiumMg = 300), a + Nutrients.ZERO)
    }

    @Test fun plusSkipsUnknownValues() {
        assertEquals(100, (kcal(100) + kcal(null)).kcalX10)
        assertEquals(100, (kcal(null) + kcal(100)).kcalX10)
        assertEquals(null, (kcal(null) + kcal(null)).kcalX10)
        assertEquals(Nutrients.ZERO, Nutrients.ZERO + Nutrients.UNKNOWN)
        // A known zero is not unknown.
        assertEquals(7, (kcal(0) + kcal(7)).kcalX10)
    }

    @Test fun plusClampsOverflow() {
        assertEquals(Int.MAX_VALUE, (kcal(Int.MAX_VALUE) + kcal(1)).kcalX10)
    }

    @Test fun v2FieldsDefaultUnknownScaleAndSum() {
        val old = Nutrients(100, 10, 5, 1, 20, 4, 2, 300, 0)
        assertEquals(null, old.potassiumMg)
        assertEquals(9, old.knownCount)
        val a = old.copy(potassiumMg = 200, vitdX10 = 15, transfatX10 = 3)
        assertEquals(400, a.scaled(2000).potassiumMg)
        assertEquals(30, a.scaled(2000).vitdX10)
        assertEquals(null, a.scaled(2000).calciumMg)
        val b = Nutrients.UNKNOWN.copy(potassiumMg = 50, calciumMg = 20)
        val sum = a + b
        assertEquals(250, sum.potassiumMg)
        assertEquals(20, sum.calciumMg)
        assertEquals(3, sum.transfatX10)
        assertEquals(null, sum.ironX10)
        assertEquals(250, sum[Nutrients.Field.POTASSIUM])
        assertEquals(Nutrients.Field.entries.size, Nutrients.ZERO.knownCount)
        assertEquals(a, Nutrients.fromList(a.toList()))
    }

    @Test fun dietInfoNeverClaimsFreeWhenUnknown() {
        val unknown = DietInfo.UNKNOWN
        assertEquals(null, unknown.vegan)
        assertEquals(null, unknown.has(Allergen.MILK))
        assertEquals(true, unknown.isUnknown)
        // Vegan decided yes, vegetarian not decided: vegan implies vegetarian.
        val vegan = DietInfo(DietInfo.VEGAN, DietInfo.VEGAN, 0, false)
        assertEquals(true, vegan.vegan)
        assertEquals(true, vegan.vegetarian)
        // Decided "not vegan" (known bit without the diet bit).
        val notVegan = DietInfo(DietInfo.VEGETARIAN, DietInfo.VEGAN or DietInfo.VEGETARIAN, Allergen.MILK.bit, true)
        assertEquals(false, notVegan.vegan)
        assertEquals(true, notVegan.vegetarian)
        assertEquals(true, notVegan.has(Allergen.MILK))
        assertEquals(false, notVegan.has(Allergen.PEANUT)) // full list checked
        assertEquals(listOf(Allergen.MILK), notVegan.contains)
        val gf = DietInfo(DietInfo.GLUTEN_FREE_NATURAL, 0, 0, false)
        assertEquals(false, gf.has(Allergen.GLUTEN))
        assertEquals(null, gf.has(Allergen.SOY))
        assertEquals(Allergen.MILK.bit or Allergen.SOY.bit, Allergen.maskOf(listOf(Allergen.MILK, Allergen.SOY)))
        assertEquals(setOf(Allergen.GLUTEN, Allergen.SESAME), Allergen.of(257))
    }
}
