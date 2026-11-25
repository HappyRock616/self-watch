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
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.ui.format.Fmt

/**
 * Sodium (C25) for the day shown against 2,300 mg. Foods without a sodium value are counted, not
 * guessed: "4 of 6 foods have sodium data", and the total is then a floor.
 */
object SodiumCard : Card<SodiumDay>("sodium", CardTab.FOOD, R.string.card_sodium, R.string.card_sodium_summary) {

    override val tables: Set<Table> = setOf(Table.DIARY)

    override suspend fun load(env: CardEnv): SodiumDay = FoodCardLoader(env.db, env.clock).sodium(env.day)

    override fun small(ctx: Context, data: SodiumDay): SmallFace? {
        val c = data.coverage
        if (c.entries == 0) return null
        val total = c.sum ?: return null
        val limit = data.limitMg
        // A limit, not a goal: under it is on track, near it close, over it off.
        val status = when {
            total > limit -> CardStatus.OFF
            total >= limit * NEAR_LIMIT -> CardStatus.CLOSE
            else -> CardStatus.GOOD
        }
        return SmallFace(
            value = Fmt.number(total),
            label = ctx.getString(R.string.small_limit_mg, Fmt.number(limit.toLong())),
            unit = ctx.getString(R.string.food_card_unit_mg),
            status = status,
            note = if (total > limit) ctx.getString(R.string.small_sodium_over) else ctx.getString(R.string.small_mg_left, Fmt.number(limit - total)),
            progress = if (limit > 0) total.toFloat() / limit else null,
        )
    }

    private const val NEAR_LIMIT = 0.9

    override fun createView(host: CardHost, parent: ViewGroup): CardView<SodiumDay> = Holder(parent)

    private class Holder(parent: ViewGroup) : FoodMetricView<SodiumDay>(parent, R.id.food_sodium_card, R.string.card_sodium) {

        override fun bind(data: SodiumDay) {
            val c = data.coverage
            val mg = c.sum
            when {
                c.entries == 0 -> return showEmpty(ctx.getString(R.string.sodium_empty))
                mg == null -> return showEmpty(ctx.getString(R.string.sodium_none_known))
            }
            showContent()
            val total: Long = mg
            tile.value = Fmt.number(total)
            tile.unit = ctx.getString(R.string.food_card_unit_mg)
            val limit = ctx.getString(R.string.sodium_limit, Fmt.number(data.limitMg.toLong()))
            tile.label = limit
            val over = total > data.limitMg
            bar.set(
                total.toFloat(),
                maxOf(total.toFloat(), data.limitMg * BAR_HEADROOM),
                if (over) palette.alarm else palette.primary,
                limit = data.limitMg.toFloat(),
            )
            show(bar, true)
            val line = if (over) ctx.getString(R.string.sodium_over) else ctx.getString(R.string.sodium_left, Fmt.number(data.limitMg - total))
            text(note, line)
            val cov = FoodCardText.coverage(ctx, R.plurals.sodium_coverage, c)
            text(coverage, cov?.let { it + ". " + ctx.getString(R.string.nutrient_partial) })
            card.contentDescription = ctx.getString(
                R.string.food_card_description,
                ctx.getString(R.string.card_sodium),
                "${tile.value} ${tile.unit}, $limit",
                listOfNotNull(line, cov).joinToString(". "),
            )
        }

        private companion object {
            const val BAR_HEADROOM = 1.25f
        }
    }
}
