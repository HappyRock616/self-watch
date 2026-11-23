/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.cards

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.core.content.ContextCompat
import app.selfwatch.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import app.selfwatch.ui.R as UiR

/**
 * "What's your main goal?": a list of radio rows, one per [MainGoal] (name, then a short line), exactly
 * one chosen. Used by onboarding and Me › Goals. [onPick] runs only when the user picks, not for
 * [select].
 */
class MainGoalPicker(ctx: Context, private val onPick: (MainGoal) -> Unit) {

    private var quiet = false

    /** The view to add to a layout. */
    val view: RadioGroup = RadioGroup(ctx).apply { orientation = RadioGroup.VERTICAL }

    /** The chosen goal, or null before anything is chosen. */
    val selected: MainGoal?
        get() = view.findViewById<View>(view.checkedRadioButtonId)?.tag as? MainGoal

    init {
        val minHeight = ctx.resources.getDimensionPixelSize(UiR.dimen.sw_touch_target)
        val pad = ctx.resources.getDimensionPixelSize(UiR.dimen.sw_space_1)
        val muted = ContextCompat.getColor(ctx, UiR.color.sw_muted)
        for (goal in MainGoal.entries) {
            val title = ctx.getString(goal.label)
            val text = SpannableStringBuilder(title).append('\n').append(ctx.getString(goal.help)).apply {
                setSpan(RelativeSizeSpan(HELP_SIZE), title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(muted), title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val b = RadioButton(ctx).apply {
                id = View.generateViewId()
                tag = goal
                this.text = text
                this.minHeight = minHeight
                minimumHeight = minHeight
                setPaddingRelative(paddingStart + pad, pad / 2, pad, pad / 2)
            }
            view.addView(b, RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        view.setOnCheckedChangeListener { g, checkedId ->
            if (quiet || checkedId == View.NO_ID) return@setOnCheckedChangeListener
            (g.findViewById<View>(checkedId)?.tag as? MainGoal)?.let(onPick)
        }
    }

    /** Shows [goal] as chosen without calling onPick. */
    fun select(goal: MainGoal) {
        val b = (0 until view.childCount).map { view.getChildAt(it) }.firstOrNull { it.tag == goal } ?: return
        if (view.checkedRadioButtonId == b.id) return
        quiet = true
        try {
            view.check(b.id)
        } finally {
            quiet = false
        }
    }

    private companion object {
        const val HELP_SIZE = 0.85f
    }
}

/**
 * The one question asked when the main goal changes: switch the cards on Body, Activity and Food to
 * the new goal's defaults, or keep the user's own. [answer] gets true for switch; closing the dialog
 * counts as keep.
 */
object MainGoalSwitchDialog {
    /** Shows the question for [goal]. */
    fun ask(ctx: Context, goal: MainGoal, answer: (switchCards: Boolean) -> Unit) {
        var answered = false
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.main_goal_switch_title)
            .setMessage(ctx.getString(R.string.main_goal_switch_message, ctx.getString(goal.label)))
            .setPositiveButton(R.string.main_goal_switch_yes) { _, _ ->
                answered = true
                answer(true)
            }
            .setNegativeButton(R.string.main_goal_switch_no) { _, _ ->
                answered = true
                answer(false)
            }
            .setOnDismissListener { if (!answered) answer(false) }
            .show()
    }
}
