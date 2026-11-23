/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.db

import android.database.sqlite.SQLiteDatabase
import app.selfwatch.model.food.MealSlot
import java.util.Locale

/**
 * Maps the meal names of imported diary lines onto the user's meal slots.
 *
 * **Decision: imports never create meal slots.** Slots are the user's day layout and `meal_slot` has
 * no `import_id`, so a slot created by an import could not be undone. Instead each meal name goes to:
 * 1. the slot with the same name (case-insensitive), the user's own slots included;
 * 2. else one of the four standard slots by keyword ("breakfast", "lunch", "dinner"/"supper",
 *    "snack"/"other"/"dessert", in English and a few common other spellings);
 * 3. else by the line's time of day (before 10:30 breakfast, before 14:30 lunch, 17:00 to 21:30
 *    dinner), and Snacks when there is no time.
 *
 * A standard slot is found by name, then by its seed id (1 to 4) if the user renamed it, then the first
 * slot in display order.
 */
internal class MealSlots(private val slots: List<MealSlot>) {

    /** The four standard slots with their seed ids and a nominal local time for lines without one. */
    enum class Standard(val seedId: Long, val label: String, val nominalSec: Int) {
        BREAKFAST(1, "Breakfast", 8 * 3600),
        LUNCH(2, "Lunch", 12 * 3600 + 1800),
        DINNER(3, "Dinner", 18 * 3600 + 1800),
        SNACKS(4, "Snacks", 15 * 3600),
    }

    private val byName: Map<String, MealSlot> = slots.associateBy { it.name.trim().lowercase(Locale.ROOT) }

    /** The slot id for [meal] at [secOfDay]. */
    fun resolve(meal: String, secOfDay: Int?): Long = slotFor(meal, secOfDay).first

    /** The slot id for [meal] and the nominal time of day to use when the line has none. */
    fun slotFor(meal: String, secOfDay: Int?): Pair<Long, Int> {
        val key = meal.trim().lowercase(Locale.ROOT)
        byName[key]?.let { s -> return s.id to (Standard.entries.firstOrNull { it.seedId == s.id }?.nominalSec ?: NOON) }
        val std = byKeyword(key) ?: byTime(secOfDay)
        return standardSlot(std) to std.nominalSec
    }

    private fun standardSlot(std: Standard): Long =
        byName[std.label.lowercase(Locale.ROOT)]?.id
            ?: slots.firstOrNull { it.id == std.seedId }?.id
            ?: slots.minByOrNull { it.order }?.id
            ?: std.seedId

    /** Constants and loading. */
    companion object {
        private const val NOON = 12 * 3600

        private val KEYWORDS: List<Pair<Standard, List<String>>> = listOf(
            Standard.BREAKFAST to listOf("breakfast", "morning", "frühstück", "fruhstuck", "petit", "desayuno", "ontbijt"),
            Standard.LUNCH to listOf("lunch", "midday", "noon", "mittag", "déjeuner", "dejeuner", "almuerzo"),
            Standard.DINNER to listOf("dinner", "supper", "evening", "abend", "dîner", "diner", "cena", "avondeten"),
            Standard.SNACKS to listOf("snack", "other", "extra", "dessert", "uncategor", "zwischen", "collation", "merienda"),
        )

        /** The standard slot whose keyword [key] contains, or null. */
        fun byKeyword(key: String): Standard? = KEYWORDS.firstOrNull { (_, words) -> words.any { key.contains(it) } }?.first

        /** The standard slot for a time of day (Snacks when unknown). */
        fun byTime(secOfDay: Int?): Standard = when {
            secOfDay == null -> Standard.SNACKS
            secOfDay < 10 * 3600 + 1800 -> Standard.BREAKFAST
            secOfDay < 14 * 3600 + 1800 -> Standard.LUNCH
            secOfDay in 17 * 3600 until 21 * 3600 + 1800 -> Standard.DINNER
            else -> Standard.SNACKS
        }

        /** Reads the slots from [d]. */
        fun load(d: SQLiteDatabase): MealSlots {
            val out = ArrayList<MealSlot>()
            d.rawQuery("SELECT id, name, ord, hidden FROM meal_slot ORDER BY ord, id", null).use { c ->
                while (c.moveToNext()) out.add(MealSlot(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3) != 0))
            }
            return MealSlots(out)
        }
    }
}
