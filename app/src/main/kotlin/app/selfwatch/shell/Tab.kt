/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.shell

import androidx.annotation.IdRes
import androidx.fragment.app.Fragment
import app.selfwatch.R
import app.selfwatch.activity.ActivityFragment
import app.selfwatch.body.BodyFragment
import app.selfwatch.devices.DevicesFragment
import app.selfwatch.food.FoodFragment
import app.selfwatch.me.MeFragment

/** The five bottom tabs (APP_LAYOUT §1). Body is home. */
enum class Tab(
    /** Fragment tag. */
    val tag: String,
    /** Bottom navigation item id. */
    @field:IdRes @get:IdRes val itemId: Int,
    /** Whether the + button shows on this tab. */
    val hasAddButton: Boolean,
) {
    /** Home. */
    BODY("tab_body", R.id.tab_body, true),
    ACTIVITY("tab_activity", R.id.tab_activity, false),
    FOOD("tab_food", R.id.tab_food, true),
    DEVICES("tab_devices", R.id.tab_devices, false),
    ME("tab_me", R.id.tab_me, false),
    ;

    /** A new fragment for this tab. */
    fun newFragment(): Fragment = when (this) {
        BODY -> BodyFragment()
        ACTIVITY -> ActivityFragment()
        FOOD -> FoodFragment()
        DEVICES -> DevicesFragment()
        ME -> MeFragment()
    }

    /** Lookup. */
    companion object {
        /** The tab whose bottom navigation item is [itemId], or null. */
        fun ofItem(@IdRes itemId: Int): Tab? = entries.firstOrNull { it.itemId == itemId }
    }
}
