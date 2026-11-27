/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.health

import app.selfwatch.model.food.Goal
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.time.Day
import app.selfwatch.service.ServiceDbTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class StepAutoGoalTest : ServiceDbTest() {
    private val today = Day(20261123)

    private fun steps(day: Day, n: Int) =
        db.writableDatabase.execSQL("INSERT INTO day_summary (day, tz_offset_min, steps, updated_sec) VALUES (${day.yyyymmdd}, 0, $n, 1)")

    @Test
    fun raisesInGarminSizedStepsAndRounds() {
        assertEquals(7_790, StepAutoGoal.raised(7_350)) // 6% below 7,500: 7,791 rounds to 7,790
        assertEquals(7_800, StepAutoGoal.raised(7_500)) // 4% from 7,500
        assertEquals(10_200, StepAutoGoal.raised(10_000)) // 2% above 10,000
        assertEquals(StepAutoGoal.MAX, StepAutoGoal.raised(StepAutoGoal.MAX))
    }

    @Test
    fun startsAt7500AndGoesUpOnlyAfterMetDays() = runBlocking {
        db.settings.set(StepAutoGoal.CHECKED_KEY, today.plusDays(-4).yyyymmdd.toString())
        db.goals.set(Goal(GoalKind.STEPS, 7_500, today.plusDays(-10)))
        steps(today.plusDays(-3), 9_000) // met: 7,800 from the next day
        steps(today.plusDays(-2), 2_000) // missed: no change, never down
        steps(today.plusDays(-1), 8_000) // met 7,800: 8,110 from today
        StepAutoGoal.update(db, clock)
        assertEquals(7_800, db.goals.goalOn(GoalKind.STEPS, today.plusDays(-2))!!.value)
        assertEquals(7_800, db.goals.goalOn(GoalKind.STEPS, today.plusDays(-1))!!.value)
        assertEquals(8_110, db.goals.goalOn(GoalKind.STEPS, today)!!.value)
        // Running again changes nothing.
        StepAutoGoal.update(db, clock)
        assertEquals(8_110, db.goals.goalOn(GoalKind.STEPS, today)!!.value)
    }

    @Test
    fun noGoalYetStartsAt7500AndAFixedGoalIsLeftAlone() = runBlocking {
        StepAutoGoal.update(db, clock)
        assertEquals(7_500, db.goals.goalOn(GoalKind.STEPS, today)!!.value)
        db.settings.set(StepAutoGoal.AUTO_KEY, "0")
        db.goals.set(Goal(GoalKind.STEPS, 12_000, today))
        steps(today, 20_000)
        clock.advance(86_400)
        StepAutoGoal.update(db, clock)
        assertEquals(12_000, db.goals.goalOn(GoalKind.STEPS, today.plusDays(1))!!.value)
    }
}
