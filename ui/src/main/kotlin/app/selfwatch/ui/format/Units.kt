/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.format

import java.util.Locale

/**
 * Display unit system for [Fmt].
 *
 * Local stand-in until core-model's `UnitSystem` lands (WP-02); screens will map that type onto this one,
 * or this enum will be replaced by it.
 */
enum class Units {
    /** Kilograms, centimetres, kilometres. */
    METRIC,

    /** Pounds, inches, feet and miles. */
    IMPERIAL;

    companion object {
        private val IMPERIAL_COUNTRIES: Set<String> = setOf("US", "LR", "MM")

        /** The phone's default: imperial in the US, Liberia and Myanmar, metric everywhere else. */
        fun forLocale(locale: Locale): Units =
            if (locale.country.uppercase(Locale.ROOT) in IMPERIAL_COUNTRIES) IMPERIAL else METRIC
    }
}
