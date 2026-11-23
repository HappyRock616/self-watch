/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db.dao

import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.db.args
import app.selfwatch.db.queryList
import app.selfwatch.db.queryOne
import app.selfwatch.db.read
import app.selfwatch.db.withStatement
import app.selfwatch.db.write
import app.selfwatch.model.food.Goal
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.time.Day

/**
 * Goals (table `goal`). A goal applies from its `fromDay` until the next goal of the same kind, so
 * changing a goal never rewrites the past.
 */
public class GoalDao internal constructor(private val db: SelfWatchDb) {

    /** Stores [goal] (a goal of the same kind and day is replaced). */
    public suspend fun set(goal: Goal) {
        db.write(setOf(Table.GOAL)) { d ->
            d.withStatement("INSERT OR REPLACE INTO goal (kind, from_day, value) VALUES (?, ?, ?)") { st ->
                st.bindLong(1, goal.kind.code.toLong())
                st.bindLong(2, goal.fromDay.yyyymmdd.toLong())
                st.bindLong(3, goal.value.toLong())
                st.executeInsert()
            }
        }
    }

    /** The goal of [kind] in force on [day] (the latest with `fromDay <= day`), or null. */
    public suspend fun goalOn(kind: GoalKind, day: Day): Goal? = db.read { d ->
        d.queryOne(
            "SELECT from_day, value FROM goal WHERE kind = ? AND from_day <= ? ORDER BY from_day DESC LIMIT 1",
            args(kind.code, day.yyyymmdd),
        ) { c -> Goal(kind, c.getInt(1), Day(c.getInt(0))) }
    }

    /** Every goal of [kind], oldest first. */
    public suspend fun history(kind: GoalKind): List<Goal> = db.read { d ->
        d.queryList("SELECT from_day, value FROM goal WHERE kind = ? ORDER BY from_day", args(kind.code)) { c ->
            Goal(kind, c.getInt(1), Day(c.getInt(0)))
        }
    }

    /** The goal of every kind in force on [day]. */
    public suspend fun allOn(day: Day): Map<GoalKind, Goal> {
        val out = LinkedHashMap<GoalKind, Goal>()
        for (k in GoalKind.entries) goalOn(k, day)?.let { out[k] = it }
        return out
    }

    /** Removes the goal of [kind] starting [fromDay]. Returns true if it existed. */
    public suspend fun delete(kind: GoalKind, fromDay: Day): Boolean = db.write(setOf(Table.GOAL)) {
        it.delete("goal", "kind = ? AND from_day = ?", args(kind.code, fromDay.yyyymmdd)) > 0
    }
}
