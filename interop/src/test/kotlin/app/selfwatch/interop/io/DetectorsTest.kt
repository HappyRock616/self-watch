/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.io

import app.selfwatch.interop.api.WeightUnit
import app.selfwatch.model.time.Day
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorsTest {
    @Test
    fun parsesEachLayout() {
        assertEquals(LocalStamp(Day(20260501), null), DateFormat.ISO.parse("2026-05-01"))
        assertEquals(LocalStamp(Day(20260501), null), DateFormat.US.parse("5/1/2026"))
        assertEquals(LocalStamp(Day(20260105), null), DateFormat.EU.parse("05/01/2026"))
        assertEquals(LocalStamp(Day(20260105), null), DateFormat.DOTTED.parse("05.01.26"))
        assertEquals(LocalStamp(Day(20260501), 7 * 3600 + 15 * 60), DateFormat.ISO.parse("2026-05-01T07:15:00Z"))
        assertEquals(LocalStamp(Day(20260501), 19 * 3600 + 5 * 60), DateFormat.US.parse("05/01/2026 7:05 PM"))
        assertEquals(LocalStamp(Day(20260501), 30), DateFormat.US.parse("05/01/2026 12:00:30 am"))
    }

    @Test
    fun rejectsImpossibleDates() {
        assertNull(DateFormat.ISO.parse("2026-02-30"))
        assertNull(DateFormat.US.parse("13/01/2026"))
        assertNull(DateFormat.ISO.parse("05/01/2026"))
        assertNull(DateFormat.DOTTED.parse("05/01/2026"))
        assertNull(DateFormat.ISO.parse("2026-05-01 25:00"))
        assertNull(DateFormat.US.parse("hello"))
    }

    @Test
    fun formatsEachLayout() {
        assertEquals("2026-05-01", DateFormat.ISO.format(Day(20260501)))
        assertEquals("05/01/2026", DateFormat.US.format(Day(20260501)))
        assertEquals("01/05/2026", DateFormat.EU.format(Day(20260501)))
        assertEquals("01.05.2026", DateFormat.DOTTED.format(Day(20260501)))
    }

    @Test
    fun clearUsDates() {
        val g = DateFormatDetector.detect(listOf("03/04/2026", "03/25/2026"))
        assertEquals("MM/dd/yyyy", g.best)
        assertFalse(g.ambiguous)
    }

    @Test
    fun clearEuropeanDates() {
        val g = DateFormatDetector.detect(listOf("03/04/2026", "25/03/2026"), prefer = DateFormat.US)
        assertEquals("dd/MM/yyyy", g.best)
        assertFalse(g.ambiguous)
    }

    @Test
    fun ambiguousDatesListCandidatesAndPreferTheImportersFormat() {
        val g = DateFormatDetector.detect(listOf("03/04/2026", "04/05/2026", "12/12/2026"), prefer = DateFormat.EU)
        assertTrue(g.ambiguous)
        assertEquals("dd/MM/yyyy", g.best)
        assertEquals(listOf("MM/dd/yyyy", "dd/MM/yyyy"), g.candidates)
        // 12/12 reads the same both ways, so it is not shown as an example.
        assertEquals(listOf("03/04/2026", "04/05/2026"), g.ambiguousSamples)
    }

    @Test
    fun isoAndDottedAreNeverAmbiguous() {
        assertEquals(listOf("yyyy-MM-dd"), DateFormatDetector.detect(listOf("2026-01-02", "2026-03-04")).candidates)
        assertEquals("dd.MM.yyyy", DateFormatDetector.detect(listOf("01.02.2026")).best)
    }

    @Test
    fun mostlyBadValuesStillGiveABestGuess() {
        val g = DateFormatDetector.detect(listOf("2026-01-02", "garbage", "", "2026-01-03"))
        assertEquals("yyyy-MM-dd", g.best)
        assertEquals(DateFormatDetector.detect(emptyList(), DateFormat.US).best, "MM/dd/yyyy")
    }

    @Test
    fun isoTime() {
        assertEquals(1_777_619_700L, IsoTime.parse("2026-05-01T07:15:00Z"))
        assertEquals(1_777_619_700L, IsoTime.parse("2026-05-01T09:15:00.250+02:00"))
        assertEquals(1_777_619_700L, IsoTime.parse("2026-05-01T07:15:00"))
        assertNull(IsoTime.parse("yesterday"))
        assertEquals("2026-05-01T07:15:00Z", IsoTime.format(1_777_619_700L))
    }

    @Test
    fun decimals() {
        assertEquals(1234.5, Numbers.parseDecimal("1,234.5")!!, 1e-9)
        assertEquals(1234.5, Numbers.parseDecimal("1.234,5")!!, 1e-9)
        assertEquals(12.5, Numbers.parseDecimal("12,5")!!, 1e-9)
        assertEquals(1234.0, Numbers.parseDecimal("1,234")!!, 1e-9)
        assertEquals(1.234, Numbers.parseDecimal("1,234", decimalComma = true)!!, 1e-9)
        assertEquals(-3.0, Numbers.parseDecimal(" -3 ")!!, 1e-9)
        assertNull(Numbers.parseDecimal("12 kg"))
        assertNull(Numbers.parseDecimal(""))
        assertNull(Numbers.parseDecimal("NaN"))
    }

    @Test
    fun quantities() {
        assertEquals(1.5 to "cups flour", Numbers.leadingQuantity("1 1/2 cups flour"))
        assertEquals(0.5 to "tsp", Numbers.leadingQuantity("1/2 tsp"))
        assertEquals(0.5 to "tsp ground cumin", Numbers.leadingQuantity("½ tsp ground cumin"))
        assertEquals(2.25 to "lb", Numbers.leadingQuantity("2¼ lb"))
        assertEquals(1.5 to "cup", Numbers.leadingQuantity("1.50 cup"))
        assertEquals(2.0 to "cups", Numbers.leadingQuantity("2-3 cups"))
        assertEquals(12.0 to "eggs", Numbers.leadingQuantity("12 eggs"))
        assertNull(Numbers.leadingQuantity("salt to taste"))
    }

    @Test
    fun fixedPointFormatting() {
        assertEquals("12.5", Numbers.formatScaled(125, 10))
        assertEquals("12", Numbers.formatScaled(120, 10))
        assertEquals("1.50", Numbers.formatScaled(150, 100, trim = false))
        assertEquals("-0.05", Numbers.formatScaled(-5, 100))
        assertEquals("47.6000009", Numbers.formatScaled(476_000_009, 10_000_000))
        assertEquals(125, Numbers.scaled(12.45, 10))
        assertEquals(-125, Numbers.scaled(-12.45, 10))
    }

    @Test
    fun weightUnits() {
        assertEquals(WeightUnit.LB, UnitDetector.detect("Weight (lbs)", listOf(70.0)).unit)
        assertEquals(WeightUnit.KG, UnitDetector.detect("Weight [kg]", listOf(170.0)).unit)
        assertEquals(WeightUnit.KG, UnitDetector.detect("Weight", listOf(70.2, 71.0, 69.8)).unit)
        assertEquals(WeightUnit.LB, UnitDetector.detect(null, listOf(181.2, 180.6)).unit)
        val unsure = UnitDetector.detect("Weight", listOf(120.0))
        assertFalse(unsure.confident)
        assertEquals(WeightUnit.KG, unsure.unit)
        assertEquals(81.6466, UnitDetector.toKg(180.0, WeightUnit.LB), 1e-3)
    }

    @Test
    fun columnMatching() {
        val m = ColumnMatcher(
            linkedMapOf("date" to listOf("date", "day"), "fat" to listOf("fat"), "satfat" to listOf("saturated fat"), "sodium" to listOf("sodium")),
        ).match(listOf(0xFEFF.toChar() + "Day", "Fat (g)", "Saturated_Fat", "SODIUM [mg]", "Other"))
        assertEquals(0, m.index("date"))
        assertEquals(1, m.index("fat"))
        assertEquals(2, m.index("satfat"))
        assertEquals("mg", m.unit("sodium"))
        assertEquals("g", m.unit("fat"))
        assertEquals(listOf("Other"), m.unmatched())
        assertEquals("1.5", m[listOf("2026-01-01", " 1.5 ", "", "300", "x"), "fat"])
        assertNull(m[listOf("2026-01-01", "1.5", "  "), "satfat"])
    }

    @Test
    fun zipNamesFromHead() {
        val zip = app.selfwatch.interop.TestSupport.zipOf("a/servings.csv" to ByteArray(10), "notes.csv" to ByteArray(3))
        assertTrue(Zips.isZip(zip))
        assertEquals(listOf("a/servings.csv", "notes.csv"), Zips.namesInHead(zip))
    }
}
