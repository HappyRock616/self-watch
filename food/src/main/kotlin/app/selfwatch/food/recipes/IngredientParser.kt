/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.recipes

import app.selfwatch.food.nutrition.NutrientMath

/**
 * One parsed ingredient line.
 *
 * @property amountX100 the amount ×100 (1½ = 150), or null when the line has none ("salt to taste").
 *   A range ("1-2") is its average; "a"/"an" is 1; a unit with no number ("pinch of salt") is 1.
 * @property unit canonical unit name (see [Units]), or null ("2 eggs").
 * @property words what the ingredient is, lowercase, for matching ("black beans").
 * @property note everything else that was said: size and prep words, comments in brackets, text after
 *   the first comma ("drained"), "to taste" and the like. Parts are joined with ", ".
 * @property packageAmountX100 size of one container from "(15 oz)" or "15-ounce", ×100.
 * @property packageUnit unit of [packageAmountX100].
 * @property toTaste true when the line has no amount and says "to taste", "as needed" or similar.
 */
data class ParsedIngredient(
    val amountX100: Int?,
    val unit: String?,
    val words: String,
    val note: String?,
    val packageAmountX100: Int? = null,
    val packageUnit: String? = null,
    val toTaste: Boolean = false,
)

/**
 * Rule-based ingredient line parser (no AI), WP-11:
 * - Amounts: "1", "1.5", "1 1/2", "1-1/2", "½", "1½", "1-2" / "1 to 2" / "1 or 2" (average), "a"/"an"
 *   and number words up to twelve.
 * - Units from the [Units] dictionary, including attached forms ("200g", "2tbsp") and two-word ones
 *   ("fl oz", "fluid ounces"); "T" is a tablespoon and "t" a teaspoon.
 * - Brackets: a weight or volume in brackets ("(15 oz)") is the package size when the unit is a
 *   container or missing; anything else in brackets goes to the note.
 * - The first comma starts the note ("black beans, drained"). Trailing "to taste", "optional",
 *   "for garnish" and similar move to the note.
 * - Size and quality words before a unit ("2 heaping tbsp") go to the note.
 */
object IngredientParser {

    /** Parses one line. Never throws; an empty or unreadable line gives empty words and no amount. */
    fun parse(line: String): ParsedIngredient {
        val notes = ArrayList<String>()
        var text = normalize(line)

        // 1. Brackets: measures are package-size candidates, the rest are notes.
        val measures = ArrayList<Pair<Int, Units.Unit>>()
        text = extractBrackets(text) { inside ->
            val m = measureOf(inside)
            if (m != null && measures.isEmpty()) measures.add(m) else if (inside.isNotBlank()) notes.add(inside.trim())
        }

        // 2. The first comma starts the trailing note.
        var commaNote: String? = null
        val comma = text.indexOf(',')
        if (comma >= 0) {
            commaNote = text.substring(comma + 1).trim().trim(',', ' ').ifEmpty { null }
            text = text.substring(0, comma)
        }

        val tokens = text.split(' ').filter { it.isNotEmpty() }.toMutableList()
        var i = 0

        // 3. Amount.
        var amount: Long? = null
        var attachedUnit: Units.Unit? = null
        val first = readAmount(tokens, i)
        if (first != null) {
            amount = first.valueX100
            attachedUnit = first.attachedUnit
            i = first.next
        }

        // 4. "1 15-ounce can": a number+measure token before a container.
        var pkg: Pair<Int, Units.Unit>? = null
        if (attachedUnit == null && i < tokens.size) {
            val m = attachedMeasure(tokens[i])
            if (m != null && i + 1 < tokens.size && Units.lookup(tokens[i + 1])?.isContainer == true) {
                pkg = m
                i++
            }
        }

        // 5. Unit (size words before a unit go to the note).
        var unit: Units.Unit? = attachedUnit
        if (unit == null && i < tokens.size) {
            var j = i
            val sizeWords = ArrayList<String>()
            while (j < tokens.size - 1 && tokens[j].lowercase() in UNIT_MODIFIERS) {
                sizeWords.add(tokens[j].lowercase())
                j++
            }
            val two = if (j + 1 < tokens.size) Units.lookup("${tokens[j]} ${tokens[j + 1]}".lowercase()) else null
            val one = if (j < tokens.size) Units.lookup(tokens[j]) else null
            if (two != null) {
                unit = two
                i = j + 2
                notes.addAll(0, sizeWords)
            } else if (one != null) {
                unit = one
                i = j + 1
                notes.addAll(0, sizeWords)
            }
        }
        if (unit != null && amount == null) amount = 100

        // 6. Package size: from the bracket or the "15-ounce" token when the unit is a container or missing.
        val measure = measures.firstOrNull()
        if (pkg == null && measure != null) {
            if (unit == null || unit.isContainer) pkg = measure else notes.add(0, formatMeasure(measure))
        }

        // 7. Words, minus a leading "of" and trailing phrases.
        if (i < tokens.size && tokens[i].lowercase() == "of") i++
        var words = tokens.subList(i, tokens.size).joinToString(" ").lowercase().trim().trimEnd('.', ';', ':', '-').trim()
        var phrase: String? = null
        for (p in TRAILING_PHRASES) {
            if (words == p || words.endsWith(" $p")) {
                phrase = p
                words = words.removeSuffix(p).trim().trimEnd(',', '-').trim()
                break
            }
        }
        if (words.startsWith("of ")) words = words.removePrefix("of ").trim()
        if (phrase != null) notes.add(phrase)
        if (commaNote != null) notes.add(commaNote)

        val lower = line.lowercase()
        val toTaste = amount == null && TO_TASTE.any { it in lower }
        return ParsedIngredient(
            amountX100 = amount?.let { NutrientMath.clampInt(it) },
            unit = unit?.name,
            words = words,
            note = notes.filter { it.isNotBlank() }.joinToString(", ").ifEmpty { null },
            packageAmountX100 = pkg?.first,
            packageUnit = pkg?.second?.name,
            toTaste = toTaste,
        )
    }

