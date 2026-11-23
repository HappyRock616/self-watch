/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.diary

import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.food.nutrition.NutrientMath
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.MealSlot
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Serving
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One meal slot of a [DiaryDay]: its [entries] (time order) and their [totals]. */
data class DiaryMeal(val slot: MealSlot, val entries: List<DiaryEntry>, val totals: Nutrients)

/**
 * Everything the diary screen shows for [day]: each visible meal slot (plus hidden slots that have
 * entries), in display order, and the day [totals] (known values summed; see `Nutrients.plus`).
 */
data class DiaryDay(val day: Day, val meals: List<DiaryMeal>, val totals: Nutrients) {
    /** All entries of the day, meal by meal. */
    val entries: List<DiaryEntry> get() = meals.flatMap { it.entries }
}

/**
 * The food diary. Each line copies the food's nutrients for the amount eaten (see `NutrientMath`),
 * so later edits to a food never change past days. After every change the touched days are passed
 * to [refresher] so the day summary (energy card, watch calories left) updates.
 */
class DiaryService(
    private val db: SelfWatchDb,
    private val clock: Clock,
    private val refresher: DayRefresher = DayRefresher.NONE,
) {

    /**
     * Logs [amountX100] servings ×100 of [serving] of [food] in meal [slot] on [day]; returns the
     * entry id. Throws [IllegalArgumentException] when the amount is not positive or the food is per
     * 100 g / 100 ml and [serving] has no weight (offer `NutrientMath.GRAM` instead).
     */
    suspend fun add(day: Day, slot: Long, food: Food, serving: Serving, amountX100: Int): Long {
        require(amountX100 > 0) { "amount must be positive" }
        val nutrients = requireNotNull(NutrientMath.forServing(food, serving, amountX100)) {
            "serving has no weight for a per-weight food"
        }
        val id = db.diary.add(
            DiaryEntry(
                id = 0,
                day = day,
                mealSlotId = slot,
                tSec = clock.nowSec(),
                food = food.ref,
                name = food.name,
                brand = food.brand,
                amountX100 = amountX100,
                servingLabel = serving.label,
                gramsX10 = NutrientMath.gramsX10(serving, amountX100),
                nutrients = nutrients,
                importId = null,
            ),
        )
        refresher.refresh(setOf(day))
        return id
    }

    /** Logs a line with only a [name] and its [nutrients] (no food behind it); returns the entry id. */
    suspend fun quickAdd(day: Day, slot: Long, name: String, nutrients: Nutrients): Long {
        val id = db.diary.add(
            DiaryEntry(
                id = 0,
                day = day,
                mealSlotId = slot,
                tSec = clock.nowSec(),
                food = null,
                name = name.trim(),
                brand = null,
                amountX100 = 100,
                servingLabel = QUICK_ADD_SERVING,
                gramsX10 = null,
                nutrients = nutrients,
                importId = null,
            ),
        )
        refresher.refresh(setOf(day))
        return id
    }

    /** Copies meal [slot] of day [from] into the same meal on day [to] ("copy yesterday"); returns the count. */
    suspend fun copyMeal(from: Day, slot: Long, to: Day): Int {
        val n = db.diary.copyMeal(from, slot, to, clock.nowSec())
        if (n > 0) refresher.refresh(setOf(to))
        return n
    }

    /** Copies every meal of day [from] into day [to]; returns the count. */
    suspend fun copyDay(from: Day, to: Day): Int {
        val slots = db.diary.day(from).map { it.mealSlotId }.distinct()
        var n = 0
        for (s in slots) n += db.diary.copyMeal(from, s, to, clock.nowSec())
        if (n > 0) refresher.refresh(setOf(to))
        return n
    }

    /** Moves entry [entryId] to meal [slot] on [day]. Returns false if there is no such entry. */
    suspend fun move(entryId: Long, slot: Long, day: Day): Boolean {
        val e = db.diary.get(entryId) ?: return false
        val ok = db.diary.update(e.copy(mealSlotId = slot, day = day))
        if (ok) refresher.refresh(setOf(e.day, day))
        return ok
    }

    /**
     * Changes the amount of entry [entryId], rescaling its copied nutrients and weight in proportion.
     * Returns false if there is no such entry. [amountX100] must be positive.
     */
    suspend fun changeAmount(entryId: Long, amountX100: Int): Boolean {
        require(amountX100 > 0) { "amount must be positive" }
        val e = db.diary.get(entryId) ?: return false
        if (e.amountX100 <= 0) return false
        val num = amountX100.toLong()
        val den = e.amountX100.toLong()
        val updated = e.copy(
            amountX100 = amountX100,
            nutrients = NutrientMath.scale(e.nutrients, num, den),
            gramsX10 = e.gramsX10?.let { NutrientMath.clampInt(NutrientMath.mulDivRound(it.toLong(), num, den)) },
        )
        val ok = db.diary.update(updated)
        if (ok) refresher.refresh(setOf(e.day))
        return ok
    }

    /** Deletes entry [entryId] and returns it (for undo with [restore]), or null if there was none. */
    suspend fun delete(entryId: Long): DiaryEntry? {
        val e = db.diary.get(entryId) ?: return null
        if (!db.diary.delete(entryId)) return null
        refresher.refresh(setOf(e.day))
        return e
    }

    /** Puts a deleted [entry] back (undo); returns its new id. */
    suspend fun restore(entry: DiaryEntry): Long {
        val id = db.diary.add(entry)
        refresher.refresh(setOf(entry.day))
        return id
    }

    /**
     * The serving and amount the user last logged [ref] with, for one-tap re-logging, or null if it
     * was never logged. The serving is rebuilt from the diary line: seq -1, the logged label and the
     * weight of one serving when known.
     */
    suspend fun lastServingFor(ref: FoodRef): Pair<Serving, Int>? {
        val e = db.diary.lastEntryFor(ref) ?: return null
        if (e.amountX100 <= 0) return null
        val perServing = e.gramsX10?.let { NutrientMath.clampInt(NutrientMath.mulDivRound(it.toLong(), 100L, e.amountX100.toLong())) }
        return Serving(-1, e.servingLabel, perServing) to e.amountX100
    }

    /** [day] now and after every diary or meal-slot change (in any process). */
    fun day(day: Day): Flow<DiaryDay> =
        db.changes.observe(setOf(Table.DIARY, Table.MEAL_SLOT)).map { load(day) }

    /** Reads [day] once. */
    suspend fun load(day: Day): DiaryDay {
        val slots = db.diary.mealSlots(includeHidden = true)
        val entries = db.diary.day(day).groupBy { it.mealSlotId }
        val meals = ArrayList<DiaryMeal>()
        for (s in slots) {
            val list = entries[s.id].orEmpty()
            if (s.hidden && list.isEmpty()) continue
            meals.add(DiaryMeal(s, list, sum(list)))
        }
        // Entries pointing at a slot that no longer exists still show, at the end.
        val known = slots.map { it.id }.toSet()
        for ((slotId, list) in entries) {
            if (slotId in known) continue
            meals.add(DiaryMeal(MealSlot(slotId, "", Int.MAX_VALUE, false), list, sum(list)))
        }
        return DiaryDay(day, meals, sum(entries.values.flatten()))
    }

    /** Constants. */
    companion object {
        /** Serving label of quick-add lines. */
        const val QUICK_ADD_SERVING: String = "quick add"

        private fun sum(list: List<DiaryEntry>): Nutrients =
            if (list.isEmpty()) Nutrients.UNKNOWN else list.fold(Nutrients.UNKNOWN) { acc, e -> acc + e.nutrients }
    }
}
