/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.devices

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import app.selfwatch.R
import app.selfwatch.graph
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.Views
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch

/** The text for a [SendOutcome]. */
internal fun sendText(ctx: Context, o: SendOutcome, @StringRes sent: Int): String = ctx.getString(
    when (o) {
        SendOutcome.SENT -> sent
        SendOutcome.NOT_CONNECTED -> R.string.devices_watch_not_connected
        SendOutcome.REFUSED -> R.string.devices_service_refused
    },
)

/**
 * Notifications settings (C19): the master switch (turning it on without notification access explains
 * why in one sentence and opens the system screen), the per-app list and "Send test notification".
 */
class NotificationSettingsFragment : DetailFragment() {

    private val vm: NotificationSettingsViewModel by viewModels { NotificationSettingsViewModel.factory(requireContext().graph) }

    private lateinit var master: MaterialSwitch
    private lateinit var accessNote: TextView
    private lateinit var accessButton: MaterialButton
    private lateinit var testButton: MaterialButton
    private lateinit var appsBox: LinearLayout
    private lateinit var appsNote: TextView
    private val switches = LinkedHashMap<String, MaterialSwitch>()
    private var shownApps: List<PhoneApp>? = null
    private var updating = false

    override fun title(): CharSequence = getString(R.string.devices_notifications)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val top = Views.card(column, null, first = true)
        val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        top.addView(t)
        master = DevicesUi.switch(t, getString(R.string.devices_notify_master))
        master.setOnCheckedChangeListener { _, on -> if (!updating) setEnabled(on) }
        accessNote = DevicesUi.note(t, null)
        accessButton = DevicesUi.button(t, getString(R.string.devices_notify_open_access), outlined = true) { openAccess() }
        testButton = DevicesUi.button(t, getString(R.string.devices_notify_test), outlined = true) {
            viewLifecycleOwner.lifecycleScope.launch {
                DevicesUi.toast(ctx, sendText(ctx, vm.sendTest(), R.string.devices_notify_test_sent))
            }
        }

        val appsCard = Views.card(column, getString(R.string.devices_notify_apps))
        val a = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        appsCard.addView(a)
        appsNote = DevicesUi.note(a, getString(R.string.devices_notify_apps_loading))
        appsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        a.addView(appsBox, Views.matchWrap(ctx, 4))
        collectWhileStarted(vm.state) { render(it) }
    }

    override fun onResume() {
        super.onResume()
        vm.recheck()
    }

    private fun setEnabled(on: Boolean) {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val needsAccess = vm.setEnabled(on)
            if (needsAccess && DevicesUi.askReason(ctx, R.string.devices_notify_access_reason)) openAccess()
            vm.recheck()
        }
    }

    private fun openAccess() {
        val ctx = requireContext()
        val ok = DevicesUi.open(
            ctx,
            AndroidPermissionChecker.notificationAccessIntent(ctx),
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        )
        if (!ok) DevicesUi.toast(ctx, getString(R.string.devices_notify_access_manual), long = true)
    }

    private fun render(s: NotificationSettingsUiState) {
        val ctx = requireContext()
        updating = true
        master.isChecked = s.on
        accessNote.text = getString(
            when {
                s.access -> R.string.devices_notify_access_on
                s.wanted -> R.string.devices_notify_access_needed
                else -> R.string.devices_notify_off
            },
        )
        accessButton.isVisible = s.wanted && !s.access
        testButton.isVisible = s.on
        if (shownApps != s.apps) {
            shownApps = s.apps
            appsBox.removeAllViews()
            switches.clear()
            for (app in s.apps) {
                val sw = DevicesUi.switch(appsBox, app.label)
                appIcon(ctx, app.packageName)?.let { d ->
                    val size = Views.dp(ctx, 32)
                    d.setBounds(0, 0, size, size)
                    sw.setCompoundDrawablesRelative(d, null, null, null)
                    sw.compoundDrawablePadding = Views.dp(ctx, 12)
                }
                sw.setOnCheckedChangeListener { _, on ->
                    if (!updating) viewLifecycleOwner.lifecycleScope.launch { vm.setAllowed(app.packageName, on) }
                }
                switches[app.packageName] = sw
            }
        }
        for ((pkg, sw) in switches) {
            sw.isChecked = pkg !in s.denied
            sw.isEnabled = s.on
        }
        updating = false
        appsNote.text = getString(if (s.loading) R.string.devices_notify_apps_loading else R.string.devices_notify_apps_note)
    }

    private fun appIcon(ctx: Context, pkg: String) = try {
        ctx.packageManager.getApplicationIcon(pkg)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}

