/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.cards

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.selfwatch.R
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.db.Table
import app.selfwatch.food.DiaryViewModel
import app.selfwatch.food.FoodViews
import app.selfwatch.food.diary.WaterDay
import app.selfwatch.food.diary.WaterService
import app.selfwatch.graph
import app.selfwatch.model.time.Day
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Fmt
import com.google.android.material.chip.ChipGroup
import app.selfwatch.ui.R as UiR

/**
 * Water for the diary day shown: one square per glass (250 ml) up to the goal; tapping an empty square
 * adds a glass, tapping the last full one takes it back.
 */
object WaterCard : Card<WaterDay>("water", CardTab.FOOD, R.string.card_water, R.string.card_water_summary) {

    override val tables: Set<Table> = setOf(Table.WATER, Table.GOAL)

    override suspend fun load(env: CardEnv): WaterDay = WaterService(env.db, env.clock).load(env.day)

    override fun createView(host: CardHost, parent: ViewGroup): CardView<WaterDay> = Holder(host, parent)

    /** Squares to show: enough for the goal (or 8) and one more than the glasses drunk, at most 16. */
    fun squares(w: WaterDay): Int {
        val glasses = w.totalMl / DiaryViewModel.GLASS_ML
        val forGoal = w.goalMl?.let { (it + DiaryViewModel.GLASS_ML - 1) / DiaryViewModel.GLASS_ML } ?: DiaryViewModel.DEFAULT_GLASSES
        return maxOf(forGoal, glasses + 1).coerceAtMost(MAX_SQUARES)
    }

    private const val MAX_SQUARES = 16

    private class Holder(private val host: CardHost, parent: ViewGroup) : CardView<WaterDay>(SwCard(parent.context)) {
        private val card = root as SwCard
        // ChipGroup is a flow layout: the squares wrap at large font sizes and on narrow screens.
        private val row = ChipGroup(parent.context).apply {
            chipSpacingHorizontal = 0
            chipSpacingVertical = 0
        }
        private val text = FoodViews.text(parent.context, UiR.style.TextAppearance_SelfWatch_Caption)
        private var day: Day? = null
        private var glasses = 0

        init {
            card.id = R.id.food_water_card
            card.title = parent.context.getString(R.string.food_water)
            card.addView(row)
            card.addView(text)
        }

        override fun bind(data: WaterDay) {
            val ctx = root.context
            day = data.day
            glasses = data.totalMl / DiaryViewModel.GLASS_ML
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
                    contentDescription = ctx.getString(if (full) R.string.food_glass_full else R.string.food_glass_empty, i + 1, squares)
                    setOnClickListener { tap(i) }
                    tag = "water_$i"
                }
                frame.addView(square, FrameLayout.LayoutParams(touch - 2 * inset, touch - 2 * inset, Gravity.CENTER))
                row.addView(frame, ViewGroup.LayoutParams(touch, touch))
            }
            val goal = data.goalMl
            text.text = if (goal != null) {
                ctx.getString(R.string.food_water_of_goal, Fmt.number(data.totalMl.toLong()), Fmt.number(goal.toLong()))
            } else {
                ctx.getString(R.string.food_water_ml, Fmt.number(data.totalMl.toLong()))
            }
        }

        /** Fills up to square [index], or empties it when it is the last full one. */
        private fun tap(index: Int) {
            val d = day ?: return
            val water = host.context.graph.food.water
            host.launch { if (index < glasses) water.undoLast(d) else water.add(DiaryViewModel.GLASS_ML, d) }
        }

        companion object {
            private const val INSET_DP = 10f
        }
    }
}
