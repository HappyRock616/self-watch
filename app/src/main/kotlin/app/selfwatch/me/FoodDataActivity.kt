/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.me

import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import app.selfwatch.R
import app.selfwatch.food.FoodViews
import app.selfwatch.graph
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * Food data (C20, FOODDB_SPEC §9): whether foods.db is installed, its version and size; check for an
 * update (downloads only on Wi-Fi, through the firewall), download, load from a file (no network at
 * all), remove. Shows the Open Food Facts (ODbL) attribution.
 */
class FoodDataActivity : MeScreenActivity() {

    private val vm: FoodDataViewModel by viewModels { FoodDataViewModel.factory(graph) }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.loadFrom { contentResolver.openInputStream(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen(R.string.me_food_data)

        val card = MeViews.card(column, getString(R.string.fooddata_title))
        val status = MeViews.body(this, null)
        MeViews.add(card.content, status, gap = false)
        val progress = LinearProgressIndicator(this).apply {
            isIndeterminate = true
            contentDescription = getString(R.string.fooddata_working)
        }
        MeViews.add(card.content, progress)
        val check = FoodViews.filledButton(this, getString(R.string.fooddata_check)) { vm.check() }
        val download = FoodViews.filledButton(this, "") { vm.download() }
        val load = FoodViews.outlinedButton(this, getString(R.string.fooddata_load)) { pickFile.launch(arrayOf("*/*")) }
        val remove = FoodViews.textButton(this, getString(R.string.fooddata_remove)) {
            MeViews.confirm(this, getString(R.string.fooddata_remove_title), getString(R.string.fooddata_remove_message), getString(R.string.fooddata_remove)) { vm.remove() }
        }
        for (b in listOf(check, download, load, remove)) MeViews.add(card.content, b)
        MeViews.add(card.content, MeViews.caption(this, getString(R.string.fooddata_wifi_note)))

        val about = MeViews.card(column, getString(R.string.fooddata_about_title))
        MeViews.add(about.content, MeViews.body(this, getString(R.string.fooddata_about)), gap = false)
        MeViews.add(about.content, MeViews.caption(this, getString(R.string.fooddata_attribution)))

        collect(vm.state) { s ->
            status.text = when {
                s.loading -> ""
                s.installed -> getString(R.string.fooddata_installed, versionText(s.version ?: 0), Formatter.formatShortFileSize(this, s.sizeBytes))
                else -> getString(R.string.fooddata_not_installed)
            }
            progress.visibility = if (s.busy) View.VISIBLE else View.GONE
            val p = s.progress
            if (p != null && p.second != null && (p.second ?: 0L) > 0L) {
                progress.isIndeterminate = false
                progress.progress = ((p.first * PERCENT) / (p.second ?: 1L)).toInt()
            } else {
                progress.isIndeterminate = true
            }
            val m = s.available
            download.visibility = if (m != null) View.VISIBLE else View.GONE
            if (m != null) {
                download.text = getString(R.string.fooddata_download, versionText(m.dataVersion), Formatter.formatShortFileSize(this, m.sizeGz ?: 0L))
            }
            for (b in listOf(check, download, load)) b.isEnabled = !s.busy
            remove.visibility = if (s.installed) View.VISIBLE else View.GONE
            remove.isEnabled = !s.busy
            s.message?.let {
                toast(messageText(it))
                vm.messageShown()
            }
        }
    }

    private fun messageText(m: FoodDataMessage): String = when (m) {
        FoodDataMessage.UpToDate -> getString(R.string.fooddata_up_to_date)
        FoodDataMessage.NeedsNewerApp -> getString(R.string.fooddata_needs_app)
        is FoodDataMessage.Installed -> getString(R.string.fooddata_done, versionText(m.version))
        FoodDataMessage.Removed -> getString(R.string.fooddata_removed)
        FoodDataMessage.Blocked -> getString(R.string.fooddata_blocked)
        FoodDataMessage.Offline -> getString(R.string.fooddata_offline)
        is FoodDataMessage.Failed -> getString(R.string.fooddata_failed, m.detail)
    }

    /** `20261122` → "2026-11-22"; anything else as is. */
    private fun versionText(v: Int): String {
        val s = v.toString()
        return if (s.length == DATE_DIGITS) "${s.substring(0, 4)}-${s.substring(4, 6)}-${s.substring(6)}" else s
    }

    private companion object {
        const val PERCENT = 100L
        const val DATE_DIGITS = 8
    }
}
