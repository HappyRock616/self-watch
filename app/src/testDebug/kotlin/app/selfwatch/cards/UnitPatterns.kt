/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.cards

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.selfwatch.ui.format.Units
import com.google.android.material.textfield.TextInputLayout

/**
 * Unit strings that must not show in the other unit system: metric units under US units and US units
 * under metric. Energy (kcal) and nutrients (g, mg, µg) are the same in both and never match.
 */
object UnitPatterns {
    // A number, then (optionally a space) the unit; "m" and "l" only after a space so durations
    // ("7h 12m") and words are not caught.
    private const val N = """\d[\d.,]*[\s ]?"""
    private const val NS = """\d[\d.,]*[\s ]"""

    /** Metric units: wrong under [Units.IMPERIAL]. */
    // VO2 max in ml/kg/min is the one unit both systems use (Garmin shows it the same way), so it is exempt.
    val METRIC: List<Regex> = listOf(
        Regex("""$N(ml|kg|km|cm)\b(?!/kg/min)"""),
        Regex("""$NS(m|l|L)\b(?![a-z])"""),
        Regex("""°C"""),
        Regex("""km/h|/km\b|(?<!ml)/kg\b|\bper kg\b"""),
        Regex("""\b(millilit|kilogram|kilomet|centimet|litre|liter)""", RegexOption.IGNORE_CASE),
    )

    /** US units: wrong under [Units.METRIC]. */
    val US: List<Regex> = listOf(
        Regex("""$N(lb|lbs|mi|ft|oz)\b"""),
        // Inches only as Fmt writes them (no-break space), so "1.7 in 90 days" is not caught.
        Regex("""\d[\d.,]* in\b"""),
        Regex("""fl\.? oz"""),
        Regex("""°F"""),
        Regex("""\bmph\b|/mi\b|/lb\b|\bper lb\b"""),
        Regex("""\b(pound|mile|feet|foot|ounce|inch)""", RegexOption.IGNORE_CASE),
    )

    /** The patterns that must not match anything shown in [units]. */
    fun wrong(units: Units): List<Regex> = if (units == Units.IMPERIAL) METRIC else US

    /** The first pattern of the other unit system that [text] matches, or null. */
    fun hit(text: String, units: Units): Regex? = wrong(units).firstOrNull { it.containsMatchIn(text) }

    /** Every text [v] shows or says: texts, hints, field suffixes and descriptions (visible views only). */
    fun texts(v: View, out: MutableList<String> = ArrayList()): MutableList<String> {
        if (v.visibility != View.VISIBLE) return out
        v.contentDescription?.let { out += it.toString() }
        if (v is TextView) {
            out += v.text.toString()
            v.hint?.let { out += it.toString() }
        }
        if (v is TextInputLayout) {
            v.suffixText?.let { out += it.toString() }
            v.prefixText?.let { out += it.toString() }
            v.hint?.let { out += it.toString() }
            v.helperText?.let { out += it.toString() }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }
}
