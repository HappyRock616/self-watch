/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.shell

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.selfwatch.body.MeasurementsFragment
import app.selfwatch.body.WeightEditor
import app.selfwatch.graph
import kotlinx.coroutines.launch

/** The + sheet's body items: "Log weight" opens the weigh-in dialog, "Log measurements" the list. */
object BodyAdd {
    /** Handles [item] from [activity]; false when it is not a body item (or no tab is shown yet). */
    fun open(activity: FragmentActivity, item: AddSheet.Item): Boolean {
        val fm = activity.supportFragmentManager
        val host = Tab.entries.mapNotNull { fm.findFragmentByTag(it.tag) }.firstOrNull { it.isVisible } ?: return false
        return when (item) {
            AddSheet.Item.WEIGHT -> {
                host.lifecycleScope.launch {
                    val units = DisplayUnits.of(activity.graph.db.settings.profile().units)
                    if (host.isAdded) WeightEditor.log(host, units)
                }
                true
            }
            AddSheet.Item.MEASUREMENTS -> {
                Nav.open(host, MeasurementsFragment())
                true
            }
            else -> false
        }
    }
}
