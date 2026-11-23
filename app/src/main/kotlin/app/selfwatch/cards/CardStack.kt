/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.cards

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.SystemClock
import app.selfwatch.shell.DisplayUnits
import app.selfwatch.shell.MainActivity
import app.selfwatch.shell.Nav
import app.selfwatch.shell.Tab
import app.selfwatch.ui.format.Units
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import app.selfwatch.ui.R as UiR

/**
 * Shows the visible cards of [tab] in [column], in the saved order, and keeps them fresh. Each visible
 * card loads on its own (its [Card.load] off the main thread) when shown, when one of its tables
 * changes, and when the day or the units change; hidden cards are not loaded. Card views are built
 * once per screen and moved, not rebuilt, when the order changes.
 *
 * @param leadingGap true when something sits above [column] (Food's header), so the first card gets
 *   the usual gap too.
 */
class CardStack(
    override val fragment: Fragment,
    private val tab: CardTab,
    private val column: ViewGroup,
    private val db: SelfWatchDb,
    private val clock: Clock = SystemClock,
    private val leadingGap: Boolean = false,
) : CardHost {

    private val store = CardStore(db.settings)
    private val mounted = HashMap<String, Mounted>()
    private var arranged: List<String>? = null

    /** The ids shown, in order (tests). */
    val shownIds: List<String> get() = arranged.orEmpty()

    override val context: Context get() = fragment.requireContext()

    override fun open(screen: Fragment) = Nav.open(fragment, screen)

    override fun openTab(tab: Tab) {
        (fragment.activity as? MainActivity)?.select(tab)
    }

    override fun launch(block: suspend () -> Unit) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.e(TAG, "card action failed", e)
            }
        }
    }

    /**
     * Starts showing cards while the screen is started. [day] gives the day shown (null = today, read
     * at each load).
     */
    fun start(day: Flow<Day?> = flowOf(null)) {
        val owner = fragment.viewLifecycleOwner
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) { run(day) }
        }
    }

    private suspend fun run(day: Flow<Day?>) = coroutineScope {
        val inputs = combine(day.distinctUntilChanged(), DisplayUnits.observe(db)) { d, u -> Inputs(d, u) }
        val jobs = HashMap<String, Job>()
        store.observeVisible(tab, db.changes.observe(CardStore.TABLES)).collect { cards ->
            arrange(cards)
            val ids = cards.map { it.id }.toSet()
            jobs.keys.filter { it !in ids }.forEach { jobs.remove(it)?.cancel() }
            for (c in cards) {
                if (c.id in jobs) continue
                val m = mounted[c.id] ?: continue
                jobs[c.id] = m.start(this, inputs)
            }
        }
    }

    /** Puts the views of [cards] into [column] in order; views of hidden cards are taken out. */
    private fun arrange(cards: List<Card<*>>) {
        val ids = cards.map { it.id }
        if (ids == arranged) return
        arranged = ids
        column.removeAllViews()
        val gap = column.resources.getDimensionPixelSize(UiR.dimen.sw_space_2)
        cards.forEachIndexed { i, card ->
            val m = mounted.getOrPut(card.id) { mount(card) }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = if (i == 0 && !leadingGap) 0 else gap
            m.root.visibility = View.VISIBLE
            column.addView(m.root, lp)
        }
        column.visibility = if (cards.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun <T> mount(card: Card<T>): Mounted {
        val view = card.createView(this, column)
        return Mounted(view.root) { scope, inputs ->
            scope.launch {
                combine(db.changes.observe(card.tables), inputs) { _, i -> i }.conflate().collect { i ->
                    val env = CardEnv(db, clock, i.day ?: clock.today(), i.units)
                    val data = try {
                        withContext(Dispatchers.IO) { card.load(env) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        SwLog.e(TAG, "card $card failed to load", e)
                        return@collect
                    }
                    view.bind(data)
                }
            }
        }
    }

    private class Inputs(val day: Day?, val units: Units)

    private class Mounted(val root: View, val start: (CoroutineScope, Flow<Inputs>) -> Job)

    private companion object {
        const val TAG = "CardStack"
    }
}
