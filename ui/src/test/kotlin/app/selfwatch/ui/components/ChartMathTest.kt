/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.components

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ChartMathTest {
    private val eps = 0.001f

    @Test
    fun sweepClampsAndHandlesNaN() {
        assertEquals(0f, RingMath.sweepDegrees(-0.5f), eps)
        assertEquals(90f, RingMath.sweepDegrees(0.25f), eps)
        assertEquals(360f, RingMath.sweepDegrees(1f), eps)
        assertEquals(360f, RingMath.sweepDegrees(1.7f), eps)
        assertEquals(0f, RingMath.sweepDegrees(Float.NaN), eps)
    }

    @Test
    fun arcBoxIsCentredSquareInsetByHalfStroke() {
        val box = RingMath.arcBox(width = 200f, height = 100f, stroke = 10f)
        assertEquals(Box(55f, 5f, 145f, 95f), box)
        assertEquals(box.width, box.height, eps)
    }

    @Test
    fun arcBoxRespectsPadding() {
        val box = RingMath.arcBox(120f, 120f, 8f, padStart = 10f, padTop = 10f, padEnd = 10f, padBottom = 10f)
        assertEquals(Box(14f, 14f, 106f, 106f), box)
    }

    @Test
    fun arcBoxNeverNegative() {
        val box = RingMath.arcBox(4f, 4f, 10f)
        assertEquals(0f, box.width, eps)
    }

    @Test
    fun innerTextWidthIsEightyPercentOfInnerDiameter() {
        val box = Box(0f, 0f, 100f, 100f)
        assertEquals(72f, RingMath.innerTextWidth(box, 10f), eps)
    }

    @Test
    fun fitTextScale() {
        assertEquals(1f, fitTextScale(50f, 100f), eps)
        assertEquals(0.5f, fitTextScale(200f, 100f), eps)
        assertEquals(0.4f, fitTextScale(1000f, 100f), eps)
        assertEquals(1f, fitTextScale(0f, 100f), eps)
    }

    @Test
    fun niceCeil() {
        assertEquals(1f, BarsMath.niceCeil(0f), eps)
        assertEquals(1f, BarsMath.niceCeil(0.8f), eps)
        assertEquals(2000f, BarsMath.niceCeil(1240f), eps)
        assertEquals(2500f, BarsMath.niceCeil(2420f), eps)
        assertEquals(5000f, BarsMath.niceCeil(2600f), eps)
        assertEquals(10000f, BarsMath.niceCeil(7842f), eps)
        assertEquals(100f, BarsMath.niceCeil(100f), eps)
    }

    @Test
    fun scaleMaxCoversValuesAndGoal() {
        val s = listOf(intArrayOf(1180, 2100), intArrayOf(2420, 1900))
        assertEquals(2500f, BarsMath.scaleMax(s, null), eps)
        assertEquals(5000f, BarsMath.scaleMax(s, 3000f), eps)
        assertEquals(1f, BarsMath.scaleMax(emptyList(), null), eps)
    }

    @Test
    fun valueToYMapsAndClamps() {
        assertEquals(100f, BarsMath.valueToY(0f, 2000f, 0f, 100f), eps)
        assertEquals(50f, BarsMath.valueToY(1000f, 2000f, 0f, 100f), eps)
        assertEquals(0f, BarsMath.valueToY(5000f, 2000f, 0f, 100f), eps)
        assertEquals(100f, BarsMath.valueToY(-5f, 2000f, 0f, 100f), eps)
        assertEquals(100f, BarsMath.valueToY(10f, 0f, 0f, 100f), eps)
    }

    @Test
    fun slotsSplitGroupsAndSeries() {
        // 2 groups of 100 px, 20% gap (10 px each side), 2 series, no bar gap: bars 40 px wide.
        assertEquals(BarsMath.Slot(10f, 50f), BarsMath.slot(0, 0, 2, 2, 200f, groupGap = 0.2f))
        assertEquals(BarsMath.Slot(50f, 90f), BarsMath.slot(0, 1, 2, 2, 200f, groupGap = 0.2f))
        assertEquals(BarsMath.Slot(110f, 150f), BarsMath.slot(1, 0, 2, 2, 200f, groupGap = 0.2f))
    }

    @Test
    fun slotsMirrorInRtl() {
        assertEquals(BarsMath.Slot(150f, 190f), BarsMath.slot(0, 0, 2, 2, 200f, groupGap = 0.2f, rtl = true))
    }

    @Test
    fun slotDegenerateInputs() {
        assertEquals(BarsMath.Slot(0f, 0f), BarsMath.slot(0, 0, 0, 1, 100f))
        assertEquals(BarsMath.Slot(0f, 0f), BarsMath.slot(0, 0, 3, 1, 0f))
    }

    @Test
    fun sparkPoints() {
        val pts = SparkMath.points(intArrayOf(0, 10, 5), 100f, 50f, pad = 0f)
        assertArrayEquals(floatArrayOf(0f, 50f, 50f, 0f, 100f, 25f), pts, eps)
    }

    @Test
    fun sparkFlatAndSingle() {
        assertArrayEquals(floatArrayOf(2f, 12f, 98f, 12f), SparkMath.points(intArrayOf(7, 7), 100f, 24f, 2f), eps)
        assertArrayEquals(floatArrayOf(50f, 12f), SparkMath.points(intArrayOf(3), 100f, 24f, 2f), eps)
        assertEquals(0, SparkMath.points(IntArray(0), 100f, 24f).size)
    }

    @Test
    fun stepperSnapsToGridAndClamps() {
        assertEquals(1300L, StepperMath.step(1250L, 100L, +1, 0L, 10_000L))
        assertEquals(1200L, StepperMath.step(1250L, 100L, -1, 0L, 10_000L))
        assertEquals(1300L, StepperMath.step(1200L, 100L, +1, 0L, 10_000L))
        assertEquals(1100L, StepperMath.step(1200L, 100L, -1, 0L, 10_000L))
        assertEquals(0L, StepperMath.step(50L, 100L, -1, 0L, 10_000L))
        assertEquals(10_000L, StepperMath.step(9_990L, 100L, +1, 0L, 10_000L))
        assertEquals(25L, StepperMath.step(0L, 25L, +1, 0L, 100L))
    }
}
