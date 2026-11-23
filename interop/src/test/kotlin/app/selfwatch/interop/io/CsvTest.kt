/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader

class CsvTest {
    @Test
    fun plainRows() {
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), CsvReader.parse("a,b,c\n1,2,3\n"))
    }

    @Test
    fun quotedFieldsWithDelimiterQuotesAndNewlines() {
        val rows = CsvReader.parse("name,note\r\n\"Oats, rolled\",\"say \"\"hi\"\"\"\r\n\"two\nlines\",x\r\n")
        assertEquals(listOf("Oats, rolled", "say \"hi\""), rows[1])
        assertEquals(listOf("two\nlines", "x"), rows[2])
    }

    @Test
    fun byteOrderMarkIsDropped() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Date,Weight\n2026-01-01,80\n".toByteArray()
        val rows = CsvReader.open(ByteArrayInputStream(bytes)).readAll()
        assertEquals("Date", rows[0][0])
        assertEquals(2, rows.size)
    }

    @Test
    fun semicolonAndTabAreDetected() {
        val semi = CsvReader(StringReader("a;b;\"c;d\"\n1;2;3"))
        assertEquals(';', semi.delimiter)
        assertEquals(listOf("a", "b", "c;d"), semi.readRow())
        assertEquals('\t', CsvReader(StringReader("a\tb\tc\n")).delimiter)
        assertEquals(',', CsvReader(StringReader("single\n")).delimiter)
        // Delimiters inside quotes on the first line don't count.
        assertEquals(',', CsvReader(StringReader("\"x;y;z\",b\n")).delimiter)
    }

    @Test
    fun emptyLinesSkippedEmptyFieldsKept() {
        val rows = CsvReader.parse("a,b\n\n,\n1,\n\r\n\"\",2")
        assertEquals(listOf(listOf("a", "b"), listOf("", ""), listOf("1", ""), listOf("", "2")), rows)
    }

    @Test
    fun crOnlyLineEndsAndNoFinalNewline() {
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c")), CsvReader.parse("a\rb\rc"))
    }

    @Test
    fun unterminatedQuoteRunsToEnd() {
        assertEquals(listOf(listOf("a", "open\nfield")), CsvReader.parse("a,\"open\nfield"))
    }

    @Test
    fun textAfterClosingQuoteIsKept() {
        assertEquals(listOf(listOf("ab c", "d")), CsvReader.parse("\"ab\" c,d"))
    }

    @Test(expected = CsvFormatException::class)
    fun hugeFieldFails() {
        CsvReader(StringReader("\"" + "x".repeat(100)), maxFieldChars = 50).readAll()
    }

    @Test
    fun writerQuotesOnlyWhenNeededAndRoundTrips() {
        val out = ByteArrayOutputStream()
        val rows = listOf(
            listOf("plain", "with,comma", "with \"quote\"", "two\nlines", " padded", ""),
            listOf("Crème brûlée", null, "x", "y", "z", "w"),
        )
        CsvWriter(out).use { w -> rows.forEach { w.row(it) } }
        val bytes = out.toByteArray()
        assertEquals(0xEF.toByte(), bytes[0])
        val text = String(bytes, Charsets.UTF_8)
        assertTrue(text.contains("plain,\"with,comma\",\"with \"\"quote\"\"\",\"two\nlines\",\" padded\",\r\n"))
        val back = CsvReader.open(ByteArrayInputStream(bytes)).readAll()
        assertEquals(rows.map { r -> r.map { it ?: "" } }, back)
    }

    @Test
    fun writerWithoutBomAndSemicolon() {
        val out = ByteArrayOutputStream()
        CsvWriter(out, bom = false, delimiter = ';').use { it.row("a;b", "c") }
        assertEquals("\"a;b\";c\r\n", String(out.toByteArray(), Charsets.UTF_8))
    }
}
