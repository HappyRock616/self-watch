/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.selfwatch.R
import app.selfwatch.food.nutrition.DietPrefs
import app.selfwatch.food.source.FoodHit
import app.selfwatch.model.food.FoodRef
import app.selfwatch.ui.components.ListRow
import app.selfwatch.ui.components.SourceTag
import com.google.android.material.button.MaterialButton

/**
 * Food rows: name with a source pill, "brand · serving · kcal" below, and a round "+" at the end
 * (hidden when [onPlus] is null). Tapping the row calls [onOpen]. A red badge ("Contains gluten",
 * "Not vegan") shows only when a food is known to clash with [prefs]; there is no badge otherwise.
 */
class FoodHitAdapter(
    private val onOpen: (FoodHit) -> Unit,
    private val onPlus: ((FoodHit) -> Unit)?,
) : ListAdapter<FoodHit, FoodHitAdapter.Holder>(Diff) {

    /** The user's diets and allergens; set it before [submitList] (rows rebind when it changes). */
    var prefs: DietPrefs = DietPrefs.NONE
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    /** Rows whose diet data is unknown for the active Add food filters (a "?" badge); set before [submitList]. */
    var unsure: Set<FoodRef> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    /** One row. */
    class Holder(val row: ListRow, val tag: SourceTag, val conflict: DietBadgeView, val unknown: DietBadgeView, val plus: MaterialButton?) : RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val row = ListRow(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            showChevron = false
        }
        val tag = SourceTag(ctx)
        row.addTitleAccessory(tag)
        val conflict = DietBadgeView(ctx).apply {
            this.conflict = true
            visibility = android.view.View.GONE
        }
        row.addTitleAccessory(conflict)
        val unknown = DietBadgeView(ctx).apply {
            text = ctx.getString(R.string.filter_unknown_badge)
            this.tag = "diet_unknown"
            visibility = android.view.View.GONE
        }
        row.addTitleAccessory(unknown)
        val plus = onPlus?.let { FoodViews.plusButton(ctx, "") {} }
        if (plus != null) row.addTrailingAccessory(plus)
        return Holder(row, tag, conflict, unknown, plus)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val hit = getItem(position)
        val ctx = holder.row.context
        holder.row.title = hit.name
        holder.row.subtitle = FoodViews.hitSubtitle(ctx, hit)
        val (label, style) = FoodViews.tagOf(hit)
        holder.tag.text = ctx.getString(label)
        holder.tag.style = style
        holder.row.onClick = { onOpen(hit) }
        holder.row.showChevron = false
        val clash = prefs.conflicts(hit.dietInfo).firstOrNull()?.let { NutrientUi.conflictText(ctx, it) }
        holder.conflict.text = clash
        FoodViews.show(holder.conflict, clash != null)
        val isUnsure = clash == null && hit.ref in unsure
        FoodViews.show(holder.unknown, isUnsure)
        val description = ctx.getString(R.string.food_row_description, hit.name, ctx.getString(label), holder.row.subtitle)
        holder.row.contentDescription = when {
            clash != null -> "$description. $clash"
            isUnsure -> "$description. " + ctx.getString(R.string.filter_unknown_description)
            else -> description
        }
        holder.plus?.let { p ->
            p.contentDescription = ctx.getString(R.string.food_quick_log, hit.name)
            p.setOnClickListener { onPlus?.invoke(hit) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<FoodHit>() {
        override fun areItemsTheSame(oldItem: FoodHit, newItem: FoodHit): Boolean = oldItem.ref == newItem.ref
        override fun areContentsTheSame(oldItem: FoodHit, newItem: FoodHit): Boolean = oldItem == newItem
    }
}
