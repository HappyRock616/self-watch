/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.barcode

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GtinTest {

    @Test
    fun validCodesPadToFourteenDigits() {
        assertEquals(12345678905L, Gtin.normalize("012345678905")) // UPC-A
        assertEquals(12345678905L, Gtin.normalize("0012345678905")) // the same as EAN-13
        assertEquals(12345678905L, Gtin.normalize("00012345678905")) // and as GTIN-14
        assertEquals(4006381333931L, Gtin.normalize("4006381333931")) // EAN-13
        assertEquals(96385074L, Gtin.normalize("96385074")) // EAN-8
        assertEquals(10012345678902L, Gtin.normalize("10012345678902")) // GTIN-14 with indicator 1
        assertEquals(12345678905L, Gtin.normalize(" 0 12345-67890 5 ")) // separators dropped
    }

    @Test
    fun invalidCodesAreRejected() {
        assertNull(Gtin.normalize("012345678904")) // bad check digit
        assertNull(Gtin.normalize("4006381333932"))
        assertNull(Gtin.normalize("96385075"))
        assertNull(Gtin.normalize("1234567")) // 7 digits
        assertNull(Gtin.normalize("12345678901")) // 11 digits
        assertNull(Gtin.normalize("123456789012345")) // 15 digits
        assertNull(Gtin.normalize(""))
        assertNull(Gtin.normalize("abcdefgh"))
        assertNull(Gtin.normalize("00000000")) // below the stored range
    }

    @Test
    fun checkDigitVectors() {
        assertEquals(5, Gtin.checkDigit("01234567890"))
        assertEquals(1, Gtin.checkDigit("400638133393"))
        assertEquals(4, Gtin.checkDigit("9638507"))
        assertTrue(Gtin.hasValidCheckDigit("4006381333931"))
        assertFalse(Gtin.hasValidCheckDigit("4006381333930"))
        assertFalse(Gtin.hasValidCheckDigit("4"))
        assertFalse(Gtin.hasValidCheckDigit("40063813339a1"))
    }

    @Test
    fun anySingleDigitErrorIsCaught() {
        val r = Random(7)
        repeat(500) {
            val body = (1..12).map { r.nextInt(10) }.joinToString("")
            val code = body + Gtin.checkDigit(body)
            assertTrue(Gtin.hasValidCheckDigit(code))
            val pos = r.nextInt(code.length)
            val wrong = (code[pos] - '0' + 1 + r.nextInt(9)) % 10
            val bad = code.substring(0, pos) + wrong + code.substring(pos + 1)
            assertFalse(bad, Gtin.hasValidCheckDigit(bad))
        }
    }

    @Test
    fun upcEExpansion() {
        // Each last-digit rule (0–2, 3, 4, 5–9).
        assertEquals("012000003455", Gtin.expandUpcE("01234505"))
        assertEquals("01200000345", Gtin.expandUpcE("0123450")?.dropLast(1))
        assertEquals("01230000045", Gtin.expandUpcE("0123453")?.dropLast(1))
        assertEquals("01234000005", Gtin.expandUpcE("0123454")?.dropLast(1))
        assertEquals("01234500007", Gtin.expandUpcE("0123457")?.dropLast(1))
        assertEquals("04252614", "0425261" + Gtin.checkDigit("04210000526"))
        assertEquals("042100005264", Gtin.expandUpcE("04252614"))
        assertEquals("042100005264", Gtin.expandUpcE("425261")) // number system 0 assumed
        assertNull(Gtin.expandUpcE("04252615")) // wrong check digit
        assertNull(Gtin.expandUpcE("24252614")) // number system 2 does not exist
        assertNull(Gtin.expandUpcE("12345"))
        assertEquals(42100005264L, Gtin.normalize(Gtin.expandUpcE("04252614")!!))
    }

    @Test
    fun shortFormForLookups() {
        assertEquals("96385074", Gtin.shortForm(96385074L))
        assertEquals("0012345678905", Gtin.shortForm(12345678905L))
        assertEquals("4006381333931", Gtin.shortForm(4006381333931L))
        assertEquals("10012345678902", Gtin.shortForm(10012345678902L))
    }
}
