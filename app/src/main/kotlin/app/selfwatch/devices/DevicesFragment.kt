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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import app.selfwatch.R
import app.selfwatch.SelfWatchApp
import app.selfwatch.ble.log.BleLog
import app.selfwatch.ble.pair.PairFilter
import app.selfwatch.ble.pair.PairedDevice
import app.selfwatch.ble.pair.Pairing
import app.selfwatch.graph
import app.selfwatch.log.LogSinks
import app.selfwatch.me.HelpFragment
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.time.Day
import app.selfwatch.service.device.WatchStatus
import app.selfwatch.shell.Nav
import app.selfwatch.shell.TabFragment
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.ListRow
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Fmt
import app.selfwatch.watch.WatchSettings
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.ZoneId
import kotlin.coroutines.resume

/**
 * Devices (C06): pair the watch (Nearby devices permission, then the system picker), the watch card
 * with its live state, "Sync now" and "Remove watch", the "Data so far" check, and in debug builds the
 * Bluetooth capture switch and buttons to share captures and logs.
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

    private class Views(root: View) {
        val empty: EmptyState = root.findViewById(R.id.watch_empty)
        val paired: View = root.findViewById(R.id.watch_paired)
        val name: TextView = root.findViewById(R.id.watch_name)
        val status: TextView = root.findViewById(R.id.watch_status)
        val detail: TextView = root.findViewById(R.id.watch_detail)
        val sync: MaterialButton = root.findViewById(R.id.watch_sync)
        val remove: MaterialButton = root.findViewById(R.id.watch_remove)
        val dataCard: SwCard = root.findViewById(R.id.data_card)
        val activity: ListRow = root.findViewById(R.id.data_activity)
        val steps: ListRow = root.findViewById(R.id.data_steps)
        val metrics: ListRow = root.findViewById(R.id.data_metrics)
        val sleep: ListRow = root.findViewById(R.id.data_sleep)
        val workouts: ListRow = root.findViewById(R.id.data_workouts)
        val files: ListRow = root.findViewById(R.id.data_files)
        val last: ListRow = root.findViewById(R.id.data_last)
        val debugCard: SwCard = root.findViewById(R.id.debug_card)
        val capture: MaterialSwitch = root.findViewById(R.id.debug_capture)
        val shareCapture: MaterialButton = root.findViewById(R.id.debug_share_capture)
        val shareLogs: MaterialButton = root.findViewById(R.id.debug_share_logs)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val v = Views(view)
        views = v
        v.empty.onButton = { startPairing() }
        v.sync.setOnClickListener {
            if (vm.syncNow()) toast(R.string.devices_sync_started) else toast(R.string.devices_service_refused)
        }
        v.remove.setOnClickListener { vm.state.value.watch?.let { confirmRemove(it) } }
        v.debugCard.isVisible = WatchSettings.isDebuggable(requireContext())
        v.capture.setOnCheckedChangeListener { button, checked -> if (button.isPressed) vm.setBleCapture(checked) }
        v.shareCapture.setOnClickListener { share(BleLog.forContext(requireContext()).dir, "application/octet-stream", "Self Watch Bluetooth capture") }
        v.shareLogs.setOnClickListener {
            (requireContext().applicationContext as? SelfWatchApp)?.logFile?.flush()
            share(LogSinks.dir(requireContext()), "text/plain", "Self Watch logs")
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
            vm.state.value.watch?.let { confirmRemove(it) }
        }
        val others = OtherDeviceCards(this, view.findViewById(R.id.other_devices), vm) { pairing }
        collectWhileStarted(vm.state) {
            render(it)
            others.show(it.others, it.primaryId)
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
        toolbar.subtitle = if (w == null && !s.loading) getString(R.string.devices_no_watch) else null
        v.empty.isVisible = w == null && !s.loading
        v.empty.buttonText = getString(if (s.busy) R.string.devices_pairing else R.string.devices_pair)
        v.paired.isVisible = w != null
        v.dataCard.isVisible = w != null
        if (w != null) {
            v.name.text = if (w.model.isNotBlank() && w.model != w.name) getString(R.string.devices_detail_join, w.name, w.model) else w.name
            v.status.text = statusText(ctx, w.state)
            val sync = w.lastSyncSec?.let { getString(R.string.devices_last_sync, whenText(ctx, it)) }
                ?: getString(R.string.devices_never_synced)
            v.detail.text = w.batteryPct?.let { getString(R.string.devices_detail_join, sync, getString(R.string.devices_battery, it)) } ?: sync
            v.sync.isEnabled = !s.busy
            v.remove.isEnabled = !s.busy
        }
        s.counts?.let { c ->
            v.activity.trailing = Fmt.number(c.activityMinutes)
            v.steps.trailing = Fmt.number(c.steps)
            v.metrics.trailing = Fmt.number(c.metrics)
            v.sleep.trailing = Fmt.number(c.sleepStages)
            v.workouts.trailing = Fmt.number(c.workouts)
            v.files.trailing = Fmt.number(c.filesStored)
            v.last.trailing = c.lastIngestSec?.let { whenText(ctx, it) } ?: getString(R.string.dash)
        }
        if (v.capture.isChecked != s.bleCapture) v.capture.isChecked = s.bleCapture
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
                toastText(getString(R.string.devices_pair_failed, WatchStatus.reason(r.error)))
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

    // ---- Remove -----------------------------------------------------------------------------------

    private fun confirmRemove(w: WatchCard) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.devices_remove_title, w.name))
            .setMessage(R.string.devices_remove_message)
            .setPositiveButton(R.string.devices_remove) { _, _ -> remove(w) }
            .setNegativeButton(R.string.devices_not_now, null)
            .show()
    }

    private fun remove(w: WatchCard) {
        lifecycleScope.launch {
            vm.setBusy(true)
            try {
                val r = pairing.unpair(PairedDevice(w.address, w.name, w.associationId))
                if (r is Outcome.Fail) SwLog.w(TAG, "unpair: ${r.error}")
                vm.forget(w)
                val fully = r is Outcome.Ok && r.value.bondRemoved
                toast(if (fully) R.string.devices_removed else R.string.devices_remove_forget, long = !fully)
            } finally {
                vm.setBusy(false)
            }
        }
    }

    // ---- Sharing ----------------------------------------------------------------------------------

    private fun share(dir: java.io.File, mime: String, subject: String) {
        val ctx = requireContext()
        val intent = FileShare.intent(ctx, FileShare.filesIn(dir), mime, subject, getString(R.string.devices_share_title))
        if (intent == null || !FileShare.start(ctx, intent)) toast(R.string.devices_share_none)
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
