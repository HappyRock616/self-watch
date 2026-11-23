/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.cards

import app.selfwatch.cards.Card

/**
 * Every card the Food tab can show, in the strip between the calories-left header and the meals. The
 * header, the day switcher and the meals are not cards. A new Food card is one more entry here (and in
 * `CardPresets`); its loader gets the diary day shown as `CardEnv.day`.
 */
object FoodCards {
    /** All Food cards; hidden ones are listed in this order in Edit cards. */
    val ALL: List<Card<*>> = listOf(
        MacrosTodayCard,
        WaterCard,
        ProteinPerKgCard,
        ProteinPerMealCard,
        CarbsPerKgCard,
        WorkoutFuelCard,
        FuelBalanceCard,
        LoggingDaysCard,
        SodiumCard,
        FibreCard,
        TotalSugarCard,
    )
}
