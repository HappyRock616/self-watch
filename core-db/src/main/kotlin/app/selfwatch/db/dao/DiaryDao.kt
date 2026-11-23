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
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.db.args
import app.selfwatch.db.bindIntOrNull
import app.selfwatch.db.bindLongOrNull
import app.selfwatch.db.bindStrOrNull
import app.selfwatch.db.bool
import app.selfwatch.db.intOrNull
import app.selfwatch.db.longOrNull
import app.selfwatch.db.queryList
import app.selfwatch.db.queryOne
import app.selfwatch.db.read
import app.selfwatch.db.strOrNull
import app.selfwatch.db.withStatement
import app.selfwatch.db.write
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.MealSlot
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange

/** A food the user logs often: its most recent diary [entry] and how many times it was logged. */
public data class FrequentFood(val entry: DiaryEntry, val count: Int)

/**
 * The food diary (table `diary_entry`) and meal slots (table `meal_slot`).
 *
 * `kcal_x10` is NOT NULL in the schema, so an entry with unknown energy is stored (and reads back)
 * as 0 kcal.
 */
public class DiaryDao internal constructor(private val db: SelfWatchDb) {

    /** Adds [e] (its `id` is ignored); returns the new id. */
    public suspend fun add(e: DiaryEntry): Long = db.write(setOf(Table.DIARY)) { d ->
        d.withStatement(INSERT) { st -> insertWith(st, e) }
    }

    /** Adds [entries] in one transaction (one compiled statement); returns the count. */
    public suspend fun addAll(entries: List<DiaryEntry>): Int {
        if (entries.isEmpty()) return 0
        return db.write(setOf(Table.DIARY)) { d ->
            d.withStatement(INSERT) { st -> entries.forEach { insertWith(st, it) } }
            entries.size
        }
    }

    /** Rewrites entry `e.id`. Returns true if it existed. */
    public suspend fun update(e: DiaryEntry): Boolean = db.write(setOf(Table.DIARY)) { d ->
        d.withStatement("UPDATE diary_entry SET $SET_COLS WHERE id = ?") { st ->
            bindCols(st, e)
            st.bindLong(COLS + 1, e.id)
            st.executeUpdateDelete() > 0
        }
    }

    /** Deletes entry [id]. Returns true if it existed. */
    public suspend fun delete(id: Long): Boolean = db.write(setOf(Table.DIARY)) { it.delete("diary_entry", "id = ?", args(id)) > 0 }

    /** Entry [id], or null. */
    public suspend fun get(id: Long): DiaryEntry? = db.read { it.queryOne("$SELECT WHERE e.id = ?", args(id), ::map) }

    /** The entries of [day], in meal-slot order then time. */
    public suspend fun day(day: Day): List<DiaryEntry> = db.read {
        it.queryList(
            "$SELECT LEFT JOIN meal_slot s ON s.id = e.meal_slot_id WHERE e.day = ? ORDER BY s.ord, e.meal_slot_id, e.t_sec, e.id",
            args(day.yyyymmdd),
            ::map,
        )
    }

    /**
     * The entries of every day in [days] (inclusive; every day when null), by day, then meal-slot
     * order, then time. For exports.
     */
    public suspend fun range(days: DayRange?): List<DiaryEntry> = db.read {
        val (where, a) = if (days == null) "" to null else "WHERE e.day BETWEEN ? AND ?" to args(days.first.yyyymmdd, days.last.yyyymmdd)
        it.queryList(
            "$SELECT LEFT JOIN meal_slot s ON s.id = e.meal_slot_id $where ORDER BY e.day, s.ord, e.meal_slot_id, e.t_sec, e.id",
            a,
            ::map,
        )
    }

    /** The most recent entry (by time) that logged [ref], or null if it was never logged. */
    public suspend fun lastEntryFor(ref: FoodRef): DiaryEntry? = db.read {
        it.queryOne(
            "$SELECT WHERE e.food_origin = ? AND e.food_id = ? ORDER BY e.t_sec DESC, e.id DESC LIMIT 1",
            args(ref.origin.code, ref.id),
            ::map,
        )
    }

