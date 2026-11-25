/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.cards

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.selfwatch.AppGraph
import app.selfwatch.R
import app.selfwatch.graph
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.Views
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.selfwatch.ui.R as UiR

/** One row of Edit cards. */
sealed interface EditRow {
    /** A group title. */
    data class Header(@field:StringRes @get:StringRes val title: Int) : EditRow

    /** A card on this tab ([recorded] false: no device records its data). */
    data class Shown(val slot: CardSlot, val recorded: Boolean) : EditRow

    /** A card not on this tab. */
    data class Hidden(val card: Card<*>, val recorded: Boolean) : EditRow
}

/** Edit cards state: the tab's [slots] in order, what the devices record, and whether "Show all" is on. */
data class EditCardsUiState(
    val slots: List<CardSlot> = emptyList(),
    val recorded: Recorded = Recorded.ALL,
    val showAll: Boolean = false,
    val loading: Boolean = true,
) {
    /** The cards on the tab, in order (the rows shown; auto cards without data only with [showAll]). */
    val shownCards: List<Card<*>> get() = slots.map { it.card }

    /**
     * The rows: "On this tab" with the tab's cards, then every other card grouped by its home tab ([tab]
     * first). Cards whose data no device records are listed only with [showAll] (unless the user put
     * them on the tab).
     */
    fun rows(tab: CardTab): List<EditRow> {
        val out = ArrayList<EditRow>()
        out += EditRow.Header(R.string.edit_cards_on_tab)
        for (s in slots) {
            val rec = recorded.records(s.card)
            if (rec || showAll || !s.auto) out += EditRow.Shown(s, rec)
        }
        val on = slots.map { it.card }.toSet()
        fun offered(c: Card<*>) = c !in on && (showAll || recorded.records(c))
        for (home in listOf(tab) + CardTab.entries.filter { it != tab }) {
            val hidden = CardRegistry.of(home).filter { it.group == null && offered(it) }
            if (hidden.isEmpty()) continue
            out += EditRow.Header(home.label)
            hidden.forEach { out += EditRow.Hidden(it, recorded.records(it)) }
        }
        // Cards with a group of their own (Device cards) come after the tabs.
        for ((group, cards) in CardRegistry.all.filter { it.group != null && offered(it) }.groupBy { it.group!! }) {
            out += EditRow.Header(group)
            cards.forEach { out += EditRow.Hidden(it, recorded.records(it)) }
        }
        return out
    }
}

/** Edit cards for one [tab]: every change (move, add, remove, size, reset) is saved at once. */
class EditCardsViewModel(private val store: CardStore, private val tab: CardTab) : ViewModel() {

    private val mutable = MutableStateFlow(EditCardsUiState())
    private val saving = Mutex()

    /** Screen state. */
    val state: StateFlow<EditCardsUiState> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            val rec = store.recorded()
            mutable.value = EditCardsUiState(store.slots(tab, rec), rec, loading = false)
        }
    }

    /** Moves [card] to where [target] is (both on the tab). */
    fun move(card: Card<*>, target: Card<*>) {
        val slots = mutable.value.slots.toMutableList()
        val from = slots.indexOfFirst { it.card == card }
        val to = slots.indexOfFirst { it.card == target }
        if (from < 0 || to < 0 || from == to) return
        slots.add(to, slots.removeAt(from))
        update(slots)
    }

    /** Puts [card] on the tab (at the end, in its default size) or takes it off. */
    fun setShown(card: Card<*>, shown: Boolean) {
        val slots = mutable.value.slots
        val has = slots.any { it.card == card }
        if (shown && !has) update(slots + CardSlot(card, card.defaultSize))
        if (!shown && has) update(slots.filter { it.card != card })
    }

    /** Shows [card] small or large. */
    fun setSize(card: Card<*>, size: CardSize) {
        val slots = mutable.value.slots
        if (slots.none { it.card == card && it.size != size }) return
        update(slots.map { if (it.card == card) it.copy(size = size) else it })
    }

    /** "Show all": list the cards whose data no device records too. */
    fun setShowAll(on: Boolean) {
        mutable.value = mutable.value.copy(showAll = on)
    }

    /** Back to the default layout for this tab; [done] runs after. */
    fun reset(done: () -> Unit) {
        viewModelScope.launch {
            saving.withLock {
                val slots = store.reset(tab)
                mutable.value = mutable.value.copy(slots = slots, recorded = store.recorded())
                done()
            }
        }
    }

    private fun update(slots: List<CardSlot>) {
        mutable.value = mutable.value.copy(slots = slots)
        viewModelScope.launch {
            // The newest slots are written last, whatever order the saves finish in.
            saving.withLock { store.saveSlots(tab, mutable.value.slots) }
        }
    }

    /** Factory. */
    companion object {
        /** Builds the view model for [tab] from [graph]. */
        fun factory(graph: AppGraph, tab: CardTab): ViewModelProvider.Factory = viewModelFactory {
            initializer { EditCardsViewModel(CardStore.of(graph.db), tab) }
        }
    }
}