    // ---- amounts ----

    private class Amount(val valueX100: Long, val next: Int, val attachedUnit: Units.Unit?)

    /** Reads an amount starting at token [start] (with mixed numbers and ranges), or null. */
    private fun readAmount(t: List<String>, start: Int): Amount? {
        if (start >= t.size) return null
        val firstTok = t[start]
        val lower = firstTok.lowercase()
        NUMBER_WORDS[lower]?.let { return Amount(it * 100L, start + 1, null) }

        val a = numberToken(firstTok) ?: return null
        var value = a.valueX100
        var next = start + 1
        var unit = a.unit
        // Mixed number: "1 1/2".
        if (unit == null && a.isWhole && next < t.size) {
            val f = numberToken(t[next])
            if (f != null && f.isFraction && f.valueX100 < 100) {
                value += f.valueX100
                next++
                unit = f.unit
            }
        }
        // Range: "1-2" (token form handled in numberToken), "1 - 2", "1 to 2", "1 or 2".
        if (unit == null && next + 1 < t.size && t[next].lowercase() in RANGE_WORDS) {
            val b = numberToken(t[next + 1])
            if (b != null) {
                var high = b.valueX100
                var after = next + 2
                var u = b.unit
                if (u == null && b.isWhole && after < t.size) {
                    val f = numberToken(t[after])
                    if (f != null && f.isFraction && f.valueX100 < 100) {
                        high += f.valueX100
                        after++
                        u = f.unit
                    }
                }
                value = NutrientMath.divRound(value + high, 2)
                next = after
                unit = u
            }
        }
        return Amount(value, next, unit)
    }

    private class Num(val valueX100: Long, val isWhole: Boolean, val isFraction: Boolean, val unit: Units.Unit?)

    private val NUMBER_RE = Regex("""^(\d+(?:\.\d+)?|\.\d+)(?:/(\d+))?$""")
    private val RANGE_TOKEN_RE = Regex("""^([\d./]+)-([\d./]+)$""")
    private val ATTACHED_RE = Regex("""^([\d./]+(?:-[\d./]+)?)-?([A-Za-z][A-Za-z.]*)$""")

    /** One token as a number: "2", "1.5", "1/2", "1-1/2", "2-3" (average), "200g", "15-ounce". */
    private fun numberToken(tok: String): Num? {
        plainNumber(tok)?.let { return it }
        RANGE_TOKEN_RE.matchEntire(tok)?.let { m ->
            val a = plainNumber(m.groupValues[1]) ?: return null
            val b = plainNumber(m.groupValues[2]) ?: return null
            // "1-1/2" is one and a half; "1-2" and "1/2-1" are ranges.
            return if (a.isWhole && b.isFraction && b.valueX100 < 100) {
                Num(a.valueX100 + b.valueX100, isWhole = false, isFraction = false, unit = null)
            } else {
                Num(NutrientMath.divRound(a.valueX100 + b.valueX100, 2), isWhole = false, isFraction = false, unit = null)
            }
        }
        ATTACHED_RE.matchEntire(tok)?.let { m ->
            val u = Units.lookup(m.groupValues[2]) ?: return null
            val n = numberToken(m.groupValues[1]) ?: return null
            if (n.unit != null) return null
            return Num(n.valueX100, n.isWhole, n.isFraction, u)
        }
        return null
    }

