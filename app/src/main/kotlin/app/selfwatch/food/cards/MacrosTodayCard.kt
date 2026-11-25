/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.cards

import android.content.Context
import android.view.ViewGroup
import app.selfwatch.R
import app.selfwatch.body.BodyLoader
import app.selfwatch.body.MacrosInfo
import app.selfwatch.body.cards.MacrosCardView
import app.selfwatch.body.cards.MacrosTables
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardStatusColors
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.me.GoalsFragment
import app.selfwatch.ui.format.Fmt

/** Protein, carbs and fat for the diary day shown, against the targets; opens Goals. Same view as Body's macros card. */
object MacrosTodayCard : Card<MacrosInfo>(
    "macros_today",
    CardTab.FOOD,
    R.string.card_macros_today,
    R.string.card_macros_today_summary,
) {
    override val tables: Set<Table> = MacrosTables.ALL

    override suspend fun load(env: CardEnv): MacrosInfo = BodyLoader(env.db, env.clock).macros(env.day)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override fun small(ctx: Context, data: MacrosInfo): SmallFace? {
        if (data.isEmpty) return null
        val protein = data.proteinG ?: 0
        val goal = data.proteinGoalG
        val dash = ctx.getString(R.string.dash)
        return SmallFace(
            value = Fmt.number(protein.toLong()),
            label = if (goal != null) ctx.getString(R.string.small_protein_of, Fmt.number(goal.toLong())) else ctx.getString(R.string.macros_protein),
            unit = ctx.getString(R.string.unit_g),
            status = if (goal != null) CardStatusColors.ofProgress(protein.toDouble(), goal.toDouble()) else CardStatus.NONE,
            note = ctx.getString(
                R.string.small_macros_note,
                data.carbG?.let { Fmt.number(it.toLong()) } ?: dash,
                data.fatG?.let { Fmt.number(it.toLong()) } ?: dash,
            ),
            progress = goal?.takeIf { it > 0 }?.let { protein.toFloat() / it },
        )
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(GoalsFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<MacrosInfo> =
        MacrosCardView(
            parent,
            onOpen = { host.open(GoalsFragment()) },
            onSetGoal = { host.open(GoalsFragment()) },
            description = R.string.macros_today_description,
        ).also { it.root.id = R.id.food_macros_card }
}
