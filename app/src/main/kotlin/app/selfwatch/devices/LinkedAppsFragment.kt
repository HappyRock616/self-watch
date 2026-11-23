/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.devices

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import app.selfwatch.R
import app.selfwatch.graph
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.Views
import app.selfwatch.ui.components.ListRow
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import app.selfwatch.ui.R as UiR

/**
 * Linked phone apps (C19): which phone apps may message which watch apps, with Allow / Revoke, the
 * master switch, and the watch app that shows the calories left.
 */
class LinkedAppsFragment : DetailFragment() {

    private val vm: LinkedAppsViewModel by viewModels { LinkedAppsViewModel.factory(requireContext().graph) }

    private lateinit var master: MaterialSwitch
    private lateinit var calories: ListRow
    private lateinit var empty: TextView
    private lateinit var list: LinearLayout
    private var updating = false
    private val icons = HashMap<String, Drawable?>()

    override fun title(): CharSequence = getString(R.string.devices_linked_apps)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val top = Views.card(column, null, first = true)
        val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        top.addView(t)
        master = DevicesUi.switch(t, getString(R.string.devices_link_enabled))
        master.setOnCheckedChangeListener { _, on -> if (!updating) vm.setEnabled(on) }
        DevicesUi.note(t, getString(R.string.devices_link_note))

        val cal = Views.card(column, getString(R.string.devices_calories_title))
        // Kept for a future Self Watch watch companion; hidden until one exists (no watch app to receive it).
        cal.isVisible = SHOW_CALORIES_ON_WATCH
        val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        cal.addView(c)
        calories = ListRow(ctx).apply {
            title = getString(R.string.devices_calories_app)
            onClick = { pickCalories() }
        }
        c.addView(calories, Views.matchWrap(ctx))
        DevicesUi.note(c, getString(R.string.devices_calories_note))

        val appsCard = Views.card(column, getString(R.string.devices_link_apps))
        val a = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        appsCard.addView(a)
        empty = DevicesUi.note(a, getString(R.string.devices_link_empty))
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        a.addView(list, Views.matchWrap(ctx))
        collectWhileStarted(vm.state) { render(it) }
    }

    private fun render(s: LinkedAppsUiState) {
        val ctx = requireContext()
        updating = true
        master.isChecked = s.enabled
        updating = false
        val chosen = s.caloriesApp
        calories.subtitle = when {
            chosen == null -> getString(R.string.devices_calories_off)
            else -> s.watchApps.firstOrNull { it.uuid == chosen }?.let { WatchAppsFragment.appName(ctx, it) } ?: chosen.toString()
        }
        calories.contentDescription = getString(R.string.devices_settings_choice_cd, calories.title, calories.subtitle)
        empty.isVisible = !s.loading && s.rows.isEmpty()
        list.removeAllViews()
        for (row in s.rows) list.addView(rowView(ctx, row, s.enabled), Views.matchWrap(ctx, 4))
    }

    private fun rowView(ctx: Context, row: LinkedAppRow, enabled: Boolean): View {
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val size = Views.dp(ctx, 40)
        val icon = ImageView(ctx).apply {
            setImageDrawable(icon(ctx, row.packageName))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        line.addView(icon, LinearLayout.LayoutParams(size, size).apply { marginEnd = Views.dp(ctx, 12) })
        val text = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        text.addView(Views.text(ctx, UiR.style.TextAppearance_SelfWatch_BodyLarge, row.label))
        text.addView(Views.caption(ctx, subtitle(ctx, row)))
        line.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val button = DevicesUi.button(line, getString(if (row.allowed) R.string.devices_link_revoke else R.string.devices_link_allow), outlined = row.allowed) {
            if (row.allowed) vm.revoke(row) else vm.allow(row)
        }
        button.isEnabled = enabled
        button.contentDescription = getString(
            if (row.allowed) R.string.devices_link_revoke_cd else R.string.devices_link_allow_cd,
            row.label,
            row.watchAppName ?: row.appUuid.toString(),
        )
        (button.layoutParams as LinearLayout.LayoutParams).apply {
            topMargin = 0
            marginStart = Views.dp(ctx, 8)
        }
        return line
    }

    private fun icon(ctx: Context, pkg: String): Drawable? = icons.getOrPut(pkg) {
        try {
            ctx.packageManager.getApplicationIcon(pkg)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } ?: ctx.getDrawable(R.drawable.devices_ic_watch_app)
    }

    private fun pickCalories() {
        val ctx = requireContext()
        val s = vm.state.value
        val choices = listOf<Pair<String, java.util.UUID?>>(getString(R.string.devices_calories_off) to null) +
            s.watchApps.map { WatchAppsFragment.appName(ctx, it) to it.uuid }
        val checked = choices.indexOfFirst { it.second == s.caloriesApp }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.devices_calories_app)
            .setSingleChoiceItems(choices.map { it.first }.toTypedArray(), checked) { d, which ->
                vm.setCaloriesApp(choices[which].second)
                d.dismiss()
            }
            .setNegativeButton(R.string.devices_cancel, null)
            .show()
    }

    /** Text helpers (pure, for tests). */
    companion object {
        /** Shows the "Calories left on watch" card. Off until Self Watch has a watch companion app. */
        const val SHOW_CALORIES_ON_WATCH: Boolean = false

        /** "Watch app: Calories · Allowed · Last used Today 08:12". */
        fun subtitle(ctx: Context, row: LinkedAppRow): String {
            val app = ctx.getString(R.string.devices_link_watch_app, row.watchAppName ?: row.appUuid.toString())
            val state = ctx.getString(if (row.allowed) R.string.devices_link_allowed else R.string.devices_link_refused)
            val used = row.lastUsedSec?.let { ctx.getString(R.string.devices_link_last_used, DevicesFragment.whenText(ctx, it)) }
                ?: ctx.getString(R.string.devices_link_never_used)
            return listOf(app, state, used).joinToString(" · ")
        }
    }
}
