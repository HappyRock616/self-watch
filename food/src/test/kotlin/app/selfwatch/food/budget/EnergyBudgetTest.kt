/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.budget

import app.selfwatch.db.SettingKeys
import app.selfwatch.food.DbTestBase
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Profile
import app.selfwatch.model.food.Sex
import app.selfwatch.model.food.UnitSystem
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.DaySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyBudgetTest : DbTestBase() {

    @Test
    fun mifflinStJeorFormula() {
        // 80 kg, 180 cm, 40 years: 800 + 1125 − 200 = 1725, +5 / −161 / −78.
        assertEquals(1730, Bmr.mifflinStJeor(8000, 180, 40, Sex.MALE))
        assertEquals(1564, Bmr.mifflinStJeor(8000, 180, 40, Sex.FEMALE))
        assertEquals(1647, Bmr.mifflinStJeor(8000, 180, 40, Sex.UNSPECIFIED))
        // 62.5 kg, 165 cm, 31: 625 + 1031.25 − 155 − 161 = 1340.25 → 1340.
        assertEquals(1340, Bmr.mifflinStJeor(6250, 165, 31, Sex.FEMALE))
        // 70.55 kg, 171 cm, 25 male: 705.5 + 1068.75 − 125 + 5 = 1654.25 → 1654; halves round away from zero.
        assertEquals(1654, Bmr.mifflinStJeor(7055, 171, 25, Sex.MALE))
        assertEquals(1655, Bmr.mifflinStJeor(7060, 171, 25, Sex.MALE)) // 1654.75
        assertEquals(0, Bmr.mifflinStJeor(100, 50, 120, Sex.FEMALE)) // never negative
    }

    @Test
    fun autoGoalAndFloors() {
        // 1,700 resting × 1.2 = 2,040; losing 500 g/week = −550 a day.
        assertEquals(AutoGoal(1490, false), EnergyBudget.autoGoal(1700, -500, Sex.FEMALE))
        assertEquals(AutoGoal(1500, true), EnergyBudget.autoGoal(1700, -500, Sex.MALE))
        assertEquals(AutoGoal(2040, false), EnergyBudget.autoGoal(1700, 0, Sex.MALE))
        assertEquals(AutoGoal(2315, false), EnergyBudget.autoGoal(1700, 250, Sex.MALE)) // gaining 250 g/week: +275
        assertEquals(AutoGoal(1200, true), EnergyBudget.autoGoal(1200, -1000, Sex.FEMALE))
        assertEquals(AutoGoal(1350, true), EnergyBudget.autoGoal(1000, 0, Sex.UNSPECIFIED))
        assertEquals(1500, EnergyBudget.floorFor(Sex.MALE))
        assertEquals(-200, EnergyBudget.remaining(1800, 2500, 500))
        assertEquals(800, EnergyBudget.remaining(1800, 1500, 500))
    }

    @Test
    fun todayUsesWatchDataWhenThereIsSome() = test {
        val budget = EnergyBudget(db, clock)
        val today = clock.today()
        db.summaries.upsertDay(
            DaySummary(today, -300, 8000, 450, 1650, null, null, null, null, null, null, null, null, null, null, null),
            clock.nowSec(),
        )
        db.diary.add(
            DiaryEntry(0, today, 1, clock.nowSec(), null, "Lunch", null, 100, "quick add", null,
                Nutrients(6505, null, null, null, null, null, null, null, null), null),
        )
        val b = budget.today()
        assertEquals(GoalMode.AUTO, b.mode)
        assertEquals(1650, b.restingKcal)
        assertFalse(b.restingEstimated)
        assertEquals(1980, b.goalKcal) // 1,650 × 1.2
        assertEquals(651, b.eatenKcal) // 650.5 → 651
        assertEquals(450, b.activeKcal)
        assertEquals(1980 - 651 + 450, b.remainingKcal)

        budget.setAuto(-500)
        assertEquals(1430, budget.today().goalKcal) // 1,980 − 550
        budget.setManual(2100)
        val manual = budget.today()
        assertEquals(GoalMode.MANUAL, manual.mode)
        assertEquals(2100, manual.goalKcal)
        assertEquals(2100 - 651 + 450, manual.remainingKcal)
    }

    @Test
    fun todaysEffectiveGoalIsPublishedForTheService() = test {
        val budget = EnergyBudget(db, clock)
        val key = SettingKeys.FOOD_GOAL_EFFECTIVE_KCAL
        assertEquals(null, db.settings.getInt(key))
        val auto = budget.today()
        assertEquals(auto.goalKcal, db.settings.getInt(key))
        budget.setManual(2100)
        budget.today()
        assertEquals(2100, db.settings.getInt(key))
        // Another day's budget doesn't overwrite today's goal.
        budget.setAuto(-500)
        budget.forDay(clock.today().plusDays(-3))
        assertEquals(2100, db.settings.getInt(key))
    }

    @Test
    fun withoutWatchDataTheProfileEstimatesRestingEnergy() = test {
        val budget = EnergyBudget(db, clock)
        val none = budget.today()
        assertTrue(none.restingEstimated)
        assertEquals(EnergyBudget.DEFAULT_RESTING_KCAL, none.restingKcal)
        assertEquals(0, none.eatenKcal)

        db.settings.setProfile(Profile(heightCm = 165, birthYear = 1995, sex = Sex.FEMALE, units = UnitSystem.METRIC))
        db.body.insert(BodyMeasure(0, clock.nowSec() - 86_400, BodyKind.WEIGHT, null, 6250, DataSource.MANUAL, null, null))
        budget.setAuto(-1000)
        val b = budget.today()
        assertEquals(1340, b.restingKcal) // age 31 in 2026
        assertEquals(1200, b.goalKcal) // 1,608 − 1,100 is below the floor
        assertTrue(b.floorApplied)
    }
}
