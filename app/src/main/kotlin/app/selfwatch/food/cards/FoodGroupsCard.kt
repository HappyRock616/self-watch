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
import androidx.annotation.StringRes
import app.selfwatch.R
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardStatusColors
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.GoalLine
import app.selfwatch.cards.PictureFrame
import app.selfwatch.cards.SmallFace
import app.selfwatch.cards.TrendPicture
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.components.SplitPart
import app.selfwatch.ui.components.StatTile
import app.selfwatch.db.Table
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.shell.Views
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Fmt

/** The five groups of a balanced plate, with the portions a day it suggests (MyPlate, about 2,000 kcal). */
enum class FoodGroup(@field:StringRes @get:StringRes val label: Int, val portions: Int) {
    FRUITS(R.string.food_group_fruits, 2),
    VEGETABLES(R.string.food_group_vegetables, 3),
    GRAINS(R.string.food_group_grains, 3),
    PROTEIN(R.string.food_group_protein, 2),
    DAIRY(R.string.food_group_dairy, 2),
}

/**
 * Which group a logged food belongs to, from the food category copied onto its diary line when it was
 * logged ([DiaryEntry.categoryId], foods.db v2 `category.id`, see `tools/fooddb/categories.csv`).
 * Categories outside the five groups (fats, sauces, meals, snacks and sweets, drinks, other) count as
 * none, and so do lines logged before categories were kept.
 */
object FoodGroupHook {
    /**
     * foods.db category id to group (MyPlate's groups). Potatoes count as vegetables; beans, lentils and
     * legumes too (MyPlate lists them in both, and foods.db files them under Fruits & vegetables); eggs,
     * nuts and seeds as protein foods; plant-based milks with dairy (MyPlate counts fortified soy milk there).
     */
    val CATEGORY_GROUPS: Map<Int, FoodGroup> = mapOf(
        101 to FoodGroup.FRUITS,
        102 to FoodGroup.VEGETABLES,
        103 to FoodGroup.VEGETABLES,
        104 to FoodGroup.VEGETABLES,
        201 to FoodGroup.GRAINS,
        202 to FoodGroup.GRAINS,
        203 to FoodGroup.GRAINS,
        301 to FoodGroup.DAIRY,
        302 to FoodGroup.DAIRY,
        303 to FoodGroup.DAIRY,
        304 to FoodGroup.PROTEIN,
        305 to FoodGroup.DAIRY,
        401 to FoodGroup.PROTEIN,
        402 to FoodGroup.PROTEIN,
        403 to FoodGroup.PROTEIN,
        404 to FoodGroup.PROTEIN,
        405 to FoodGroup.PROTEIN,
        406 to FoodGroup.PROTEIN,
    )

    /** The group of foods.db category [categoryId], or null (outside the five groups, or unknown). */
    fun groupOf(categoryId: Int?): FoodGroup? = categoryId?.let { CATEGORY_GROUPS[it] }

    /** The group of [entry], or null when unknown. */
    fun groupOf(entry: DiaryEntry): FoodGroup? = groupOf(entry.categoryId)
}

/**
 * Food groups (optional, Food; card design standard): fruits, vegetables, grains, protein foods and dairy
 * logged for the day shown, as a split bar of portions by group, with the groups that reached a balanced
 * plate's portions as the number. Each logged food counts as one portion of its group. Small form: the
 * number and the split bar.
 */
object FoodGroupsCard : Card<FoodGroupsCard.Data>("food_groups", CardTab.FOOD, R.string.card_food_groups, R.string.card_food_groups_summary) {

    /** Portions per group (empty when no logged food has a group yet) and whether anything is logged. */
    data class Data(val portions: Map<FoodGroup, Int>, val logged: Boolean)

    override val tables: Set<Table> = setOf(Table.DIARY)

