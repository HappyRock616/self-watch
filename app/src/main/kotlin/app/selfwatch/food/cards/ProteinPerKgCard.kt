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
import app.selfwatch.body.WeightDetailFragment
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.db.Table
import app.selfwatch.me.GoalsFragment
import app.selfwatch.ui.format.Fmt

/**
 * Protein per kg (C7): protein for the day shown ÷ the latest weight, against the target for the main
 * goal (lose weight ≥ 1.2, or 1.6 with strength training this week; build muscle 1.6; athletic and
 * endurance 1.2–2.0 as a band; stay active 1.0–1.2; others 1.2). A protein goal the user set wins.
 * Opens Goals.
 */
object ProteinPerKgCard : Card<InUnits<ProteinPerKg>>(
    "protein_per_kg",
    CardTab.FOOD,
    R.string.card_protein_per_kg,
    R.string.card_protein_per_kg_summary,
) {
    override val tables: Set<Table> = setOf(Table.DIARY, Table.BODY, Table.GOAL, Table.WORKOUT, Table.SETTING)

    override suspend fun load(env: CardEnv): InUnits<ProteinPerKg> =
        InUnits(FoodCardLoader(env.db, env.clock).proteinPerKg(env.day), env.units)

    override fun createView(host: CardHost, parent: ViewGroup): CardView<InUnits<ProteinPerKg>> = Holder(host, parent)

    private class Holder(host: CardHost, parent: ViewGroup) :
        FoodMetricView<InUnits<ProteinPerKg>>(parent, R.id.food_protein_per_kg_card, R.string.card_protein_per_kg) {

        init {
            card.isClickable = true
            card.setOnClickListener { host.open(GoalsFragment()) }
            empty.buttonText = null
            empty.onButton = { host.open(WeightDetailFragment()) }
        }

        override fun bind(data: InUnits<ProteinPerKg>) {
            val p = data.value
            val kg = p.kgX100
            if (kg == null) {
                empty.buttonText = ctx.getString(R.string.food_card_log_weight)
                showEmpty(ctx.getString(R.string.food_card_no_weight))
                return
            }
            val perKg = p.perKg
            if (perKg == null || p.proteinG == null) {
                empty.buttonText = null
                showEmpty(ctx.getString(R.string.protein_kg_empty))
                return
            }
            showContent()
            val t = p.target.target
            tile.value = FoodCardText.perKg(perKg)
            tile.unit = ctx.getString(R.string.food_card_unit_g_per_kg)
            val target = FoodCardText.target(ctx, t)
            tile.label = target
            val reached = perKg >= t.low
            bar.set(
                perKg.toFloat(), FoodCardText.barMax(perKg, t),
                if (reached) palette.primary else palette.secondary,
                low = t.low.toFloat(), high = t.high?.toFloat(),
            )
            show(bar, true)
            val reason = when (p.target.reason) {
                ProteinReason.LOSS -> ctx.getString(R.string.protein_kg_reason_loss)
                ProteinReason.LOSS_STRENGTH -> ctx.getString(R.string.protein_kg_reason_loss_strength)
                ProteinReason.MUSCLE -> ctx.getString(R.string.protein_kg_reason_muscle)
                ProteinReason.TRAINING -> ctx.getString(R.string.protein_kg_reason_training)
                ProteinReason.ACTIVE_AGING -> ctx.getString(R.string.protein_kg_reason_active_aging)
                ProteinReason.GENERAL -> ctx.getString(R.string.protein_kg_reason_general)
                ProteinReason.OWN_GOAL -> ctx.getString(R.string.protein_kg_reason_own, Fmt.number((p.goalG ?: 0).toLong()))
            }
            text(note, reason)
            text(coverage, ctx.getString(R.string.protein_kg_inputs, Fmt.number(p.proteinG.toLong()), Fmt.weight(kg, data.units)))
            card.contentDescription = ctx.getString(
                R.string.food_card_description,
                ctx.getString(R.string.card_protein_per_kg),
                "${tile.value} ${tile.unit}",
                "$target. $reason",
            )
        }
    }
}
