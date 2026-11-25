/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body.cards

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.selfwatch.R
import app.selfwatch.body.BodyLoader
import app.selfwatch.body.Energy
import app.selfwatch.body.EnergySheet
import app.selfwatch.body.EnergyWeek
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.components.BarSeries
import app.selfwatch.ui.components.BarsView
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * C3 Energy: calories in vs out today, the 7-day bars and the 7-day energy balance (labelled an
 * estimate); opens the calorie explainer.
 */
object EnergyCard : Card<EnergyWeek>("energy", CardTab.FOOD, R.string.card_energy, R.string.card_energy_summary) {

    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY, Table.DIARY, Table.GOAL, Table.SETTING, Table.BODY)

    override suspend fun load(env: CardEnv): EnergyWeek = BodyLoader(env.db, env.clock).energy()

    override val defaultSize: CardSize get() = CardSize.LARGE

    override fun small(ctx: Context, data: EnergyWeek): SmallFace? {
        val e = data.energy
        val kcal = ctx.getString(R.string.unit_kcal)
        val eaten = e.inKcal
        val burned = e.outKcal
        val inOut = listOfNotNull(
            eaten?.let { ctx.getString(R.string.body_in, Fmt.number(it.toLong())) },
            burned?.let { ctx.getString(R.string.body_out, Fmt.number(it.toLong())) },
        ).joinToString(" · ").ifEmpty { null }
        val remaining = e.remainingKcal
        val net = e.netKcal
        return when {
            remaining != null -> {
                val budget = (e.goalKcal ?: 0) + (e.activeKcal ?: 0)
                SmallFace(
                    value = Fmt.number(abs(remaining).toLong()),
                    label = ctx.getString(if (remaining >= 0) R.string.small_energy_left else R.string.small_energy_over),
                    unit = kcal,
                    note = inOut,
                    progress = if (budget > 0) (eaten ?: 0).toFloat() / budget else null,
                )
            }
            net != null -> {
                val sign = if (net < 0) "−" else if (net > 0) "+" else ""
                SmallFace(sign + Fmt.number(abs(net).toLong()), ctx.getString(R.string.small_energy_net), unit = kcal, note = inOut)
            }
            eaten != null -> SmallFace(Fmt.number(eaten.toLong()), ctx.getString(R.string.small_energy_eaten), unit = kcal)
            burned != null -> SmallFace(Fmt.number(burned.toLong()), ctx.getString(R.string.small_energy_burned), unit = kcal)
            else -> null
        }
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<EnergyWeek> = Holder(host, parent)

    private class Holder(private val host: CardHost, parent: ViewGroup) :
        CardView<EnergyWeek>(CardView.inflate(parent, R.layout.card_body_energy)) {
        private val card = root as SwCard
        private var energy = Energy(null, null, null, null)

        init {
            card.setOnClickListener { EnergySheet.show(host.fragment, energy) }
            card.onAction = { EnergySheet.show(host.fragment, energy) }
        }

        override fun bind(data: EnergyWeek) {
            val ctx = root.context
            val e = data.energy
            energy = e
            val labels = FmtLabels.from(ctx)
            val loc = Locale.getDefault()
            val palette = ChartPalette.of(ctx)
            val inText = find<TextView>(R.id.energy_in)
            val outText = find<TextView>(R.id.energy_out)
            // In = series 1 (primary), out = series 2 (secondary), as text colors that read on the card.
            inText.setTextColor(palette.primaryText)
            outText.setTextColor(palette.secondaryText)
            inText.text = e.inKcal?.let { ctx.getString(R.string.body_in, Fmt.number(it.toLong())) } ?: ctx.getString(R.string.body_in_empty)
            outText.text = e.outKcal?.let { ctx.getString(R.string.body_out, Fmt.number(it.toLong())) } ?: ctx.getString(R.string.body_out_empty)
            card.actionText = when {
                e.remainingKcal != null -> ctx.getString(R.string.body_left, Fmt.energy(e.remainingKcal ?: 0, loc, labels))
                e.netKcal != null -> Fmt.energy(e.netKcal ?: 0, loc, labels, signed = true)
                else -> ctx.getString(R.string.body_how)
            }
            val inBar = find<LinearProgressIndicator>(R.id.energy_in_bar)
            val outBar = find<LinearProgressIndicator>(R.id.energy_out_bar)
            val max = maxOf(e.inKcal ?: 0, e.outKcal ?: 0, e.goalKcal ?: 0, 1)
            inBar.max = max
            outBar.max = max
            inBar.setProgressCompat(e.inKcal ?: 0, false)
            outBar.setProgressCompat(e.outKcal ?: 0, false)

            val bars = find<BarsView>(R.id.energy_week)
            bars.labels = data.week.map { d -> Fmt.dayToDate(d.day).dayOfWeek.getDisplayName(TextStyle.NARROW, loc) }
            bars.series = listOf(
                BarSeries(ctx.getString(R.string.body_in_label), palette.primary, data.week.map { it.inKcal }.toIntArray()),
                BarSeries(ctx.getString(R.string.body_out_label), palette.secondary, data.week.map { it.outKcal }.toIntArray()),
            )
            bars.goalLine = e.goalKcal?.toFloat()
            val weekHasData = data.week.any { it.inKcal >= 0 || it.outKcal >= 0 }
            val empty = e.isEmpty && !weekHasData
            find<EmptyState>(R.id.energy_empty).visibility = if (empty) View.VISIBLE else View.GONE
            for (id in intArrayOf(R.id.energy_in_bar, R.id.energy_out_bar, R.id.energy_week, R.id.energy_caption)) {
                find<View>(id).visibility = if (empty) View.GONE else View.VISIBLE
            }
            // The 7-day balance, labelled as an estimate (energy maths is never exact).
            val balance = find<TextView>(R.id.energy_balance)
            val b = data.balanceKcal
            balance.text = b?.let { ctx.getString(R.string.energy_balance, Fmt.energy(it, loc, labels, signed = true)) }
            balance.visibility = if (b == null || empty) View.GONE else View.VISIBLE
            card.contentDescription = ctx.getString(R.string.body_energy_description, inText.text, outText.text, card.actionText ?: "")
        }
    }
}
