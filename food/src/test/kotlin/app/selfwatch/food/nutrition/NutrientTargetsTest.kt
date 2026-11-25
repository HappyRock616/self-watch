/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.nutrition

import app.selfwatch.model.food.Allergen
import app.selfwatch.model.food.DietInfo
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Nutrients.Field
import app.selfwatch.model.food.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NutrientTargetsTest {

    @Test
    fun guidelineDefaultsByAgeAndSex() {
        val woman = NutrientTargets.of(TargetInputs(age = 35, sex = Sex.FEMALE))
        assertEquals(180, woman.getValue(Field.IRON).low) // 18 mg
        assertEquals(250, woman.getValue(Field.FIBER).low) // 25 g without a calorie goal
        assertEquals(2600, woman.getValue(Field.POTASSIUM).low)
        assertEquals(460, woman.getValue(Field.PROTEIN).low)
        val older = NutrientTargets.of(TargetInputs(age = 72, sex = Sex.MALE))
        assertEquals(80, older.getValue(Field.IRON).low)
        assertEquals(1200, older.getValue(Field.CALCIUM).low)
        assertEquals(200, older.getValue(Field.VITD).low) // 20 µg over 70
        assertEquals(300, older.getValue(Field.FIBER).low)
        // No sex: the average of both.
        assertEquals(130, NutrientTargets.of(TargetInputs()).getValue(Field.IRON).low)
        // Everyone: sodium 2,300 mg, saturated fat and added sugar under 10% of 2,000 kcal.
        assertEquals(2300, woman.getValue(Field.SODIUM).limit)
        assertEquals(222, woman.getValue(Field.SATFAT).limit) // 200 kcal / 9 = 22.2 g
        assertEquals(500, woman.getValue(Field.ADDSUGAR).limit) // 200 kcal / 4 = 50 g
        assertNull(woman[Field.KCAL])
        assertNull(woman[Field.TRANSFAT])
    }

    @Test
    fun heartHealthyPreset() {
        val t = NutrientTargets.of(TargetInputs(kcalGoal = 1800, prefs = DietPrefs(setOf(Diet.HEART_HEALTHY), emptySet())))
        val sodium = t.getValue(Field.SODIUM)
        assertEquals(TargetStatus.UNDER_LIMIT, sodium.status(1400))
        assertEquals(TargetStatus.NEAR_LIMIT, sodium.status(1800)) // amber above 1,500
        assertEquals(TargetStatus.OVER, sodium.status(2400)) // red above 2,300
        assertEquals(200, t.getValue(Field.SATFAT).limit) // 10% of 1,800 kcal = 20 g
        assertEquals(120, t.getValue(Field.SATFAT).caution) // 6% = 12 g
        assertEquals(252, t.getValue(Field.FIBER).low) // 14 g per 1,000 kcal = 25.2 g
        assertEquals(18000, t.getValue(Field.KCAL).low)
        assertEquals(listOf(Field.SODIUM, Field.SATFAT, Field.FIBER, Field.POTASSIUM, Field.ADDSUGAR), NutrientTargets.focus(DietPrefs(setOf(Diet.HEART_HEALTHY), emptySet())))
    }

    @Test
    fun highProteinUsesBodyWeightAndGoalsWin() {
        val prefs = DietPrefs(setOf(Diet.HIGH_PROTEIN), emptySet())
        val p = NutrientTargets.of(TargetInputs(weightKgX100 = 80_00, prefs = prefs)).getValue(Field.PROTEIN)
        assertEquals(960, p.low) // 1.2 g/kg × 80 kg
        assertEquals(1600, p.high) // 2.0 g/kg
        assertEquals(640, NutrientTargets.of(TargetInputs(weightKgX100 = 80_00)).getValue(Field.PROTEIN).low) // 0.8 g/kg
        assertEquals(1500, NutrientTargets.of(TargetInputs(weightKgX100 = 80_00, proteinGoalG = 150, prefs = prefs)).getValue(Field.PROTEIN).low)
        assertEquals(TargetStatus.BELOW, p.status(500))
        assertEquals(TargetStatus.MET, p.status(1000))
        assertEquals(TargetStatus.UNKNOWN, p.status(null))
        assertEquals(52, p.percent(500))
    }

    @Test
    fun dietConflictsOnlyFromKnownFacts() {
        val vegan = DietPrefs(setOf(Diet.VEGAN), emptySet())
        val egg = DietInfo(DietInfo.VEGETARIAN, 3, Allergen.EGG.bit, true)
        assertEquals(listOf(DietConflict.NotVegan), vegan.conflicts(egg))
        val chicken = DietInfo(0, 3, 0, true)
        assertEquals(listOf(DietConflict.NotVegetarian), vegan.conflicts(chicken))
        assertTrue(vegan.conflicts(DietInfo.UNKNOWN).isEmpty())
        assertTrue(vegan.conflicts(null).isEmpty())
        val glutenFree = DietPrefs(setOf(Diet.GLUTEN_FREE), setOf(Allergen.MILK))
        val pancakes = DietInfo(2, 3, Allergen.GLUTEN.bit or Allergen.MILK.bit or Allergen.EGG.bit, true)
        assertEquals(listOf(DietConflict.Contains(Allergen.GLUTEN), DietConflict.Contains(Allergen.MILK)), glutenFree.conflicts(pancakes))
        assertTrue(DietPrefs.NONE.conflicts(pancakes).isEmpty())
        // Settings round trip.
        val p = DietPrefs(setOf(Diet.VEGAN, Diet.HEART_HEALTHY), setOf(Allergen.TREE_NUT))
        assertEquals(p, DietPrefs.of(DietPrefs.dietIds(p), DietPrefs.allergenIds(p)))
        assertEquals(listOf("heart_healthy", "vegan"), DietPrefs.dietIds(p))
        assertEquals(listOf("tree_nut"), DietPrefs.allergenIds(p))
    }

    @Test
    fun completenessCountsKnownValues() {
        val a = Nutrients.UNKNOWN.copy(kcalX10 = 100, potassiumMg = 200)
        val b = Nutrients.UNKNOWN.copy(kcalX10 = 50)
        assertEquals(1, NutrientTargets.known(listOf(a, b), Field.POTASSIUM))
        assertEquals(2, NutrientTargets.known(listOf(a, b), Field.KCAL))
    }
}
