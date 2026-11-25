/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import android.content.Context
import android.view.View
import app.selfwatch.R
import app.selfwatch.food.nutrition.TargetStatus
import app.selfwatch.model.food.Nutrients.Field
import app.selfwatch.shell.Views
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import app.selfwatch.ui.R as UiR

/** The nutrient rows of Day nutrition, shared with the Meal summary. */
internal object NutrientRows {

    /**
     * One nutrient: name and amount (against its target, or what is left when [showLeft]), a bar when
     * there is a target, and how many of the foods know it ("Fiber known for 2 of 3 foods"). [onClick]
     * null makes the row plain.
     */
    fun row(ctx: Context, s: DayNutritionState, f: Field, showLeft: Boolean, onClick: (() -> Unit)?): View {
        val res = ctx.resources
        val v = s.totals[f]
        val t = s.targets[f]
        val name = ctx.getString(NutrientUi.name(f))
        val col = FoodViews.column(ctx).apply {
            tag = "nutrient_${f.name.lowercase()}"
            minimumHeight = res.getDimensionPixelSize(UiR.dimen.sw_touch_target)
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                val out = android.util.TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                setBackgroundResource(out.resourceId)
                setOnClickListener { onClick() }
            }
        }
        val line = FoodViews.row(ctx)
        line.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, name), FoodViews.weight())
        val ref = t?.reference
        val valueText = when {
            showLeft && ref != null -> {
                val left = ref - (v ?: 0)
                if (left >= 0) {
                    ctx.getString(R.string.day_nutrition_left_value, NutrientUi.amount(ctx, f, left))
                } else {
                    ctx.getString(R.string.day_nutrition_over_value, NutrientUi.amount(ctx, f, -left))
                }
            }
            ref != null && t.limit != null -> NutrientUi.amount(ctx, f, v) + " · " + ctx.getString(R.string.day_nutrition_limit, NutrientUi.amount(ctx, f, ref))
            ref != null -> NutrientUi.amount(ctx, f, v) + " " + ctx.getString(R.string.day_nutrition_target, NutrientUi.amount(ctx, f, ref))
            else -> NutrientUi.amount(ctx, f, v)
        }
        // Name and value share the row and wrap, so neither is squeezed out at large font sizes.
        line.addView(
            FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Body_Number, valueText).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_END },
            FoodViews.weight(),
        )
        col.addView(line)
        val status = t?.status(v) ?: TargetStatus.NONE
        if (ref != null) {
            val bar = NutrientBarView(ctx)
            bar.set((v ?: 0).toFloat() / ref.coerceAtLeast(1), NutrientUi.statusColor(ctx, status))
            bar.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            col.addView(bar, Views.matchWrap(ctx, 2))
        }
        val notes = ArrayList<String>(2)
        when (status) {
            TargetStatus.OVER -> notes.add(ctx.getString(R.string.status_over))
            TargetStatus.NEAR_LIMIT -> notes.add(ctx.getString(R.string.status_near_limit))
            else -> Unit
        }
        val known = s.known(f)
        if (known < s.entries.size) notes.add(ctx.getString(R.string.day_nutrition_known, name, known, s.entries.size))
        if (notes.isNotEmpty()) {
            val note = Views.caption(ctx, notes.joinToString(" · "))
            if (status == TargetStatus.OVER) note.setTextColor(NutrientUi.alarmText(ctx))
            col.addView(note, Views.matchWrap(ctx))
        }
        val pct = t?.percent(v)
        col.contentDescription = buildString {
            append(
                when {
                    v == null -> ctx.getString(R.string.nutrient_unknown_description, name)
                    pct != null -> ctx.getString(R.string.nutrient_row_description, name, NutrientUi.amount(ctx, f, v), pct.toString())
                    else -> ctx.getString(R.string.nutrient_row_description_plain, name, NutrientUi.amount(ctx, f, v))
                },
            )
            if (notes.isNotEmpty()) append(". ").append(notes.joinToString(". "))
        }
        return col
    }

    /** The foods behind [f], most first, in a dialog. */
    fun showTopFoods(ctx: Context, s: DayNutritionState, f: Field) {
        val name = ctx.getString(NutrientUi.name(f))
        val top = s.topFoods(f)
        val b = MaterialAlertDialogBuilder(ctx).setTitle(ctx.getString(R.string.day_nutrition_top_foods, name.lowercase()))
        if (top.isEmpty()) {
            b.setMessage(ctx.getString(R.string.day_nutrition_no_foods, name.lowercase()))
        } else {
            b.setItems(top.map { ctx.getString(R.string.day_nutrition_top_food_row, it.name, NutrientUi.amount(ctx, f, it.nutrients[f])) }.toTypedArray(), null)
        }
        b.setPositiveButton(android.R.string.ok, null).show()
    }
}
