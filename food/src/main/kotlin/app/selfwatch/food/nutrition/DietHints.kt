/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.nutrition

import app.selfwatch.model.food.FoodCategory

/**
 * The gentle "Set vegetarian?" offer (FOOD_UI_PLAN §4.5): after two weeks of logging with no meat, the
 * Food tab asks once. Meat is the food database categories 401–404 (beef, pork and lamb; chicken and
 * turkey; fish and seafood; deli meats).
 */
object VegetarianHint {
    /** Logged days needed before the offer. */
    const val DAYS: Int = 14

    /** How far back the logged days are looked for. */
    const val LOOKBACK_DAYS: Int = 28

    /** The meat categories. */
    val MEAT_CATEGORIES: IntRange = 401..404

    /**
     * Whether to offer, from the category ids of every food logged per day over the lookback (one list per
     * day with entries; null for a food whose category is unknown). The offer needs [DAYS] logged days,
     * no meat at all, and categories known for at least half of the foods (so a diary of unknown foods
     * never earns it). Never when [prefs] already include vegetarian or vegan, or after [dismissed].
     */
    fun shouldOffer(days: List<List<Int?>>, prefs: DietPrefs, dismissed: Boolean): Boolean {
        if (dismissed || Diet.VEGETARIAN in prefs.diets || Diet.VEGAN in prefs.diets) return false
        val logged = days.filter { it.isNotEmpty() }
        if (logged.size < DAYS) return false
        val all = logged.flatten()
        if (all.any { it != null && it in MEAT_CATEGORIES }) return false
        val known = all.count { it != null }
        return known * 2 >= all.size
    }
}

/**
 * The 42 food categories of food database v2 (the `category` table, `tools/fooddb/categories.csv`), for
 * the custom food editor when foods.db is not installed yet.
 */
object FoodCategories {
    /** Every category, in id order. */
    val ALL: List<FoodCategory> = listOf(
        FoodCategory(101, "Fruits", "Fruits & vegetables"),
        FoodCategory(102, "Vegetables", "Fruits & vegetables"),
        FoodCategory(103, "Potatoes & fries", "Fruits & vegetables"),
        FoodCategory(104, "Beans, lentils & legumes", "Fruits & vegetables"),
        FoodCategory(201, "Rice, grains & pasta", "Grains"),
        FoodCategory(202, "Bread, bagels & tortillas", "Grains"),
        FoodCategory(203, "Breakfast cereals & oatmeal", "Grains"),
        FoodCategory(301, "Milk & cream", "Dairy & eggs"),
        FoodCategory(302, "Yogurt", "Dairy & eggs"),
        FoodCategory(303, "Cheese", "Dairy & eggs"),
        FoodCategory(304, "Eggs", "Dairy & eggs"),
        FoodCategory(305, "Plant-based milks & dairy alternatives", "Dairy & eggs"),
        FoodCategory(401, "Beef, pork & lamb", "Meat & protein"),
        FoodCategory(402, "Chicken & turkey", "Meat & protein"),
        FoodCategory(403, "Fish & seafood", "Meat & protein"),
        FoodCategory(404, "Deli meats, sausages & bacon", "Meat & protein"),
        FoodCategory(405, "Tofu & meat alternatives", "Meat & protein"),
        FoodCategory(406, "Nuts, seeds & nut butters", "Meat & protein"),
        FoodCategory(501, "Oils, butter & fats", "Fats, sauces & seasonings"),
        FoodCategory(502, "Sauces, dressings & dips", "Fats, sauces & seasonings"),
        FoodCategory(503, "Spices, herbs & seasonings", "Fats, sauces & seasonings"),
        FoodCategory(504, "Sugar, honey, syrups & jams", "Fats, sauces & seasonings"),
        FoodCategory(601, "Soups", "Meals"),
        FoodCategory(602, "Entrées & mixed dishes", "Meals"),
        FoodCategory(603, "Sandwiches, burgers & wraps", "Meals"),
        FoodCategory(604, "Pizza", "Meals"),
        FoodCategory(605, "Salads", "Meals"),
        FoodCategory(606, "Appetizers & sides", "Meals"),
        FoodCategory(701, "Chips, crackers & salty snacks", "Snacks & sweets"),
        FoodCategory(702, "Snack, granola & protein bars", "Snacks & sweets"),
        FoodCategory(703, "Candy & chocolate", "Snacks & sweets"),
        FoodCategory(704, "Cookies, cakes & pastries", "Snacks & sweets"),
        FoodCategory(705, "Ice cream & desserts", "Snacks & sweets"),
        FoodCategory(801, "Water", "Drinks"),
        FoodCategory(802, "Soda & energy drinks", "Drinks"),
        FoodCategory(803, "Coffee & tea", "Drinks"),
        FoodCategory(804, "Juice & smoothies", "Drinks"),
        FoodCategory(805, "Protein shakes, sports drinks & supplements", "Drinks"),
        FoodCategory(806, "Beer, wine & spirits", "Drinks"),
        FoodCategory(807, "Other drinks", "Drinks"),
        FoodCategory(901, "Baby & toddler food", "Other"),
        FoodCategory(999, "Other", "Other"),
    )

    /** The category with [id], or null. */
    fun byId(id: Int): FoodCategory? = ALL.firstOrNull { it.id == id }
}
