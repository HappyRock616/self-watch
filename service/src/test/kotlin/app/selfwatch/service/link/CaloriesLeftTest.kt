/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.link

import app.selfwatch.db.SettingKeys
import app.selfwatch.model.food.Goal
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.health.DaySummary
import app.selfwatch.model.time.Day
import app.selfwatch.model.watch.CiqValue
import app.selfwatch.service.ServiceDbTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** F08: the budget numbers, the message and the 5-minute limit. */
class CaloriesLeftTest : ServiceDbTest() {
    private val watchApp = UUID.fromString("c0ffee00-0000-4000-8000-000000000001")
    private val sent = ArrayList<Pair<UUID, CiqValue>>()
    private val waits = ArrayList<Long>()
    private var accept = true

    private val calories by lazy {
        CaloriesLeft(db, clock, send = { app, msg ->
            sent.add(app to msg)
            accept
        }, sleep = { ms ->
            waits.add(ms)
            clock.advance(ms / 1000)
        })
    }

    private fun summary(eaten: Int?, active: Int?, resting: Int?) = runBlocking {
        db.summaries.upsertDay(
            DaySummary(clock.today(), -300, null, active, resting, null, null, null, null, null, eaten, null, null, null, null, null),
            clock.nowSec(),
        )
    }

    @Test
    fun numbersFollowTheBudgetFormula() = runBlocking {
        summary(eaten = 1200, active = 400, resting = 1500)
        assertEquals(CaloriesLeft.Values(null, 1200, 1900), calories.compute())
        db.goals.set(Goal(GoalKind.KCAL_IN, 2000, Day(20260101)))
        // goal − eaten + active
        assertEquals(CaloriesLeft.Values(1200, 1200, 1900), calories.compute())
        assertEquals(
            CiqValue.Dict(mapOf(CiqValue.Str("kcalLeft") to CiqValue.Num(1200), CiqValue.Str("kcalIn") to CiqValue.Num(1200), CiqValue.Str("kcalOut") to CiqValue.Num(1900))),
            calories.compute().message(),
        )
    }

    @Test
    fun withoutAManualGoalTheFoodModulesAutomaticGoalIsUsed() = runBlocking {
        summary(eaten = 1200, active = 400, resting = 1500)
        db.settings.setInt(SettingKeys.FOOD_GOAL_EFFECTIVE_KCAL, 1900)
        assertEquals(CaloriesLeft.Values(1100, 1200, 1900), calories.compute())
        // A manual goal wins while the food goal mode is not auto...
        db.goals.set(Goal(GoalKind.KCAL_IN, 2000, Day(20260101)))
        assertEquals(1200, calories.compute().kcalLeft)
        // ...and the published goal wins once the user switched back to auto.
        db.settings.set(SettingKeys.FOOD_GOAL_MODE, "auto")
        assertEquals(1100, calories.compute().kcalLeft)
    }

    @Test
    fun offUntilAWatchAppIsChosen() = runBlocking {
        summary(500, 100, 1400)
        calories.step()
        assertTrue(sent.isEmpty())
        db.settings.set(LinkSettings.CALORIES_APP, watchApp.toString())
        calories.step()
        assertEquals(watchApp, sent.single().first)
    }

    @Test
    fun sendsOnChangeAtMostEveryFiveMinutes() = runBlocking {
        db.settings.set(LinkSettings.CALORIES_APP, watchApp.toString())
        summary(500, 100, 1400)
        calories.step()
        assertEquals(1, sent.size)
        assertTrue(waits.isEmpty())
        // A change two minutes later waits the remaining three minutes.
        clock.advance(120)
        summary(800, 100, 1400)
        calories.step()
        assertEquals(2, sent.size)
        assertEquals(listOf(180_000L), waits)
        // Nothing changed: nothing sent (after waiting out the interval).
        calories.step()
        assertEquals(2, sent.size)
        assertEquals(CaloriesLeft.MIN_INTERVAL_SEC * 1000, waits.last())
    }

    @Test
    fun undeliveredIsTriedAgain() = runBlocking {
        db.settings.set(LinkSettings.CALORIES_APP, watchApp.toString())
        summary(500, 100, 1400)
        accept = false
        calories.step()
        accept = true
        calories.step()
        assertEquals(2, sent.size)
        assertTrue(waits.isEmpty())
    }
}
