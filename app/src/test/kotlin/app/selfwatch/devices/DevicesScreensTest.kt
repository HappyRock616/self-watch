/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.devices

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import app.selfwatch.R
import app.selfwatch.SelfWatchApp
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.dao.LinkPermission
import app.selfwatch.db.dao.WatchAppRow
import app.selfwatch.db.dao.WatchAppSettings
import app.selfwatch.me.MeTestSupport
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.device.Feature
import app.selfwatch.model.device.SupportTier
import app.selfwatch.model.watch.Alarm
import app.selfwatch.service.device.AlarmSettings
import app.selfwatch.service.device.WatchStatus
import app.selfwatch.shell.MainActivity
import app.selfwatch.shell.Tab
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.util.UUID

/** Robolectric smoke tests: every C19 screen opens with empty data and with sample data. */
@RunWith(RobolectricTestRunner::class)
class DevicesScreensTest {
    private lateinit var app: SelfWatchApp
    private val appUuid: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")

    @Before
    fun setUp() {
        app = MeTestSupport.freshApp()
        runBlocking { app.graph.settings.setBool(SettingKeys.ONBOARDING_DONE, true) }
    }

    @After
    fun tearDown() = MeTestSupport.resetDb()

    private fun texts(v: View): List<String> = when {
        v.visibility != View.VISIBLE -> emptyList()
        v is TextView -> listOf(v.text.toString())
        v is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun MainActivity.shows(text: String): Boolean = texts(window.decorView).any { it.contains(text) }

    private fun main(): MainActivity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

    private fun MainActivity.open(f: Fragment, mustShow: String) {
        supportFragmentManager.beginTransaction().replace(R.id.detail_container, f).commitNow()
        MeTestSupport.waitFor("$mustShow on ${f.javaClass.simpleName}", { texts(window.decorView).joinToString(" | ") }) { shows(mustShow) }
    }

    private fun sampleData(features: Set<Feature> = emptySet()) = runBlocking {
        val db = app.graph.db
        val id = db.devices.upsert(
            DeviceInfo(0, DeviceKind.GARMIN_WATCH, "AA:BB:CC:DD:EE:01", "vívoactive 5", "vívoactive 5", null, null, null, SupportTier.VERIFIED, features, null),
        )
        db.settings.set(WatchStatus.stateKey(id), "ready")
        db.watchApps.upsert(WatchAppRow(id, appUuid, "Calories field", 3, 4, true, 1))
        db.watchApps.saveSettings(WatchAppSettings(appUuid, 100, """{"goal":2000,"metric":true}""", null))
        db.links.set(LinkPermission("com.example.run", appUuid, "cert", true, 10, null))
        db.settings.set(AlarmSettings.key(id), AlarmSettings.encode(listOf(Alarm(1, 6, 45, setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), true, "Run"))))
        id
    }

    @Test
    fun screensOpenWithEmptyData() {
        val a = main()
        a.open(WatchAppsFragment.newInstance(), "Pair your watch first")
        a.open(LinkedAppsFragment(), "No phone app has asked yet.")
        a.open(NotificationSettingsFragment(), "Show phone notifications on the watch")
        a.open(CallSettingsFragment(), "Show incoming calls on the watch")
        a.open(WeatherFragment(), "No forecast received yet")
        a.open(AlarmsFragment(), "Pair your watch first")
        a.open(WatchSettingsFragment(), "Pair your watch first")
        a.open(WatchAppSettingsFragment.newInstance(1, appUuid, "Some app"), "No settings yet")
    }

    @Test
    fun screensOpenWithSampleData() {
        val id = sampleData()
        val a = main()
        a.open(WatchAppsFragment.newInstance(), "Calories field")
        assertTrue(a.shows("Version 3 · Data field"))
        a.open(LinkedAppsFragment(), "com.example.run")
        assertTrue(a.shows("Watch app: Calories field"))
        a.open(AlarmsFragment(), "Weekends")
        a.open(WatchSettingsFragment(), "Find my watch")
        a.open(WatchAppSettingsFragment.newInstance(id, appUuid, "Calories field"), "goal")
        assertTrue(a.shows("Raw keys"))
    }

    @Test
    fun devicesTabHidesRowsTheWatchCannotDo() {
        sampleData(setOf(Feature.NOTIFICATIONS, Feature.WATCH_APP_MANAGE))
        val a = main()
        a.select(Tab.DEVICES)
        MeTestSupport.waitFor("watch card") { a.shows("vívoactive 5") && !a.shows("Alarms") }
        assertTrue(a.shows("Notifications"))
        assertTrue(a.shows("Watch apps"))
        assertFalse(a.shows("Weather on watch"))
        assertFalse(a.shows("Calls"))
    }

    @Test
    fun alarmsSayWhenTheWatchCannotDoThem() {
        sampleData(setOf(Feature.NOTIFICATIONS))
        main().open(AlarmsFragment(), "Alarms aren't supported by this watch.")
    }
}

