/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.model.json

import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import java.math.BigDecimal

/**
 * A JSON value (RFC 8259). Objects keep their key order. Numbers keep their exact text, so 64-bit
 * ids and decimals round-trip without loss.
 */
public sealed interface JsonValue {
    /** JSON `null`. */
    public data object Null : JsonValue

    /** JSON `true` / `false`. */
    public data class Bool(val v: Boolean) : JsonValue

    /**
     * A JSON number holding its exact [text] (which must be a valid JSON number). Two numbers are
     * equal only when their text is equal (`1` and `1.0` differ); compare [toDouble] or [toLongOrNull]
     * to compare values.
     */
    public data class Num(val text: String) : JsonValue {
        init {
            require(isJsonNumber(text)) { "not a JSON number" }
        }

        /** The value as a Long if it is a whole number in range (`1e3` and `2.0` count), else null. */
        public fun toLongOrNull(): Long? = text.toLongOrNull() ?: try {
            BigDecimal(text).longValueExact()
        } catch (_: ArithmeticException) {
            null
        }

        /** The value as a Double (may lose precision or become infinite for huge exponents). */
        public fun toDouble(): Double = text.toDouble()

        /** Factories. */
        public companion object {
            /** The number [v]. */
            public fun of(v: Long): Num = Num(v.toString())

            /** The number [v]; must be finite (JSON has no NaN or infinity). */
            public fun of(v: Double): Num {
                require(v.isFinite()) { "JSON numbers must be finite" }
                return Num(v.toString())
            }
        }
    }

    /** A JSON string. */
    public data class Str(val v: String) : JsonValue

    /** A JSON array. */
    public data class Arr(val items: List<JsonValue>) : JsonValue

    /** A JSON object; key order is kept. */
    public data class Obj(val fields: Map<String, JsonValue>) : JsonValue

    /** The field [key] if this is an object that has it, else null. */
    public operator fun get(key: String): JsonValue? = (this as? Obj)?.fields?.get(key)

    /** The element at [index] if this is an array long enough, else null. */
    public operator fun get(index: Int): JsonValue? = (this as? Arr)?.items?.getOrNull(index)

    /** The string if this is a [Str], else null. */
    public val asString: String? get() = (this as? Str)?.v

    /** The value if this is a whole-number [Num] that fits in a Long, else null. */
    public val asLong: Long? get() = (this as? Num)?.toLongOrNull()

    /** The value if this is a [Num], else null. */
    public val asDouble: Double? get() = (this as? Num)?.toDouble()

    /** The value if this is a [Bool], else null. */
    public val asBoolean: Boolean? get() = (this as? Bool)?.v

    /** The items if this is an [Arr], else null. */
    public val asList: List<JsonValue>? get() = (this as? Arr)?.items

    /** The fields if this is an [Obj], else null. */
    public val asMap: Map<String, JsonValue>? get() = (this as? Obj)?.fields
}

/**
 * Minimal, dependency-free JSON reader and writer.
 *
 * [parse] is strict RFC 8259: one value, optional surrounding whitespace, no comments, no trailing
 * commas, no leading zeros, no raw control characters in strings, no unpaired surrogate escapes.
 * Malformed input returns [Outcome.Fail] with [SwError.BadInput]; it never throws. Duplicate object
 * keys keep the last value.
 */
public object Json {
    /** Default limit on array/object nesting, to bound stack use on hostile input. */
    public const val DEFAULT_MAX_DEPTH: Int = 256

    /** Parses [text] into a [JsonValue], failing with [SwError.BadInput] on malformed input. */
    public fun parse(text: String, maxDepth: Int = DEFAULT_MAX_DEPTH): Outcome<JsonValue> = try {
        val p = Parser(text, maxDepth)
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        if (!p.atEnd()) p.fail("unexpected trailing characters")
        Outcome.Ok(v)
    } catch (e: JsonSyntaxError) {
        Outcome.Fail(SwError.BadInput(e.message ?: "bad JSON"))
    }

    /** Writes [value] as compact JSON. Non-ASCII characters are written as-is (the output is UTF-16 text). */
    public fun write(value: JsonValue): String = StringBuilder().also { writeTo(it, value) }.toString()

