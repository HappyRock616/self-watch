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
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardStatusColors
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.PictureFrame
import app.selfwatch.cards.SmallFace
import app.selfwatch.cards.TrendPicture
import app.selfwatch.db.Table
import app.selfwatch.ui.charts.LevelColors
import app.selfwatch.ui.format.Fmt

/**
 * Fiber (C26, card design standard) for the day shown: a ring toward 14 g per 1,000 kcal of the calorie
 * goal with the grams inside. Foods without a fiber value are counted for TalkBack ("x of y foods have
 * fiber data"). Small form: the ring.
 */
object FibreCard : Card<FibreDay>("fibre", CardTab.FOOD, R.string.card_fibre, R.string.card_fibre_summary) {

    override val tables: Set<Table> = setOf(Table.DIARY)

    override suspend fun load(env: CardEnv): FibreDay = FoodCardLoader(env.db, env.clock).fibre(env.day)

    override fun small(ctx: Context, data: FibreDay): SmallFace? {
        val c = data.coverage
        if (c.entries == 0) return null
        val grams = FoodCardLoader.grams(c.sum) ?: return null
        val target = data.targetG
        return SmallFace(
            value = Fmt.number(grams.toLong()),
            label = if (target != null) ctx.getString(R.string.small_of_g, Fmt.number(target.toLong())) else ctx.getString(R.string.small_fiber_logged),
            unit = ctx.getString(R.string.unit_g),
            status = if (target != null) CardStatusColors.ofProgress(grams.toDouble(), target.toDouble()) else CardStatus.NONE,
            note = when {
                target == null -> null
                grams >= target -> ctx.getString(R.string.fibre_reached)
                else -> ctx.getString(R.string.fibre_left, Fmt.number((target - grams).toLong()))
            },
            progress = target?.takeIf { it > 0 }?.let { grams.toFloat() / it },
        )
    }

    /** The ring: grams inside, toward the target (met = good, short = close), or null with nothing known. */
    fun picture(ctx: Context, data: FibreDay): TrendPicture? {
        val grams = FoodCardLoader.grams(data.coverage.sum ?: return null) ?: return null
        val target = data.targetG
        val c = LevelColors.of(ctx)
        val sub = if (target != null) ctx.getString(R.string.small_of_g, Fmt.number(target.toLong())) else ctx.getString(R.string.unit_g)
        return TrendPicture.Ring(
            progress = target?.takeIf { it > 0 }?.let { grams.toFloat() / it } ?: 0f,
            color = if (target != null && grams >= target) c.good else c.close,
            center = Fmt.number(grams.toLong()),
            sub = sub,
        )
    }

    override fun createSmallGraphic(ctx: Context): View = PictureFrame(ctx, small = true)

    override val smallGraphicShowsValue: Boolean = true

    override fun bindSmallGraphic(graphic: View, data: FibreDay) {
        (graphic as PictureFrame).show(picture(graphic.context, data))
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<FibreDay> = Holder(parent)

    private class Holder(parent: ViewGroup) : FoodMetricView<FibreDay>(parent, R.id.food_fibre_card, R.string.card_fibre) {

        override fun bind(data: FibreDay) {
            val c = data.coverage
            val x10 = c.sum
            when {
                c.entries == 0 -> return showEmpty(ctx.getString(R.string.fibre_empty))
                x10 == null -> return showEmpty(ctx.getString(R.string.fibre_none_known))
            }
            val grams = FoodCardLoader.grams(x10) ?: 0
            tile.value = Fmt.number(grams.toLong())
            tile.unit = ctx.getString(R.string.unit_g)
            val target = data.targetG
            val label = if (target != null) ctx.getString(R.string.fibre_target, Fmt.number(target.toLong())) else ctx.getString(R.string.fibre_target_none)
            tile.label = label
            showContent(picture(ctx, data))
            val line = when {
                target == null -> null
                grams >= target -> ctx.getString(R.string.fibre_reached)
                else -> ctx.getString(R.string.fibre_left, Fmt.number((target - grams).toLong()))
            }
            val cov = FoodCardText.coverage(ctx, R.plurals.fibre_coverage, c)
            card.contentDescription = ctx.getString(
                R.string.food_card_description,
                ctx.getString(R.string.card_fibre),
                "${tile.value} ${tile.unit}, $label",
                listOfNotNull(line, cov).joinToString(". "),
            )
        }
    }
}
