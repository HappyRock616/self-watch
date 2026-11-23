/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.recipes

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IngredientParserTest {

    private data class Expected(val line: String, val amount: Int?, val unit: String?, val words: String, val note: String?, val pkgAmount: Int?, val pkgUnit: String?)

    private fun corpus(): List<Expected> {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream("ingredients/corpus.tsv")) { "corpus missing" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        return text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { row ->
            val c = row.split('\t')
            require(c.size == 6) { "bad corpus row: $row" }
            fun n(s: String): String? = if (s == "-") null else s
            val pkg = n(c[5])?.split(' ', limit = 2)
            Expected(c[0], n(c[1])?.toInt(), n(c[2]), c[3], n(c[4]), pkg?.get(0)?.toInt(), pkg?.get(1))
        }
    }

    @Test
    fun corpusParsesAsExpected() {
        val rows = corpus()
        assertTrue("corpus has ${rows.size} lines", rows.size >= 150)
        val failures = ArrayList<String>()
        for (e in rows) {
            val p = IngredientParser.parse(e.line)
            val got = Expected(e.line, p.amountX100, p.unit, p.words, p.note, p.packageAmountX100, p.packageUnit)
            if (got != e) failures.add("\n  line: '${e.line}'\n  want: $e\n  got:  $got")
        }
        assertTrue("${failures.size} of ${rows.size} lines differ:${failures.joinToString("")}", failures.isEmpty())
    }

    @Test
    fun toTasteLinesAreFlagged() {
        assertTrue(IngredientParser.parse("Salt and pepper to taste").toTaste)
        assertTrue(IngredientParser.parse("salt, to taste").toTaste)
        assertTrue(IngredientParser.parse("Olive oil as needed").toTaste)
        assertTrue(IngredientParser.parse("Fresh parsley for garnish").toTaste)
        assertFalse(IngredientParser.parse("1 tablespoon sugar plus more to taste").toTaste) // has an amount
        assertFalse(IngredientParser.parse("2 cups flour").toTaste)
        assertFalse(IngredientParser.parse("cooking spray").toTaste)
    }

    @Test
    fun emptyAndOddInputNeverThrows() {
        val empty = IngredientParser.parse("")
        assertNull(empty.amountX100)
        assertEquals("", empty.words)
        assertEquals("", IngredientParser.parse("   ").words)
        assertNull(IngredientParser.parse("1/0 cup flour").amountX100) // a zero denominator is not a number
        assertEquals("flour", IngredientParser.parse("((2 cups)) flour").words)
        val r = Random(5)
        val alphabet = "0123456789 /-.,()[]½¼abcdeftTgozlbcup\t—x#"
        repeat(5_000) {
            val s = (0 until r.nextInt(0, 30)).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
            IngredientParser.parse(s) // must not throw
        }
        IngredientParser.parse("99999999999999999999 cups flour")
        IngredientParser.parse("1/99999999999999999999 cups flour")
    }
}