    private fun writeTo(sb: StringBuilder, v: JsonValue) {
        when (v) {
            JsonValue.Null -> sb.append("null")
            is JsonValue.Bool -> sb.append(if (v.v) "true" else "false")
            is JsonValue.Num -> sb.append(v.text)
            is JsonValue.Str -> writeString(sb, v.v)
            is JsonValue.Arr -> {
                sb.append('[')
                v.items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    writeTo(sb, item)
                }
                sb.append(']')
            }
            is JsonValue.Obj -> {
                sb.append('{')
                var first = true
                for ((k, item) in v.fields) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k)
                    sb.append(':')
                    writeTo(sb, item)
                }
                sb.append('}')
            }
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c < ' ' || c == '\u2028' || c == '\u2029' -> appendUnicodeEscape(sb, c)
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> {
                    sb.append(c).append(s[i + 1])
                    i++
                }
                c.isSurrogate() -> appendUnicodeEscape(sb, c) // unpaired: keep it visible rather than corrupt
                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
    }

    private fun appendUnicodeEscape(sb: StringBuilder, c: Char) {
        val hex = "0123456789abcdef"
        val code = c.code
        sb.append("\\u")
        for (shift in intArrayOf(12, 8, 4, 0)) sb.append(hex[(code shr shift) and 0xF])
    }

    private class JsonSyntaxError(message: String) : Exception(message)

    private class Parser(private val s: String, private val maxDepth: Int) {
        private var i = 0

        fun atEnd(): Boolean = i >= s.length

        fun fail(msg: String): Nothing = throw JsonSyntaxError("$msg at offset $i")

        fun skipWs() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++ else break
            }
        }

        fun value(depth: Int): JsonValue {
            if (atEnd()) fail("unexpected end of input")
            return when (val c = s[i]) {
                '{' -> obj(depth + 1)
                '[' -> arr(depth + 1)
                '"' -> JsonValue.Str(string())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> if (c == '-' || c in '0'..'9') number() else fail("unexpected character")
            }
        }

        private fun literal(word: String, v: JsonValue): JsonValue {
            if (!s.startsWith(word, i)) fail("invalid literal")
            i += word.length
            return v
        }

        private fun obj(depth: Int): JsonValue {
            if (depth > maxDepth) fail("nesting too deep")
            i++ // {
            val fields = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (peek() == '}') {
                i++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWs()
                if (peek() != '"') fail("expected string key")
                val key = string()
                skipWs()
                if (peek() != ':') fail("expected ':'")
                i++
                skipWs()
                fields[key] = value(depth)
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return JsonValue.Obj(fields)
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arr(depth: Int): JsonValue {
            if (depth > maxDepth) fail("nesting too deep")
            i++ // [
            val items = ArrayList<JsonValue>()
            skipWs()
            if (peek() == ']') {
                i++
                return JsonValue.Arr(items)
            }
            while (true) {
                skipWs()
                items.add(value(depth))
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return JsonValue.Arr(items)
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        /** Current char, or NUL at end (NUL never matches any expected token). */
        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        private fun string(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                val c = s[i]
                when {
                    c == '"' -> {
                        i++
                        return sb.toString()
                    }
                    c == '\\' -> escape(sb)
                    c < ' ' -> fail("control character in string")
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
        }

        private fun escape(sb: StringBuilder) {
            i++ // backslash
            if (atEnd()) fail("unterminated escape")
            when (s[i]) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> {
                    val first = hex4()
                    if (first.isHighSurrogate()) {
                        if (!s.startsWith("\\u", i + 1)) fail("unpaired high surrogate")
                        i += 2
                        val second = hex4()
                        if (!second.isLowSurrogate()) fail("unpaired high surrogate")
                        sb.append(first).append(second)
                    } else if (first.isLowSurrogate()) {
                        fail("unpaired low surrogate")
                    } else {
                        sb.append(first)
                    }
                }
                else -> fail("invalid escape")
            }
            i++
        }

        /** Reads 4 hex digits after the current 'u'; leaves [i] on the last digit. */
        private fun hex4(): Char {
            if (i + 4 >= s.length) fail("truncated \\u escape")
            var code = 0
            for (k in 1..4) {
                val d = Character.digit(s[i + k], 16)
                if (d < 0) fail("bad hex digit in \\u escape")
                code = code * 16 + d
            }
            i += 4
            return code.toChar()
        }

        private fun number(): JsonValue {
            val start = i
            val end = scanNumber(s, i)
            if (end < 0) fail("malformed number")
            i = end
            return JsonValue.Num(s.substring(start, end))
        }
    }
}

/**
 * Scans a JSON number starting at [from]; returns the index just past it, or -1 if malformed.
 * Grammar: `-? (0 | [1-9][0-9]*) (. [0-9]+)? ([eE] [+-]? [0-9]+)?`.
 */
private fun scanNumber(s: String, from: Int): Int {
    var i = from
    fun digitAt(k: Int): Boolean = k < s.length && s[k] in '0'..'9'
    if (i < s.length && s[i] == '-') i++
    if (!digitAt(i)) return -1
    if (s[i] == '0') i++ else while (digitAt(i)) i++
    if (i < s.length && s[i] == '.') {
        i++
        if (!digitAt(i)) return -1
        while (digitAt(i)) i++
    }
    if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
        i++
        if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
        if (!digitAt(i)) return -1
        while (digitAt(i)) i++
    }
    return i
}

private fun isJsonNumber(text: String): Boolean = scanNumber(text, 0) == text.length
