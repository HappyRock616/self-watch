/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import app.selfwatch.R
import app.selfwatch.me.GoalsFragment
import app.selfwatch.shell.Nav
import app.selfwatch.shell.Views
import app.selfwatch.ui.components.SwSheet
import app.selfwatch.ui.format.Fmt
import com.google.android.material.button.MaterialButton
import app.selfwatch.ui.R as UiR

/** Builds the formula line from today's numbers. */
object EnergyFormula {
    /** "2,000 − 1,180 + 420 = 1,240", or null without a goal. */
    fun numbers(e: Energy): String? {
        val goal = e.goalKcal ?: return null
        val remaining = e.remainingKcal ?: return null
        return "${Fmt.number(goal.toLong())} − ${Fmt.number((e.inKcal ?: 0).toLong())} + " +
            "${Fmt.number((e.activeKcal ?: 0).toLong())} = ${Fmt.number(remaining.toLong())}"
    }
}

/**
 * The small info sheet behind the energy card: **Goal − Food + Activity = Remaining**, today's numbers
 * in that formula, and what each word means.
 */
class EnergySheet : SwSheet() {

    override val sheetTitle: CharSequence get() = getString(R.string.energy_sheet_title)

    override fun onCreateSheetContent(inflater: LayoutInflater, container: ViewGroup?): View {
        val ctx = inflater.context
        val args = requireArguments()
        fun opt(key: String): Int? = if (args.containsKey(key)) args.getInt(key) else null
        val e = Energy(opt(ARG_GOAL), opt(ARG_IN), opt(ARG_ACTIVE), opt(ARG_RESTING))
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Views.dp(ctx, 16)
            setPadding(pad, 0, pad, pad)
        }
        col.addView(Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Title, getString(R.string.energy_formula)))
        val numbers = EnergyFormula.numbers(e)
        col.addView(
            Views.text(ctx, UiR.style.TextAppearance_SelfWatch_BodyLarge_Number, numbers ?: getString(R.string.energy_no_goal)),
            Views.matchWrap(ctx, 8),
        )
        for (res in intArrayOf(R.string.energy_explain_goal, R.string.energy_explain_food, R.string.energy_explain_activity, R.string.energy_explain_out)) {
            col.addView(Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, getText(res)), Views.matchWrap(ctx, 12))
        }
        val goals = MaterialButton(ctx).apply {
            text = getString(if (e.goalKcal == null) R.string.energy_set_goal else R.string.energy_change_goal)
            setOnClickListener {
                val host = parentFragment ?: activity?.supportFragmentManager?.fragments?.firstOrNull { it.isVisible }
                dismiss()
                host?.let { Nav.open(it, GoalsFragment()) }
            }
        }
        col.addView(goals, Views.matchWrap(ctx, 16))
        return col
    }

    /** Factory. */
    companion object {
        /** Fragment tag. */
        const val TAG: String = "energy_sheet"
        private const val ARG_GOAL = "goal"
        private const val ARG_IN = "in"
        private const val ARG_ACTIVE = "active"
        private const val ARG_RESTING = "resting"

        /** Opens the sheet over [host] with today's [energy]. */
        fun show(host: Fragment, energy: Energy) {
            if (host.childFragmentManager.findFragmentByTag(TAG) != null) return
            EnergySheet().apply {
                arguments = Bundle().apply {
                    energy.goalKcal?.let { putInt(ARG_GOAL, it) }
                    energy.inKcal?.let { putInt(ARG_IN, it) }
                    energy.activeKcal?.let { putInt(ARG_ACTIVE, it) }
                    energy.restingKcal?.let { putInt(ARG_RESTING, it) }
                }
            }.show(host.childFragmentManager, TAG)
        }
    }
}