/**
 * Edit cards (each card tab's menu): the cards on this tab with a drag handle, an on/off switch and a
 * Small / Large choice, then every other card grouped by home tab, ready to add. "Show all" also lists
 * cards whose data none of the user's devices records ("Your devices don't record this"). With TalkBack,
 * each card's switch has "Move up" and "Move down" actions instead of dragging. Make one with
 * [newInstance].
 */
class EditCardsFragment : DetailFragment() {

    private val tab: CardTab
        get() = CardTab.entries.firstOrNull { it.key == arguments?.getString(ARG_TAB) } ?: CardTab.BODY

    private val vm: EditCardsViewModel by viewModels { EditCardsViewModel.factory(requireContext().graph, tab) }

    override fun title(): CharSequence = getString(tab.editTitle)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        column.addView(Views.caption(ctx, getString(R.string.edit_cards_help)), Views.matchWrap(ctx))
        val showAll = MaterialSwitch(ctx).apply {
            id = R.id.edit_cards_show_all
            text = getString(R.string.edit_cards_show_all)
            minHeight = Views.dp(ctx, TOUCH_DP)
            setOnCheckedChangeListener { _, on -> vm.setShowAll(on) }
        }
        column.addView(showAll, Views.matchWrap(ctx, 8))
        column.addView(Views.caption(ctx, getString(R.string.edit_cards_show_all_help)), Views.matchWrap(ctx))
        val list = RecyclerView(ctx).apply {
            id = R.id.edit_cards_list
            layoutManager = LinearLayoutManager(ctx)
            isNestedScrollingEnabled = false
            itemAnimator = null
        }
        val card = Views.card(column, null)
        card.addView(list, Views.matchWrap(ctx))
        val adapter = Adapter()
        list.adapter = adapter
        val touch = ItemTouchHelper(
            object : ItemTouchHelper.Callback() {
                override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
                    if (adapter.rowAt(vh.bindingAdapterPosition) is EditRow.Shown) makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) else 0

                override fun isLongPressDragEnabled(): Boolean = false

                override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean =
                    adapter.move(vh.bindingAdapterPosition, target.bindingAdapterPosition)

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

                override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                    super.clearView(rv, vh)
                    // Rows changed places: refresh their move up / move down actions.
                    rv.post { adapter.notifyItemRangeChanged(0, adapter.itemCount) }
                }
            },
        )
        touch.attachToRecyclerView(list)
        adapter.touch = touch
        column.addView(
            MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = R.id.edit_cards_reset
                text = getString(R.string.edit_cards_reset)
                isAllCaps = false
                setOnClickListener {
                    val app = ctx.applicationContext
                    vm.reset { Toast.makeText(app, R.string.edit_cards_reset_done, Toast.LENGTH_SHORT).show() }
                }
            },
            Views.matchWrap(ctx, 16),
        )
        collectWhileStarted(vm.state) { s ->
            if (s.loading) return@collectWhileStarted
            if (showAll.isChecked != s.showAll) showAll.isChecked = s.showAll
            adapter.show(s.rows(tab))
        }
    }

    private class Holder(
        val row: LinearLayout,
        val handle: ImageView?,
        val toggle: MaterialSwitch?,
        val summary: TextView?,
        val size: MaterialButtonToggleGroup?,
        val header: TextView?,
    ) : RecyclerView.ViewHolder(row) {
        val actions = ArrayList<Int>()
        var quiet = false
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        private var rows: List<EditRow> = emptyList()
        var touch: ItemTouchHelper? = null

        fun rowAt(position: Int): EditRow? = rows.getOrNull(position)

        @SuppressLint("NotifyDataSetChanged")
        fun show(newRows: List<EditRow>) {
            if (newRows == rows) return
            rows = newRows
            notifyDataSetChanged()
        }

        /** Drag from [from] onto [to] (both cards on the tab); false when not allowed. */
        fun move(from: Int, to: Int): Boolean {
            val a = rows.getOrNull(from) as? EditRow.Shown ?: return false
            val b = rows.getOrNull(to) as? EditRow.Shown ?: return false
            rows = rows.toMutableList().also { it.add(to, it.removeAt(from)) }
            notifyItemMoved(from, to)
            vm.move(a.slot.card, b.slot.card)
            return true
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is EditRow.Header -> TYPE_HEADER
            is EditRow.Shown -> TYPE_SHOWN
            is EditRow.Hidden -> TYPE_HIDDEN
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val touchSize = Views.dp(ctx, TOUCH_DP)
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            if (viewType == TYPE_HEADER) {
                val title = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Title).apply { ViewCompat.setAccessibilityHeading(this, true) }
                row.minimumHeight = touchSize
                row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = Views.dp(ctx, 8) })
                return Holder(row, null, null, null, null, title)
            }
            row.minimumHeight = Views.dp(ctx, ROW_DP)
            val handle = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_drag_handle)
                scaleType = ImageView.ScaleType.CENTER
            }
            row.addView(handle, LinearLayout.LayoutParams(touchSize, touchSize))
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val toggle = MaterialSwitch(ctx).apply { minHeight = touchSize }
            val summary = Views.caption(ctx).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            texts.addView(toggle, Views.matchWrap(ctx))
            texts.addView(summary, Views.matchWrap(ctx))
            var size: MaterialButtonToggleGroup? = null
            if (viewType == TYPE_SHOWN) {
                lateinit var holderRef: Holder
                size = Views.segments(ctx, listOf(getString(R.string.edit_cards_small), getString(R.string.edit_cards_large))) { i ->
                    val h = holderRef
                    if (h.quiet) return@segments
                    val r = rows.getOrNull(h.bindingAdapterPosition) as? EditRow.Shown ?: return@segments
                    val s = if (i == 0) CardSize.SMALL else CardSize.LARGE
                    if (r.slot.size != s) {
                        rows = rows.toMutableList().also { it[h.bindingAdapterPosition] = r.copy(slot = r.slot.copy(size = s)) }
                        vm.setSize(r.slot.card, s)
                    }
                }
                texts.addView(size, Views.matchWrap(ctx, 4))
                row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val holder = Holder(row, handle, toggle, summary, size, null)
                holderRef = holder
                handle.setOnTouchListener { _, e ->
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) touch?.startDrag(holder)
                    false
                }
                return holder
            }
            handle.visibility = View.INVISIBLE
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return Holder(row, handle, toggle, summary, size, null)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            when (val r = rows[position]) {
                is EditRow.Header -> holder.header?.text = getString(r.title)
                is EditRow.Shown -> bindCard(holder, r.slot.card, true, r.recorded, position, r.slot.size)
                is EditRow.Hidden -> bindCard(holder, r.card, false, r.recorded, position, null)
            }
        }

        private fun bindCard(holder: Holder, card: Card<*>, shown: Boolean, recorded: Boolean, position: Int, size: CardSize?) {
            val toggle = holder.toggle ?: return
            val title = getString(card.title)
            toggle.setOnCheckedChangeListener(null)
            toggle.text = title
            toggle.isChecked = shown
            toggle.setOnCheckedChangeListener { _, on -> vm.setShown(card, on) }
            holder.summary?.text = if (recorded) getString(card.summary) else getString(R.string.card_not_recorded)
            holder.handle?.contentDescription = getString(R.string.edit_cards_drag, title)
            holder.size?.let { g ->
                holder.quiet = true
                Views.selectSegment(g, if (size == CardSize.SMALL) 0 else 1)
                holder.quiet = false
                g.contentDescription = getString(R.string.edit_cards_size_description, title)
            }
            for (id in holder.actions) ViewCompat.removeAccessibilityAction(toggle, id)
            holder.actions.clear()
            if (!shown) return
            if (rows.getOrNull(position - 1) is EditRow.Shown) {
                holder.actions += ViewCompat.addAccessibilityAction(toggle, getString(R.string.edit_cards_move_up)) { _, _ -> moveBy(holder, -1) }
            }
            if (rows.getOrNull(position + 1) is EditRow.Shown) {
                holder.actions += ViewCompat.addAccessibilityAction(toggle, getString(R.string.edit_cards_move_down)) { _, _ -> moveBy(holder, 1) }
            }
        }

        private fun moveBy(holder: Holder, step: Int): Boolean {
            val from = holder.bindingAdapterPosition
            val to = from + step
            if (from == RecyclerView.NO_POSITION || !move(from, to)) return false
            // Neighbours change their up/down actions.
            notifyItemRangeChanged(minOf(from, to), 2)
            return true
        }
    }

    /** Constants and the factory. */
    companion object {
        private const val TOUCH_DP = 48
        private const val ROW_DP = 64
        private const val ARG_TAB = "tab"
        private const val TYPE_HEADER = 0
        private const val TYPE_SHOWN = 1
        private const val TYPE_HIDDEN = 2

        /** Edit cards for [tab]. */
        fun newInstance(tab: CardTab): EditCardsFragment =
            EditCardsFragment().apply { arguments = Bundle().apply { putString(ARG_TAB, tab.key) } }
    }
}
