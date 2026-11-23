/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.cards

import app.selfwatch.activity.cards.ActivityCards
import app.selfwatch.body.cards.BodyCards
import app.selfwatch.food.cards.FoodCards

/**
 * Every card type per tab. Each tab keeps its own list ([BodyCards], [ActivityCards], [FoodCards]) so
 * cards for different tabs can be added in parallel without touching the same file.
 */
object CardRegistry {

    /** Every card on [tab], in Edit cards order for hidden cards. */
    fun of(tab: CardTab): List<Card<*>> = when (tab) {
        CardTab.BODY -> BodyCards.ALL
        CardTab.ACTIVITY -> ActivityCards.ALL
        CardTab.FOOD -> FoodCards.ALL
    }

    /** The card on [tab] with [id], or null for an unknown (removed or newer) id. */
    fun byId(tab: CardTab, id: String): Card<*>? = of(tab).firstOrNull { it.id == id }
}
