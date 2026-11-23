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
import app.selfwatch.db.dao.WaterEntry
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Water for one [day]: [entries] oldest first, their [totalMl], and the goal in force ([goalMl], if set). */
data class WaterDay(val day: Day, val entries: List<WaterEntry>, val totalMl: Int, val goalMl: Int?)

/** The water log: one-tap drinks, undo, and the day's total against the water goal. */
class WaterService(
    private val db: SelfWatchDb,
    private val clock: Clock,
    private val refresher: DayRefresher = DayRefresher.NONE,
) {
    /** Logs [ml] (must be positive) on [day] (today by default); returns the entry id. */
    suspend fun add(ml: Int, day: Day = clock.today()): Long {
        require(ml > 0) { "ml must be positive" }
        val id = db.water.add(day, clock.nowSec(), ml)
        refresher.refresh(setOf(day))
        return id
    }

    /** Deletes entry [id] of [day] (undo). Returns true if it existed. */
    suspend fun delete(id: Long, day: Day): Boolean {
        val ok = db.water.delete(id)
        if (ok) refresher.refresh(setOf(day))
        return ok
    }

    /** Removes the newest drink of [day] (the "−" button). Returns the removed entry, or null if none. */
    suspend fun undoLast(day: Day = clock.today()): WaterEntry? {
        val last = db.water.entries(day).lastOrNull() ?: return null
        return if (delete(last.id, day)) last else null
    }

    /** Reads [day] once. */
    suspend fun load(day: Day): WaterDay {
        val entries = db.water.entries(day)
        return WaterDay(day, entries, entries.sumOf { it.ml }, db.goals.goalOn(GoalKind.WATER_ML, day)?.value)
    }

    /** [day] now and after every water or goal change. */
    fun day(day: Day): Flow<WaterDay> = db.changes.observe(setOf(Table.WATER, Table.GOAL)).map { load(day) }
}
