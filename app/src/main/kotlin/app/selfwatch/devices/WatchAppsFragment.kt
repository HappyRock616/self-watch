/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.devices

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import app.selfwatch.R
import app.selfwatch.garmin.features.PrgFile
import app.selfwatch.graph
import app.selfwatch.model.device.Feature
import app.selfwatch.model.watch.WatchAppKind
import app.selfwatch.service.watchapps.WatchAppOp
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.Nav
import app.selfwatch.shell.Views
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.ListRow
import app.selfwatch.ui.components.SwCard
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch
import app.selfwatch.ui.R as UiR

/**
 * Watch apps (C19): the apps on the watch with per-app actions (open on watch, settings, delete), and
 * "Install from file": pick the `.prg`, optionally its settings file and strings.xml, then Install.
 */
class WatchAppsFragment : DetailFragment(R.menu.menu_watch_apps) {

    private val vm: WatchAppsViewModel by viewModels { WatchAppsViewModel.factory(requireContext().graph) }

    private val pickPrg = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { readPrg(it) } }
    private val pickSettings = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { readText(it, settings = true) } }
    private val pickStrings = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { readText(it, settings = false) } }

    private lateinit var installButton: MaterialButton
    private lateinit var progressRow: View
    private lateinit var progressText: TextView
    private lateinit var pendingCard: SwCard
    private lateinit var pendingText: TextView
    private lateinit var settingsButton: MaterialButton
    private lateinit var stringsButton: MaterialButton
    private lateinit var empty: EmptyState
    private lateinit var list: LinearLayout
    private lateinit var noWatch: TextView
    private lateinit var installHint: TextView
    private lateinit var appsCard: SwCard

    override fun title(): CharSequence = getString(R.string.devices_watch_apps)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val top = Views.card(column, null, first = true)
        val inner = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        top.addView(inner)
        noWatch = DevicesUi.note(inner, getString(R.string.devices_need_watch)).apply { isVisible = false }
        installButton = DevicesUi.button(inner, getString(R.string.devices_install_file)) { startInstall() }
        installHint = DevicesUi.note(inner, getString(R.string.devices_install_hint))
        val pr = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isVisible = false
        }
        pr.addView(LinearProgressIndicator(ctx).apply { isIndeterminate = true }, Views.matchWrap(ctx, 12))
        progressText = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        pr.addView(progressText, Views.matchWrap(ctx, 4))
        inner.addView(pr, Views.matchWrap(ctx))
        progressRow = pr

        pendingCard = Views.card(column, getString(R.string.devices_install_ready)).apply { isVisible = false }
        val p = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        pendingCard.addView(p)
        pendingText = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body)
        p.addView(pendingText, Views.matchWrap(ctx))
        settingsButton = DevicesUi.button(p, getString(R.string.devices_install_add_settings), outlined = true) {
            pickSettings.launch(arrayOf("*/*"))
        }
        stringsButton = DevicesUi.button(p, getString(R.string.devices_install_add_strings), outlined = true) {
            pickStrings.launch(arrayOf("*/*"))
        }
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        p.addView(buttons, Views.matchWrap(ctx, 8))
        DevicesUi.button(buttons, getString(R.string.devices_install)) { viewLifecycleOwner.lifecycleScope.launch { vm.install() } }
        DevicesUi.button(buttons, getString(R.string.devices_cancel), outlined = true) { vm.cancelInstall() }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = Views.dp(ctx, 8)
        }

        appsCard = Views.card(column, getString(R.string.devices_apps_on_watch))
        val a = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        appsCard.addView(a)
        empty = EmptyState(ctx).apply {
            message = getString(R.string.devices_apps_empty)
            buttonText = getString(R.string.devices_refresh)
            onButton = { vm.refresh() }
            setIcon(R.drawable.ic_tab_devices)
        }
        a.addView(empty, Views.matchWrap(ctx))
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        a.addView(list, Views.matchWrap(ctx))

        collectWhileStarted(vm.state) { render(it) }
        if (savedInstanceState == null && arguments?.getBoolean(ARG_INSTALL) == true) startInstall()
    }

    override fun onMenuItem(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_install_file -> {
            startInstall()
            true
        }
        R.id.menu_refresh_apps -> {
            vm.refresh()
            true
        }
        else -> false
    }

    private fun render(s: WatchAppsUiState) {
        val ctx = requireContext()
        val features = s.watch?.features.orEmpty()
        val canInstall = s.watch != null && FeatureGate.shows(features, Feature.WATCH_APP_INSTALL)
        noWatch.isVisible = !s.loading && s.watch == null
        installButton.isVisible = canInstall
        installHint.isVisible = canInstall
        appsCard.isVisible = s.watch != null
        installButton.isEnabled = s.progress == null
        toolbar.menu.findItem(R.id.menu_install_file)?.isVisible = canInstall
        toolbar.menu.findItem(R.id.menu_refresh_apps)?.isVisible = s.watch != null

        progressRow.isVisible = s.progress != null
        s.progress?.let { progressText.text = progressLabel(ctx, it) }

        val p = s.pending
        pendingCard.isVisible = p != null
        if (p != null) {
            pendingText.text = getString(
                R.string.devices_install_summary,
                p.fileName,
                kindLabel(ctx, p.info.kind),
                p.info.appUuid.toString(),
            )
            settingsButton.text = p.settingsName?.let { getString(R.string.devices_install_settings_added, it) }
                ?: getString(R.string.devices_install_add_settings)
            stringsButton.text = p.stringsName?.let { getString(R.string.devices_install_strings_added, it) }
                ?: getString(R.string.devices_install_add_strings)
        }

        empty.isVisible = !s.loading && s.watch != null && s.apps.isEmpty()
        list.removeAllViews()
        for (app in s.apps) list.addView(appRow(ctx, app, features), Views.matchWrap(ctx))

        s.message?.let { m ->
            DevicesUi.toast(ctx, messageText(ctx, m), long = m is WatchAppsMessage.Failed || m is WatchAppsMessage.BadPrg)
            vm.messageShown()
        }
    }

    private fun appRow(ctx: Context, app: WatchAppItem, features: Set<app.selfwatch.model.device.Feature>): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(ctx).apply {
            setImageResource(R.drawable.devices_ic_watch_app)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val size = Views.dp(ctx, 40)
        row.addView(icon, LinearLayout.LayoutParams(size, size).apply { marginEnd = Views.dp(ctx, 12) })
        val name = appName(ctx, app)
        val lr = ListRow(ctx).apply {
            title = name
            subtitle = app.version?.let { getString(R.string.devices_app_version_kind, it, kindLabel(ctx, app.kind)) }
                ?: kindLabel(ctx, app.kind)
            contentDescription = getString(R.string.devices_app_actions_cd, name)
            onClick = { showActions(app, features) }
        }
        row.addView(lr, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun showActions(app: WatchAppItem, features: Set<Feature>) {
        val ctx = requireContext()
        val actions = buildList {
            if (FeatureGate.shows(features, Feature.WATCH_APP_LAUNCH)) add(R.string.devices_app_open to { vm.launch(app) })
            if (FeatureGate.shows(features, Feature.WATCH_APP_SETTINGS)) add(R.string.devices_app_settings to { openSettings(app) })
            add(R.string.devices_app_delete to { confirmDelete(app) })
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(appName(ctx, app))
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(R.string.devices_cancel, null)
            .show()
    }

    private fun openSettings(app: WatchAppItem) {
        val w = vm.state.value.watch ?: return
        Nav.open(this, WatchAppSettingsFragment.newInstance(w.id, app.uuid, appName(requireContext(), app)))
    }

    private fun confirmDelete(app: WatchAppItem) {
        val ctx = requireContext()
        DevicesUi.confirm(
            ctx,
            getString(R.string.devices_app_delete_title, appName(ctx, app)),
            getString(R.string.devices_app_delete_message),
            R.string.devices_app_delete,
        ) { vm.delete(app) }
    }

    private fun startInstall() {
        pickPrg.launch(arrayOf("*/*"))
    }

    private fun readPrg(uri: Uri) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val bytes = DevicesUi.read(ctx, uri, PrgFile.MAX_SIZE)
            if (bytes == null) {
                DevicesUi.toast(ctx, getString(R.string.devices_install_unreadable), long = true)
                return@launch
            }
            vm.pickPrg(DevicesUi.name(ctx, uri), bytes)
        }
    }

    private fun readText(uri: Uri, settings: Boolean) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val bytes = DevicesUi.read(ctx, uri, MAX_TEXT_BYTES)
            if (bytes == null) {
                DevicesUi.toast(ctx, getString(R.string.devices_install_unreadable), long = true)
                return@launch
            }
            val name = DevicesUi.name(ctx, uri)
            val text = bytes.toString(Charsets.UTF_8)
            if (settings) vm.addSettings(name, text) else vm.addStrings(name, text)
        }
    }

    /** Arguments and text helpers (pure, for tests). */
    companion object {
        private const val ARG_INSTALL = "install"
        private const val MAX_TEXT_BYTES = 2 * 1024 * 1024

        /** The screen; [install] opens the file picker straight away. */
        fun newInstance(install: Boolean = false): WatchAppsFragment =
            WatchAppsFragment().apply { arguments = Bundle().apply { putBoolean(ARG_INSTALL, install) } }

        /** "Watch app", "Widget"… */
        fun kindLabel(ctx: Context, kind: WatchAppKind): String = ctx.getString(
            when (kind) {
                WatchAppKind.APP -> R.string.devices_kind_app
                WatchAppKind.WIDGET -> R.string.devices_kind_widget
                WatchAppKind.WATCH_FACE -> R.string.devices_kind_watch_face
                WatchAppKind.DATA_FIELD -> R.string.devices_kind_data_field
                WatchAppKind.OTHER -> R.string.devices_kind_other
            },
        )

        /** The app's name, or "Unnamed app" when the watch gave none. */
        fun appName(ctx: Context, app: WatchAppItem): String = app.name?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.devices_app_unnamed)

        /** "Installing app.prg…" and so on. */
        fun progressLabel(ctx: Context, p: WatchAppProgress): String {
            val label = p.label ?: ctx.getString(R.string.devices_app_unnamed)
            return when (p.op) {
                WatchAppOp.REFRESH -> ctx.getString(R.string.devices_progress_refresh)
                WatchAppOp.INSTALL -> ctx.getString(R.string.devices_progress_install, label)
                WatchAppOp.DELETE -> ctx.getString(R.string.devices_progress_delete, label)
                WatchAppOp.LAUNCH -> ctx.getString(R.string.devices_progress_launch, label)
                WatchAppOp.READ_SETTINGS -> ctx.getString(R.string.devices_progress_read_settings)
                WatchAppOp.WRITE_SETTINGS -> ctx.getString(R.string.devices_progress_write_settings)
            }
        }

        /** The text of [m]. */
        fun messageText(ctx: Context, m: WatchAppsMessage): String = when (m) {
            is WatchAppsMessage.BadPrg -> ctx.getString(R.string.devices_install_bad_prg, m.reason)
            is WatchAppsMessage.BadSettings -> ctx.getString(R.string.devices_install_bad_settings, m.reason)
            WatchAppsMessage.BadStrings -> ctx.getString(R.string.devices_install_bad_strings)
            WatchAppsMessage.NoWatch -> ctx.getString(R.string.devices_need_watch)
            WatchAppsMessage.Refused -> ctx.getString(R.string.devices_service_refused)
            is WatchAppsMessage.NoAnswer -> ctx.getString(R.string.devices_no_answer)
            is WatchAppsMessage.Failed -> ctx.getString(R.string.devices_op_failed, m.reason ?: ctx.getString(R.string.devices_unknown_reason))
            is WatchAppsMessage.Done -> {
                val label = m.label ?: ctx.getString(R.string.devices_app_unnamed)
                when (m.op) {
                    WatchAppOp.REFRESH -> ctx.getString(R.string.devices_done_refresh)
                    WatchAppOp.INSTALL -> ctx.getString(R.string.devices_done_install, label)
                    WatchAppOp.DELETE -> ctx.getString(R.string.devices_done_delete, label)
                    WatchAppOp.LAUNCH -> ctx.getString(R.string.devices_done_launch, label)
                    WatchAppOp.READ_SETTINGS -> ctx.getString(R.string.devices_done_read_settings)
                    WatchAppOp.WRITE_SETTINGS -> ctx.getString(R.string.devices_done_write_settings)
                }
            }
        }
    }
}
