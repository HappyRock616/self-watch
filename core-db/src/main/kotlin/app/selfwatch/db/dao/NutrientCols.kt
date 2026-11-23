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

/** The nine nutrient columns shared by `custom_food`, `diary_entry` and `recipe`, in [Nutrients] order. */
internal object NutrientCols {
    const val NAMES: String =
        "kcal_x10, protein_x10, fat_x10, satfat_x10, carb_x10, sugar_x10, fibre_x10, sodium_mg, cholesterol_mg"

    const val COUNT: Int = 9

    /** `SUM(kcal_x10), …` for totals (SUM of only NULLs is NULL, matching the [Nutrients.plus] rule). */
    val SUMS: String = NAMES.split(", ").joinToString(", ") { "SUM($it)" }

    /** `kcal_x10 = ?, …` for UPDATE statements. */
    val SETS: String = NAMES.split(", ").joinToString(", ") { "$it = ?" }

    /** Binds [n] to parameters [from] … [from] + 8. */
    fun bind(st: SQLiteStatement, from: Int, n: Nutrients?) {
        st.bindIntOrNull(from, n?.kcalX10)
        st.bindIntOrNull(from + 1, n?.proteinX10)
        st.bindIntOrNull(from + 2, n?.fatX10)
        st.bindIntOrNull(from + 3, n?.satfatX10)
        st.bindIntOrNull(from + 4, n?.carbX10)
        st.bindIntOrNull(from + 5, n?.sugarX10)
        st.bindIntOrNull(from + 6, n?.fibreX10)
        st.bindIntOrNull(from + 7, n?.sodiumMg)
        st.bindIntOrNull(from + 8, n?.cholesterolMg)
    }

    /** Reads nine columns starting at [from]. */
    fun read(c: Cursor, from: Int): Nutrients = Nutrients(
        kcalX10 = c.intOrNull(from),
        proteinX10 = c.intOrNull(from + 1),
        fatX10 = c.intOrNull(from + 2),
        satfatX10 = c.intOrNull(from + 3),
        carbX10 = c.intOrNull(from + 4),
        sugarX10 = c.intOrNull(from + 5),
        fibreX10 = c.intOrNull(from + 6),
        sodiumMg = c.intOrNull(from + 7),
        cholesterolMg = c.intOrNull(from + 8),
    )

    /** Reads nine columns, or null when every one is NULL. */
    fun readOrNull(c: Cursor, from: Int): Nutrients? = read(c, from).takeIf { it != Nutrients.UNKNOWN }
}
