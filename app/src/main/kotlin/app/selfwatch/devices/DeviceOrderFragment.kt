/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.devices

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
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
import app.selfwatch.ui.R as UiR
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Device order state. */
data class DeviceOrderUiState(val rows: List<DeviceCard> = emptyList(), val loading: Boolean = true)

/** Device order: every move is saved at once (first = primary). */
class DeviceOrderViewModel(private val list: DeviceList) : ViewModel() {
    private val mutable = MutableStateFlow(DeviceOrderUiState())
    private val saving = Mutex()

    /** Screen state. */
    val state: StateFlow<DeviceOrderUiState> = mutable.asStateFlow()

    init {
        viewModelScope.launch { mutable.value = DeviceOrderUiState(list.devices.first(), loading = false) }
    }

    /** Moves the row at [from] to [to] and saves the order. Out-of-range positions are ignored. */
    fun move(from: Int, to: Int) {
        val rows = mutable.value.rows.toMutableList()
        if (from !in rows.indices || to !in rows.indices || from == to) return
        rows.add(to, rows.removeAt(from))
        mutable.value = mutable.value.copy(rows = rows)
        viewModelScope.launch {
            // The newest order is written last, whatever order the saves finish in.
            saving.withLock { list.setOrder(mutable.value.rows.map { it.id }) }
        }
    }

    /** Factory. */
    companion object {
        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { DeviceOrderViewModel(graph.deviceList) }
        }
    }
}

/**
 * Device order (Devices menu): every paired device with a drag handle, first = primary. The order is
 * the display order on Devices and the priority when two devices report the same thing; the first
 * connected watch gets notifications. With TalkBack, each row has "Move up" and "Move down" actions.
 */
class DeviceOrderFragment : DetailFragment() {

    private val vm: DeviceOrderViewModel by viewModels { DeviceOrderViewModel.factory(requireContext().graph) }

    override fun title(): CharSequence = getString(R.string.menu_device_order)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        column.addView(Views.caption(ctx, getString(R.string.device_order_help)), Views.matchWrap(ctx))
        val empty = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_BodyLarge, getString(R.string.device_order_empty))
        empty.isVisible = false
        column.addView(empty, Views.matchWrap(ctx, 16))
        val list = RecyclerView(ctx).apply {
            id = R.id.device_order_list
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
                    // Rows changed places: refresh "Primary" and the move up / move down actions.
                    rv.post { adapter.notifyItemRangeChanged(0, adapter.itemCount) }
                }
            },
        )
        touch.attachToRecyclerView(list)
        adapter.touch = touch
        collectWhileStarted(vm.state) { s ->
            if (s.loading) return@collectWhileStarted
            empty.isVisible = s.rows.isEmpty()
            card.isVisible = s.rows.isNotEmpty()
            adapter.show(s.rows)
        }
    }

    private class Holder(row: LinearLayout, val handle: ImageView, val name: TextView, val kind: TextView) : RecyclerView.ViewHolder(row) {
        val actions = ArrayList<Int>()
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        private var rows: List<DeviceCard> = emptyList()
        var touch: ItemTouchHelper? = null

        @SuppressLint("NotifyDataSetChanged")
        fun show(newRows: List<DeviceCard>) {
            if (newRows.map { it.id } == rows.map { it.id }) return
            rows = newRows
            notifyDataSetChanged()
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
                isFocusable = true
            }
            val handle = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_drag_handle)
                scaleType = ImageView.ScaleType.CENTER
            }
            row.addView(handle, LinearLayout.LayoutParams(touchSize, touchSize))
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val name = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_BodyLarge)
            val kind = Views.caption(ctx)
            texts.addView(name, Views.matchWrap(ctx))
            texts.addView(kind, Views.matchWrap(ctx))
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val holder = Holder(row, handle, name, kind)
            handle.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) touch?.startDrag(holder)
                false
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val d = rows[position]
            val kind = getString(SensorType.label(d.kind))
            holder.name.text = d.name
            holder.kind.text = if (position == 0) getString(R.string.device_order_primary, kind) else kind
            holder.handle.contentDescription = getString(R.string.device_order_drag, d.name)
            val view: View = holder.itemView
            for (id in holder.actions) ViewCompat.removeAccessibilityAction(view, id)
            holder.actions.clear()
            if (position > 0) {
                holder.actions += ViewCompat.addAccessibilityAction(view, getString(R.string.edit_cards_move_up)) { _, _ -> moveBy(holder, -1) }
            }
            if (position < rows.size - 1) {
                holder.actions += ViewCompat.addAccessibilityAction(view, getString(R.string.edit_cards_move_down)) { _, _ -> moveBy(holder, 1) }
            }
        }

        private fun moveBy(holder: Holder, step: Int): Boolean {
            val from = holder.bindingAdapterPosition
            val to = from + step
            if (from == RecyclerView.NO_POSITION || to !in rows.indices) return false
            move(from, to)
            notifyItemRangeChanged(minOf(from, to), 2)
            return true
        }
    }

    private companion object {
        const val TOUCH_DP = 48
        const val ROW_DP = 64
    }
}
