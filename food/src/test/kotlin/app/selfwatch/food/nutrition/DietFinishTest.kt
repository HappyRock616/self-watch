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
import app.selfwatch.model.time.Day
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hand-typed targets, the Add food diet filters, the vegetarian offer, week averages and the category list. */
class DietFinishTest {

    // --- Target overrides ---

    @Test
    fun overridesWinAndKeepTheirKind() {
        val heart = TargetInputs(kcalGoal = 2000, prefs = DietPrefs(setOf(Diet.HEART_HEALTHY), emptySet()))
        val base = NutrientTargets.of(heart)
        assertEquals(2300, base.getValue(Field.SODIUM).limit)
        val mine = NutrientTargets.of(heart.copy(overrides = mapOf(Field.SODIUM to 2000, Field.IRON to 150, Field.FAT to 700)))
        // A limit stays a limit; the heart-healthy amber point below it is kept.
        assertEquals(2000, mine.getValue(Field.SODIUM).limit)
        assertEquals(1500, mine.getValue(Field.SODIUM).caution)
        // A goal becomes the typed goal; fat had no target and now has one.
        assertEquals(150, mine.getValue(Field.IRON).low)
        assertEquals(700, mine.getValue(Field.FAT).low)
        // Lowering a limit under the amber point drops the amber point.
        val low = NutrientTargets.of(heart.copy(overrides = mapOf(Field.SODIUM to 1200)))
        assertNull(low.getValue(Field.SODIUM).caution)
        // Calories follow the goal, never an override; recommended ignores overrides.
        val kcal = NutrientTargets.of(heart.copy(overrides = mapOf(Field.KCAL to 9990)))
        assertEquals(20000, kcal.getValue(Field.KCAL).low)
        assertEquals(base, NutrientTargets.recommended(heart.copy(overrides = mapOf(Field.SODIUM to 2000))))
        assertEquals(2000, NutrientTargets.editValue(mine.getValue(Field.SODIUM)))
        assertEquals(150, NutrientTargets.editValue(mine.getValue(Field.IRON)))
    }

    @Test
    fun overridesRoundTripThroughSettings() {
        val map = mapOf(Field.VITD to 200, Field.SODIUM to 1500, Field.IRON to 0)
        val items = TargetOverrides.encode(map)
        assertEquals(listOf("SODIUM=1500", "VITD=200"), items)
        assertEquals(mapOf(Field.SODIUM to 1500, Field.VITD to 200), TargetOverrides.decode(items))
        assertEquals(mapOf(Field.IRON to 80), TargetOverrides.decode(listOf("iron=80", "BOGUS=1", "CALCIUM=x", "SODIUM=-5", "noequals")))
    }

    // --- Diet filters ---

    private val milkVeg = DietInfo(DietInfo.VEGETARIAN, DietInfo.VEGAN or DietInfo.VEGETARIAN, Allergen.MILK.bit, true)
    private val veganBread = DietInfo(DietInfo.VEGAN or DietInfo.VEGETARIAN, 3, Allergen.GLUTEN.bit, false)

    @Test
    fun filtersFollowThePrefs() {
        assertTrue(DietFilter.forPrefs(DietPrefs.NONE).isEmpty())
        assertTrue(DietFilter.forPrefs(DietPrefs(setOf(Diet.HIGH_PROTEIN), emptySet())).isEmpty())
        val all = DietFilter.forPrefs(DietPrefs(setOf(Diet.VEGAN, Diet.VEGETARIAN, Diet.GLUTEN_FREE), setOf(Allergen.MILK, Allergen.GLUTEN)))
        assertEquals(listOf(DietFilter.Vegan, DietFilter.GlutenFree, DietFilter.Without(Allergen.MILK)), all)
        assertEquals(listOf(DietFilter.Vegetarian), DietFilter.forPrefs(DietPrefs(setOf(Diet.VEGETARIAN), emptySet())))
        assertEquals("no_milk", DietFilter.Without(Allergen.MILK).id)
    }

