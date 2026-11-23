/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.io

import app.selfwatch.interop.api.UnitGuess
import app.selfwatch.interop.api.WeightUnit
import kotlin.math.abs
import kotlin.math.roundToLong

/** Locale-independent number parsing and formatting for import and export files. */
object Numbers {
    /** Unicode vulgar fractions by code point. */
    private val FRACTIONS: Map<Char, Double> = mapOf(
        0x00BD.toChar() to 0.5, 0x00BC.toChar() to 0.25, 0x00BE.toChar() to 0.75,
        0x2153.toChar() to 1.0 / 3, 0x2154.toChar() to 2.0 / 3, 0x2155.toChar() to 0.2,
        0x215B.toChar() to 0.125, 0x215C.toChar() to 0.375, 0x215D.toChar() to 0.625, 0x215E.toChar() to 0.875,
    )

    /**
     * Parses a decimal such as `1234.5`, `1,234.5`, `1.234,5` or `12,5`. With both separators the last
     * one is the decimal point. A single comma is a decimal comma when [decimalComma] is true or when
     * it is not followed by exactly three digits. Returns null for blanks and non-numbers.
     */
    fun parseDecimal(text: String?, decimalComma: Boolean = false): Double? {
        if (text == null) return null
        var s = text.trim().trim('"').filterNot { it == ' ' || it == 0x00A0.toChar() || it == 0x202F.toChar() || it == '\'' }
        if (s.isEmpty()) return null
        val lastDot = s.lastIndexOf('.')
        val lastComma = s.lastIndexOf(',')
        s = when {
            lastDot >= 0 && lastComma >= 0 ->
                if (lastComma > lastDot) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            lastComma >= 0 -> {
                val commas = s.count { it == ',' }
                val after = s.length - lastComma - 1
                if (commas == 1 && (decimalComma || after != 3)) s.replace(',', '.') else s.replace(",", "")
            }
            else -> s
        }
        if (!NUMBER.matches(s)) return null
        return s.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private val NUMBER = Regex("""[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")

    /**
     * Reads a quantity at the start of [text]: `2`, `1.5`, `1,5`, `1/2`, `1 1/2`, `½`, `1½`, or a range
     * `1-2` (the first value). Returns the value and the rest of the text (trimmed), or null.
     */
    fun leadingQuantity(text: String): Pair<Double, String>? {
        val s = text.trimStart()
        var i = 0
        fun digits(): String {
            val start = i
            while (i < s.length && s[i].isAsciiDigit()) i++
            return s.substring(start, i)
        }
        var value: Double? = null
        val whole = digits()
        if (whole.isNotEmpty()) {
            if (i + 1 < s.length && (s[i] == '.' || s[i] == ',') && s[i + 1].isAsciiDigit()) {
                i++
                val frac = digits()
                value = "$whole.$frac".toDouble()
            } else if (i < s.length && s[i] == '/') {
                val save = i
                i++
                val den = digits()
                if (den.isNotEmpty() && den.toInt() != 0) return whole.toDouble() / den.toInt() to s.substring(i).trim()
                i = save
                value = whole.toDouble()
            } else {
                value = whole.toDouble()
                // "1 1/2" or "1½"
                var j = i
                while (j < s.length && s[j] == ' ') j++
                if (j < s.length && FRACTIONS.containsKey(s[j])) {
                    value += FRACTIONS.getValue(s[j])
                    i = j + 1
                } else {
                    val k = j
                    var e = k
                    while (e < s.length && s[e].isAsciiDigit()) e++
                    if (e > k && e < s.length && s[e] == '/' && j > i) {
                        var f = e + 1
                        while (f < s.length && s[f].isAsciiDigit()) f++
                        if (f > e + 1) {
                            val num = s.substring(k, e).toInt()
                            val den = s.substring(e + 1, f).toInt()
                            if (den != 0) {
                                value += num.toDouble() / den
                                i = f
                            }
                        }
                    }
                }
            }
        } else if (i < s.length && FRACTIONS.containsKey(s[i])) {
            value = FRACTIONS.getValue(s[i])
            i++
        }
        if (value == null || value.isInfinite() || value.isNaN()) return null
        // A range "1-2" keeps its first value.
        if (i < s.length && (s[i] == '-' || s[i] == 0x2013.toChar())) {
            var j = i + 1
            while (j < s.length && (s[j].isAsciiDigit() || s[j] == '.' || s[j] == '/')) j++
            if (j > i + 1) i = j
        }
        return value to s.substring(i).trim()
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    /** [v] ×[scale] rounded half away from zero, clamped to Int. */
    fun scaled(v: Double, scale: Int): Int {
        val x = v * scale
        val r = if (x < 0) -(abs(x) + 0.5).toLong().toDouble() else (x + 0.5).toLong().toDouble()
        return r.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()
    }

    /** Formats a fixed-point value: [v] / [scale] with as many decimals as [scale] has zeros, trailing zeros trimmed. */
    fun formatScaled(v: Int, scale: Int, trim: Boolean = true): String {
        val decimals = scale.toString().length - 1
        val neg = v < 0
        val a = abs(v.toLong())
        val whole = a / scale
        val frac = (a % scale).toString().padStart(decimals, '0')
        var s = if (decimals == 0) whole.toString() else "$whole.$frac"
        if (trim && decimals > 0) s = s.trimEnd('0').trimEnd('.')
        return if (neg && s != "0") "-$s" else s
    }

    /** Formats [v] with up to [decimals] decimals (trailing zeros trimmed), always with a dot. */
    fun format(v: Double, decimals: Int): String {
        var scale = 1
        repeat(decimals) { scale *= 10 }
        val r = (v * scale).roundToLong()
        return formatScaled(r.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(), scale)
    }
}

/**
 * Guesses whether weights are in kilograms or pounds: the header text wins (`Weight (lb)`), then the
 * typical value (adult weights under 80 read as kg, over 160 as lb); in between the guess is
 * [default] and not confident, so the preview asks.
 */
object UnitDetector {
    /** Detects the unit of the weight column with header [header] and [values]. */
    fun detect(header: String?, values: List<Double>, default: String = WeightUnit.KG): UnitGuess {
        val h = header?.lowercase().orEmpty()
        if (Regex("""\b(lbs?|pounds?)\b""").containsMatchIn(h)) return UnitGuess(WeightUnit.LB, true, "header")
        if (Regex("""\b(kgs?|kilo(gram)?s?)\b""").containsMatchIn(h)) return UnitGuess(WeightUnit.KG, true, "header")
        val v = values.filter { it > 0 }.sorted()
        if (v.isEmpty()) return UnitGuess(default, false, "default")
        val median = v[v.size / 2]
        return when {
            median < 80 -> UnitGuess(WeightUnit.KG, true, "range")
            median > 160 -> UnitGuess(WeightUnit.LB, true, "range")
            else -> UnitGuess(default, false, "default")
        }
    }

    /** [value] in [unit] as kilograms. */
    fun toKg(value: Double, unit: String): Double = if (unit == WeightUnit.LB) value / WeightUnit.LB_PER_KG else value
}