    private fun plainNumber(tok: String): Num? {
        val m = NUMBER_RE.matchEntire(tok) ?: return null
        val top = m.groupValues[1]
        val bottom = m.groupValues[2]
        if (bottom.isEmpty()) {
            val v = top.toBigDecimalOrNull() ?: return null
            val x100 = v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP)
            if (x100 > java.math.BigDecimal(Int.MAX_VALUE)) return null
            return Num(x100.toLong(), isWhole = !top.contains('.'), isFraction = false, unit = null)
        }
        if (top.contains('.')) return null
        val n = top.toLongOrNull() ?: return null
        val d = bottom.toLongOrNull() ?: return null
        if (d == 0L || n > Int.MAX_VALUE) return null
        return Num(NutrientMath.divRound(n * 100, d), isWhole = false, isFraction = true, unit = null)
    }

    /** "15-ounce" or "14.5oz" as (amount ×100, unit) when the unit is a weight or volume. */
    private fun attachedMeasure(tok: String): Pair<Int, Units.Unit>? {
        val n = numberToken(tok) ?: return null
        val u = n.unit ?: return null
        if (u.kind == UnitKind.COUNT) return null
        return NutrientMath.clampInt(n.valueX100) to u
    }

    /** A bracket's text as a weight or volume ("15 oz", "15-ounce", "1 lb"), or null. */
    private fun measureOf(inside: String): Pair<Int, Units.Unit>? {
        val t = normalize(inside).split(' ').filter { it.isNotEmpty() }
        if (t.isEmpty() || t.size > 3) return null
        val a = readAmount(t, 0) ?: return null
        val unit = a.attachedUnit ?: run {
            if (a.next >= t.size) return null
            val two = if (a.next + 1 < t.size) Units.lookup("${t[a.next]} ${t[a.next + 1]}".lowercase()) else null
            val used = if (two != null) 2 else 1
            val u = two ?: Units.lookup(t[a.next]) ?: return null
            if (a.next + used != t.size) return null
            u
        }
        if (a.attachedUnit != null && a.next != t.size) return null
        if (unit.kind == UnitKind.COUNT) return null
        return NutrientMath.clampInt(a.valueX100) to unit
    }

    private fun formatMeasure(m: Pair<Int, Units.Unit>): String {
        val v = m.first
        val whole = v / 100
        val frac = v % 100
        val num = if (frac == 0) "$whole" else "$whole.${frac.toString().padStart(2, '0').trimEnd('0')}"
        return "$num ${m.second.name}"
    }

    // ---- text ----

    /** Unicode fractions to ASCII, dashes to '-', bullets dropped, spaces collapsed. */
    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            val frac = VULGAR[c]
            when {
                frac != null -> {
                    if (sb.isNotEmpty() && sb.last().isDigit()) sb.append(' ')
                    sb.append(frac)
                }
                c == '⁄' || c == '∕' -> sb.append('/')
                c == '–' || c == '—' || c == '‒' || c == '−' -> sb.append('-')
                c == ' ' || c == '\t' -> sb.append(' ')
                else -> sb.append(c)
            }
        }
        var out = sb.toString().replace(Regex("\\s+"), " ").trim()
        out = out.trimStart('•', '*', '·', '▢', '□', '◻', ' ')
        if (out.startsWith("- ")) out = out.substring(2)
        // "1 - 2" → keep as tokens; "1 -2"/"1- 2" → "1 - 2".
        out = out.replace(Regex("(\\d)\\s*-\\s+(\\d)"), "$1 - $2").replace(Regex("(\\d)\\s+-\\s*(\\d)"), "$1 - $2")
        return out
    }

    /** Removes bracketed parts ( ) and [ ] from [s], passing each one's text to [onGroup]. */
    private fun extractBrackets(s: String, onGroup: (String) -> Unit): String {
        val out = StringBuilder()
        val group = StringBuilder()
        var depth = 0
        for (c in s) {
            when {
                c == '(' || c == '[' -> {
                    if (depth > 0) group.append(c)
                    depth++
                }
                (c == ')' || c == ']') && depth > 0 -> {
                    depth--
                    if (depth == 0) {
                        onGroup(group.toString())
                        group.setLength(0)
                        out.append(' ')
                    } else {
                        group.append(c)
                    }
                }
                depth > 0 -> group.append(c)
                else -> out.append(c)
            }
        }
        if (depth > 0 && group.isNotEmpty()) onGroup(group.toString())
        return out.toString().replace(Regex("\\s+"), " ").replace(" ,", ",").trim()
    }

    private val VULGAR: Map<Char, String> = mapOf(
        '½' to "1/2", '⅓' to "1/3", '⅔' to "2/3", '¼' to "1/4", '¾' to "3/4", '⅕' to "1/5", '⅖' to "2/5",
        '⅗' to "3/5", '⅘' to "4/5", '⅙' to "1/6", '⅚' to "5/6", '⅛' to "1/8", '⅜' to "3/8", '⅝' to "5/8",
        '⅞' to "7/8",
    )

    private val NUMBER_WORDS: Map<String, Int> = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
    )

    private val RANGE_WORDS = setOf("-", "to", "or")

    private val UNIT_MODIFIERS = setOf(
        "heaping", "heaped", "level", "scant", "generous", "rounded", "large", "small", "medium", "big", "full",
        "good", "packed", "loosely", "firmly", "lightly", "very",
    )

    private val TRAILING_PHRASES = listOf(
        "or more to taste", "or to taste", "plus more to taste", "to taste", "as needed", "as desired", "if desired",
        "if needed", "for garnish", "for serving", "for frying", "for greasing", "for dusting", "optional",
    )

    private val TO_TASTE = listOf("to taste", "as needed", "as desired", "if desired", "for garnish", "for serving")
}