    /**
     * Copies the entries of meal [slotId] on [fromDay] to [toDay] (into [toSlotId], by default the
     * same slot), stamped [tSec] and without an import id. Returns the number copied.
     */
    public suspend fun copyMeal(fromDay: Day, slotId: Long, toDay: Day, tSec: Long, toSlotId: Long = slotId): Int =
        db.write(setOf(Table.DIARY)) { d ->
            d.withStatement(
                "INSERT INTO diary_entry ($COL_NAMES) SELECT ?, ?, ?, food_origin, food_id, name, brand, amount_x100, " +
                    "serving_label, grams_x10, ${NutrientCols.NAMES}, NULL FROM diary_entry WHERE day = ? AND meal_slot_id = ? ORDER BY t_sec, id",
            ) { st ->
                st.bindLong(1, toDay.yyyymmdd.toLong())
                st.bindLong(2, toSlotId)
                st.bindLong(3, tSec)
                st.bindLong(4, fromDay.yyyymmdd.toLong())
                st.bindLong(5, slotId)
                st.executeUpdateDelete()
            }
        }

    /**
     * The most recently logged distinct foods (newest first), one entry each. Entries without a food
     * reference (quick adds) count as distinct by name.
     */
    public suspend fun recents(limit: Int): List<DiaryEntry> = db.read { d ->
        // SQLite returns the bare columns of the row holding MAX(t_sec) in each group.
        d.queryList(
            "SELECT $SELECT_COLS, MAX(e.t_sec) FROM diary_entry e $GROUP_FOOD ORDER BY MAX(e.t_sec) DESC, e.id DESC LIMIT ${limit.coerceAtLeast(0)}",
            null,
            ::map,
        )
    }

    /** The most often logged distinct foods (most first), each with its latest entry. */
    public suspend fun frequent(limit: Int): List<FrequentFood> = db.read { d ->
        d.queryList(
            "SELECT $SELECT_COLS, MAX(e.t_sec), COUNT(*) FROM diary_entry e $GROUP_FOOD " +
                "ORDER BY COUNT(*) DESC, MAX(e.t_sec) DESC, e.id DESC LIMIT ${limit.coerceAtLeast(0)}",
            null,
        ) { c -> FrequentFood(map(c), c.getInt(SELECT_COUNT + 1)) }
    }

    /** Nutrient totals for [day] (known values summed; a nutrient no entry reports stays null). */
    public suspend fun dayTotals(day: Day): Nutrients = db.read { d ->
        d.queryOne("SELECT ${NutrientCols.SUMS} FROM diary_entry WHERE day = ?", args(day.yyyymmdd)) { NutrientCols.read(it, 0) }
            ?: Nutrients.UNKNOWN
    }

    /**
     * Energy logged per day over [days] (inclusive), in tenths of a kcal, from one grouped query. Days
     * without entries are absent.
     */
    public suspend fun dailyKcalX10(days: DayRange): Map<Day, Long> = db.read { d ->
        d.queryList(
            "SELECT day, SUM(kcal_x10) FROM diary_entry WHERE day BETWEEN ? AND ? GROUP BY day ORDER BY day",
            args(days.first.yyyymmdd, days.last.yyyymmdd),
        ) { c -> Day(c.getInt(0)) to c.getLong(1) }.toMap()
    }

    /** Nutrient totals for [day] per meal slot id (slots with no entries are absent). */
    public suspend fun slotTotals(day: Day): Map<Long, Nutrients> = db.read { d ->
        d.queryList(
            "SELECT meal_slot_id, ${NutrientCols.SUMS} FROM diary_entry WHERE day = ? GROUP BY meal_slot_id",
            args(day.yyyymmdd),
        ) { c -> c.getLong(0) to NutrientCols.read(c, 1) }.toMap()
    }

