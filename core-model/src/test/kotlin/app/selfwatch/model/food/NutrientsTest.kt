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
        assertEquals(Nutrients.ZERO, n.scaled(0))
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
        assertEquals(a, a + Nutrients.ZERO)
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
}
