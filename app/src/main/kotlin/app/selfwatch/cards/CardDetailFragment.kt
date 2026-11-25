/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.cards

import android.os.Bundle
import android.widget.LinearLayout
import app.selfwatch.R
import app.selfwatch.graph
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.Views

/**
 * One card's large form on its own screen: what a small card opens when it has no detail screen of its
 * own. Make one with [newInstance].
 */
class CardDetailFragment : DetailFragment() {

    private val card: Card<*>? get() = arguments?.getString(ARG_ID)?.let { CardRegistry.byId(it) }

    /** The stack showing the card (tests). */
    var stack: CardStack? = null
        private set

    override fun title(): CharSequence = card?.let { getString(it.title) } ?: getString(R.string.menu_edit_cards)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val c = card ?: return
        val holder = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        column.addView(holder, Views.matchWrap(requireContext()))
        val s = CardStack(this, c.tab, holder, requireContext().graph.db, fixed = listOf(CardSlot(c, CardSize.LARGE)))
        stack = s
        s.start()
    }

    /** Factory. */
    companion object {
        private const val ARG_ID = "card"

        /** The large form of the card with [id]. */
        fun newInstance(id: String): CardDetailFragment = CardDetailFragment().apply { arguments = Bundle().apply { putString(ARG_ID, id) } }
    }
}
