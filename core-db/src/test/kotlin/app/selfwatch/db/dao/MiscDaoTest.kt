/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db.dao

import app.selfwatch.db.CustomFoodOrigin
import app.selfwatch.db.DbTest
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.Goal
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Recipe
import app.selfwatch.model.food.RecipeKind
import app.selfwatch.model.food.Serving
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.DaySummary
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.health.SleepStageSample
import app.selfwatch.model.net.NetFeature
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.util.UUID

class MiscDaoTest : DbTest() {

    // ---- goals ----

    @Test
    fun goals() = test {
        db.goals.set(Goal(GoalKind.STEPS, 8000, Day(20260101)))
        db.goals.set(Goal(GoalKind.STEPS, 10000, Day(20260601)))
        db.goals.set(Goal(GoalKind.KCAL_IN, 2000, Day(20260301)))
        assertNull(db.goals.goalOn(GoalKind.STEPS, Day(20251231)))
        assertEquals(8000, db.goals.goalOn(GoalKind.STEPS, Day(20260531))!!.value)
        assertEquals(Goal(GoalKind.STEPS, 10000, Day(20260601)), db.goals.goalOn(GoalKind.STEPS, Day(20260601)))
        db.goals.set(Goal(GoalKind.STEPS, 12000, Day(20260601))) // replace
        assertEquals(listOf(8000, 12000), db.goals.history(GoalKind.STEPS).map { it.value })
        assertEquals(setOf(GoalKind.STEPS, GoalKind.KCAL_IN), db.goals.allOn(Day(20261123)).keys)
        assertTrue(db.goals.delete(GoalKind.STEPS, Day(20260601)))
        assertFalse(db.goals.delete(GoalKind.STEPS, Day(20260601)))
        assertEquals(8000, db.goals.goalOn(GoalKind.STEPS, Day(20261123))!!.value)
    }

    // ---- summaries ----

    private fun summary(day: Day, steps: Int?, weight: Int? = null) = DaySummary(
        day, 60, steps, 400, 1600, 5000, 30, 55, 42, 420, 2100, 90, 70, 250, weight, 2000,
    )

    @Test
    fun daySummariesAndAggregation() = test {
        // 2026-11-23 is a Monday. Nov 23..Dec 6 = two full weeks, crossing a month end.
        var d = Day(20261123)
        var i = 0
        while (d <= Day(20261206)) {
            db.summaries.upsertDay(summary(d, steps = 1000 * (i + 1), weight = if (i % 2 == 0) 8000 + i else null), updatedSec = 1)
            d = d.plusDays(1)
            i++
        }
        db.summaries.upsertDay(summary(Day(20261123), steps = 1500, weight = 8000), updatedSec = 2) // replace day 1
        assertEquals(summary(Day(20261123), 1500, 8000), db.summaries.day(Day(20261123)))
        assertNull(db.summaries.day(Day(20200101)))
        assertEquals(14, db.summaries.days(DayRange(Day(20261101), Day(20261231))).size)
        assertEquals(3, db.summaries.days(DayRange(Day(20261129), Day(20261201))).size)

        val weeks = db.summaries.weeks(DayRange(Day(20261123), Day(20261206)))
        assertEquals(listOf(Day(20261123), Day(20261130)), weeks.map { it.first })
        assertEquals(7, weeks[0].dayCount)
        assertEquals(1500L + 2000 + 3000 + 4000 + 5000 + 6000 + 7000, weeks[0].sum.steps)
        assertEquals(4071L, weeks[0].avg.steps) // 28500 / 7 = 4071.4
        assertEquals(8000L + 8002 + 8004 + 8006, weeks[0].sum.weightKgX100) // nulls skipped
        assertEquals(8003L, weeks[0].avg.weightKgX100)
        assertEquals(7L * 400, weeks[1].sum.activeKcal)
        assertEquals(42L, weeks[1].avg.hrvAvgMs)

        // Sunday-start weeks: Nov 22 (Sun) .. Dec 6 (Sun) -> three groups.
        val sunWeeks = db.summaries.weeks(DayRange(Day(20261123), Day(20261206)), DayOfWeek.SUNDAY)
        assertEquals(listOf(Day(20261122), Day(20261129), Day(20261206)), sunWeeks.map { it.first })
        assertEquals(listOf(6, 7, 1), sunWeeks.map { it.dayCount })

        val months = db.summaries.months(DayRange(Day(20261101), Day(20261231)))
        assertEquals(listOf(Day(20261101), Day(20261201)), months.map { it.first })
        assertEquals(listOf(8, 6), months.map { it.dayCount })
        assertEquals(1500L + 2000 + 3000 + 4000 + 5000 + 6000 + 7000 + 8000, months[0].sum.steps)

        // A partial range only aggregates days inside it.
        val part = db.summaries.months(DayRange(Day(20261129), Day(20261202)))
        assertEquals(listOf(2, 2), part.map { it.dayCount })
    }