/**
 * Calls settings (C19): call alerts (asks for phone state and answering calls when turned on), caller
 * number (call log, API 29+) and caller name (contacts), each asked at that moment with its reason.
 */
class CallSettingsFragment : DetailFragment() {

    private val vm: CallSettingsViewModel by viewModels { CallSettingsViewModel.factory(requireContext().graph) }
    private val permissions = PermissionRequester(this)

    private lateinit var calls: MaterialSwitch
    private lateinit var answerNote: TextView
    private lateinit var number: MaterialSwitch
    private lateinit var name: MaterialSwitch
    private var updating = false

    override fun title(): CharSequence = getString(R.string.devices_calls)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val card = Views.card(column, null, first = true)
        val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        card.addView(c)
        calls = DevicesUi.switch(c, getString(R.string.devices_calls_master))
        answerNote = DevicesUi.note(c, null)
        number = DevicesUi.switch(c, getString(R.string.devices_calls_number))
        DevicesUi.note(c, getString(R.string.devices_calls_number_note))
        name = DevicesUi.switch(c, getString(R.string.devices_calls_name))
        DevicesUi.note(c, getString(R.string.devices_calls_name_note))
        calls.setOnCheckedChangeListener { _, on -> if (!updating) flip(CallOption.CALLS, on) }
        number.setOnCheckedChangeListener { _, on -> if (!updating) flip(CallOption.NUMBER, on) }
        name.setOnCheckedChangeListener { _, on -> if (!updating) flip(CallOption.NAME, on) }
        collectWhileStarted(vm.state) { render(it) }
    }

    override fun onResume() {
        super.onResume()
        vm.recheck()
    }

    private fun flip(option: CallOption, on: Boolean) {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val missing = vm.prepare(option, on)
            if (missing.isEmpty()) return@launch
            val reason = when (option) {
                CallOption.CALLS -> R.string.devices_calls_reason
                CallOption.NUMBER -> R.string.devices_calls_number_reason
                CallOption.NAME -> R.string.devices_calls_name_reason
            }
            if (!DevicesUi.askReason(ctx, reason)) {
                vm.recheck()
                return@launch
            }
            val ok = vm.granted(option, permissions.request(missing))
            if (!ok) DevicesUi.toast(ctx, getString(R.string.devices_permission_refused), long = true)
        }
    }

    private fun render(s: CallSettingsUiState) {
        updating = true
        calls.isChecked = s.calls
        number.isChecked = s.number
        name.isChecked = s.name
        number.isEnabled = s.calls
        name.isEnabled = s.calls
        updating = false
        answerNote.text = getString(if (s.calls && !s.canAnswer) R.string.devices_calls_no_answer else R.string.devices_calls_note)
    }
}

/** Weather on watch (C19): the switch, what it needs (a weather app that sends forecasts) and the last forecast. */
class WeatherFragment : DetailFragment() {

    private val vm: WeatherViewModel by viewModels { WeatherViewModel.factory(requireContext().graph) }
    private lateinit var enabled: MaterialSwitch
    private lateinit var last: TextView
    private var updating = false

    override fun title(): CharSequence = getString(R.string.devices_weather)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val card = Views.card(column, null, first = true)
        val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        card.addView(c)
        enabled = DevicesUi.switch(c, getString(R.string.devices_weather_master))
        enabled.setOnCheckedChangeListener { _, on -> if (!updating) vm.setEnabled(on) }
        DevicesUi.note(c, getString(R.string.devices_weather_note))
        last = DevicesUi.note(c, null)
        collectWhileStarted(vm.state) { s ->
            updating = true
            enabled.isChecked = s.enabled
            updating = false
            last.text = s.lastForecastSec?.let { getString(R.string.devices_weather_last, DevicesFragment.whenText(ctx, it)) }
                ?: getString(R.string.devices_weather_none)
        }
    }
}
