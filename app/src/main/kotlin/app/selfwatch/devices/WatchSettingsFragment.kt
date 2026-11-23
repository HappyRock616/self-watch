/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.devices

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.lifecycleScope
import app.selfwatch.AppGraph
import app.selfwatch.R
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.Table
import app.selfwatch.graph
import app.selfwatch.me.SettingsActivity
import app.selfwatch.model.device.Feature
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.Views
import app.selfwatch.ui.components.ListRow
import app.selfwatch.watch.WatchSettings
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Watch settings state. */
data class WatchSettingsUiState(
    /** True until the first load. */
    val loading: Boolean = true,
    /** The watch, or null. */
    val watch: ActiveWatch? = null,
    /** True in sync-only mode. */
    val syncOnly: Boolean = false,
    /** Bluetooth capture on. */
    val bleCapture: Boolean = false,
) {
    /** True when find my watch shows. */
    val canFind: Boolean get() = watch != null && FeatureGate.shows(watch.features, Feature.FIND_WATCH)
}

/** Watch settings (C19): find my watch, sync now, connection mode, Bluetooth capture (debug), remove. */
class WatchSettingsViewModel(
    private val db: SelfWatchDb,
    private val commands: WatchCommands,
    private val debuggable: Boolean,
) : ViewModel() {

    /** Screen state. */
    val state: StateFlow<WatchSettingsUiState> =
        db.changes.observe(setOf(Table.SETTING, Table.DEVICE)).conflate()
            .map {
                WatchSettingsUiState(
                    loading = false,
                    watch = ActiveWatches.load(db),
                    syncOnly = db.settings.get(SettingKeys.CONNECTION_MODE) == SYNC_ONLY,
                    bleCapture = WatchSettings.bleCaptureOn(db.settings.get(WatchSettings.BLE_CAPTURE), debuggable),
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WatchSettingsUiState())

    /** Rings ([on]) or stops the watch. */
    suspend fun ring(on: Boolean): SendOutcome {
        val w = ActiveWatches.load(db)
        if (w == null || !w.connected) return SendOutcome.NOT_CONNECTED
        return if (commands.findWatch(w.id, on)) SendOutcome.SENT else SendOutcome.REFUSED
    }

    /** "Sync now". */
    fun syncNow(): Boolean = commands.syncNow()

    /** The Bluetooth capture switch. */
    fun setBleCapture(on: Boolean) {
        viewModelScope.launch { db.settings.setBool(WatchSettings.BLE_CAPTURE, on) }
    }

    /** Factory. */
    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val SYNC_ONLY = "sync_only"

        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph, debuggable: Boolean): ViewModelProvider.Factory = viewModelFactory {
            initializer { WatchSettingsViewModel(graph.db, graph.watchCommands, debuggable) }
        }
    }
}

/** Watch settings (C19). "Remove watch" goes back to Devices, which ends the pairing. */
class WatchSettingsFragment : DetailFragment() {

    private val vm: WatchSettingsViewModel by viewModels {
        WatchSettingsViewModel.factory(requireContext().graph, WatchSettings.isDebuggable(requireContext()))
    }

    private lateinit var findBox: LinearLayout
    private lateinit var mode: ListRow
    private lateinit var capture: MaterialSwitch
    private lateinit var remove: MaterialButton
    private lateinit var noWatch: TextView
    private var updating = false

    override fun title(): CharSequence = getString(R.string.devices_watch_settings)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val top = Views.card(column, null, first = true)
        val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        top.addView(t)
        noWatch = DevicesUi.note(t, getString(R.string.devices_need_watch))
        findBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        t.addView(findBox, Views.matchWrap(ctx))
        findBox.addView(Views.text(ctx, app.selfwatch.ui.R.style.TextAppearance_SelfWatch_BodyLarge, getString(R.string.devices_find_watch)))
        DevicesUi.note(findBox, getString(R.string.devices_find_note))
        val ringRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        findBox.addView(ringRow, Views.matchWrap(ctx))
        DevicesUi.button(ringRow, getString(R.string.devices_find_ring)) { ring(true) }
        DevicesUi.button(ringRow, getString(R.string.devices_find_stop), outlined = true) { ring(false) }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = Views.dp(ctx, 8)
        }
        DevicesUi.button(t, getString(R.string.devices_sync_now), outlined = true) {
            DevicesUi.toast(ctx, getString(if (vm.syncNow()) R.string.devices_sync_started else R.string.devices_service_refused))
        }

        val m = Views.card(column, null)
        val mc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        m.addView(mc)
        mode = ListRow(ctx).apply {
            title = getString(R.string.devices_connection_mode)
            onClick = { startActivity(Intent(ctx, SettingsActivity::class.java)) }
        }
        mc.addView(mode, Views.matchWrap(ctx))
        capture = DevicesUi.switch(mc, getString(R.string.devices_ble_capture))
        capture.isVisible = WatchSettings.isDebuggable(ctx)
        capture.setOnCheckedChangeListener { _, on -> if (!updating) vm.setBleCapture(on) }

        val r = Views.card(column, null)
        val rc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        r.addView(rc)
        remove = DevicesUi.button(rc, getString(R.string.devices_remove), outlined = true) {
            parentFragmentManager.setFragmentResult(DevicesFragment.REQUEST_REMOVE, bundleOf())
            close()
        }
        collectWhileStarted(vm.state) { s ->
            noWatch.isVisible = !s.loading && s.watch == null
            findBox.isVisible = s.canFind
            remove.isVisible = s.watch != null
            mode.subtitle = getString(if (s.syncOnly) R.string.devices_mode_sync_only else R.string.devices_mode_always)
            updating = true
            capture.isChecked = s.bleCapture
            updating = false
        }
    }

    private fun ring(on: Boolean) {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            DevicesUi.toast(ctx, sendText(ctx, vm.ring(on), if (on) R.string.devices_find_ringing else R.string.devices_find_stopped))
        }
    }
}
