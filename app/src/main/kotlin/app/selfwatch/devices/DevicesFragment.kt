/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.devices

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import app.selfwatch.R
import app.selfwatch.ble.pair.PairFilter
import app.selfwatch.ble.pair.PairedDevice
import app.selfwatch.ble.pair.Pairing
import app.selfwatch.debug.DebugTools
import app.selfwatch.graph
import app.selfwatch.me.HelpFragment
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.time.Day
import app.selfwatch.service.device.WatchStatus
import app.selfwatch.shell.Nav
import app.selfwatch.shell.TabFragment
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.ListRow
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Fmt
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.ZoneId
import kotlin.coroutines.resume

/**
 * Devices (C06, restart plan workstream C): pairing, one card per paired device ([DeviceCardsView]:
 * the feature rows (watch apps, notifications, alarms, linked apps, watch settings), with its live state,
 * "Sync now" and "Remove watch"), the "Data so far" check, and in debug builds only the Debug card
 * ([DebugTools.addDevicesCard]: Bluetooth capture and buttons to share captures and logs).
 */
class DevicesFragment : TabFragment(R.layout.fragment_devices, R.menu.menu_devices) {

    private val vm: DevicesViewModel by viewModels { DevicesViewModel.factory(requireContext().graph) }

    private var permissionWait: CompletableDeferred<Boolean>? = null
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionWait?.complete(granted)
        permissionWait = null
    }

    private val pairing: Pairing by lazy { Pairing(requireActivity()) }

    private lateinit var views: Views
    private var rows: Map<DeviceRow, ListRow> = emptyMap()

    /** Shows the Bluetooth capture state on the Debug card (debug builds only). */
    private var showCapture: ((Boolean) -> Unit)? = null

    private class Views(root: View) {
        val empty: EmptyState = root.findViewById(R.id.watch_empty)
        val emptyCard: SwCard = root.findViewById(R.id.watch_card)
        val cards: LinearLayout = root.findViewById(R.id.device_cards)
        val dataCard: SwCard = root.findViewById(R.id.data_card)
        val activity: ListRow = root.findViewById(R.id.data_activity)
        val steps: ListRow = root.findViewById(R.id.data_steps)
        val metrics: ListRow = root.findViewById(R.id.data_metrics)
        val sleep: ListRow = root.findViewById(R.id.data_sleep)
        val workouts: ListRow = root.findViewById(R.id.data_workouts)
        val files: ListRow = root.findViewById(R.id.data_files)
        val last: ListRow = root.findViewById(R.id.data_last)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val v = Views(view)
        views = v
        v.empty.onButton = { choosePairing() }
        val cards = DeviceCardsView(this, v.cards, vm, { pairing }) {
            if (vm.syncNow()) toast(R.string.devices_sync_started) else toast(R.string.devices_service_refused)
        }
        if (DebugTools.ENABLED) {
            val column = v.dataCard.parent as ViewGroup
            showCapture = DebugTools.addDevicesCard(this, column, column.indexOfChild(v.dataCard) + 1) { vm.setBleCapture(it) }
        }
        rows = DeviceRow.entries.associateWith { r -> view.findViewById<ListRow>(r.viewId) }
        for ((r, row) in rows) {
            row.onClick = when (r) {
                DeviceRow.WATCH_APPS -> { { Nav.open(this, WatchAppsFragment.newInstance()) } }
                DeviceRow.INSTALL -> { { Nav.open(this, WatchAppsFragment.newInstance(install = true)) } }
                DeviceRow.NOTIFICATIONS -> { { Nav.open(this, NotificationSettingsFragment()) } }
                DeviceRow.CALLS -> { { Nav.open(this, CallSettingsFragment()) } }
                DeviceRow.ALARMS -> { { Nav.open(this, AlarmsFragment()) } }
                DeviceRow.WEATHER -> { { Nav.open(this, WeatherFragment()) } }
                DeviceRow.LINKED -> { { Nav.open(this, LinkedAppsFragment()) } }
                DeviceRow.WATCH_SETTINGS -> { { Nav.open(this, WatchSettingsFragment()) } }
                DeviceRow.OTHER -> { { Nav.open(this, OtherDevicesFragment()) } }
            }
        }
        // Registered here (not when the menu item is tapped) so "Pair again" in Bluetooth help survives rotation.
        parentFragmentManager.setFragmentResultListener(HelpFragment.REQUEST_PAIR, viewLifecycleOwner) { _, _ -> startPairing() }
        parentFragmentManager.setFragmentResultListener(REQUEST_REMOVE, viewLifecycleOwner) { _, _ ->
            val s = vm.state.value
            s.devices.firstOrNull { it.id == s.watch?.id }?.let { cards.askRemove(it) }
        }
        collectWhileStarted(vm.state) {
            render(it)
            cards.show(it.devices, it.primaryId, it.busy)
        }
    }

    override fun onMenuItem(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_pair_device -> {
            choosePairing()
            true
        }
        R.id.menu_device_order -> {
            Nav.open(this, DeviceOrderFragment())
            true
        }
        R.id.menu_bluetooth_help -> {
            // "Pair again" in Bluetooth help comes back as a fragment result while this view lives.
            Nav.open(this, HelpFragment.bluetooth())
            true
        }
        else -> false
    }

    /** "Pair new device": a Garmin watch here, anything else on Other devices. */
    private fun choosePairing() {
        val items = arrayOf(getString(R.string.devices_pair_choose_watch), getString(R.string.devices_pair_choose_other))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.devices_pair_choose_title)
            .setItems(items) { _, which -> if (which == 0) startPairing() else Nav.open(this, OtherDevicesFragment()) }
            .show()
    }

    private fun render(s: DevicesUiState) {
        val v = views
        val ctx = requireContext()
        val w = s.watch
        val none = w == null && s.devices.isEmpty() && !s.loading
        toolbar.subtitle = if (w == null && !s.loading) getString(R.string.devices_no_watch) else null
        v.emptyCard.isVisible = none
        v.empty.buttonText = getString(if (s.busy) R.string.devices_pairing else R.string.devices_pair)
        v.dataCard.isVisible = !none && !s.loading
        s.counts?.let { c ->
            v.activity.trailing = Fmt.number(c.activityMinutes)
            v.steps.trailing = Fmt.number(c.steps)
            v.metrics.trailing = Fmt.number(c.metrics)
            v.sleep.trailing = Fmt.number(c.sleepStages)
            v.workouts.trailing = Fmt.number(c.workouts)
            v.files.trailing = Fmt.number(c.filesStored)
            v.last.trailing = c.lastIngestSec?.let { whenText(ctx, it) } ?: getString(R.string.dash)
        }
        showCapture?.invoke(s.bleCapture)
        val features = w?.features.orEmpty()
        for ((r, row) in rows) row.isVisible = DeviceRow.visible(r, features)
    }

    // ---- Pairing ----------------------------------------------------------------------------------

    private fun startPairing() {
        if (vm.state.value.busy) return
        lifecycleScope.launch {
            vm.setBusy(true)
            try {
                pairFlow()
            } finally {
                vm.setBusy(false)
            }
        }
    }

    private suspend fun pairFlow() {
        if (!ensureBluetoothConnect()) {
            toast(R.string.devices_bt_denied, long = true)
            return
        }
        when (val r = pairing.pair(PairFilter.GARMIN, bond = false)) {
            is Outcome.Ok -> {
                vm.savePaired(r.value)
                toastText(getString(R.string.devices_paired, r.value.name ?: AppDeviceActions.UNNAMED))
                ensureNotifications()
                if (!vm.startService()) toast(R.string.devices_service_refused, long = true)
            }
            is Outcome.Fail -> {
                SwLog.i(TAG, "pairing ended: ${r.error}")
                if (r.error is SwError.PermissionMissing) {
                    toastText(getString(R.string.devices_pair_failed, WatchStatus.reason(r.error)))
                } else {
                    // Cancelled or nothing found: usually the watch isn't in pairing mode (it then
                    // broadcasts without a name and the Garmin filter hides it).
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.devices_pair_hint_title)
                        .setMessage(R.string.devices_pair_hint)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    /** `BLUETOOTH_CONNECT` (API 31+), asked with its reason. */
    private suspend fun ensureBluetoothConnect(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val p = Manifest.permission.BLUETOOTH_CONNECT
        if (granted(p)) return true
        if (!askReason(R.string.devices_bt_reason)) return false
        return request(p)
    }

    /** `POST_NOTIFICATIONS` (API 33+), asked with its reason. Refusing is fine: the service still runs. */
    private suspend fun ensureNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val p = Manifest.permission.POST_NOTIFICATIONS
        if (granted(p)) return
        if (askReason(R.string.devices_notify_reason)) request(p)
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED

    private suspend fun request(permission: String): Boolean {
        val wait = CompletableDeferred<Boolean>()
        permissionWait = wait
        permissionLauncher.launch(permission)
        return wait.await()
    }

    private suspend fun askReason(@StringRes reason: Int): Boolean = suspendCancellableCoroutine { cont ->
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setMessage(reason)
            .setPositiveButton(R.string.devices_continue) { _, _ -> if (cont.isActive) cont.resume(true) }
            .setNegativeButton(R.string.devices_not_now) { _, _ -> if (cont.isActive) cont.resume(false) }
            .setOnCancelListener { if (cont.isActive) cont.resume(false) }
            .show()
        cont.invokeOnCancellation { dialog.dismiss() }
    }

    private fun toast(@StringRes text: Int, long: Boolean = false) = toastText(getString(text), long)

    private fun toastText(text: CharSequence, long: Boolean = false) {
        Toast.makeText(requireContext(), text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    /** Text helpers (pure, for tests). */
    companion object {
        private const val TAG = "DevicesFragment"

        /** Fragment result asking Devices to remove the watch (sent by Watch settings). */
        const val REQUEST_REMOVE: String = "devices.remove"

        /** The status line for [state] (null: the service isn't managing the watch). */
        fun statusText(ctx: Context, state: ConnectionState?): String = when (state) {
            null -> ctx.getString(R.string.devices_state_off)
            ConnectionState.Disconnected -> ctx.getString(R.string.devices_state_disconnected)
            ConnectionState.Connecting -> ctx.getString(R.string.devices_state_connecting)
            ConnectionState.Handshaking -> ctx.getString(R.string.devices_state_handshaking)
            ConnectionState.Ready -> ctx.getString(R.string.devices_state_ready)
            is ConnectionState.Syncing ->
                if (state.total > 0) {
                    ctx.getString(R.string.devices_state_syncing_progress, state.done, state.total, state.label)
                } else {
                    ctx.getString(R.string.devices_state_syncing)
                }
            is ConnectionState.Failed -> {
                val reason = WatchStatus.reason(state.error)
                val retry = state.retryInSec
                if (retry != null) {
                    ctx.getString(R.string.devices_state_failed_retry, reason, retry)
                } else {
                    ctx.getString(R.string.devices_state_failed, reason)
                }
            }
        }

        /** "Today 08:12", "Yesterday 22:40", "Sat 22 Nov 09:00". */
        fun whenText(ctx: Context, tSec: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            Fmt.day(ctx, Day.of(tSec, zone).yyyymmdd) + " " + Fmt.time(ctx, tSec, zone)
    }
}
