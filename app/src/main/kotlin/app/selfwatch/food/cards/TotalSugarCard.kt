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
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.ui.format.Fmt

/**
 * Total sugar (C27, a swap-in outside the default stacks) for the day shown and its share of the
 * calories logged. Labelled "total sugar": added sugar cannot be measured from labels, so there is no
 * limit line.
 */
object TotalSugarCard : Card<SugarDay>("total_sugar", CardTab.FOOD, R.string.card_total_sugar, R.string.card_total_sugar_summary) {

    override val tables: Set<Table> = setOf(Table.DIARY)

    override suspend fun load(env: CardEnv): SugarDay = FoodCardLoader(env.db, env.clock).sugar(env.day)

    override fun small(ctx: Context, data: SugarDay): SmallFace? {
        val c = data.coverage
        if (c.entries == 0) return null
        val x10 = c.sum ?: return null
        return SmallFace(
            value = Fmt.number((FoodCardLoader.grams(x10) ?: 0).toLong()),
            label = ctx.getString(R.string.small_sugar_label),
            unit = ctx.getString(R.string.unit_g),
            note = kcalShare(x10, data.kcal)?.let { ctx.getString(R.string.small_sugar_share, Fmt.percent(it)) },
        )
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<SugarDay> = Holder(parent)

    /** Share of [kcal] that [sugarX10] tenths of a gram of sugar make up (4 kcal per g), or null. */
    fun kcalShare(sugarX10: Long, kcal: Int?): Double? = kcal?.takeIf { it > 0 }?.let { sugarX10 / 10.0 * KCAL_PER_G / it }

    private const val KCAL_PER_G = 4

    private class Holder(parent: ViewGroup) : FoodMetricView<SugarDay>(parent, R.id.food_total_sugar_card, R.string.card_total_sugar) {

        override fun bind(data: SugarDay) {
            val c = data.coverage
            val x10 = c.sum
            when {
                c.entries == 0 -> return showEmpty(ctx.getString(R.string.total_sugar_empty))
                x10 == null -> return showEmpty(ctx.getString(R.string.total_sugar_none_known))
            }
            showContent()
            tile.value = Fmt.number((FoodCardLoader.grams(x10) ?: 0).toLong())
            tile.unit = ctx.getString(R.string.unit_g)
            tile.label = ctx.getString(R.string.total_sugar_label)
            val share = kcalShare(x10, data.kcal)
            val line = listOfNotNull(
                share?.let { ctx.getString(R.string.total_sugar_share, Fmt.percent(it)) },
                ctx.getString(R.string.total_sugar_note),
            ).joinToString(" ")
            text(note, line)
            val cov = FoodCardText.coverage(ctx, R.plurals.total_sugar_coverage, c)
            text(coverage, cov?.let { it + ". " + ctx.getString(R.string.nutrient_partial) })
            card.contentDescription = ctx.getString(
                R.string.food_card_description,
                ctx.getString(R.string.card_total_sugar),
                "${tile.value} ${tile.unit}",
                listOfNotNull(line, cov).joinToString(". "),
            )
        }
    }
}
