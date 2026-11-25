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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import app.selfwatch.ui.R as UiR

/**
 * Shows the cards of [tab] in [column], in the saved order and sizes, and keeps them fresh. Large cards
 * take a row each; small cards next to each other share a row in pairs (a lone small card keeps half the
 * width). Each shown card loads on its own (its [Card.load] off the main thread) when shown, when one of
 * its tables changes, and when the day or the units change; hidden cards are not loaded. A card the user
 * chose whose data no device records shows "Your devices don't record this" and is not loaded. Card
 * views are built once per screen and moved, not rebuilt, when the order changes.
 *
 * @param leadingGap true when something sits above [column], so the first row gets the usual gap too.
 * @param fixed when set, these slots are shown instead of the tab's saved ones (one card on its own
 *   screen).
 */
class CardStack(
    override val fragment: Fragment,
    private val tab: CardTab,
    private val column: ViewGroup,
    private val db: SelfWatchDb,
    private val clock: Clock = SystemClock,
    private val leadingGap: Boolean = false,
    private val fixed: List<CardSlot>? = null,
) : CardHost {

    private val store = CardStore.of(db, clock)
    private val mounted = HashMap<String, Mounted>()
    private var arranged: List<String>? = null

    /** The card ids shown, in order (tests). */
    val shownIds: List<String> get() = arranged.orEmpty().map { it.substringBefore(':') }

    /** The shown slots as `id:size` (`:w` for a card waiting for data), in order (tests). */
    val shownKeys: List<String> get() = arranged.orEmpty()

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

    private fun source(): Flow<ShownCards> {
        val f = fixed
        if (f != null) return flowOf(ShownCards(f, Recorded.ALL))
        return db.changes.observe(CardStore.TABLES + RecordedLoader.TABLES).conflate()
            .map { withContext(Dispatchers.IO) { store.shown(tab) } }
            .distinctUntilChanged()
    }

    private suspend fun run(day: Flow<Day?>) = coroutineScope {
        val inputs = combine(day.distinctUntilChanged(), DisplayUnits.observe(db)) { d, u -> Inputs(d, u) }
        val jobs = HashMap<String, Job>()
        source().collect { shown ->
            val placed = shown.slots.map { Placed(it, fixed == null && !shown.recorded.records(it.card)) }
            arrange(placed)
            val keys = placed.map { it.key }.toSet()
            jobs.keys.filter { it !in keys }.forEach { jobs.remove(it)?.cancel() }
            for (p in placed) {
                if (p.waiting || p.key in jobs) continue
                val m = mounted[p.key] ?: continue
                jobs[p.key] = m.start(this, inputs)
            }
        }
    }

    /** Puts the views of [placed] into [column] in rows; views of hidden cards are taken out. */
    private fun arrange(placed: List<Placed>) {
        val keys = placed.map { it.key }
        if (keys == arranged) return
        arranged = keys
        for (i in 0 until column.childCount) (column.getChildAt(i) as? ViewGroup)?.takeIf { it.tag == ROW_TAG }?.removeAllViews()
        column.removeAllViews()
        val gap = column.resources.getDimensionPixelSize(UiR.dimen.sw_space_2)
        val ctx = column.context
        var first = true
        fun addRow(view: View) {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = if (first && !leadingGap) 0 else gap
            first = false
            column.addView(view, lp)
        }
        for (row in rows(placed)) {
            val views = row.map { p -> mounted.getOrPut(p.key) { mount(p) }.root.also { detach(it); it.visibility = View.VISIBLE } }
            if (row.size == 1 && row[0].slot.size == CardSize.LARGE) {
                addRow(views[0])
                continue
            }
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                tag = ROW_TAG
            }
            views.forEachIndexed { i, v ->
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                if (i > 0) lp.marginStart = gap
                line.addView(v, lp)
            }
            if (views.size == 1) line.addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = gap })
            addRow(line)
        }
        column.visibility = if (placed.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun detach(v: View) {
        (v.parent as? ViewGroup)?.removeView(v)
    }

    private fun mount(p: Placed): Mounted {
        if (p.waiting) return Mounted(WaitingCardView.create(p.slot.card, p.slot.size, column.context)) { scope, _ -> scope.launch { } }
        return mountCard(p.slot.card, p.slot.size)
    }

    private fun <T> mountCard(card: Card<T>, size: CardSize): Mounted {
        val view: CardView<T> = if (size == CardSize.SMALL) SmallCardView(card, this, column) else card.createView(this, column)
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

    /** A slot and whether it waits for data (no device records it). */
    private class Placed(val slot: CardSlot, val waiting: Boolean) {
        val key: String = "${slot.card.id}:${if (waiting) "w${slot.size.code}" else slot.size.code}"
    }

    private class Inputs(val day: Day?, val units: Units)

    private class Mounted(val root: View, val start: (CoroutineScope, Flow<Inputs>) -> Job)

    /** Pure parts. */
    companion object {
        private const val TAG = "CardStack"
        private const val ROW_TAG = "card_row"

        /** [items] in rows: each large one alone, small ones next to each other in pairs. */
        fun <T> rowsOf(items: List<T>, isSmall: (T) -> Boolean): List<List<T>> {
            val out = ArrayList<List<T>>()
            var pending: T? = null
            for (it in items) {
                if (!isSmall(it)) {
                    pending?.let { p -> out += listOf(p) }
                    pending = null
                    out += listOf(it)
                } else if (pending == null) {
                    pending = it
                } else {
                    out += listOf(pending, it)
                    pending = null
                }
            }
            pending?.let { out += listOf(it) }
            return out
        }

        private fun rows(placed: List<Placed>): List<List<Placed>> = rowsOf(placed) { it.slot.size == CardSize.SMALL }
    }
}
