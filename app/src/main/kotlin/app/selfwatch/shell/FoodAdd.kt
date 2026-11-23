/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.shell

import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.selfwatch.R
import app.selfwatch.food.FoodBackend
import app.selfwatch.food.FoodFragment
import app.selfwatch.food.GraphFoodBackend
import app.selfwatch.food.QuickLog
import app.selfwatch.food.QuickLogResult
import app.selfwatch.graph
import app.selfwatch.model.log.SwLog
import app.selfwatch.ui.components.SwSnackbar
import app.selfwatch.ui.format.Fmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The + sheet's "Log water" and "Start or stop fast": done in one tap, confirmed by a snackbar. */
object FoodAdd {
    private const val TAG = "FoodAdd"

    /** Handles [item] from [activity]; false when it is not water or fasting. */
    fun open(activity: FragmentActivity, item: AddSheet.Item, backend: () -> FoodBackend = { GraphFoodBackend(activity.graph.food) }): Boolean {
        val action: suspend (FoodBackend) -> QuickLogResult = when (item) {
            AddSheet.Item.WATER -> { b -> QuickLog.water(b) }
            AddSheet.Item.FAST -> { b -> QuickLog.toggleFast(b) }
            else -> return false
        }
        activity.lifecycleScope.launch {
            val b = backend()
            val result = try {
                action(b)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "quick log failed", e)
                show(activity, activity.getString(R.string.food_failed))
                return@launch
            }
            when (result) {
                is QuickLogResult.Water -> {
                    val text = result.goalMl?.let {
                        activity.getString(R.string.add_water_logged_goal, Fmt.number(result.totalMl.toLong()), Fmt.number(it.toLong()))
                    } ?: activity.getString(R.string.add_water_logged, Fmt.number(result.totalMl.toLong()))
                    show(activity, text) { activity.lifecycleScope.launch { QuickLog.undoWater(b) } }
                }
                is QuickLogResult.FastStarted ->
                    show(activity, activity.getString(R.string.add_fast_started, FoodFragment.hm(result.goalMin * 60L)))
                is QuickLogResult.FastEnded ->
                    show(activity, activity.getString(R.string.add_fast_ended, FoodFragment.hm(result.elapsedSec)))
            }
        }
        return true
    }

    private fun show(activity: FragmentActivity, text: String, onUndo: (() -> Unit)? = null) {
        val root = activity.findViewById<View>(R.id.tab_container) ?: activity.findViewById(android.R.id.content) ?: return
        val anchor = activity.findViewById<View>(R.id.add_fab)?.takeIf { it.isShown }
        if (onUndo == null) SwSnackbar.show(root, text, anchor = anchor) else SwSnackbar.undo(root, text, anchor, onUndo)
    }
}
