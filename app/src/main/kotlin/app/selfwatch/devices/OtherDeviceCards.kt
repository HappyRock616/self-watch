/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.devices

import android.content.Context
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.selfwatch.R
import app.selfwatch.ble.pair.PairedDevice
import app.selfwatch.ble.pair.Pairing
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.service.device.WatchStatus
import app.selfwatch.shell.Views
import app.selfwatch.ui.components.ListRow
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * The Devices tab's compact cards for every paired device besides the main watch card: more watches,
 * scales, straps and cuffs, each with its state, battery and (for straps) live heart rate. Tapping a
 * card offers "Make primary" and "Remove".
 */
class OtherDeviceCards(
    private val fragment: Fragment,
    private val container: LinearLayout,
    private val vm: DevicesViewModel,
    private val pairing: () -> Pairing,
) {
    private var shown: List<DeviceCard>? = null

    /** Shows [others]; [primaryId] is the first device of the order (it may be the main watch). */
    fun show(others: List<DeviceCard>, primaryId: Long?) {
        if (others == shown) return
        shown = others
        container.removeAllViews()
        val ctx = container.context
        for (d in others) {
            val card = Views.card(container, d.name)
            val row = ListRow(ctx)
            val kind = ctx.getString(SensorType.label(d.kind))
            row.title = if (d.id == primaryId) ctx.getString(R.string.device_order_primary, kind) else kind
            row.subtitle = detail(ctx, d)
            row.contentDescription = ctx.getString(R.string.devices_device_actions_cd, d.name)
            row.onClick = { actions(d, d.id == primaryId) }
            card.addView(row, Views.matchWrap(ctx))
        }
    }

    private fun actions(d: DeviceCard, primary: Boolean) {
        val ctx = fragment.requireContext()
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (!primary) items += ctx.getString(R.string.devices_make_primary) to { vm.makePrimary(d.id) }
        items += ctx.getString(R.string.devices_other_remove) to { confirmRemove(d) }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(d.name)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun confirmRemove(d: DeviceCard) {
        val ctx = fragment.requireContext()
        DevicesUi.confirm(ctx, ctx.getString(R.string.devices_remove_title, d.name), ctx.getString(R.string.devices_remove_device_message), R.string.devices_other_remove) {
            remove(d)
        }
    }

    private fun remove(d: DeviceCard) {
        val app = fragment.requireContext().applicationContext
        fragment.lifecycleScope.launch {
            vm.setBusy(true)
            try {
                val r = pairing().unpair(PairedDevice(d.address, d.name, d.associationId))
                if (r is Outcome.Fail) SwLog.w(TAG, "unpair: ${r.error}")
                vm.forget(d)
                val fully = r is Outcome.Ok && r.value.bondRemoved
                DevicesUi.toast(app, app.getString(if (fully) R.string.devices_device_removed else R.string.devices_device_remove_forget), long = !fully)
            } finally {
                vm.setBusy(false)
            }
        }
    }

    /** Text helpers (pure, for tests). */
    companion object {
        private const val TAG = "OtherDeviceCards"

        /** "Connected · Battery 80% · Heart rate 72 bpm" for [d]. */
        fun detail(ctx: Context, d: DeviceCard): String {
            val parts = ArrayList<String>()
            parts += stateText(ctx, d)
            d.batteryPct?.let { parts += ctx.getString(R.string.devices_battery, it) }
            d.heartRateNow?.let { parts += ctx.getString(R.string.devices_hr_now, it) }
            return parts.joinToString(" · ")
        }

        /** Watches use the watch wording; a sensor that is not connected is simply asleep. */
        fun stateText(ctx: Context, d: DeviceCard): String {
            val s = d.state
            if (d.isWatch) return DevicesFragment.statusText(ctx, s)
            return when {
                s is ConnectionState.Ready || s is ConnectionState.Syncing -> ctx.getString(R.string.devices_state_ready)
                s is ConnectionState.Connecting || s is ConnectionState.Handshaking -> ctx.getString(R.string.devices_state_connecting)
                s is ConnectionState.Failed && s.retryInSec == null ->
                    ctx.getString(R.string.devices_state_failed, WatchStatus.reason(s.error))
                else -> ctx.getString(R.string.devices_sensor_waiting)
            }
        }
    }
}