    override suspend fun load(env: CardEnv): Data {
        val entries = env.db.diary.day(env.day)
        val grouped = entries.mapNotNull { FoodGroupHook.groupOf(it) }
        return Data(if (grouped.isEmpty()) emptyMap() else FoodGroup.entries.associateWith { g -> grouped.count { it == g } }, entries.isNotEmpty())
    }

    /** Groups at or above their portions. */
    fun groupsMet(portions: Map<FoodGroup, Int>): Int = FoodGroup.entries.count { (portions[it] ?: 0) >= it.portions }

    override fun small(ctx: Context, data: Data): SmallFace? {
        if (data.portions.isEmpty()) return null
        val met = groupsMet(data.portions)
        return SmallFace(
            value = met.toString(),
            unit = ctx.getString(R.string.goal_lines_of_count, FoodGroup.entries.size),
            label = ctx.getString(R.string.food_groups_label),
            status = CardStatusColors.ofProgress(met.toDouble(), FoodGroup.entries.size.toDouble()),
            progress = met.toFloat() / FoodGroup.entries.size,
        )
    }

    /** Portions by group as a split bar ("Fruits 2" under each part that fits); null with no group yet. */
    fun picture(ctx: Context, data: Data): TrendPicture? {
        if (data.portions.isEmpty() || data.portions.values.sum() == 0) return null
        val palette = ChartPalette.of(ctx)
        return TrendPicture.Split(
            FoodGroup.entries.mapIndexedNotNull { i, g ->
                val n = data.portions[g] ?: 0
                if (n == 0) null else SplitPart(n.toFloat(), palette.series(i), ctx.getString(g.label) + " " + n)
            },
        )
    }

    override fun createSmallGraphic(ctx: Context): View = PictureFrame(ctx, small = true)

    override fun bindSmallGraphic(graphic: View, data: Data) {
        (graphic as PictureFrame).show(picture(graphic.context, data))
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = object : CardView<Data>(SwCard(parent.context)) {
        private val ctx = parent.context
        private val tile = StatTile(ctx).apply { setValueAppearance(app.selfwatch.ui.R.style.TextAppearance_SelfWatch_Display_Number) }
        private val split = PictureFrame(ctx, small = false)
        private val empty = EmptyState(ctx)

        init {
            (root as SwCard).title = ctx.getString(R.string.card_food_groups)
            root.addView(tile, Views.matchWrap(ctx))
            root.addView(split, Views.matchWrap(ctx, 8))
            root.addView(empty, Views.matchWrap(ctx))
        }

        override fun bind(data: Data) {
            val none = data.portions.isEmpty()
            empty.visibility = if (none) View.VISIBLE else View.GONE
            tile.visibility = if (none) View.GONE else View.VISIBLE
            empty.message = ctx.getString(if (data.logged) R.string.food_groups_waiting else R.string.food_groups_empty)
            if (none) {
                split.show(null)
                root.contentDescription = listOf(ctx.getString(R.string.card_food_groups), empty.message).joinToString(". ")
                return
            }
            val met = groupsMet(data.portions)
            tile.value = met.toString()
            tile.unit = ctx.getString(R.string.goal_lines_of_count, FoodGroup.entries.size)
            tile.label = ctx.getString(R.string.food_groups_label)
            split.show(picture(ctx, data))
            val spoken = FoodGroup.entries.map { g ->
                val n = data.portions[g] ?: 0
                val line = GoalLine(app.selfwatch.cards.GoalMetric.WORKOUTS, n, g.portions)
                listOfNotNull(
                    ctx.getString(g.label),
                    ctx.getString(R.string.goal_line_of, Fmt.number(n.toLong()), Fmt.number(g.portions.toLong())),
                    CardStatusColors.spoken(ctx, line.status),
                ).joinToString(" ")
            }
            root.contentDescription = (listOf(ctx.getString(R.string.card_food_groups), "${tile.value} ${tile.unit} ${tile.label}") + spoken).joinToString(". ")
        }
    }
}
