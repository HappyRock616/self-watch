/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Day
import kotlinx.coroutines.flow.first

/** What a one-tap log from the + sheet did, for its snackbar. */
sealed interface QuickLogResult {
    /** A glass was added; the day now has [totalMl] (goal [goalMl], if set). */
    data class Water(val totalMl: Int, val goalMl: Int?) : QuickLogResult

    /** A fast started, aiming at [goalMin] minutes. */
    data class FastStarted(val goalMin: Int) : QuickLogResult

    /** The running fast ended after [elapsedSec]. */
    data class FastEnded(val elapsedSec: Long) : QuickLogResult
}

/**
 * The + sheet's "Log water" and "Start or stop fast": the same rules as the Food tab's water card
 * (one glass of [glassMl] on today, see [WaterGlass]) and fasting card (start a 16-hour fast, or end the
 * running one).
 */
object QuickLog {
    /** Adds one glass of water ([glassMl], from [WaterGlass.ml] for the user's units) to today. */
    suspend fun water(backend: FoodBackend, glassMl: Int): QuickLogResult.Water {
        val today = backend.clock.today()
        backend.addWater(glassMl, today)
        val w = backend.waterDay(today).first()
        return QuickLogResult.Water(w.totalMl, w.goalMl)
    }

    /** Removes the glass [water] added (Undo). */
    suspend fun undoWater(backend: FoodBackend) {
        backend.removeWater(backend.clock.today())
    }

    /**
     * "Recent for <meal>" one-tap add: logs [last]'s food into [slot] on [day] with the serving it was
     * last logged with (Add food's quick log rule, [FoodRows.quickServing]). When the food is gone or
     * cannot be logged that way, the diary line itself is copied. Returns the new entry's id.
     */
    suspend fun relog(backend: FoodBackend, last: DiaryEntry, day: Day, slot: Long): Long {
        val ref = last.food
        val food = ref?.let { backend.food(it) }
        if (ref != null && food != null) {
            val (serving, amount) = FoodRows.quickServing(food, backend.lastServing(ref))
            try {
                return backend.logFood(day, slot, food, serving, amount)
            } catch (e: IllegalArgumentException) {
                SwLog.w(TAG, "cannot log as is; copying the last line", e)
            }
        }
        return backend.restore(last.copy(id = 0, day = day, mealSlotId = slot, tSec = backend.clock.nowSec(), importId = null))
    }

    private const val TAG = "QuickLog"

    /** Starts a fast, or ends the running one. */
    suspend fun toggleFast(backend: FoodBackend): QuickLogResult {
        val running = backend.fastStatus().first()
        return if (running == null) {
            backend.startFast(DiaryViewModel.DEFAULT_FAST_MIN)
            QuickLogResult.FastStarted(DiaryViewModel.DEFAULT_FAST_MIN)
        } else {
            val elapsed = backend.clock.nowSec() - running.fast.startSec
            backend.endFast()
            QuickLogResult.FastEnded(elapsed.coerceAtLeast(0))
        }
    }
}
