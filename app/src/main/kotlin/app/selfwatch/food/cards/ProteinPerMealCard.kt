/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.cards

import android.content.Context
import android.view.View
import android.view.ViewGroup
import app.selfwatch.R
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardStatusColors
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.PictureFrame
import app.selfwatch.cards.SmallFace
import app.selfwatch.cards.TrendPicture
import app.selfwatch.cards.TrendPictures
import app.selfwatch.db.Table
import app.selfwatch.ui.format.Fmt
import java.util.Locale

/**
 * Protein per meal (C8, card design standard): a bar per meal of the day shown (B, L, D, S) with the
 * threshold as the line: 0.25 g of protein per kg of body weight (clamped to 20–40 g; 20 g without a
 * weigh-in). Bars at the line are "good", under it "close". The number is the meals that reached it.
 * Small form: the number and the bars.
 */
object ProteinPerMealCard : Card<ProteinMeals>(
    "protein_per_meal",
    CardTab.FOOD,
    R.string.card_protein_per_meal,
    R.string.card_protein_per_meal_summary,
) {
    override val tables: Set<Table> = setOf(Table.DIARY, Table.MEAL_SLOT, Table.BODY)

    override suspend fun load(env: CardEnv): ProteinMeals = FoodCardLoader(env.db, env.clock).proteinMeals(env.day)

    override fun small(ctx: Context, data: ProteinMeals): SmallFace? {
        if (data.eaten == 0 || data.meals.isEmpty()) return null
        val n = data.meals.size
        return SmallFace(
            value = data.reached.toString(),
            label = ctx.resources.getQuantityString(R.plurals.small_meals_of, n, n),
            status = CardStatusColors.ofProgress(data.reached.toDouble(), n.toDouble()),
            note = ctx.getString(R.string.small_meal_threshold, Fmt.number(data.thresholdG.toLong())),
            progress = data.reached.toFloat() / n,
        )
    }

    /** The meals as bars (first letter under each) with the threshold line; null with nothing eaten. */
    fun picture(ctx: Context, data: ProteinMeals): TrendPicture? {
        if (data.eaten == 0 || data.meals.isEmpty()) return null
        return TrendPicture.Bars(
            labels = data.meals.map { it.name.take(1).uppercase(Locale.getDefault()) },
            values = data.meals.map { it.proteinG ?: 0 }.toIntArray(),
            goal = data.thresholdG.toFloat(),
            colorFor = TrendPictures.goalColors(ctx, data.thresholdG.toDouble()),
            description = describe(ctx, data),
        )
    }

    /** "Breakfast 28 g, Lunch 34 g, Dinner nothing yet…". */
    private fun describe(ctx: Context, data: ProteinMeals): String = data.meals.joinToString(", ") { m ->
        val g = m.proteinG
        if (m.eaten && g != null) ctx.getString(R.string.protein_meals_item, m.name, Fmt.number(g.toLong())) else ctx.getString(R.string.protein_meals_item_none, m.name)
    }

    override fun createSmallGraphic(ctx: Context): View = PictureFrame(ctx, small = true)

    override fun bindSmallGraphic(graphic: View, data: ProteinMeals) {
        (graphic as PictureFrame).show(picture(graphic.context, data))
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<ProteinMeals> = Holder(parent)

    private class Holder(parent: ViewGroup) :
        FoodMetricView<ProteinMeals>(parent, R.id.food_protein_per_meal_card, R.string.card_protein_per_meal) {

        override fun bind(data: ProteinMeals) {
            if (data.eaten == 0 || data.meals.isEmpty()) {
                showEmpty(ctx.getString(R.string.protein_meals_empty))
                return
            }
            val threshold = Fmt.number(data.thresholdG.toLong())
            val n = data.meals.size
            tile.value = data.reached.toString()
            tile.unit = ctx.getString(R.string.goal_lines_of_count, n)
            tile.label = ctx.getString(R.string.d2_tag_meals, threshold)
            showContent(picture(ctx, data))
            val line = ctx.resources.getQuantityString(R.plurals.protein_meals_reached, n, data.reached, n, threshold)
            card.contentDescription = ctx.getString(R.string.food_card_description, ctx.getString(R.string.card_protein_per_meal), line, describe(ctx, data))
        }
    }
}
