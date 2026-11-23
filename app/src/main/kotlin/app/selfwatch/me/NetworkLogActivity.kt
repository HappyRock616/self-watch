/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.me

import android.content.Context
import android.os.Bundle
import android.text.format.Formatter
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import app.selfwatch.R
import app.selfwatch.db.dao.NetLogEntry
import app.selfwatch.food.FoodViews
import app.selfwatch.graph
import app.selfwatch.model.log.SwLog
import app.selfwatch.ui.components.ListRow
import app.selfwatch.ui.format.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

/**
 * Network activity (C20): every request by day, newest first, with time, host, feature, bytes and
 * result. Clear deletes the log; Export writes it as CSV to a file the user picks.
 */
class NetworkLogActivity : MeScreenActivity() {

    private val vm: NetworkLogViewModel by viewModels { NetworkLogViewModel.factory(graph) }

    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = vm.csv()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
                } catch (e: IOException) {
                    SwLog.w(TAG, "export failed", e)
                    false
                }
            }
            toast(getString(if (ok) R.string.netlog_exported else R.string.netlog_export_failed))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen(R.string.me_network)
        toolbar.menu.add(0, MENU_EXPORT, 0, R.string.netlog_export)
        toolbar.menu.add(0, MENU_CLEAR, 1, R.string.netlog_clear)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_EXPORT -> exportFile.launch("selfwatch-network-${LocalDate.now()}.csv")
                MENU_CLEAR -> MeViews.confirm(this, getString(R.string.netlog_clear_title), getString(R.string.netlog_clear_message), getString(R.string.netlog_clear)) { vm.clear() }
            }
            true
        }
        MeViews.add(column, MeViews.caption(this, getString(R.string.netlog_help)))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        MeViews.add(column, list)
        collect(vm.days) { days ->
            list.removeAllViews()
            if (days == null) return@collect
            if (days.isEmpty()) {
                MeViews.add(list, MeViews.body(this, getString(R.string.netlog_empty)))
                return@collect
            }
            for (d in days) {
                val card = MeViews.card(list, dayTitle(this, d))
                for (e in d.entries) card.content.addView(entryRow(this, e), FoodViews.matchWrap())
            }
        }
    }

    /** Row and title builders. */
    companion object {
        private const val TAG = "NetworkLogActivity"
        private const val MENU_EXPORT = 1
        private const val MENU_CLEAR = 2

        /** "Today · 12 requests · 1.2 MB". */
        fun dayTitle(ctx: Context, d: NetDay): String {
            val day = Fmt.day(ctx, Fmt.dateToDay(d.date))
            val n = ctx.resources.getQuantityString(R.plurals.netlog_requests, d.entries.size, d.entries.size)
            return ctx.getString(R.string.netlog_day_title, day, n, Formatter.formatShortFileSize(ctx, d.bytes))
        }

        /** One request: "10:42 · world.openfoodfacts.org", then feature, method, bytes and result. */
        fun entryRow(ctx: Context, e: NetLogEntry, zone: ZoneId = ZoneId.systemDefault()): ListRow = ListRow(ctx).apply {
            title = ctx.getString(R.string.netlog_row_title, Fmt.time(ctx, e.tSec, zone), e.host)
            subtitle = ctx.getString(
                R.string.netlog_row_detail,
                ctx.getString(PrivacyActivity.featureLabel(e.feature)),
                e.method,
                Formatter.formatShortFileSize(ctx, e.bytesUp),
                Formatter.formatShortFileSize(ctx, e.bytesDown),
            )
            trailing = resultLabel(ctx, e.result)
        }

        /** "200", "Blocked", "Offline", "Failed". */
        fun resultLabel(ctx: Context, result: Int): String = when (result) {
            NetLogEntry.BLOCKED -> ctx.getString(R.string.netlog_blocked)
            NetLogEntry.OFFLINE -> ctx.getString(R.string.netlog_offline)
            NetLogEntry.ERROR -> ctx.getString(R.string.netlog_error)
            else -> result.toString()
        }
    }
}
