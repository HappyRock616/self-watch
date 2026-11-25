/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.nutrition

import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Nutrients.Field
import app.selfwatch.model.time.Day

/**
 * A run of days of diary totals (the Day nutrition week view): [totals] has one entry per day of [days],
 * null for a day with nothing logged.
 *
 * Averages are per logged day, and for each nutrient only over the logged days that report it, so a
 * day without the nutrient does not pull the average down (the completeness line says how many foods
 * knew it). Rounded half away from zero.
 */
data class WeekTotals(val days: List<Day>, val totals: List<Nutrients?>) {
    init {
        require(days.size == totals.size) { "one total per day" }
    }

    /** How many days have anything logged. */
    val logged: Int get() = totals.count { it != null }

    /** The value of [f] on each day (null: not logged, or not reported that day). */
    fun perDay(f: Field): List<Int?> = totals.map { it?.get(f) }

    /** The average of [f] per logged day that reports it, or null when none does. */
    fun average(f: Field): Int? {
        val values = totals.mapNotNull { it?.get(f) }
        if (values.isEmpty()) return null
        return NutrientMath.clampInt(NutrientMath.divRound(values.sumOf { it.toLong() }, values.size.toLong()))
    }

    /** Every average as one [Nutrients]. */
    val averages: Nutrients get() = Nutrients.fromList(Field.entries.map { average(it) })

    /** Factory. */
    companion object {
        /** The seven days ending on [last], oldest first. */
        fun weekEnding(last: Day): List<Day> = (WEEK - 1 downTo 0).map { last.plusDays(-it) }

        /** Days in a week. */
        const val WEEK: Int = 7
    }
}