    @Test
    fun filtersHideOnlyKnownClashes() {
        assertEquals(FilterFit.CONFLICTS, DietFilter.Vegan.fit(milkVeg))
        assertEquals(FilterFit.FITS, DietFilter.Vegetarian.fit(milkVeg))
        assertEquals(FilterFit.CONFLICTS, DietFilter.Without(Allergen.MILK).fit(milkVeg))
        assertEquals(FilterFit.FITS, DietFilter.Without(Allergen.EGG).fit(milkVeg)) // full ingredient list
        assertEquals(FilterFit.UNKNOWN, DietFilter.Without(Allergen.EGG).fit(veganBread)) // partial list
        assertEquals(FilterFit.CONFLICTS, DietFilter.GlutenFree.fit(veganBread))
        assertEquals(FilterFit.UNKNOWN, DietFilter.Vegan.fit(null))
        assertEquals(FilterFit.UNKNOWN, DietFilter.Vegan.fit(DietInfo.UNKNOWN))
        val active = listOf(DietFilter.Vegan, DietFilter.Without(Allergen.EGG))
        assertEquals(FilterFit.CONFLICTS, DietFilter.fitAll(milkVeg, active))
        assertEquals(FilterFit.UNKNOWN, DietFilter.fitAll(veganBread, active))
        assertEquals(FilterFit.FITS, DietFilter.fitAll(veganBread, listOf(DietFilter.Vegan)))
        assertEquals(FilterFit.FITS, DietFilter.fitAll(null, emptyList()))
    }

    // --- Vegetarian offer ---

    private fun days(n: Int, cat: Int?): List<List<Int?>> = List(n) { listOf(cat, 101) }

    @Test
    fun vegetarianOfferNeedsTwoMeatFreeWeeks() {
        val none = DietPrefs.NONE
        assertTrue(VegetarianHint.shouldOffer(days(14, 302), none, dismissed = false))
        assertFalse(VegetarianHint.shouldOffer(days(13, 302), none, dismissed = false))
        // Empty days do not count.
        assertFalse(VegetarianHint.shouldOffer(days(13, 302) + listOf(emptyList()), none, dismissed = false))
        // Any meat category, once, stops it.
        for (meat in 401..404) assertFalse(VegetarianHint.shouldOffer(days(13, 302) + listOf(listOf(meat)), none, dismissed = false))
        // Tofu (405) and nuts (406) are not meat.
        assertTrue(VegetarianHint.shouldOffer(days(14, 405), none, dismissed = false))
        // Mostly unknown foods never earn it.
        assertFalse(VegetarianHint.shouldOffer(List(14) { listOf(null, null, 101) }, none, dismissed = false))
        assertTrue(VegetarianHint.shouldOffer(List(14) { listOf(null, 101) }, none, dismissed = false))
        // Never after a dismiss, or with the diet already set.
        assertFalse(VegetarianHint.shouldOffer(days(14, 302), none, dismissed = true))
        assertFalse(VegetarianHint.shouldOffer(days(14, 302), DietPrefs(setOf(Diet.VEGETARIAN), emptySet()), dismissed = false))
        assertFalse(VegetarianHint.shouldOffer(days(14, 302), DietPrefs(setOf(Diet.VEGAN), emptySet()), dismissed = false))
        assertTrue(VegetarianHint.shouldOffer(days(14, 302), DietPrefs(setOf(Diet.HEART_HEALTHY), emptySet()), dismissed = false))
    }

    // --- Week totals ---

    @Test
    fun weekAveragesSkipEmptyDaysAndUnknownNutrients() {
        val last = Day(20261123)
        val days = WeekTotals.weekEnding(last)
        assertEquals(7, days.size)
        assertEquals(Day(20261117), days.first())
        assertEquals(last, days.last())
        fun n(kcal: Int, k: Int?) = Nutrients(kcal * 10, 100, null, null, null, null, null, null, null, potassiumMg = k)
        val w = WeekTotals(days, listOf(n(2000, 3000), null, n(1500, null), null, null, n(1800, 2001), null))
        assertEquals(3, w.logged)
        assertEquals(17667, w.average(Field.KCAL)) // (20000 + 15000 + 18000) / 3
        assertEquals(2501, w.average(Field.POTASSIUM)) // (3000 + 2001) / 2, rounded half up
        assertNull(w.average(Field.FAT))
        assertEquals(listOf(20000, null, 15000, null, null, 18000, null), w.perDay(Field.KCAL))
        assertEquals(17667, w.averages.kcalX10)
        assertNull(WeekTotals(days, List(7) { null }).average(Field.KCAL))
    }

    @Test
    fun builtInCategoriesMatchTheFoodDatabase() {
        assertEquals(42, FoodCategories.ALL.size)
        assertEquals(42, FoodCategories.ALL.map { it.id }.distinct().size)
        assertEquals(9, FoodCategories.ALL.map { it.group }.distinct().size)
        assertEquals("Chicken & turkey", FoodCategories.byId(402)?.name)
        assertTrue(FoodCategories.ALL.filter { it.id in VegetarianHint.MEAT_CATEGORIES }.all { it.group == "Meat & protein" })
        assertNull(FoodCategories.byId(1))
    }
}
