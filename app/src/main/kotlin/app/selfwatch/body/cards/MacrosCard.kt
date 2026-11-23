/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body.cards

import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import app.selfwatch.R
import app.selfwatch.body.BodyLoader
import app.selfwatch.body.MacrosInfo
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.db.Table
import app.selfwatch.me.GoalsFragment
import app.selfwatch.shell.Tab
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Fmt
import com.google.android.material.progressindicator.LinearProgressIndicator
import app.selfwatch.ui.R as UiR

/** Protein, carbs and fat today vs targets; opens Food. */
object MacrosCard : Card<MacrosInfo>("macros", CardTab.BODY, R.string.card_macros, R.string.card_macros_summary) {

    /** Tables that change a day's macros or their targets. */
    val MACRO_TABLES: Set<Table> = setOf(Table.DIARY, Table.GOAL, Table.BODY)

    override val tables: Set<Table> = MACRO_TABLES

    override suspend fun load(env: CardEnv): MacrosInfo = BodyLoader(env.db, env.clock).macros(env.day)

    override fun createView(host: CardHost, parent: ViewGroup): CardView<MacrosInfo> =
        MacrosCardView(parent, onOpen = { host.openTab(Tab.FOOD) }, onSetGoal = { host.open(GoalsFragment()) })
}

/**
 * The macros card's view, shared by Body's [MacrosCard] and Food's macros card: protein against its
 * goal with a bar, then carbs and fat. [onOpen] runs on a tap, [onSetGoal] on "Set a goal";
 * [description] is what TalkBack reads (protein, carbs, fat).
 */
class MacrosCardView(
    parent: ViewGroup,
    onOpen: () -> Unit,
    onSetGoal: () -> Unit,
    @param:StringRes private val description: Int = R.string.macros_description,
) :
    CardView<MacrosInfo>(CardView.inflate(parent, R.layout.card_macros)) {

    private val card = root as SwCard

    init {
        card.setOnClickListener { onOpen() }
        card.onAction = onSetGoal
    }

    override fun bind(data: MacrosInfo) {
        val ctx = root.context
        val protein = find<StatTile>(R.id.macros_protein)
        protein.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
        protein.value = data.proteinG?.let { Fmt.number(it.toLong()) } ?: "0"
        protein.unit = ctx.getString(R.string.unit_g)
        val goal = data.proteinGoalG
        protein.label = when {
            goal == null -> ctx.getString(R.string.macros_protein)
            data.proteinGoalAuto -> ctx.getString(R.string.macros_protein_auto, Fmt.number(goal.toLong()))
            else -> ctx.getString(R.string.macros_protein_goal, Fmt.number(goal.toLong()))
        }
        card.actionText = if (goal == null) ctx.getString(R.string.macros_set_goal) else null
        val bar = find<LinearProgressIndicator>(R.id.macros_protein_bar)
        bar.visibility = if (goal == null) View.GONE else View.VISIBLE
        bar.max = maxOf(goal ?: 1, 1)
        bar.setProgressCompat((data.proteinG ?: 0).coerceAtMost(bar.max), false)
        grams(find(R.id.macros_carbs), data.carbG, data.carbGoalG, R.string.macros_carbs, R.string.macros_carbs_goal)
        grams(find(R.id.macros_fat), data.fatG, data.fatGoalG, R.string.macros_fat, R.string.macros_fat_goal)
        find<EmptyState>(R.id.macros_empty).visibility = if (data.isEmpty) View.VISIBLE else View.GONE
        find<View>(R.id.macros_row).visibility = if (data.isEmpty) View.GONE else View.VISIBLE
        fun g(v: Int?): String = ctx.getString(R.string.grams_value, Fmt.number((v ?: 0).toLong()))
        card.contentDescription = ctx.getString(description, g(data.proteinG), g(data.carbG), g(data.fatG))
    }

    private fun grams(tile: StatTile, grams: Int?, goal: Int?, @StringRes label: Int, @StringRes goalLabel: Int) {
        val ctx = root.context
        tile.value = grams?.let { Fmt.number(it.toLong()) } ?: ctx.getString(R.string.dash)
        tile.unit = if (grams != null) ctx.getString(R.string.unit_g) else null
        tile.label = goal?.let { ctx.getString(goalLabel, Fmt.number(it.toLong())) } ?: ctx.getString(label)
    }
}
