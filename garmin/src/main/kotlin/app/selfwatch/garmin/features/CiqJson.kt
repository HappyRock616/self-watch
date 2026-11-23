/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.features

import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.CiqValue

/**
 * JSON ↔ [CiqValue] for watch-app web requests: request bodies the watch sends as Monkey C values go out
 * as JSON text, and JSON answers come back as Monkey C values (Ref: Gadgetbridge 160beab41
 * service/devices/garmin/http/GarminHttpRequest.java getBodyToSend, HttpHandler.java createWebResponse).
 *
 * Numbers without a fraction or exponent that fit a Long become [CiqValue.Num], others [CiqValue.Dbl].
 * Objects become dictionaries with string keys, in document order.
 */
public object CiqJson {
    /** Deepest nesting accepted when parsing. */
    public const val MAX_DEPTH: Int = 64

    /** Writes [v] as JSON. Non-string dictionary keys are written as their JSON text; NaN and infinities as null. */
    public fun write(v: CiqValue): String = StringBuilder().also { write(v, it) }.toString()

    private fun write(v: CiqValue, sb: StringBuilder) {
        when (v) {
            CiqValue.Null -> sb.append("null")
            is CiqValue.Bool -> sb.append(v.v)
            is CiqValue.Num -> sb.append(v.v)
            is CiqValue.Dbl -> if (v.v.isNaN() || v.v.isInfinite()) sb.append("null") else sb.append(v.v)
            is CiqValue.Str -> quote(v.v, sb)
            is CiqValue.Arr -> {
                sb.append('[')
                v.v.forEachIndexed { i, e ->
                    if (i > 0) sb.append(',')
                    write(e, sb)
                }
                sb.append(']')
            }
            is CiqValue.Dict -> {
                sb.append('{')
                var first = true
                for ((k, e) in v.v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(if (k is CiqValue.Str) k.v else write(k), sb)
                    sb.append(':')
                    write(e, sb)
                }
                sb.append('}')
            }
        }
    }

    private fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    /** Parses JSON [text]; an empty or blank text is [CiqValue.Null]. Fails with [SwError.BadInput]. */
    public fun parse(text: String): Outcome<CiqValue> = try {
        val p = Parser(text)
        p.ws()
        if (p.pos == text.length) {
            Outcome.Ok(CiqValue.Null)
        } else {
            val v = p.value(0)
            p.ws()
            if (p.pos != text.length) throw IllegalArgumentException("trailing text at ${p.pos}")
            Outcome.Ok(v)
        }
    } catch (e: IllegalArgumentException) {
        Outcome.Fail(SwError.BadInput("JSON: ${e.message}"))
    } catch (e: IndexOutOfBoundsException) {
        Outcome.Fail(SwError.BadInput("JSON: unexpected end"))
    }

    private class Parser(val s: String) {
        var pos = 0

        fun ws() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(depth: Int): CiqValue {
            require(depth <= MAX_DEPTH) { "nested too deep" }
            ws()
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> CiqValue.Str(str())
                't' -> lit("true", CiqValue.Bool(true))
                'f' -> lit("false", CiqValue.Bool(false))
                'n' -> lit("null", CiqValue.Null)
                else -> if (c == '-' || c in '0'..'9') num() else throw IllegalArgumentException("unexpected '$c' at $pos")
            }
        }

        fun lit(word: String, v: CiqValue): CiqValue {
            require(s.startsWith(word, pos)) { "bad literal at $pos" }
            pos += word.length
            return v
        }

        fun obj(depth: Int): CiqValue {
            pos++
            val m = LinkedHashMap<CiqValue, CiqValue>()
            ws()
            if (s[pos] == '}') {
                pos++
                return CiqValue.Dict(m)
            }
            while (true) {
                ws()
                require(s[pos] == '"') { "expected a key at $pos" }
                val k = str()
                ws()
                require(s[pos] == ':') { "expected ':' at $pos" }
                pos++
                m[CiqValue.Str(k)] = value(depth + 1)
                ws()
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return CiqValue.Dict(m)
                    else -> throw IllegalArgumentException("expected ',' or '}' at ${pos - 1}")
                }
            }
        }

        fun arr(depth: Int): CiqValue {
            pos++
            val l = ArrayList<CiqValue>()
            ws()
            if (s[pos] == ']') {
                pos++
                return CiqValue.Arr(l)
            }
            while (true) {
                l.add(value(depth + 1))
                ws()
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return CiqValue.Arr(l)
                    else -> throw IllegalArgumentException("expected ',' or ']' at ${pos - 1}")
                }
            }
        }

        fun str(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> when (val e = s[pos++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = s.substring(pos, pos + 4)
                            require(hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "bad \\u escape at $pos" }
                            sb.append(hex.toInt(16).toChar())
                            pos += 4
                        }
                        else -> throw IllegalArgumentException("bad escape at ${pos - 1}")
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): CiqValue {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val t = s.substring(start, pos)
            require(t.isNotEmpty() && t != "-") { "bad number at $start" }
            if (t.none { it == '.' || it == 'e' || it == 'E' }) t.toLongOrNull()?.let { return CiqValue.Num(it) }
            val d = t.toDoubleOrNull() ?: throw IllegalArgumentException("bad number '$t'")
            return CiqValue.Dbl(d)
        }
    }
}
