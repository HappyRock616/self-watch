/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.cards

import android.view.ViewGroup
import app.selfwatch.R
import app.selfwatch.body.BodyLoader
import app.selfwatch.body.MacrosInfo
import app.selfwatch.body.cards.MacrosCard
import app.selfwatch.body.cards.MacrosCardView
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.db.Table
import app.selfwatch.me.GoalsFragment

/** Protein, carbs and fat for the diary day shown, against the targets; opens Goals. Same view as Body's macros card. */
object MacrosTodayCard : Card<MacrosInfo>(
    "macros_today",
    CardTab.FOOD,
    R.string.card_macros_today,
    R.string.card_macros_today_summary,
) {
    override val tables: Set<Table> = MacrosCard.MACRO_TABLES

    override suspend fun load(env: CardEnv): MacrosInfo = BodyLoader(env.db, env.clock).macros(env.day)

    override fun createView(host: CardHost, parent: ViewGroup): CardView<MacrosInfo> =
        MacrosCardView(
            parent,
            onOpen = { host.open(GoalsFragment()) },
            onSetGoal = { host.open(GoalsFragment()) },
            description = R.string.macros_today_description,
        ).also { it.root.id = R.id.food_macros_card }
}
