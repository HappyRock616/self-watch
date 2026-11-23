/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.diary

import app.selfwatch.db.dao.Fast
import app.selfwatch.food.DbTestBase
import app.selfwatch.food.nutrition.NutrientMath
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.Goal
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Serving
import app.selfwatch.model.time.Day
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiaryServiceTest : DbTestBase() {
    private val refreshed = ArrayList<Set<Day>>()
    private val refresher = DayRefresher { refreshed.add(it) }
    private val today = Day(20261123)
    private val yesterday = Day(20261122)

    private val oats = Food(
        ref = FoodRef(FoodOrigin.FOODS_DB, 77),
        name = "Oats, rolled",
        brand = null,
        kind = 0,
        basis = Basis.PER_100G,
        per = Nutrients(3790, 132, 65, 11, 677, 10, 101, 6, null),
        servings = listOf(Serving(0, "1/2 cup", 400)),
        dataYear = 2019,
        sourceLabel = "USDA",
        barcode = null,
    )
    private val burger = Food(
        ref = FoodRef(FoodOrigin.FOODS_DB, 88),
        name = "Hamburger",
        brand = "Grill Co",
        kind = 2,
        basis = Basis.PER_ITEM,
        per = Nutrients(2500, 120, 90, 35, 310, 60, 10, 510, 30),
        servings = listOf(Serving(0, "1 sandwich", null)),
        dataYear = 2018,
        sourceLabel = "NYC MenuStat",
        barcode = null,
    )

    @Test
    fun addCopiesScaledNutrientsAndRefreshes() = test {
        val svc = DiaryService(db, clock, refresher)
        val id = svc.add(today, 1, oats, oats.servings[0], 150)
        val e = db.diary.get(id)!!
        assertEquals(oats.ref, e.food)
        assertEquals("1/2 cup", e.servingLabel)
        assertEquals(150, e.amountX100)
        assertEquals(600, e.gramsX10) // 60 g
        assertEquals(2274, e.nutrients.kcalX10) // 379 × 0.6
        assertEquals(79, e.nutrients.proteinX10) // 13.2 × 0.6 = 7.92
        assertNull(e.nutrients.cholesterolMg)
        assertEquals(clock.nowSec(), e.tSec)
        assertEquals(listOf(setOf(today)), refreshed)

        val b = db.diary.get(svc.add(today, 2, burger, burger.servings[0], 200))!!
        assertEquals(5000, b.nutrients.kcalX10)
        assertNull(b.gramsX10)

        val g = db.diary.get(svc.add(today, 3, oats, NutrientMath.GRAM, 5_000))!! // 50 g
        assertEquals(1895, g.nutrients.kcalX10)
        assertEquals(500, g.gramsX10)
    }

    @Test(expected = IllegalArgumentException::class)
    fun perWeightFoodNeedsAWeight() = test {
        DiaryService(db, clock).add(today, 1, oats, Serving(1, "1 bowl", null), 100)
    }

    @Test
    fun quickAddCopyMoveDeleteRestore() = test {
        val svc = DiaryService(db, clock, refresher)
        svc.quickAdd(yesterday, 1, " Coffee with milk ", Nutrients(450, null, null, null, null, null, null, null, null))
        svc.add(yesterday, 1, oats, oats.servings[0], 100)
        svc.add(yesterday, 2, burger, burger.servings[0], 100)

        // Copy yesterday's breakfast.
        assertEquals(2, svc.copyMeal(yesterday, 1, today))
        val day = svc.load(today)
        assertEquals(listOf("Coffee with milk", "Oats, rolled"), day.entries.map { it.name })
        assertEquals(1966, day.totals.kcalX10) // 45 + 151.6
        assertEquals(setOf(today), refreshed.last())
        assertEquals(0, svc.copyMeal(yesterday, 4, today))

        // Copy a whole day.
        assertEquals(3, svc.copyDay(yesterday, Day(20261124)))

        // Move the oats to lunch tomorrow.
        val oatsId = day.entries.first { it.name == "Oats, rolled" }.id
        assertTrue(svc.move(oatsId, 2, Day(20261124)))
        assertEquals(setOf(today, Day(20261124)), refreshed.last())
        assertFalse(svc.move(99_999, 2, today))

        // Delete with undo.
        val coffee = svc.load(today).entries.single()
        val removed = svc.delete(coffee.id)!!
        assertTrue(svc.load(today).entries.isEmpty())
        assertNull(svc.delete(coffee.id))
        svc.restore(removed)
        val back = svc.load(today).entries.single()
        assertEquals(removed.copy(id = back.id), back)
    }

    @Test
    fun changeAmountRescales() = test {
        val svc = DiaryService(db, clock)
        val id = svc.add(today, 1, oats, oats.servings[0], 100)
        assertTrue(svc.changeAmount(id, 250))
        val e = db.diary.get(id)!!
        assertEquals(250, e.amountX100)
        assertEquals(1000, e.gramsX10)
        assertEquals(3790, e.nutrients.kcalX10)
    }

    @Test
    fun lastServingForOneTapRelog() = test {
        val svc = DiaryService(db, clock)
        assertNull(svc.lastServingFor(oats.ref))
        svc.add(yesterday, 1, oats, oats.servings[0], 100)
        clock.advance(3600)
        svc.add(today, 1, oats, oats.servings[0], 150)
        val (serving, amount) = svc.lastServingFor(oats.ref)!!
        assertEquals("1/2 cup", serving.label)
        assertEquals(400, serving.gramsX10)
        assertEquals(150, amount)
    }

    @Test
    fun lastServingIsFoundBehindManyNewerFoods() = test {
        val svc = DiaryService(db, clock)
        svc.add(yesterday, 1, oats, oats.servings[0], 100)
        clock.advance(3600)
        // More distinct newer foods than the old recents scan looked at.
        val later = (0 until 600).map { i ->
            DiaryEntry(0, today, 1, clock.nowSec() + i, null, "Food $i", null, 100, "quick add", null,
                Nutrients(1000, null, null, null, null, null, null, null, null), null)
        }
        db.diary.addAll(later)
        assertEquals(100, svc.lastServingFor(oats.ref)?.second)
    }

    @Test
    fun dayFlowShowsSlotsAndUpdates() = test {
        val svc = DiaryService(db, clock)
        val empty = svc.day(today).first()
        assertEquals(listOf("Breakfast", "Lunch", "Dinner", "Snacks"), empty.meals.map { it.slot.name })
        assertTrue(empty.entries.isEmpty())
        assertEquals(Nutrients.UNKNOWN, empty.totals)
        svc.add(today, 3, burger, burger.servings[0], 100)
        val after = svc.day(today).first()
        assertEquals(2500, after.meals.first { it.slot.id == 3L }.totals.kcalX10)
        assertEquals(2500, after.totals.kcalX10)
    }

    @Test
    fun waterAddsUndoesAndShowsTheGoal() = test {
        val svc = WaterService(db, clock, refresher)
        val d = clock.today()
        svc.add(250)
        svc.add(500)
        db.goals.set(Goal(GoalKind.WATER_ML, 2000, Day(20260101)))
        var w = svc.load(d)
        assertEquals(750, w.totalMl)
        assertEquals(2000, w.goalMl)
        assertEquals(500, svc.undoLast()?.ml)
        w = svc.day(d).first()
        assertEquals(250, w.totalMl)
        assertEquals(setOf(d), refreshed.last())
        assertTrue(svc.delete(w.entries.single().id, d))
        assertNull(svc.undoLast())
    }

    @Test
    fun fastingStartsEndsAndNotifies() = test {
        val shown = ArrayList<String>()
        val notifier = object : FastNotifier {
            override fun show(fast: Fast) {
                shown.add("show ${fast.goalMin}")
            }

            override fun clear() {
                shown.add("clear")
            }
        }
        val svc = FastService(db, clock, notifier)
        assertNull(svc.current())
        val start = clock.nowSec()
        svc.start(16 * 60)
        clock.advance(10 * 3600)
        val now = svc.current()!!
        assertEquals(36_000L, now.elapsedSec)
        assertEquals(6 * 3600L, now.remainingSec)
        assertFalse(now.reached)
        assertEquals(625, now.progressPermille)

        // Starting again ends the running fast first.
        svc.start(18 * 60)
        assertEquals(2, svc.history(10).size)
        assertEquals(start + 36_000, svc.history(10)[1].fast.endSec)

        clock.advance(19 * 3600)
        val done = svc.end()!!
        assertTrue(done.reached)
        assertEquals(0, done.remainingSec)
        assertEquals(1000, done.progressPermille)
        assertNull(svc.current())
        assertNull(svc.end())
        assertEquals(listOf("show 960", "show 1080", "clear"), shown)
    }
}
