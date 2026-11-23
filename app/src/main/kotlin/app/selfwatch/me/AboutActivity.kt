/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.me

import android.os.Bundle
import app.selfwatch.AppInfo
import app.selfwatch.R
import app.selfwatch.SelfWatchApp
import app.selfwatch.devices.FileShare
import app.selfwatch.food.FoodViews
import app.selfwatch.log.LogSinks

/**
 * About (C20): version, the slogan, licences (AGPL app, Apache-2.0 client kit, ODbL food data with
 * the Open Food Facts attribution), thanks to the reference projects, open-source notices for every
 * library in the app, and Export logs (shares the local log files; nothing is sent by the app).
 */
class AboutActivity : MeScreenActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen(R.string.me_about)

        val head = MeViews.card(column, getString(R.string.app_name))
        MeViews.add(head.content, MeViews.body(this, getString(R.string.about_version, AppInfo.versionName(this), AppInfo.versionCode(this))), gap = false)
        MeViews.add(head.content, MeViews.caption(this, getString(R.string.about_slogan)))

        val lic = MeViews.card(column, getString(R.string.about_licences))
        for (text in listOf(R.string.about_licence_app, R.string.about_licence_kit, R.string.about_licence_food, R.string.about_licence_icons)) {
            MeViews.add(lic.content, MeViews.body(this, getString(text)))
        }

        val thanks = MeViews.card(column, getString(R.string.about_thanks))
        MeViews.add(thanks.content, MeViews.body(this, getString(R.string.about_thanks_text)), gap = false)

        val notices = MeViews.card(column, getString(R.string.about_notices))
        MeViews.add(notices.content, MeViews.caption(this, getString(R.string.about_notices_help)), gap = false)
        for (line in resources.getStringArray(R.array.about_libraries)) {
            val (name, licence) = line.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            MeViews.row(notices.content, name, licence)
        }

        val logs = MeViews.card(column, getString(R.string.about_logs))
        MeViews.add(logs.content, MeViews.caption(this, getString(R.string.about_logs_help)), gap = false)
        MeViews.add(logs.content, FoodViews.outlinedButton(this, getString(R.string.about_export_logs)) { exportLogs() })
    }

    private fun exportLogs() {
        (applicationContext as? SelfWatchApp)?.logFile?.flush()
        val intent = FileShare.intent(this, FileShare.filesIn(LogSinks.dir(this)), "text/plain", "Self Watch logs", getString(R.string.devices_share_title))
        if (intent == null || !FileShare.start(this, intent)) toast(getString(R.string.devices_share_none))
    }
}
