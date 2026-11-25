/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.devices

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import app.selfwatch.R
import app.selfwatch.debug.DebugTools
import app.selfwatch.graph
import app.selfwatch.model.watch.CiqValue
import app.selfwatch.service.watchapps.SettingControl
import app.selfwatch.service.watchapps.SettingField
import app.selfwatch.service.watchapps.SettingsForm
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.Views
import app.selfwatch.ui.components.ListRow
import app.selfwatch.ui.components.NumberStepper
import app.selfwatch.ui.format.Fmt
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.UUID
import app.selfwatch.ui.R as UiR

/**
 * Watch app settings (C19): the editor built from the app's settings definition (lists as drop-downs,
 * small number ranges as sliders, other numbers as steppers, switches, text fields, read-only rows),
 * or from the raw keys when there is no definition. Save to watch, restore a saved copy, read again.
 */
class WatchAppSettingsFragment : DetailFragment(R.menu.menu_app_settings) {

    private val deviceId: Long get() = requireArguments().getLong(ARG_DEVICE)
    private val appUuid: UUID get() = UUID.fromString(requireArguments().getString(ARG_APP))

    private val vm: AppSettingsViewModel by viewModels { AppSettingsViewModel.factory(requireContext().graph, deviceId, appUuid) }

    /** Updates one field view to a value without firing its listener. */
    private class Binding(val field: SettingField, val update: (CiqValue?, Boolean) -> Unit)

    private lateinit var progress: View
    private lateinit var progressText: TextView
    private lateinit var fieldsBox: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var sourceText: TextView
    private lateinit var save: MaterialButton
    private lateinit var restore: MaterialButton
    private var shownForm: SettingsForm? = null
    private val bindings = ArrayList<Binding>()
    private var updating = false

    /** Checks the "Show raw keys" menu item (debug builds only). */
    private var showRaw: ((Boolean) -> Unit)? = null

    override fun title(): CharSequence = requireArguments().getString(ARG_NAME) ?: getString(R.string.devices_app_settings)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val card = Views.card(column, getString(R.string.devices_app_settings), first = true)
        val inner = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        card.addView(inner)
        val pr = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        pr.addView(LinearProgressIndicator(ctx).apply { isIndeterminate = true }, Views.matchWrap(ctx))
        progressText = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        pr.addView(progressText, Views.matchWrap(ctx, 4))
        inner.addView(pr, Views.matchWrap(ctx))
        progress = pr
        sourceText = DevicesUi.note(inner, null)
        emptyText = DevicesUi.note(inner, getString(R.string.devices_settings_empty))
        fieldsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(fieldsBox, Views.matchWrap(ctx, 8))

