/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

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
 * (one 250 ml glass on today) and fasting card (start a 16-hour fast, or end the running one).
 */
object QuickLog {
    /** Adds one glass of water to today. */
    suspend fun water(backend: FoodBackend): QuickLogResult.Water {
        val today = backend.clock.today()
        backend.addWater(DiaryViewModel.GLASS_ML, today)
        val w = backend.waterDay(today).first()
        return QuickLogResult.Water(w.totalMl, w.goalMl)
    }

    /** Removes the glass [water] added (Undo). */
    suspend fun undoWater(backend: FoodBackend) {
        backend.removeWater(backend.clock.today())
    }

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
