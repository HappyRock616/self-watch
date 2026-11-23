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
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.db.Table
import app.selfwatch.ui.format.Fmt

/**
 * Protein per meal (C8): one dot per meal of the day shown, filled when the meal brought 0.25 g of
 * protein per kg of body weight (clamped to 20–40 g; 20 g without a weigh-in).
 */
object ProteinPerMealCard : Card<ProteinMeals>(
    "protein_per_meal",
    CardTab.FOOD,
    R.string.card_protein_per_meal,
    R.string.card_protein_per_meal_summary,
) {
    override val tables: Set<Table> = setOf(Table.DIARY, Table.MEAL_SLOT, Table.BODY)

    override suspend fun load(env: CardEnv): ProteinMeals = FoodCardLoader(env.db, env.clock).proteinMeals(env.day)

    override fun createView(host: CardHost, parent: ViewGroup): CardView<ProteinMeals> = Holder(parent)

    private class Holder(parent: ViewGroup) :
        FoodMetricView<ProteinMeals>(parent, R.id.food_protein_per_meal_card, R.string.card_protein_per_meal) {

        override fun bind(data: ProteinMeals) {
            if (data.eaten == 0 || data.meals.isEmpty()) {
                showEmpty(ctx.getString(R.string.protein_meals_empty))
                return
            }
            showContent()
            show(tile, false)
            val threshold = Fmt.number(data.thresholdG.toLong())
            val n = data.meals.size
            val line = ctx.resources.getQuantityString(R.plurals.protein_meals_reached, n, data.reached, n, threshold)
            setDots(data.meals.map { (it.proteinG ?: 0) >= data.thresholdG }, data.meals.map { !it.eaten })
            text(note, line)
            val items = data.meals.joinToString(", ") { m ->
                val g = m.proteinG
                if (m.eaten && g != null) {
                    ctx.getString(R.string.protein_meals_item, m.name, Fmt.number(g.toLong()))
                } else {
                    ctx.getString(R.string.protein_meals_item_none, m.name)
                }
            }
            val why = if (data.fromWeight) {
                ctx.getString(R.string.protein_meals_from_weight, threshold)
            } else {
                ctx.getString(R.string.protein_meals_no_weight, threshold)
            }
            text(coverage, items + "\n" + why)
            card.contentDescription = ctx.getString(R.string.food_card_description, ctx.getString(R.string.card_protein_per_meal), line, items)
        }
    }
}
