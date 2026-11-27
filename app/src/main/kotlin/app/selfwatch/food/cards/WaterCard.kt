/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.cards

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.selfwatch.R
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardStatusColors
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.PictureFrame
import app.selfwatch.cards.TrendPicture
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.food.FoodViews
import app.selfwatch.food.WaterGlass
import app.selfwatch.food.diary.WaterDay
import app.selfwatch.food.diary.WaterService
import app.selfwatch.graph
import app.selfwatch.model.time.Day
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Units
import com.google.android.material.chip.ChipGroup
import app.selfwatch.ui.R as UiR

/**
 * Water (card design standard: glasses) for the diary day shown, in glasses of the user's units ([WaterGlass]: 12 fl oz or 350 ml): one
 * square per glass up to the goal (8 glasses until one is set); tapping an empty square adds a glass,
 * tapping the last full one takes it back.
 */
object WaterCard : Card<WaterCard.Data>("water", CardTab.FOOD, R.string.card_water, R.string.card_water_summary) {

    /** The day's water and the display units. */
    data class Data(val water: WaterDay, val units: Units) {
        /** The goal in force, or the default of 8 glasses. */
        val goalMl: Int get() = WaterGlass.goalMl(water.goalMl, units)
    }

    override val tables: Set<Table> = setOf(Table.WATER, Table.GOAL)

    override suspend fun load(env: CardEnv): Data = Data(WaterService(env.db, env.clock).load(env.day), env.units)

    override fun small(ctx: Context, data: Data): SmallFace {
        val goal = data.goalMl
        val total = data.water.totalMl
        val goalGlasses = WaterGlass.goalGlasses(goal, data.units)
        return SmallFace(
            value = WaterGlass.glasses(total, data.units).toString(),
            label = WaterGlass.amountOfGoal(ctx, total, goal, data.units),
            unit = ctx.resources.getQuantityString(R.plurals.water_glasses_unit, goalGlasses, goalGlasses),
            status = if (data.water.goalMl != null || total > 0) CardStatusColors.ofProgress(total.toDouble(), goal.toDouble()) else CardStatus.NONE,
            progress = total.toFloat() / goal,
        )
    }

    /** The small form's glasses: one dot per glass up to the goal, filled for each glass drunk. */
    fun picture(d: Data): TrendPicture {
        val glasses = WaterGlass.glasses(d.water.totalMl, d.units)
        val n = WaterGlass.goalGlasses(d.goalMl, d.units).coerceIn(1, MAX_SQUARES)
        return TrendPicture.Dots(BooleanArray(n) { it < glasses }, emptyList())
    }

    override fun createSmallGraphic(ctx: Context): View = PictureFrame(ctx, small = true)

    override fun bindSmallGraphic(graphic: View, data: Data) {
        (graphic as PictureFrame).show(picture(data))
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    /** Squares to show: enough for the goal and one more than the glasses drunk, at most 16. */
    fun squares(d: Data): Int {
        val glasses = WaterGlass.glasses(d.water.totalMl, d.units)
        val forGoal = WaterGlass.goalGlasses(d.goalMl, d.units)
        return maxOf(forGoal, glasses + 1).coerceAtMost(MAX_SQUARES)
    }

    private const val MAX_SQUARES = 16

    private class Holder(private val host: CardHost, parent: ViewGroup) : CardView<Data>(SwCard(parent.context)) {
        private val card = root as SwCard
        // ChipGroup is a flow layout: the squares wrap at large font sizes and on narrow screens.
        private val row = ChipGroup(parent.context).apply {
            chipSpacingHorizontal = 0
            chipSpacingVertical = 0
        }
        private val text = FoodViews.text(parent.context, UiR.style.TextAppearance_SelfWatch_Caption)
        private var day: Day? = null
        private var glasses = 0
        private var glassMl = WaterGlass.METRIC_ML

        init {
            card.id = R.id.food_water_card
            card.title = parent.context.getString(R.string.food_water)
            card.addView(row)
            card.addView(text)
        }

        override fun bind(data: Data) {
            val ctx = root.context
            day = data.water.day
            glasses = WaterGlass.glasses(data.water.totalMl, data.units)
            glassMl = WaterGlass.ml(data.units)
            val glass = WaterGlass.volume(ctx, glassMl, data.units)
            val squares = squares(data)
            row.removeAllViews()
            val touch = ctx.resources.getDimensionPixelSize(UiR.dimen.sw_touch_target)
            val inset = FoodViews.dp(ctx, INSET_DP)
            for (i in 0 until squares) {
                val full = i < glasses
                val square = View(ctx).apply {
                    setBackgroundResource(R.drawable.water_glass)
                    isSelected = full
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                val frame = FrameLayout(ctx).apply {
                    contentDescription = if (full) ctx.getString(R.string.food_glass_full, i + 1, squares) else ctx.getString(R.string.water_glass_empty, i + 1, squares, glass)
                    setOnClickListener { tap(i) }
                    tag = "water_$i"
                }
                frame.addView(square, FrameLayout.LayoutParams(touch - 2 * inset, touch - 2 * inset, Gravity.CENTER))
                row.addView(frame, ViewGroup.LayoutParams(touch, touch))
            }
            text.text = WaterGlass.ofGoal(ctx, data.water.totalMl, data.goalMl, data.units)
        }

        /** Fills up to square [index], or empties it when it is the last full one. */
        private fun tap(index: Int) {
            val d = day ?: return
            val water = host.context.graph.food.water
            host.launch { if (index < glasses) water.undoLast(d) else water.add(glassMl, d) }
        }

        companion object {
            private const val INSET_DP = 10f
        }
    }
}