    @Test
    fun aggregationOfMissingValuesIsNull() = test {
        db.summaries.upsertDay(DaySummary(Day(20261123), 0, null, null, null, null, null, null, null, null, null, null, null, null, null, null), 1)
        val w = db.summaries.weeks(DayRange(Day(20261123), Day(20261123))).single()
        assertNull(w.sum.steps)
        assertNull(w.avg.steps)
        assertEquals(1, w.dayCount)
    }

    // ---- imports and undo ----

    private fun customFood(name: String, importId: Long?) = CustomFood(
        Food(FoodRef(FoodOrigin.MY_FOOD, 0), name, null, 0, Basis.PER_ITEM, Nutrients.ZERO, listOf(Serving(1, "item", null)), null, "", null),
        CustomFoodOrigin.IMPORT, null, false, 1, 1, importId,
    )

    private fun recipe(name: String, importId: Long?) = Recipe(
        0, RecipeKind.SAVED_MEAL, name, 10, null, null, null, null, null, null, null, null, setOf("t"), emptyList(), importId,
    )

    private suspend fun fill(importId: Long?, tag: String) {
        db.workouts.insert(workout(1000 + (importId ?: 0) * 10_000, deviceId = null), importId)
        db.body.insert(BodyMeasure(0, 1, BodyKind.WEIGHT, null, 8000, DataSource.IMPORT, null, importId))
        db.foods.insert(customFood("Food $tag", importId))
        db.diary.add(entry(Day(20261123), name = "Entry $tag", importId = importId))
        db.water.add(Day(20261123), 1, 250, importId)
        db.recipes.insert(recipe("Meal $tag", importId), nowSec = 1)
        // Watch data from imports sits under its own device rows; import_id tags it for undo.
        val dev = 100 + (importId ?: 0)
        db.activity.insert(listOf(ActivityMinute(dev, 60, 10, 5, 100, 1, null)), importId)
        db.metrics.insert(listOf(MetricSample(dev, MetricKind.HEART_RATE, 60, 70)), importId)
        db.sleep.insertSession(SleepSession(dev, 0, 3600, 80), importId)
        db.sleep.insertStages(listOf(SleepStageSample(dev, 0, SleepStage.DEEP)), importId)
    }

    @Test
    fun undoRemovesExactlyOneImport() = test {
        val a = db.imports.begin(100, "gadgetbridge", "gb.zip")
        val b = db.imports.begin(200, "csv.weight", null)
        fill(a, "alpha")
        fill(b, "beta")
        fill(null, "mine")
        db.imports.setCounts(a, mapOf("workout" to 1, "diary" to 1))
        assertEquals(mapOf("workout" to 1L, "diary" to 1L), db.imports.get(a)!!.counts)
        assertEquals(listOf(b, a), db.imports.list().map { it.id })

        val n = db.imports.undo(a)
        assertEquals(10, n)
        assertTrue(db.imports.get(a)!!.undone)
        assertFalse(db.imports.get(b)!!.undone)
        for (t in ImportDao.IMPORT_TABLES) {
            assertEquals(t, 0L, count(t, "import_id = $a"))
            assertEquals(t, 1L, count(t, "import_id = $b"))
            assertEquals(t, 1L, count(t, "import_id IS NULL"))
        }
        assertEquals(2L, count("custom_serving"))
        assertEquals(2L, count("recipe_tag"))
        assertEquals(4L, count("my_food_fts"))
        assertEquals(emptyList<MyFoodHit>(), db.foods.searchMine("alpha"))
        assertEquals(2, db.foods.searchMine("beta").size)
        assertEquals(0, db.imports.undo(a)) // second undo is a no-op
    }

