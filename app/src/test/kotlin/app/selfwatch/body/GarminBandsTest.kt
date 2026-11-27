/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body

import app.selfwatch.R
import app.selfwatch.model.food.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GarminBandsTest {
    @Test
    fun sleepScoreUsesGarminsFourBands() {
        assertEquals(R.string.band_excellent, GarminBands.sleepScore(90).name)
        assertEquals(R.string.band_good, GarminBands.sleepScore(89).name)
        assertEquals(R.string.band_fair, GarminBands.sleepScore(60).name)
        assertEquals(R.string.band_poor, GarminBands.sleepScore(59).name)
    }

    @Test
    fun energyAndReadinessLevels() {
        assertEquals(R.string.band_high, GarminBands.energy(76).name)
        assertEquals(R.string.band_medium, GarminBands.energy(51).name)
        assertEquals(R.string.band_low, GarminBands.energy(26).name)
        assertEquals(R.string.band_very_low, GarminBands.energy(25).name)
        assertEquals(R.string.band_prime, GarminBands.readiness(95).name)
        assertEquals(R.string.band_high, GarminBands.readiness(75).name)
        assertEquals(R.string.band_moderate, GarminBands.readiness(50).name)
        assertEquals(R.string.band_low, GarminBands.readiness(25).name)
        assertEquals(R.string.band_poor, GarminBands.readiness(24).name)
    }

    @Test
    fun hrvStatusNames() {
        assertEquals(R.string.band_balanced, GarminBands.hrvStatus(4)?.name)
        assertEquals(R.string.band_unbalanced, GarminBands.hrvStatus(3)?.name)
        assertEquals(R.string.band_low, GarminBands.hrvStatus(2)?.name)
        assertEquals(R.string.band_poor, GarminBands.hrvStatus(1)?.name)
        assertNull(GarminBands.hrvStatus(0))
    }

    @Test
    fun bodyBands() {
        assertEquals(R.string.band_normal, GarminBands.bmi(24.9).name)
        assertEquals(R.string.band_overweight, GarminBands.bmi(25.0).name)
        assertEquals(R.string.band_obesity_1, GarminBands.bmi(30.0).name)
        assertEquals(R.string.band_obesity_3, GarminBands.bmi(41.0).name)
        assertEquals(R.string.band_fitness, GarminBands.bodyFat(15.0, Sex.MALE).name)
        assertEquals(R.string.band_obese, GarminBands.bodyFat(26.0, Sex.MALE).name)
        assertEquals(R.string.band_acceptable, GarminBands.bodyFat(26.0, Sex.FEMALE).name)
    }

    @Test
    fun bloodPressureUsesTheFiveAhaCategories() {
        assertEquals(R.string.band_normal, GarminBands.bloodPressure(118, 76).name)
        assertEquals(R.string.band_bp_elevated, GarminBands.bloodPressure(124, 78).name)
        assertEquals(R.string.band_bp_stage_1, GarminBands.bloodPressure(118, 84).name)
        assertEquals(R.string.band_bp_stage_2, GarminBands.bloodPressure(142, 70).name)
        assertEquals(R.string.band_bp_crisis, GarminBands.bloodPressure(185, 100).name)
    }

    @Test
    fun vo2MaxRatingAndFitnessAge() {
        // A 43-year-old man: 40–49 group (Good 42.4–46.3).
        assertEquals(R.string.band_good, GarminBands.vo2Max(44.0, 43, Sex.MALE).name)
        assertEquals(R.string.band_superior, GarminBands.vo2Max(53.0, 43, Sex.MALE).name)
        assertEquals(R.string.band_poor, GarminBands.vo2Max(35.0, 43, Sex.MALE).name)
        // Typical for 45 (middle of Good, 44.4) gives about 45; fitter is younger, less fit is older.
        assertEquals(45, GarminBands.fitnessAge(44.4, Sex.MALE))
        assertTrue(GarminBands.fitnessAge(50.0, Sex.MALE) < 35)
        assertTrue(GarminBands.fitnessAge(38.0, Sex.MALE) > 55)
        assertEquals(20, GarminBands.fitnessAge(70.0, Sex.MALE))
        assertEquals(80, GarminBands.fitnessAge(15.0, Sex.MALE))
    }
}
