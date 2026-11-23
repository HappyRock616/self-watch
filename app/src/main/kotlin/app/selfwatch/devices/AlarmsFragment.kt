/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.devices

import android.app.TimePickerDialog
import android.content.Context
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import app.selfwatch.R
import app.selfwatch.graph
import app.selfwatch.model.watch.Alarm
import app.selfwatch.service.device.AlarmSettings
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.Views
import app.selfwatch.ui.components.ListRow
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Alarms (C19): up to 10 alarms per watch, edited here and sent with "Send to watch". */
class AlarmsFragment : DetailFragment() {

    private val vm: AlarmsViewModel by viewModels { AlarmsViewModel.factory(requireContext().graph) }

    private lateinit var unsupported: TextView
    private lateinit var empty: TextView
    private lateinit var list: LinearLayout
    private lateinit var add: MaterialButton
    private lateinit var send: MaterialButton
    private lateinit var last: TextView

    override fun title(): CharSequence = getString(R.string.devices_alarms)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val card = Views.card(column, null, first = true)
        val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        card.addView(c)
        unsupported = DevicesUi.note(c, getString(R.string.devices_alarms_unsupported))
        empty = DevicesUi.note(c, getString(R.string.devices_alarms_empty))
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        c.addView(list, Views.matchWrap(ctx))
        add = DevicesUi.button(c, getString(R.string.devices_alarms_add), outlined = true) { edit(null) }
        val s = Views.card(column, null)
        val sc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        s.addView(sc)
        send = DevicesUi.button(sc, getString(R.string.devices_alarms_send)) {
            viewLifecycleOwner.lifecycleScope.launch { DevicesUi.toast(ctx, sendText(ctx, vm.send(), R.string.devices_alarms_sending)) }
        }
        last = DevicesUi.note(sc, null)
        collectWhileStarted(vm.state) { render(it) }
    }

    private fun render(s: AlarmsUiState) {
        val ctx = requireContext()
        unsupported.isVisible = !s.supported || (!s.loading && s.watch == null)
        unsupported.text = getString(if (s.watch == null) R.string.devices_need_watch else R.string.devices_alarms_unsupported)
        empty.isVisible = s.supported && s.watch != null && s.alarms.isEmpty()
        add.isVisible = s.supported && s.watch != null
        add.isEnabled = s.canAdd
        send.isEnabled = s.supported && s.watch != null
        last.text = s.lastSend?.let { r ->
            if (r.ok) {
                getString(R.string.devices_alarms_last_ok, DevicesFragment.whenText(ctx, r.tSec))
            } else {
                getString(R.string.devices_alarms_last_fail, r.reason ?: getString(R.string.devices_unknown_reason))
            }
        } ?: getString(R.string.devices_alarms_note, AlarmSettings.MAX)
        list.removeAllViews()
        if (!s.supported) return
        for (a in s.alarms) {
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val row = ListRow(ctx).apply {
                title = timeText(ctx, a.hour, a.minute)
                subtitle = listOfNotNull(a.label, daysText(ctx, a.days)).joinToString(" · ")
                onClick = { edit(a) }
                contentDescription = getString(R.string.devices_alarm_edit_cd, title, subtitle)
            }
            line.addView(row, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val sw = MaterialSwitch(ctx).apply {
                isChecked = a.enabled
                contentDescription = getString(R.string.devices_alarm_on_cd, row.title)
                minHeight = Views.dp(ctx, 48)
                setOnCheckedChangeListener { _, on -> viewLifecycleOwner.lifecycleScope.launch { vm.setEnabled(a.id, on) } }
            }
            line.addView(sw)
            list.addView(line, Views.matchWrap(ctx))
        }
    }

    private fun edit(existing: Alarm?) {
        val ctx = requireContext()
        var hour = existing?.hour ?: DEFAULT_HOUR
        var minute = existing?.minute ?: 0
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Views.dp(ctx, 24)
            setPaddingRelative(pad, Views.dp(ctx, 8), pad, 0)
        }
        val time = DevicesUi.button(box, timeText(ctx, hour, minute), outlined = true) {}
        time.contentDescription = getString(R.string.devices_alarm_time_cd, time.text)
        time.setOnClickListener {
            TimePickerDialog(ctx, { _, h, m ->
                hour = h
                minute = m
                time.text = timeText(ctx, h, m)
                time.contentDescription = getString(R.string.devices_alarm_time_cd, time.text)
            }, hour, minute, DateFormat.is24HourFormat(ctx)).show()
        }
        box.addView(Views.caption(ctx, getString(R.string.devices_alarm_days)), Views.matchWrap(ctx, 12))
        val days = ChipGroup(ctx)
        val chips = LinkedHashMap<DayOfWeek, Chip>()
        for (d in weekOrder()) {
            val chip = Chip(ctx).apply {
                text = d.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                contentDescription = d.getDisplayName(TextStyle.FULL, Locale.getDefault())
                isCheckable = true
                isChecked = existing?.days?.contains(d) ?: false
            }
            chips[d] = chip
            days.addView(chip)
        }
        box.addView(days, Views.matchWrap(ctx))
        box.addView(Views.caption(ctx, getString(R.string.devices_alarm_days_note)), Views.matchWrap(ctx))
        val labelLayout = TextInputLayout(ctx).apply { hint = getString(R.string.devices_alarm_label) }
        val label = TextInputEditText(labelLayout.context).apply {
            setText(existing?.label.orEmpty())
            filters = arrayOf(android.text.InputFilter.LengthFilter(MAX_LABEL))
        }
        labelLayout.addView(label)
        box.addView(labelLayout, Views.matchWrap(ctx, 12))
        val on = DevicesUi.switch(box, getString(R.string.devices_alarm_enabled)).apply { isChecked = existing?.enabled ?: true }
        val scroll = ScrollView(ctx).apply { addView(box) }

        val b = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing == null) R.string.devices_alarms_add else R.string.devices_alarm_edit)
            .setView(scroll)
            .setPositiveButton(R.string.devices_save) { _, _ ->
                val alarm = Alarm(
                    id = existing?.id ?: 0,
                    hour = hour,
                    minute = minute,
                    days = chips.filterValues { it.isChecked }.keys,
                    enabled = on.isChecked,
                    label = label.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                )
                viewLifecycleOwner.lifecycleScope.launch {
                    if (!vm.save(alarm)) DevicesUi.toast(ctx, getString(R.string.devices_alarms_full, AlarmSettings.MAX))
                }
            }
            .setNegativeButton(R.string.devices_cancel, null)
        if (existing != null) {
            b.setNeutralButton(R.string.devices_alarm_delete) { _, _ -> viewLifecycleOwner.lifecycleScope.launch { vm.delete(existing.id) } }
        }
        b.show()
    }

    /** Text helpers (pure, for tests). */
    companion object {
        private const val DEFAULT_HOUR = 7
        private const val MAX_LABEL = 15

        /** The time in the phone's 12/24-hour style. */
        fun timeText(ctx: Context, hour: Int, minute: Int): String {
            val pattern = if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm a"
            return LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
        }

        /** "Once", "Every day", "Weekdays", "Weekends" or "Mon, Wed, Fri". */
        fun daysText(ctx: Context, days: Set<DayOfWeek>): String {
            val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
            return when {
                days.isEmpty() -> ctx.getString(R.string.devices_alarm_once)
                days.size == 7 -> ctx.getString(R.string.devices_alarm_every_day)
                days == DayOfWeek.entries.toSet() - weekend -> ctx.getString(R.string.devices_alarm_weekdays)
                days == weekend -> ctx.getString(R.string.devices_alarm_weekends)
                else -> weekOrder().filter { it in days }.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
            }
        }

        /** The days in the locale's week order. */
        fun weekOrder(locale: Locale = Locale.getDefault()): List<DayOfWeek> {
            val first = WeekFields.of(locale).firstDayOfWeek
            return (0L until 7L).map { first.plus(it) }
        }
    }
}