    /** Meal slots in display order; hidden ones only when [includeHidden]. */
    public suspend fun mealSlots(includeHidden: Boolean = true): List<MealSlot> = db.read { d ->
        val where = if (includeHidden) "" else "WHERE hidden = 0"
        d.queryList("SELECT id, name, ord, hidden FROM meal_slot $where ORDER BY ord, id", null) { c ->
            MealSlot(c.getLong(0), c.getString(1), c.getInt(2), c.bool(3))
        }
    }

    /** Adds a meal slot after the others; returns its id. */
    public suspend fun addMealSlot(name: String): Long = db.write(setOf(Table.MEAL_SLOT)) { d ->
        d.withStatement("INSERT INTO meal_slot (name, ord) VALUES (?, (SELECT COALESCE(MAX(ord), 0) + 1 FROM meal_slot))") { st ->
            st.bindString(1, name)
            st.executeInsert()
        }
    }

    /** Renames, reorders or hides meal slot `slot.id`. Returns true if it existed. */
    public suspend fun updateMealSlot(slot: MealSlot): Boolean = db.write(setOf(Table.MEAL_SLOT)) { d ->
        d.withStatement("UPDATE meal_slot SET name = ?, ord = ?, hidden = ? WHERE id = ?") { st ->
            st.bindString(1, slot.name)
            st.bindLong(2, slot.order.toLong())
            st.bindLong(3, if (slot.hidden) 1 else 0)
            st.bindLong(4, slot.id)
            st.executeUpdateDelete() > 0
        }
    }

    internal companion object {
        private const val COLS = 20
        private const val COL_NAMES = "day, meal_slot_id, t_sec, food_origin, food_id, name, brand, amount_x100, serving_label, " +
            "grams_x10, ${NutrientCols.NAMES}, import_id"
        private val SET_COLS = COL_NAMES.split(", ").joinToString(", ") { "$it = ?" }
        private val SELECT_COLS = "e.id, " + COL_NAMES.split(", ").joinToString(", ") { "e.$it" }
        private const val SELECT_COUNT = 21
        private val SELECT = "SELECT $SELECT_COLS FROM diary_entry e"
        private val INSERT = "INSERT INTO diary_entry ($COL_NAMES) VALUES (${"?, ".repeat(COLS - 1)}?)"
        private const val GROUP_FOOD =
            "GROUP BY e.food_origin, e.food_id, CASE WHEN e.food_id IS NULL THEN e.name END"

        private fun insertWith(st: SQLiteStatement, e: DiaryEntry): Long {
            bindCols(st, e)
            return st.executeInsert()
        }

        private fun bindCols(st: SQLiteStatement, e: DiaryEntry) {
            st.bindLong(1, e.day.yyyymmdd.toLong())
            st.bindLong(2, e.mealSlotId)
            st.bindLong(3, e.tSec)
            st.bindIntOrNull(4, e.food?.origin?.code)
            st.bindLongOrNull(5, e.food?.id)
            st.bindString(6, e.name)
            st.bindStrOrNull(7, e.brand)
            st.bindLong(8, e.amountX100.toLong())
            st.bindString(9, e.servingLabel)
            st.bindIntOrNull(10, e.gramsX10)
            NutrientCols.bind(st, 11, e.nutrients.copy(kcalX10 = e.nutrients.kcalX10 ?: 0))
            st.bindLongOrNull(20, e.importId)
        }

        internal fun map(c: Cursor): DiaryEntry {
            val origin = c.intOrNull(4)?.let { FoodOrigin.fromCode(it) }
            val foodId = c.longOrNull(5)
            return DiaryEntry(
                id = c.getLong(0),
                day = Day(c.getInt(1)),
                mealSlotId = c.getLong(2),
                tSec = c.getLong(3),
                food = if (origin != null && foodId != null) FoodRef(origin, foodId) else null,
                name = c.getString(6),
                brand = c.strOrNull(7),
                amountX100 = c.getInt(8),
                servingLabel = c.getString(9),
                gramsX10 = c.intOrNull(10),
                nutrients = NutrientCols.read(c, 11),
                importId = c.longOrNull(20),
            )
        }
    }
}
