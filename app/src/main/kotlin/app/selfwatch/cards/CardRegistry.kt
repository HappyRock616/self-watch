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
import app.selfwatch.devices.cards.DeviceCards
import app.selfwatch.food.cards.FoodCards

/**
 * Every card type, by home tab. Each tab keeps its own list ([BodyCards], [ActivityCards], [FoodCards])
 * so cards for different tabs can be added in parallel without touching the same file. Any card can be
 * shown on any tab; ids are unique across all of them.
 */
object CardRegistry {

    /** Every card whose home is [tab], in Edit cards order. */
    fun of(tab: CardTab): List<Card<*>> = when (tab) {
        CardTab.BODY -> BodyCards.ALL + DeviceCards.ALL
        CardTab.ACTIVITY -> ActivityCards.ALL
        CardTab.FOOD -> FoodCards.ALL
    }

    /** Every card, Body first, then Activity, then Food. */
    val all: List<Card<*>> by lazy { CardTab.entries.flatMap { of(it) } }

    private val byId: Map<String, Card<*>> by lazy { all.associateBy { it.id } }

    /** The card with [id] (whatever its home tab), or null for an unknown (removed or newer) id. */
    fun byId(id: String): Card<*>? = byId[id]

    /** The card with [id] when its home is [tab], else null. */
    fun byId(tab: CardTab, id: String): Card<*>? = byId(id)?.takeIf { it.tab == tab }
}
