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
import app.selfwatch.body.WeightDetailFragment
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardStatusColors
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.SmallFace
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

    override fun small(ctx: Context, data: InUnits<ProteinPerKg>): SmallFace? {
        val p = data.value
        if (p.kgX100 == null) {
            return SmallFace(
                value = ctx.getString(R.string.dash),
                label = ctx.getString(R.string.small_per_kg),
                unit = FoodCardText.gramsUnit(ctx, data.units),
                note = ctx.getString(R.string.small_needs_weight),
            )
        }
        val perKg = p.perKg ?: return null
        val protein = p.proteinG ?: return null
        val t = p.target.target
        val high = t.high
        // As on the large card: reaching the bottom of the target is on track (more protein is fine).
        return SmallFace(
            value = FoodCardText.perKg(perKg, data.units),
            label = if (high == null) {
                ctx.getString(R.string.small_aim_min, FoodCardText.perKg(t.low, data.units))
            } else {
                ctx.getString(R.string.small_aim_band, FoodCardText.perKg(t.low, data.units), FoodCardText.perKg(high, data.units))
            },
            unit = FoodCardText.gramsUnit(ctx, data.units),
            status = CardStatusColors.ofProgress(perKg, t.low),
            note = ctx.getString(R.string.small_protein_grams, Fmt.number(protein.toLong())),
            progress = if (t.low > 0) (perKg / t.low).toFloat() else null,
        )
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(GoalsFragment())
        return true
    }

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
            val u = data.units
            tile.value = FoodCardText.perKg(perKg, u)
            tile.unit = FoodCardText.gramsUnit(ctx, u)
            val target = FoodCardText.target(ctx, t, u)
            tile.label = target
            val reached = perKg >= t.low
            bar.set(
                perKg.toFloat(), FoodCardText.barMax(perKg, t),
                if (reached) palette.primary else palette.secondary,
                low = t.low.toFloat(), high = t.high?.toFloat(),
            )
            show(bar, true)
            // The target the reason names: "1.2 g/kg" or "1.2–2.0 g/kg" (per lb for US units).
            val top = t.high
            val amount = if (top == null) FoodCardText.grams(ctx, t.low, u) else FoodCardText.gramsRange(ctx, t.low, top, u)
            val reason = when (p.target.reason) {
                ProteinReason.LOSS -> ctx.getString(R.string.protein_kg_reason_loss, amount)
                ProteinReason.LOSS_STRENGTH -> ctx.getString(R.string.protein_kg_reason_loss_strength, amount)
                ProteinReason.MUSCLE -> ctx.getString(R.string.protein_kg_reason_muscle, amount)
                ProteinReason.TRAINING -> ctx.getString(R.string.protein_kg_reason_training, amount)
                ProteinReason.ACTIVE_AGING -> ctx.getString(R.string.protein_kg_reason_active_aging, amount)
                ProteinReason.GENERAL -> ctx.getString(R.string.protein_kg_reason_general, amount)
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
