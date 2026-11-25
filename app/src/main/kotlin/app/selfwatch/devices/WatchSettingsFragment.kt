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
import app.selfwatch.debug.DebugTools
import app.selfwatch.graph
import app.selfwatch.me.SettingsActivity
import app.selfwatch.me.SwitchRow
import app.selfwatch.model.device.Feature
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.Views
import app.selfwatch.ui.components.ListRow
import app.selfwatch.watch.WatchSettings
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    /** This watch's "answer the watch's Garmin sign-in requests" switch (off by default). */
    val fakeAuth: Boolean = false,
    /** The watch asked to sign in to Garmin and nobody answered (weather and GPS help may stop). */
    val signInAsked: Boolean = false,
) {
    /** True when find my watch shows. */
    val canFind: Boolean get() = watch != null && FeatureGate.shows(watch.features, Feature.FIND_WATCH)

    /** True when the "your watch asked to sign in" hint shows. */
    val showSignInHint: Boolean get() = watch != null && signInAsked && !fakeAuth
}

/**
 * Watch settings (C19): find my watch, sync now, connection mode, the per-watch Garmin sign-in answer
 * (COMPATIBILITY R1), Bluetooth capture (debug), remove.
 */
class WatchSettingsViewModel(
    private val db: SelfWatchDb,
    private val commands: WatchCommands,
    private val debuggable: Boolean,
) : ViewModel() {

    /** Screen state. */
    val state: StateFlow<WatchSettingsUiState> =
        db.changes.observe(setOf(Table.SETTING, Table.DEVICE)).conflate()
            .map {
                val watch = ActiveWatches.load(db)
                WatchSettingsUiState(
                    loading = false,
                    watch = watch,
                    syncOnly = db.settings.get(SettingKeys.CONNECTION_MODE) == SYNC_ONLY,
                    bleCapture = WatchSettings.bleCaptureOn(db.settings.get(WatchSettings.BLE_CAPTURE), debuggable),
                    fakeAuth = watch != null && WatchSettings.fakeAuthOn(db.settings, watch.id),
                    signInAsked = watch != null && db.settings.get(SettingKeys.deviceSignInAsked(watch.id)) != null,
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

    /**
     * The current watch's Garmin sign-in answer. The screen calls this with true only after the user
     * read the warning and chose "Turn on". Turning it on also clears the "watch asked" hint.
     */
    fun setFakeAuth(on: Boolean) {
        viewModelScope.launch {
            val w = ActiveWatches.load(db) ?: return@launch
            val updates = buildMap<String, String?> {
                put(SettingKeys.deviceFakeAuth(w.id), on.toString())
                if (on) put(SettingKeys.deviceSignInAsked(w.id), null)
            }
            db.settings.setAll(updates)
        }
    }

    /** The Bluetooth capture switch (debug builds only; ignored otherwise). */
    fun setBleCapture(on: Boolean) {
        if (debuggable) viewModelScope.launch { db.settings.setBool(WatchSettings.BLE_CAPTURE, on) }
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
        WatchSettingsViewModel.factory(requireContext().graph, DebugTools.ENABLED)
    }

    private lateinit var findBox: LinearLayout
    private lateinit var mode: ListRow
    private lateinit var fakeAuth: SwitchRow
    private lateinit var signInHint: TextView
    private var showCapture: ((Boolean) -> Unit)? = null
    private lateinit var remove: MaterialButton
    private lateinit var noWatch: TextView

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
        fakeAuth = SwitchRow(ctx, getString(R.string.devices_fake_auth), getString(R.string.devices_fake_auth_help)).apply {
            onChange = { on -> if (on) confirmFakeAuth() else vm.setFakeAuth(false) }
        }
        mc.addView(fakeAuth, Views.matchWrap(ctx))
        signInHint = DevicesUi.note(mc, getString(R.string.devices_fake_auth_hint))
        if (DebugTools.ENABLED) showCapture = DebugTools.addCaptureSwitch(mc) { vm.setBleCapture(it) }

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
            fakeAuth.isVisible = s.watch != null
            fakeAuth.checked = s.fakeAuth
            signInHint.isVisible = s.showSignInHint
            showCapture?.invoke(s.bleCapture)
        }
    }

    /** The risk in plain words; only "Turn on" turns the Garmin sign-in answer on. */
    private fun confirmFakeAuth() {
        fakeAuth.checked = false
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.devices_fake_auth_confirm_title)
            .setMessage(R.string.devices_fake_auth_confirm)
            .setPositiveButton(R.string.devices_fake_auth_turn_on) { _, _ -> vm.setFakeAuth(true) }
            .setNegativeButton(R.string.devices_fake_auth_keep_off, null)
            .show()
    }

    private fun ring(on: Boolean) {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            DevicesUi.toast(ctx, sendText(ctx, vm.ring(on), if (on) R.string.devices_find_ringing else R.string.devices_find_stopped))
        }
    }
}
