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
import app.selfwatch.body.BodyLoader
import app.selfwatch.body.MacrosInfo
import app.selfwatch.body.cards.MacrosTables
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardStatusColors
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.PictureFrame
import app.selfwatch.cards.SmallFace
import app.selfwatch.cards.TrendPicture
import app.selfwatch.db.Table
import app.selfwatch.me.GoalsFragment
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.LevelColors
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.RingItem
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Fmt

/**
 * Protein, carbs and fat for the diary day shown (card design standard): three rings toward the targets,
 * grams inside (met = good, short = close; a ring without a target stays empty). Small form: the rings.
 * Opens Goals.
 */
object MacrosTodayCard : Card<MacrosInfo>(
    "macros_today",
    CardTab.FOOD,
    R.string.card_macros_today,
    R.string.card_macros_today_summary,
) {
    override val tables: Set<Table> = MacrosTables.ALL

    override suspend fun load(env: CardEnv): MacrosInfo = BodyLoader(env.db, env.clock).macros(env.day)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override fun small(ctx: Context, data: MacrosInfo): SmallFace? {
        if (data.isEmpty) return null
        val protein = data.proteinG ?: 0
        val goal = data.proteinGoalG
        val dash = ctx.getString(R.string.dash)
        return SmallFace(
            value = Fmt.number(protein.toLong()),
            label = if (goal != null) ctx.getString(R.string.small_protein_of, Fmt.number(goal.toLong())) else ctx.getString(R.string.macros_protein),
            unit = ctx.getString(R.string.unit_g),
            status = if (goal != null) CardStatusColors.ofProgress(protein.toDouble(), goal.toDouble()) else CardStatus.NONE,
            note = ctx.getString(
                R.string.small_macros_note,
                data.carbG?.let { Fmt.number(it.toLong()) } ?: dash,
                data.fatG?.let { Fmt.number(it.toLong()) } ?: dash,
            ),
            progress = goal?.takeIf { it > 0 }?.let { protein.toFloat() / it },
        )
    }

    /** The three rings, or null with nothing logged. */
    fun picture(ctx: Context, data: MacrosInfo): TrendPicture? {
        if (data.isEmpty) return null
        val c = LevelColors.of(ctx)
        fun ring(g: Int?, goal: Int?, label: Int) = RingItem(
            progress = goal?.takeIf { it > 0 }?.let { (g ?: 0).toFloat() / it } ?: 0f,
            color = if (goal != null && (g ?: 0) >= goal) c.good else c.close,
            value = g?.let { Fmt.number(it.toLong()) } ?: ctx.getString(R.string.dash),
            label = ctx.getString(label),
        )
        return TrendPicture.Rings(
            listOf(
                ring(data.proteinG, data.proteinGoalG, R.string.balance_protein),
                ring(data.carbG, data.carbGoalG, R.string.balance_carbs),
                ring(data.fatG, data.fatGoalG, R.string.balance_fat),
            ),
        )
    }

    override fun createSmallGraphic(ctx: Context): View = PictureFrame(ctx, small = true)

    override val smallGraphicShowsValue: Boolean = true

    override fun bindSmallGraphic(graphic: View, data: MacrosInfo) {
        (graphic as PictureFrame).show(picture(graphic.context, data))
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(GoalsFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<MacrosInfo> = Holder(host, parent)

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<MacrosInfo>(SwCard(parent.context)) {
        private val ctx = parent.context
        private val rings = PictureFrame(ctx, small = false)
        private val empty = EmptyState(ctx).apply { message = ctx.getString(R.string.macros_empty) }

        init {
            val sw = root as SwCard
            sw.id = R.id.food_macros_card
            sw.title = ctx.getString(R.string.card_macros_today)
            sw.isClickable = true
            sw.isFocusable = true
            sw.setOnClickListener { host.open(GoalsFragment()) }
            sw.addView(rings, Views.matchWrap(ctx))
            sw.addView(empty, Views.matchWrap(ctx))
        }

        override fun bind(data: MacrosInfo) {
            rings.show(picture(ctx, data))
            empty.visibility = if (data.isEmpty) View.VISIBLE else View.GONE
            fun g(v: Int?): String = ctx.getString(R.string.grams_value, Fmt.number((v ?: 0).toLong()))
            root.contentDescription = ctx.getString(R.string.macros_today_description, g(data.proteinG), g(data.carbG), g(data.fatG))
        }
    }
}
