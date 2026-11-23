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
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
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
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One row of Edit cards: a card and whether its tab shows it. */
data class EditCardRow(val card: Card<*>, val shown: Boolean)

/** Edit cards state. */
data class EditCardsUiState(val rows: List<EditCardRow> = emptyList(), val loading: Boolean = true)

/** Edit cards for one [tab]: every change (move, show, hide, reset) is saved at once. */
class EditCardsViewModel(private val store: CardStore, private val tab: CardTab) : ViewModel() {

    private val mutable = MutableStateFlow(EditCardsUiState())
    private val saving = Mutex()

    /** Screen state. */
    val state: StateFlow<EditCardsUiState> = mutable.asStateFlow()

    init {
        viewModelScope.launch { mutable.value = EditCardsUiState(rowsOf(tab, store.visible(tab)), loading = false) }
    }

    /** Moves the row at [from] to [to]. Out-of-range positions are ignored. */
    fun move(from: Int, to: Int) {
        val rows = mutable.value.rows.toMutableList()
        if (from !in rows.indices || to !in rows.indices || from == to) return
        rows.add(to, rows.removeAt(from))
        update(rows)
    }

    /** Shows or hides [card]. */
    fun setShown(card: Card<*>, shown: Boolean) {
        update(mutable.value.rows.map { if (it.card == card) it.copy(shown = shown) else it })
    }

    /** Back to the main goal's set for this tab; [done] gets the goal. */
    fun reset(done: (MainGoal) -> Unit) {
        viewModelScope.launch {
            saving.withLock {
                val goal = store.mainGoal()
                mutable.value = EditCardsUiState(rowsOf(tab, store.reset(tab)), loading = false)
                done(goal)
            }
        }
    }

    private fun update(rows: List<EditCardRow>) {
        mutable.value = mutable.value.copy(rows = rows)
        viewModelScope.launch {
            // The newest rows are written last, whatever order the saves finish in.
            saving.withLock { store.save(tab, visibleOf(mutable.value.rows)) }
        }
    }

    /** Factory and pure parts. */
    companion object {
        /** The shown cards in order, then the hidden ones of [tab]. */
        fun rowsOf(tab: CardTab, visible: List<Card<*>>): List<EditCardRow> =
            visible.map { EditCardRow(it, true) } + CardRegistry.of(tab).filter { it !in visible }.map { EditCardRow(it, false) }

        /** The cards to save from [rows]. */
        fun visibleOf(rows: List<EditCardRow>): List<Card<*>> = rows.filter { it.shown }.map { it.card }

        /** Builds the view model for [tab] from [graph]. */
        fun factory(graph: AppGraph, tab: CardTab): ViewModelProvider.Factory = viewModelFactory {
            initializer { EditCardsViewModel(CardStore(graph.settings), tab) }
        }
    }
}

/**
 * Edit cards (each card tab's menu): every card of the tab with a drag handle and a switch, plus
 * "Reset to defaults for my goal". With TalkBack, each switch has "Move up" and "Move down" actions
 * instead of dragging. Make one with [newInstance].
 */
class EditCardsFragment : DetailFragment() {

    private val tab: CardTab
        get() = CardTab.entries.firstOrNull { it.key == arguments?.getString(ARG_TAB) } ?: CardTab.BODY

    private val vm: EditCardsViewModel by viewModels { EditCardsViewModel.factory(requireContext().graph, tab) }

    override fun title(): CharSequence = getString(tab.editTitle)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        column.addView(Views.caption(ctx, getString(R.string.edit_cards_help)), Views.matchWrap(ctx))
        val list = RecyclerView(ctx).apply {
            id = R.id.edit_cards_list
            layoutManager = LinearLayoutManager(ctx)
            isNestedScrollingEnabled = false
        }
        val card = Views.card(column, null)
        card.addView(list, Views.matchWrap(ctx))
        val adapter = Adapter()
        list.adapter = adapter
        val touch = ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                    adapter.move(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                    return true
                }

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
                    vm.reset { goal -> Toast.makeText(app, app.getString(R.string.edit_cards_reset_done, app.getString(goal.label)), Toast.LENGTH_SHORT).show() }
                }
            },
            Views.matchWrap(ctx, 16),
        )
        collectWhileStarted(vm.state) { s ->
            if (!s.loading) adapter.show(s.rows)
        }
    }

    private inner class Holder(val row: LinearLayout, val handle: ImageView, val toggle: MaterialSwitch, val summary: android.widget.TextView) :
        RecyclerView.ViewHolder(row) {
        val actions = ArrayList<Int>()
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        private var rows: List<EditCardRow> = emptyList()
        var touch: ItemTouchHelper? = null

        @SuppressLint("NotifyDataSetChanged")
        fun show(newRows: List<EditCardRow>) {
            if (newRows == rows) return
            val sameOrder = newRows.map { it.card } == rows.map { it.card }
            rows = newRows
            // Switch flips only change the switch already shown; a reset rebuilds the list.
            if (!sameOrder) notifyDataSetChanged()
        }

        fun move(from: Int, to: Int) {
            if (from !in rows.indices || to !in rows.indices || from == to) return
            rows = rows.toMutableList().also { it.add(to, it.removeAt(from)) }
            notifyItemMoved(from, to)
            vm.move(from, to)
        }

        override fun getItemCount(): Int = rows.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val touchSize = Views.dp(ctx, TOUCH_DP)
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = Views.dp(ctx, ROW_DP)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val handle = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_drag_handle)
                scaleType = ImageView.ScaleType.CENTER
            }
            row.addView(handle, LinearLayout.LayoutParams(touchSize, touchSize))
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val toggle = MaterialSwitch(ctx).apply { minHeight = touchSize }
            val summary = Views.caption(ctx).apply { importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            texts.addView(toggle, Views.matchWrap(ctx))
            texts.addView(summary, Views.matchWrap(ctx))
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val holder = Holder(row, handle, toggle, summary)
            handle.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) touch?.startDrag(holder)
                false
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val r = rows[position]
            val title = getString(r.card.title)
            holder.toggle.setOnCheckedChangeListener(null)
            holder.toggle.text = title
            holder.toggle.isChecked = r.shown
            holder.toggle.setOnCheckedChangeListener { _, on ->
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnCheckedChangeListener
                rows = rows.toMutableList().also { it[pos] = it[pos].copy(shown = on) }
                vm.setShown(rows[pos].card, on)
            }
            holder.summary.text = getString(r.card.summary)
            holder.handle.contentDescription = getString(R.string.edit_cards_drag, title)
            for (id in holder.actions) ViewCompat.removeAccessibilityAction(holder.toggle, id)
            holder.actions.clear()
            if (position > 0) {
                holder.actions += ViewCompat.addAccessibilityAction(holder.toggle, getString(R.string.edit_cards_move_up)) { _, _ ->
                    moveBy(holder, -1)
                }
            }
            if (position < rows.size - 1) {
                holder.actions += ViewCompat.addAccessibilityAction(holder.toggle, getString(R.string.edit_cards_move_down)) { _, _ ->
                    moveBy(holder, 1)
                }
            }
        }

        private fun moveBy(holder: Holder, step: Int): Boolean {
            val from = holder.bindingAdapterPosition
            val to = from + step
            if (from == RecyclerView.NO_POSITION || to !in rows.indices) return false
            move(from, to)
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

        /** Edit cards for [tab]. */
        fun newInstance(tab: CardTab): EditCardsFragment =
            EditCardsFragment().apply { arguments = Bundle().apply { putString(ARG_TAB, tab.key) } }
    }
}
