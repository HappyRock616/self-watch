/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.format

import android.content.Context
import app.selfwatch.ui.R

/**
 * Translatable words and unit symbols used by [Fmt]. [from] reads them from string resources; [ENGLISH]
 * is a resource-free copy for plain unit tests.
 *
 * [durationHm] and [durationMin] are format strings: `%1$s` hours and `%2$s` minutes, and `%1$s` minutes.
 */
data class FmtLabels(
    val today: String,
    val yesterday: String,
    val durationHm: String,
    val durationMin: String,
    val kcal: String,
    val kg: String,
    val lb: String,
    val cm: String,
    val inch: String,
    val m: String,
    val km: String,
    val ft: String,
    val mi: String,
    val ml: String = "ml",
    val l: String = "L",
    val flOz: String = "fl oz",
    val celsius: String = "°C",
    val fahrenheit: String = "°F",
) {
    companion object {
        /** English labels, matching the default `strings.xml`. */
        val ENGLISH: FmtLabels = FmtLabels(
            today = "Today",
            yesterday = "Yesterday",
            durationHm = "%1\$sh %2\$sm",
            durationMin = "%1\$s min",
            kcal = "kcal",
            kg = "kg",
            lb = "lb",
            cm = "cm",
            inch = "in",
            m = "m",
            km = "km",
            ft = "ft",
            mi = "mi",
        )

        /** Labels from the current resources (translated when a translation exists). */
        fun from(context: Context): FmtLabels {
            val r = context.resources
            return FmtLabels(
                today = r.getString(R.string.sw_today),
                yesterday = r.getString(R.string.sw_yesterday),
                durationHm = r.getString(R.string.sw_duration_hm),
                durationMin = r.getString(R.string.sw_duration_min),
                kcal = r.getString(R.string.sw_unit_kcal),
                kg = r.getString(R.string.sw_unit_kg),
                lb = r.getString(R.string.sw_unit_lb),
                cm = r.getString(R.string.sw_unit_cm),
                inch = r.getString(R.string.sw_unit_in),
                m = r.getString(R.string.sw_unit_m),
                km = r.getString(R.string.sw_unit_km),
                ft = r.getString(R.string.sw_unit_ft),
                mi = r.getString(R.string.sw_unit_mi),
                ml = r.getString(R.string.sw_unit_ml),
                l = r.getString(R.string.sw_unit_l),
                flOz = r.getString(R.string.sw_unit_fl_oz),
                celsius = r.getString(R.string.sw_unit_celsius),
                fahrenheit = r.getString(R.string.sw_unit_fahrenheit),
            )
        }
    }
}
