/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.model.json

import app.selfwatch.model.json.JsonValue.Arr
import app.selfwatch.model.json.JsonValue.Bool
import app.selfwatch.model.json.JsonValue.Num
import app.selfwatch.model.json.JsonValue.Obj
import app.selfwatch.model.json.JsonValue.Str
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonTest {
    private fun ok(text: String): JsonValue = when (val r = Json.parse(text)) {
        is Outcome.Ok -> r.value
        is Outcome.Fail -> throw AssertionError("expected OK for <$text>, got ${r.error}")
    }

    private fun bad(text: String) {
        when (val r = Json.parse(text)) {
            is Outcome.Ok -> fail("expected failure for <$text>, got ${r.value}")
            is Outcome.Fail -> assertTrue(r.error is SwError.BadInput)
        }
    }

    // --- literals and structure ---

    @Test fun literals() {
        assertEquals(JsonValue.Null, ok("null"))
        assertEquals(Bool(true), ok("true"))
        assertEquals(Bool(false), ok(" \t\r\nfalse \n"))
    }

    @Test fun nesting() {
        val v = ok("""{"a":[1,{"b":[[],{}]},"x"],"c":{"d":null}}""")
        assertEquals(Num("1"), v["a"]?.get(0))
        assertEquals(Arr(emptyList()), v["a"]?.get(1)?.get("b")?.get(0))
        assertEquals(Obj(emptyMap()), v["a"]?.get(1)?.get("b")?.get(1))
        assertEquals("x", v["a"]?.get(2)?.asString)
        assertEquals(JsonValue.Null, v["c"]?.get("d"))
        assertNull(v["missing"])
        assertNull(v["a"]?.get(9))
    }

    @Test fun objectKeepsKeyOrderAndLastDuplicateWins() {
        val v = ok("""{"z":1,"a":2,"m":3,"a":4}""")
        assertEquals(listOf("z", "a", "m"), v.asMap?.keys?.toList())
        assertEquals(4L, v["a"]?.asLong)
    }

    @Test fun deepButAllowedNesting() {
        val depth = 200
        val v = ok("[".repeat(depth) + "]".repeat(depth))
        var cur: JsonValue? = v
        repeat(depth - 1) { cur = cur?.get(0) }
        assertEquals(Arr(emptyList()), cur)
    }

    @Test fun tooDeepNestingFailsWithoutStackOverflow() {
        bad("[".repeat(100_000) + "]".repeat(100_000))
        bad("{\"a\":".repeat(300) + "1" + "}".repeat(300))
    }

    // --- strings ---

    @Test fun simpleEscapes() {
        assertEquals(" \" \\ / \b \u000C \n \r \t ", ok("""" \" \\ \/ \b \f \n \r \t """").asString)
    }

    @Test fun unicodeEscapes() {
        assertEquals("é", ok("\"\\u00e9\"").asString)
        assertEquals("é", ok("\"\\u00E9\"").asString)
        assertEquals("\u0000", ok("\"\\u0000\"").asString)
        assertEquals("€", ok("\"\\u20AC\"").asString)
    }

    @Test fun surrogatePairEscape() {
        val s = ok("\"\\uD83D\\uDE00\"").asString!!
        assertEquals("\uD83D\uDE00", s) // U+1F600
        assertEquals(0x1F600, s.codePointAt(0))
        assertEquals(1, s.codePointCount(0, s.length))
    }

    @Test fun rawUnicodePassesThrough() {
        assertEquals("vívoactive 5 \uD83C\uDFC3 日本", ok("\"vívoactive 5 \uD83C\uDFC3 日本\"").asString)
    }

    @Test fun badStrings() {
        bad("\"abc") // unterminated
        bad("\"a\nb\"") // raw control character
        bad("\"\u0001\"")
        bad("\"\\x\"") // unknown escape
        bad("\"\\u12\"") // short \u
        bad("\"\\u12G4\"") // bad hex
        bad("\"\\uD83D\"") // lone high surrogate
        bad("\"\\uD83Dx\"")
        bad("\"\\uD83D\\u0041\"") // high followed by non-low
        bad("\"\\uDE00\"") // lone low surrogate
        bad("\"\\") // truncated escape
        bad("'single'")
    }

    // --- numbers ---

    @Test fun numbers() {
        assertEquals(0L, ok("0").asLong)
        assertEquals(-0L, ok("-0").asLong)
        assertEquals(42L, ok("42").asLong)
        assertEquals(-17L, ok("-17").asLong)
        assertEquals(Long.MAX_VALUE, ok("9223372036854775807").asLong)
        assertEquals(Long.MIN_VALUE, ok("-9223372036854775808").asLong)
        assertEquals(3.25, ok("3.25").asDouble!!, 0.0)
        assertEquals(-0.5, ok("-0.5").asDouble!!, 0.0)
        assertEquals(1.5e10, ok("1.5e10").asDouble!!, 0.0)
        assertEquals(2.0e-3, ok("2E-3").asDouble!!, 0.0)
        assertEquals(1000.0, ok("1e+3").asDouble!!, 0.0)
        assertEquals(-1.0e-7, ok("-1.0E-7").asDouble!!, 0.0)
    }

    @Test fun numberToLongRules() {
        assertEquals(1000L, ok("1e3").asLong)
        assertEquals(2L, ok("2.0").asLong)
        assertNull(ok("2.5").asLong)
        assertNull(ok("9223372036854775808").asLong) // out of range
        assertEquals(9.223372036854775808e18, ok("9223372036854775808").asDouble!!, 0.0)
        assertNull(ok("\"5\"").asLong) // strings are not numbers
    }

    @Test fun numberTextIsKeptExactly() {
        assertEquals("1.50", (ok("1.50") as Num).text)
        assertEquals("12345678901234567890123", Json.write(ok("12345678901234567890123")))
    }

    @Test fun badNumbers() {
        for (s in listOf("01", "-", "+1", "1.", ".5", "1e", "1e+", "--1", "0x10", "1.2.3", "NaN", "Infinity",
            "-Infinity", "[01]", "1 2")) bad(s)
    }

    @Test fun numFactories() {
        assertEquals("7", Num.of(7L).text)
        assertEquals(2.5, Num.of(2.5).toDouble(), 0.0)
        try {
            Num.of(Double.NaN)
            fail("NaN accepted")
        } catch (_: IllegalArgumentException) {
        }
        try {
            Num("1.")
            fail("bad number text accepted")
        } catch (_: IllegalArgumentException) {
        }
    }

    // --- malformed documents ---

    @Test fun malformedDocumentsFailNotCrash() {
        for (s in listOf(
            "", " ", "[", "]", "{", "}", "[1,]", "[,1]", "{\"a\":1,}", "{\"a\"}", "{\"a\" 1}", "{a:1}",
            "{1:1}", "[1 2]", "nul", "truex", "tru", "null null", "[]]", "{}}", "[\"a\"", "// c\n1",
            "/* c */1", "\uFEFF1", "[1,2", "{\"a\":", "\u0000",
        )) bad(s)
    }

    @Test fun errorMessageHasOffset() {
        val r = Json.parse("[1, x]") as Outcome.Fail
        val detail = (r.error as SwError.BadInput).detail
        assertTrue(detail, detail.contains("offset 4"))
    }

    @Test fun randomGarbageNeverThrows() {
        val rnd = kotlin.random.Random(7)
        val alphabet = "{}[]\",:\\u0123456789abcdefE+-.tnrl \n"
        repeat(5000) {
            val len = rnd.nextInt(0, 40)
            val s = buildString { repeat(len) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            Json.parse(s) // must return, never throw
        }
    }

    // --- writer ---

    @Test fun writeCompact() {
        val v = Obj(linkedMapOf(
            "name" to Str("Self Watch"),
            "n" to Num.of(-3L),
            "ok" to Bool(true),
            "none" to JsonValue.Null,
            "list" to Arr(listOf(Num("1.5e3"), Arr(emptyList()), Obj(emptyMap()))),
        ))
        assertEquals(
            """{"name":"Self Watch","n":-3,"ok":true,"none":null,"list":[1.5e3,[],{}]}""",
            Json.write(v),
        )
    }

    @Test fun writeEscapes() {
        assertEquals(
            "\"q\\\" b\\\\ n\\n r\\r t\\t b\\b f\\f c\\u0001 s\\u2028 /\"",
            Json.write(Str("q\" b\\ n\n r\r t\t b\b f\u000C c\u0001 s\u2028 /")),
        )
        assertEquals("\"é\uD83D\uDE00\"", Json.write(Str("é\uD83D\uDE00")))
        assertEquals("\"\\ud800\"", Json.write(Str("\uD800"))) // unpaired surrogate made visible
    }

    @Test fun roundTrip() {
        val texts = listOf(
            """{"a":[1,2.5,-3e-2,true,false,null],"b":{"c":"d\"e\\f\n\u0000\u00e9\ud83d\ude00"},"e":[]}""",
            """[[[[["deep"]]]]]""",
            """"just a string"""",
            "123",
        )
        for (t in texts) {
            val v = ok(t)
            assertEquals(t, v, ok(Json.write(v)))
        }
    }

    @Test fun accessorsOnWrongTypesAreNull() {
        val v = ok("""{"s":"x","n":1,"b":true,"a":[1],"o":{}}""")
        assertNull(v["s"]?.asLong)
        assertNull(v["n"]?.asString)
        assertNull(v["b"]?.asList)
        assertNull(v["a"]?.asMap)
        assertNull(v["o"]?.asBoolean)
        assertNull(v["a"]?.get("k"))
        assertNull(v[0])
        assertFalse(v["b"]?.asBoolean == false)
    }
}
