/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.cards

import app.selfwatch.body.cards.EnergyCard
import app.selfwatch.cards.Card

/**
 * Every card whose home is Food (food, water, calories in vs out). On Food they sit above the meals;
 * any of them can be added to Body or Activity too. Loaders get the diary day shown as `CardEnv.day`
 * (today on the other tabs). A new Food card is one more entry here (and in `CardPresets` when it is a
 * default).
 */
object FoodCards {
    /** All Food cards, in Edit cards order. */
    val ALL: List<Card<*>> = listOf(
        DailyBalanceCard,
        WeightGoalCard,
        WaterCard,
        ProteinPerMealCard,
        EnergyCard,
        MacrosTodayCard,
        FoodGroupsCard,
        ProteinPerKgCard,
        CarbsPerKgCard,
        WorkoutFuelCard,
        FuelBalanceCard,
        LoggingDaysCard,
        SodiumCard,
        FibreCard,
        TotalSugarCard,
    )
}
