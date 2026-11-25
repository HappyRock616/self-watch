/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import android.view.View
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.selfwatch.R
import app.selfwatch.food.source.FoodHit
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.time.Day
import app.selfwatch.ui.R as UiR

/** "Recent for <meal>" (Food tab meal cards and the Meal summary): food rows with a one-tap +. */
internal object RecentStrip {

    /**
     * The strip for meal [slotId] (shown as [mealName]) on [day]: a heading, then [recents] as food
     * rows. Tapping a row opens its detail (logging into this meal); the + calls [onLog] with the
     * food's last diary line (logged with its last serving).
     */
    fun build(
        parentFragments: FragmentManager,
        ctx: android.content.Context,
        day: Day,
        slotId: Long,
        mealName: String,
        recents: List<DiaryEntry>,
        onLog: (DiaryEntry) -> Unit,
    ): View {
        val col = FoodViews.column(ctx).apply { tag = "recent_$slotId" }
        col.addView(
            FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, ctx.getString(R.string.food_recent_for, mealName)).also {
                ViewCompat.setAccessibilityHeading(it, true)
            },
            FoodViews.matchWrap().apply { topMargin = ctx.resources.getDimensionPixelSize(UiR.dimen.sw_space_1) },
        )
        val byRef = recents.associateBy { it.food }
        val hits = recents.mapNotNull { e ->
            val ref = e.food ?: return@mapNotNull null
            FoodHit(ref, e.name, e.brand, e.nutrients.kcalX10, FoodFragment.amountText(ctx, e), FoodRows.tagFor(ref.origin), 0)
        }
        val adapter = FoodHitAdapter(
            onOpen = { hit -> FoodDetailSheet.forFood(hit.ref, day, slotId).show(parentFragments, FoodDetailSheet.TAG) },
            onPlus = { hit -> byRef[hit.ref]?.let(onLog) },
        )
        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            isNestedScrollingEnabled = false
            itemAnimator = null
            this.adapter = adapter
        }
        adapter.submitList(hits)
        col.addView(list, FoodViews.matchWrap())
        return col
    }
}