    // ---- net log ----

    @Test
    fun netLog() = test {
        val day = 86_400L
        db.netLog.insert(NetLogEntry(0, 100, NetFeature.FOOD_DATA, "foods.example", "GET", 200, 5000, 200))
        db.netLog.insert(NetLogEntry(0, 100 + 50 * day, NetFeature.OPEN_FOOD_FACTS, "off.example", "GET", 100, 0, NetLogEntry.BLOCKED))
        db.netLog.insert(NetLogEntry(0, 100 + 95 * day, NetFeature.FOOD_DATA, "foods.example", "HEAD", 0, 0, NetLogEntry.OFFLINE))
        assertEquals(3, db.netLog.recent(10).size)
        assertEquals(NetLogEntry.OFFLINE, db.netLog.recent(1)[0].result)
        assertEquals(2, db.netLog.recent(10, NetFeature.FOOD_DATA).size)
        assertEquals(2, db.netLog.since(100 + day).size)
        val now = 100 + 95 * day
        assertEquals(1, db.netLog.prune(now - NetLogDao.RETENTION_DAYS * day))
        assertEquals(2, db.netLog.recent(10).size)
        assertEquals(0, db.netLog.prune(0))
    }

    // ---- links ----

    @Test
    fun linkPermissions() = test {
        val u = UUID.fromString("6f1e0c2a-3b4d-4e5f-8a9b-0c1d2e3f4a5b")
        val p = LinkPermission("com.example.app", u, "AB:CD", true, 10, null)
        db.links.set(p)
        assertEquals(p, db.links.get("com.example.app", u))
        assertNull(db.links.get("com.other", u))
        assertTrue(db.links.touch("com.example.app", u, 50))
        assertFalse(db.links.touch("com.other", u, 50))
        assertEquals(50L, db.links.get("com.example.app", u)!!.lastUsedSec)
        db.links.set(p.copy(allowed = false, decidedSec = 60))
        assertEquals(listOf(p.copy(allowed = false, decidedSec = 60)), db.links.list())
        assertTrue(db.links.delete("com.example.app", u))
        assertFalse(db.links.delete("com.example.app", u))
    }

    // ---- watch apps ----

    @Test
    fun watchApps() = test {
        val u1 = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val u2 = UUID.fromString("00000000-0000-0000-0000-000000000002")
        db.watchApps.upsert(WatchAppRow(1, u1, "Zeta", 3, 1, true, 10))
        db.watchApps.upsert(WatchAppRow(1, u2, "Alpha", null, null, true, 10))
        db.watchApps.upsert(WatchAppRow(2, u1, "Zeta", 3, 1, true, 10))
        assertEquals(listOf("Alpha", "Zeta"), db.watchApps.forDevice(1).map { it.name })
        assertTrue(db.watchApps.setInstalled(1, u2, false, 20))
        assertFalse(db.watchApps.setInstalled(9, u2, false, 20))
        assertEquals(listOf("Zeta"), db.watchApps.forDevice(1).map { it.name })
        assertEquals(2, db.watchApps.forDevice(1, includeRemoved = true).size)
        assertEquals(WatchAppRow(1, u2, "Alpha", null, null, false, 20), db.watchApps.get(1, u2))
        assertTrue(db.watchApps.delete(1, u2))
        assertFalse(db.watchApps.delete(1, u2))

        for (t in 1L..5L) db.watchApps.saveSettings(WatchAppSettings(u1, t, "{\"v\":$t}", if (t == 5L) "[]" else null))
        assertEquals(WatchAppSettings(u1, 5, "{\"v\":5}", "[]"), db.watchApps.latestSettings(u1))
        assertNull(db.watchApps.latestSettings(u2))
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), db.watchApps.settingsHistory(u1).map { it.savedSec })
        assertEquals(3, db.watchApps.pruneSettings(u1, keep = 2))
        assertEquals(listOf(5L, 4L), db.watchApps.settingsHistory(u1).map { it.savedSec })
    }
}
