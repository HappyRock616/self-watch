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
import app.selfwatch.cards.PictureFrame
import app.selfwatch.cards.SmallFace
import app.selfwatch.cards.TrendPicture
import app.selfwatch.db.Table
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.components.BarSeries
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Fmt
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import app.selfwatch.ui.R as UiR

/**
 * C3 Energy (card design standard): today's calories left (or in minus out) as the number, tagged with
 * in and out, over the last 7 days as bars (eaten and burned, day letters, the calorie goal as the line).
 * Small form: the number and the bars. Opens the calorie explainer.
 */
object EnergyCard : Card<EnergyWeek>("energy", CardTab.FOOD, R.string.card_energy, R.string.card_energy_summary) {

    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY, Table.DIARY, Table.GOAL, Table.SETTING, Table.BODY)

    override suspend fun load(env: CardEnv): EnergyWeek = BodyLoader(env.db, env.clock).energy()

    override val defaultSize: CardSize get() = CardSize.LARGE

    /** "in 1,820 · out 2,310", or null when neither is known. */
    private fun inOut(ctx: Context, e: Energy): String? {
        val eaten = e.inKcal
        val burned = e.outKcal
        if (eaten == null && burned == null) return null
        val dash = ctx.getString(R.string.dash)
        return ctx.getString(R.string.d2_tag_in_out, eaten?.let { Fmt.number(it.toLong()) } ?: dash, burned?.let { Fmt.number(it.toLong()) } ?: dash)
    }

    override fun small(ctx: Context, data: EnergyWeek): SmallFace? {
        val e = data.energy
        val kcal = ctx.getString(R.string.unit_kcal)
        val eaten = e.inKcal
        val burned = e.outKcal
        val inOut = inOut(ctx, e)
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

    /** The week as bars: eaten (primary) and burned (secondary) per day, the goal as the line; null when empty. */
    fun picture(ctx: Context, data: EnergyWeek): TrendPicture? {
        if (data.week.none { it.inKcal >= 0 || it.outKcal >= 0 }) return null
        val palette = ChartPalette.of(ctx)
        val loc = Locale.getDefault()
        return TrendPicture.Bars(
            labels = data.week.map { d -> Fmt.dayToDate(d.day).dayOfWeek.getDisplayName(TextStyle.NARROW, loc) },
            values = IntArray(0),
            goal = data.energy.goalKcal?.toFloat(),
            series = listOf(
                BarSeries(ctx.getString(R.string.body_in_label), palette.primary, data.week.map { it.inKcal }.toIntArray()),
                BarSeries(ctx.getString(R.string.body_out_label), palette.secondary, data.week.map { it.outKcal }.toIntArray()),
            ),
        )
    }

    override fun createSmallGraphic(ctx: Context): View = PictureFrame(ctx, small = true)

    override fun bindSmallGraphic(graphic: View, data: EnergyWeek) {
        (graphic as PictureFrame).show(picture(graphic.context, data))
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<EnergyWeek> = Holder(host, parent)

    private class Holder(private val host: CardHost, parent: ViewGroup) :
        CardView<EnergyWeek>(CardView.inflate(parent, R.layout.card_body_energy)) {
        private val card = root as SwCard
        private var energy = Energy(null, null, null, null)
        private val tile = find<StatTile>(R.id.energy_tile)
        private val week = find<PictureFrame>(R.id.energy_week)
        private val empty = find<EmptyState>(R.id.energy_empty)

        init {
            card.setOnClickListener { EnergySheet.show(host.fragment, energy) }
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
        }

        override fun bind(data: EnergyWeek) {
            val ctx = root.context
            val e = data.energy
            energy = e
            val face = small(ctx, data)
            val p = picture(ctx, data)
            val isEmpty = face == null && p == null
            empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
            tile.visibility = if (face == null) View.GONE else View.VISIBLE
            week.show(p)
            if (isEmpty) {
                card.contentDescription = listOf(ctx.getString(R.string.body_energy_today), ctx.getString(R.string.body_energy_empty)).joinToString(". ")
                return
            }
            if (face != null) {
                tile.value = face.value
                tile.unit = face.unit
                tile.label = inOut(ctx, e)?.takeIf { face.note != null } ?: face.label
            }
            val number = face?.let { "${it.value} ${it.unit ?: ""} ${it.label}" } ?: ctx.getString(R.string.dash)
            card.contentDescription = ctx.getString(R.string.d2_energy_description, number, inOut(ctx, e) ?: "")
        }
    }
}