        val actions = Views.card(column, null)
        val a = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        actions.addView(a)
        save = DevicesUi.button(a, getString(R.string.devices_settings_save)) { vm.save() }
        restore = DevicesUi.button(a, getString(R.string.devices_settings_restore), outlined = true) { pickCopy() }
        if (DebugTools.ENABLED) showRaw = DebugTools.addRawKeysItem(toolbar) { vm.setRaw(!vm.state.value.raw) }
        collectWhileStarted(vm.state) { render(it) }
    }

    override fun onMenuItem(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_settings_read -> {
            vm.readAgain()
            true
        }
        R.id.menu_settings_restore -> {
            pickCopy()
            true
        }
        else -> false
    }

    private fun render(s: AppSettingsUiState) {
        val ctx = requireContext()
        progress.isVisible = s.busy != null
        s.busy?.let { progressText.text = WatchAppsFragment.progressLabel(ctx, WatchAppProgress(it, null)) }
        sourceText.text = getString(if (s.form.fromDefinition) R.string.devices_settings_from_definition else R.string.devices_settings_raw)
        sourceText.isVisible = s.form.fields.isNotEmpty()
        emptyText.isVisible = !s.loading && s.form.fields.isEmpty()
        save.isEnabled = s.busy == null && s.form.fields.any { !it.readOnly }
        restore.isEnabled = s.history.isNotEmpty()
        showRaw?.invoke(s.raw)
        if (shownForm != s.form) build(ctx, s.form)
        updating = true
        for (b in bindings) b.update(s.values[b.field.key], b.field.key in s.invalid)
        updating = false
        s.message?.let { m ->
            DevicesUi.toast(ctx, messageText(ctx, m), long = m is AppSettingsMessage.Failed)
            vm.messageShown()
        }
    }

    private fun build(ctx: Context, form: SettingsForm) {
        shownForm = form
        fieldsBox.removeAllViews()
        bindings.clear()
        for (f in form.fields) {
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            fieldsBox.addView(box, Views.matchWrap(ctx, 12))
            val b = when (SettingsEditor.kindOf(f)) {
                EditorKind.DROPDOWN -> dropdown(box, f)
                EditorKind.SWITCH -> switch(box, f)
                EditorKind.SLIDER -> slider(box, f)
                EditorKind.STEPPER -> stepper(box, f)
                EditorKind.TEXT -> text(box, f)
                EditorKind.INFO -> info(box, f)
            }
            f.prompt?.takeIf { it.isNotBlank() && SettingsEditor.kindOf(f) != EditorKind.TEXT }?.let { DevicesUi.note(box, it) }
            bindings += b
        }
    }

    private fun errorNote(box: LinearLayout): TextView = DevicesUi.note(box, getString(R.string.devices_settings_invalid)).apply {
        setTextColor(ContextCompat.getColor(context, UiR.color.sw_error))
        isVisible = false
    }

    private fun dropdown(box: LinearLayout, f: SettingField): Binding {
        val row = ListRow(box.context).apply {
            title = f.title
            showChevron = true
        }
        box.addView(row, Views.matchWrap(box.context))
        val err = errorNote(box)
        var current: CiqValue? = null
        row.onClick = {
            val labels = f.options.map { it.label }.toTypedArray()
            val checked = f.options.indexOfFirst { o -> f.optionLabel(current) == o.label }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(f.title)
                .setSingleChoiceItems(labels, checked) { d, which ->
                    vm.set(f.key, f.options[which].value)
                    d.dismiss()
                }
                .setNegativeButton(R.string.devices_cancel, null)
                .show()
        }
        return Binding(f) { v, bad ->
            current = v
            row.subtitle = f.optionLabel(v) ?: SettingsEditor.text(v).ifEmpty { getString(R.string.dash) }
            row.contentDescription = getString(R.string.devices_settings_choice_cd, f.title, row.subtitle)
            err.isVisible = bad
        }
    }

    private fun switch(box: LinearLayout, f: SettingField): Binding {
        val s: MaterialSwitch = DevicesUi.switch(box, f.title)
        s.setOnCheckedChangeListener { _, checked -> if (!updating) vm.set(f.key, CiqValue.Bool(checked)) }
        return Binding(f) { v, _ -> s.isChecked = (v as? CiqValue.Bool)?.v ?: false }
    }

    private fun slider(box: LinearLayout, f: SettingField): Binding {
        val ctx = box.context
        val label = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_BodyLarge)
        box.addView(label, Views.matchWrap(ctx))
        val min = f.min ?: 0.0
        val max = f.max ?: 100.0
        val slider = Slider(ctx).apply {
            valueFrom = min.toFloat()
            valueTo = max.toFloat()
            stepSize = 1f
            contentDescription = f.title
        }
        slider.addOnChangeListener { _, value, fromUser ->
            label.text = getString(R.string.devices_settings_value, f.title, Fmt.number(value.toDouble()))
            if (fromUser && !updating) vm.set(f.key, CiqValue.Num(value.toLong()))
        }
        box.addView(slider, Views.matchWrap(ctx))
        val err = errorNote(box)
        return Binding(f) { v, bad ->
            val x = (SettingsEditor.toX100(v)?.let { it / 100.0 } ?: min).coerceIn(min, max)
            slider.value = Math.round(x).toFloat()
            label.text = getString(R.string.devices_settings_value, f.title, Fmt.number(slider.value.toDouble()))
            err.isVisible = bad
        }
    }

    private fun stepper(box: LinearLayout, f: SettingField): Binding {
        val ctx = box.context
        box.addView(Views.text(ctx, UiR.style.TextAppearance_SelfWatch_BodyLarge, f.title), Views.matchWrap(ctx))
        val bounds = SettingsEditor.boundsX100(f)
        val st = NumberStepper(ctx).apply {
            decimals = if (SettingsEditor.integral(f)) 0 else 2
            stepX100 = 100L
            minX100 = bounds.first
            maxX100 = bounds.last
            contentDescription = f.title
        }
        st.onValueChange = { x100 -> if (!updating) vm.set(f.key, SettingsEditor.fromX100(f, x100)) }
        box.addView(st, Views.matchWrap(ctx))
        val err = errorNote(box)
        return Binding(f) { v, bad ->
            SettingsEditor.toX100(v)?.let { if (st.valueX100 != it) st.valueX100 = it }
            err.isVisible = bad
        }
    }

    private fun text(box: LinearLayout, f: SettingField): Binding {
        val ctx = box.context
        val layout = TextInputLayout(ctx).apply {
            hint = f.title
            helperText = f.prompt
            if (f.control == SettingControl.PASSWORD) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val edit = TextInputEditText(layout.context).apply {
            inputType = inputTypeFor(f)
            f.maxLength?.let { filters = arrayOf(android.text.InputFilter.LengthFilter(it)) }
        }
        layout.addView(edit)
        box.addView(layout, Views.matchWrap(ctx))
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                val v = SettingsEditor.parse(f, s?.toString().orEmpty())
                if (v == null) layout.error = getString(R.string.devices_settings_invalid) else vm.set(f.key, v)
            }
        })
        return Binding(f) { v, bad ->
            val t = SettingsEditor.text(v)
            if (!edit.hasFocus() && edit.text?.toString() != t) edit.setText(t)
            layout.error = if (bad) getString(R.string.devices_settings_invalid) else null
        }
    }

    private fun info(box: LinearLayout, f: SettingField): Binding {
        val row = ListRow(box.context).apply { title = f.title }
        box.addView(row, Views.matchWrap(box.context))
        return Binding(f) { v, _ -> row.subtitle = f.optionLabel(v) ?: SettingsEditor.text(v).ifEmpty { getString(R.string.dash) } }
    }

    private fun pickCopy() {
        val ctx = requireContext()
        val history = vm.state.value.history
        if (history.isEmpty()) {
            DevicesUi.toast(ctx, getString(R.string.devices_settings_no_copies))
            return
        }
        val labels = history.map { DevicesFragment.whenText(ctx, it.savedSec) }.toTypedArray()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.devices_settings_restore)
            .setItems(labels) { _, which -> vm.restore(history[which].savedSec) }
            .setNegativeButton(R.string.devices_cancel, null)
            .show()
    }

    /** Arguments and text helpers. */
    companion object {
        private const val ARG_DEVICE = "device"
        private const val ARG_APP = "app"
        private const val ARG_NAME = "name"

        /** The editor for app [appUuid] (shown as [name]) on watch [deviceId]. */
        fun newInstance(deviceId: Long, appUuid: UUID, name: String?): WatchAppSettingsFragment = WatchAppSettingsFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_DEVICE, deviceId)
                putString(ARG_APP, appUuid.toString())
                putString(ARG_NAME, name)
            }
        }

        /** The keyboard for a text field of [f]. */
        fun inputTypeFor(f: SettingField): Int = when {
            SettingsEditor.integral(f) -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            f.valueType.numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
            f.control == SettingControl.PASSWORD -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            f.control == SettingControl.EMAIL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            f.control == SettingControl.URL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            f.control == SettingControl.PHONE -> InputType.TYPE_CLASS_PHONE
            else -> InputType.TYPE_CLASS_TEXT
        }

        /** The text of [m]. */
        fun messageText(ctx: Context, m: AppSettingsMessage): String = when (m) {
            AppSettingsMessage.Saved -> ctx.getString(R.string.devices_done_write_settings)
            AppSettingsMessage.Read -> ctx.getString(R.string.devices_done_read_settings)
            is AppSettingsMessage.Failed -> ctx.getString(R.string.devices_op_failed, m.reason ?: ctx.getString(R.string.devices_unknown_reason))
            AppSettingsMessage.NoAnswer -> ctx.getString(R.string.devices_no_answer)
            AppSettingsMessage.Refused -> ctx.getString(R.string.devices_service_refused)
            is AppSettingsMessage.Invalid -> ctx.resources.getQuantityString(R.plurals.devices_settings_fix, m.keys.size, m.keys.size)
            AppSettingsMessage.Restored -> ctx.getString(R.string.devices_settings_restored)
        }
    }
}

