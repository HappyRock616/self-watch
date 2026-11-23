/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.shell

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.StringRes
import app.selfwatch.R
import app.selfwatch.food.AddFoodActivity
import app.selfwatch.food.QuickAddSheet
import app.selfwatch.food.ScanActivity
import app.selfwatch.ui.components.ListRow
import app.selfwatch.ui.components.SwSheet

/**
 * The + sheet (APP_LAYOUT §1): scan barcode, search food, quick add calories, log weight, log
 * measurements, log water, start or stop fast. The three food items are left out when food tracking
 * is off. Scan, search and quick add open the food screens; weight and measurements the body ones;
 * water and fasting log in one tap ([FoodAdd]).
 */
class AddSheet : SwSheet() {

    /** The sheet's items, in order. */
    enum class Item(@field:StringRes @get:StringRes val label: Int, val isFood: Boolean) {
        SCAN(R.string.add_scan, true),
        SEARCH(R.string.add_search, true),
        QUICK_ADD(R.string.add_quick, true),
        WEIGHT(R.string.add_weight, false),
        MEASUREMENTS(R.string.add_measurements, false),
        WATER(R.string.add_water, false),
        FAST(R.string.add_fast, false),
    }

    override val sheetTitle: CharSequence get() = getString(R.string.add_title)

    override fun onCreateSheetContent(inflater: LayoutInflater, container: ViewGroup?): View {
        val ctx = inflater.context
        val foodEnabled = requireArguments().getBoolean(ARG_FOOD, true)
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (item in items(foodEnabled)) {
            val label = getString(item.label)
            val row = ListRow(ctx).apply {
                id = View.generateViewId()
                tag = item.name
                title = label
                onClick = {
                    if (!BodyAdd.open(requireActivity(), item) && !FoodAdd.open(requireActivity(), item)) open(item)
                    dismiss()
                }
            }
            column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return column
    }

    /** Opens the food screen behind [item]. */
    private fun open(item: Item) {
        val activity = requireActivity()
        when (item) {
            Item.SCAN -> startActivity(ScanActivity.intent(activity, null, 0L))
            Item.SEARCH -> startActivity(AddFoodActivity.intent(activity, null, 0L))
            Item.QUICK_ADD -> QuickAddSheet.newInstance(null, 0L).show(activity.supportFragmentManager, QuickAddSheet.TAG)
            else -> Unit
        }
    }

    /** Factory and helpers. */
    companion object {
        /** Fragment tag. */
        const val TAG: String = "add_sheet"
        private const val ARG_FOOD = "food_enabled"

        /** The items shown when food tracking is [foodEnabled] or not. */
        fun items(foodEnabled: Boolean): List<Item> = Item.entries.filter { foodEnabled || !it.isFood }

        /** A new sheet. */
        fun newInstance(foodEnabled: Boolean): AddSheet =
            AddSheet().apply { arguments = Bundle().apply { putBoolean(ARG_FOOD, foodEnabled) } }
    }
}
