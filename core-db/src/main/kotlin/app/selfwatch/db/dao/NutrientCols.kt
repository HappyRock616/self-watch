/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db.dao

import android.database.Cursor
import android.database.sqlite.SQLiteStatement
import app.selfwatch.db.bindIntOrNull
import app.selfwatch.db.intOrNull
import app.selfwatch.model.food.Nutrients

/**
 * The nutrient columns shared by `custom_food`, `diary_entry` and `recipe`, in [Nutrients] order: the
 * nine of schema 1 and the ten food database v2 columns added by schema 2.
 */
internal object NutrientCols {
    const val NAMES: String =
        "kcal_x10, protein_x10, fat_x10, satfat_x10, carb_x10, sugar_x10, fibre_x10, sodium_mg, cholesterol_mg, " +
            "transfat_x10, monofat_x10, polyfat_x10, addsugar_x10, potassium_mg, calcium_mg, iron_x10, vita_ug, vitc_x10, vitd_x10"

    const val COUNT: Int = 19

    /** `SUM(kcal_x10), …` for totals (SUM of only NULLs is NULL, matching the [Nutrients.plus] rule). */
    val SUMS: String = NAMES.split(", ").joinToString(", ") { "SUM($it)" }

    /** `kcal_x10 = ?, …` for UPDATE statements. */
    val SETS: String = NAMES.split(", ").joinToString(", ") { "$it = ?" }

    /** Binds [n] to parameters [from] … [from] + [COUNT] − 1. */
    fun bind(st: SQLiteStatement, from: Int, n: Nutrients?) {
        val values = (n ?: Nutrients.UNKNOWN).toList()
        for (i in 0 until COUNT) st.bindIntOrNull(from + i, values[i])
    }

    /** Reads [COUNT] columns starting at [from]. */
    fun read(c: Cursor, from: Int): Nutrients = Nutrients.fromList(List(COUNT) { c.intOrNull(from + it) })

    /** Reads [COUNT] columns, or null when every one is NULL. */
    fun readOrNull(c: Cursor, from: Int): Nutrients? = read(c, from).takeIf { it != Nutrients.UNKNOWN }
}
