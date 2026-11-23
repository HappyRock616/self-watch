/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.devices

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.AppGraph
import app.selfwatch.R
import app.selfwatch.ble.pair.PairedDevice
import app.selfwatch.ble.pair.Pairing
import app.selfwatch.graph
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.service.device.WatchStatus
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.Views
import app.selfwatch.ui.components.ListRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Other devices state: the paired non-watch devices. */
data class OtherDevicesUiState(val paired: List<DeviceCard> = emptyList(), val busy: Boolean = false, val loading: Boolean = true)

/** Other devices: pair and remove scales, straps and cuffs. */
class OtherDevicesViewModel(private val list: DeviceList) : ViewModel() {
    private val busy = MutableStateFlow(false)

    /** Screen state. */
    val state: StateFlow<OtherDevicesUiState> =
        combine(list.devices.map { all -> all.filter { !it.isWatch } }, busy) { paired, b -> OtherDevicesUiState(paired, b, loading = false) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), OtherDevicesUiState())

    /** Marks pairing or removing as in progress. */
    fun setBusy(on: Boolean) {
        busy.value = on
    }

    /** Saves a newly paired device of [type] and starts the service. */
    suspend fun savePaired(paired: PairedDevice, type: SensorType, fallbackName: String): Boolean {
        list.savePairedSensor(paired, type.kind, fallbackName)
        return list.startService()
    }

    /** Forgets [device] (after its companion association is ended). */
    suspend fun forget(device: DeviceCard) {
        list.forget(device)
    }

    /** Factory. */
    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { OtherDevicesViewModel(graph.deviceList) }
        }
    }
}

/**
 * Other devices (Devices › Other devices): the device types that follow the open Bluetooth SIG health
 * profiles, each with a Pair button (the system picker, filtered by the type's service UUIDs, so no
 * location permission), the paired ones with Remove, and an honest list of what is not supported yet.
 */
class OtherDevicesFragment : DetailFragment() {

    private val vm: OtherDevicesViewModel by viewModels { OtherDevicesViewModel.factory(requireContext().graph) }
    private val permissions = PermissionRequester(this)
    private val pairing: Pairing by lazy { Pairing(requireActivity()) }
    private val pairButtons = ArrayList<android.view.View>()

    override fun title(): CharSequence = getString(R.string.devices_other)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        column.addView(Views.caption(ctx, getString(R.string.devices_other_intro)), Views.matchWrap(ctx))

        val supported = Views.card(column, getString(R.string.devices_other_supported))
        pairButtons.clear()
        for (type in SensorType.entries) {
            val title = getString(type.title)
            supported.addView(Views.text(ctx, app.selfwatch.ui.R.style.TextAppearance_SelfWatch_Title, title), Views.matchWrap(ctx, 12))
            supported.addView(Views.caption(ctx, getString(type.summary)), Views.matchWrap(ctx, 4))
            val holder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            supported.addView(holder, Views.matchWrap(ctx))
            val b = DevicesUi.button(holder, getString(R.string.devices_other_pair)) { pair(type) }
            b.contentDescription = getString(R.string.devices_other_pair_cd, title.lowercase())
            pairButtons += b
        }

        val pairedCard = Views.card(column, getString(R.string.devices_other_paired))
        val pairedRows = LinearLayout(ctx).apply {
            id = R.id.other_devices_paired
            orientation = LinearLayout.VERTICAL
        }
        pairedCard.addView(pairedRows, Views.matchWrap(ctx))

        val unsupported = Views.card(column, getString(R.string.devices_other_unsupported_title))
        unsupported.addView(Views.caption(ctx, getString(R.string.devices_other_unsupported)), Views.matchWrap(ctx))

        collectWhileStarted(vm.state) { s ->
            if (s.loading) return@collectWhileStarted
            pairButtons.forEach { it.isEnabled = !s.busy }
            showPaired(pairedRows, s.paired, s.busy)
        }
    }

    private fun showPaired(rows: LinearLayout, paired: List<DeviceCard>, busy: Boolean) {
        val ctx = rows.context
        rows.removeAllViews()
        if (paired.isEmpty()) {
            rows.addView(Views.caption(ctx, getString(R.string.devices_other_none)), Views.matchWrap(ctx))
            return
        }
        for (d in paired) {
            val row = ListRow(ctx)
            row.title = d.name
            row.subtitle = getString(R.string.devices_detail_join, getString(SensorType.label(d.kind)), OtherDeviceCards.detail(ctx, d))
            rows.addView(row, Views.matchWrap(ctx))
            val remove = DevicesUi.button(rows, getString(R.string.devices_other_remove), outlined = true) { confirmRemove(d) }
            remove.contentDescription = getString(R.string.devices_other_remove_cd, d.name)
            remove.isEnabled = !busy
        }
    }

    private fun pair(type: SensorType) {
        if (vm.state.value.busy) return
        val app = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            vm.setBusy(true)
            try {
                if (!ensureBluetoothConnect()) {
                    DevicesUi.toast(app, app.getString(R.string.devices_bt_denied), long = true)
                    return@launch
                }
                val fallback = app.getString(type.title)
                when (val r = pairing.pair(type.filter, bond = false)) {
                    is Outcome.Ok -> {
                        val started = vm.savePaired(r.value, type, fallback)
                        DevicesUi.toast(app, app.getString(R.string.devices_paired, r.value.name ?: fallback))
                        if (!started) DevicesUi.toast(app, app.getString(R.string.devices_service_refused), long = true)
                    }
                    is Outcome.Fail -> {
                        SwLog.i(TAG, "pairing ended: ${r.error}")
                        DevicesUi.toast(app, app.getString(R.string.devices_pair_failed, WatchStatus.reason(r.error)))
                    }
                }
            } finally {
                vm.setBusy(false)
            }
        }
    }

    /** `BLUETOOTH_CONNECT` (API 31+), asked with its reason. */
    private suspend fun ensureBluetoothConnect(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val p = Manifest.permission.BLUETOOTH_CONNECT
        if (ContextCompat.checkSelfPermission(requireContext(), p) == android.content.pm.PackageManager.PERMISSION_GRANTED) return true
        if (!DevicesUi.askReason(requireContext(), R.string.devices_other_bt_reason)) return false
        return permissions.request(listOf(p))[p] == true
    }

    private fun confirmRemove(d: DeviceCard) {
        val ctx = requireContext()
        DevicesUi.confirm(ctx, getString(R.string.devices_remove_title, d.name), getString(R.string.devices_remove_device_message), R.string.devices_other_remove) {
            remove(d)
        }
    }

    private fun remove(d: DeviceCard) {
        val app = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            vm.setBusy(true)
            try {
                val r = pairing.unpair(PairedDevice(d.address, d.name, d.associationId))
                if (r is Outcome.Fail) SwLog.w(TAG, "unpair: ${r.error}")
                vm.forget(d)
                val fully = r is Outcome.Ok && r.value.bondRemoved
                DevicesUi.toast(app, app.getString(if (fully) R.string.devices_device_removed else R.string.devices_device_remove_forget), long = !fully)
            } finally {
                vm.setBusy(false)
            }
        }
    }

    private companion object {
        const val TAG = "OtherDevices"
    }
}
