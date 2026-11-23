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
import app.selfwatch.food.source.FoodHit
import app.selfwatch.ui.components.ListRow
import app.selfwatch.ui.components.SourceTag
import com.google.android.material.button.MaterialButton

/**
 * Food rows: name with a source pill, "brand · serving · kcal" below, and a round "+" at the end
 * (hidden when [onPlus] is null). Tapping the row calls [onOpen].
 */
class FoodHitAdapter(
    private val onOpen: (FoodHit) -> Unit,
    private val onPlus: ((FoodHit) -> Unit)?,
) : ListAdapter<FoodHit, FoodHitAdapter.Holder>(Diff) {

    /** One row. */
    class Holder(val row: ListRow, val tag: SourceTag, val plus: MaterialButton?) : RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val row = ListRow(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            showChevron = false
        }
        val tag = SourceTag(ctx)
        row.addTitleAccessory(tag)
        val plus = onPlus?.let { FoodViews.plusButton(ctx, "") {} }
        if (plus != null) row.addTrailingAccessory(plus)
        return Holder(row, tag, plus)
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
        holder.row.contentDescription = ctx.getString(R.string.food_row_description, hit.name, ctx.getString(label), holder.row.subtitle)
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
