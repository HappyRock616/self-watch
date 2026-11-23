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
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import app.selfwatch.R
import app.selfwatch.SelfWatchApp
import app.selfwatch.ble.pair.PairedDevice
import app.selfwatch.db.SettingKeys
import app.selfwatch.me.MeTestSupport
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.device.Feature
import app.selfwatch.model.device.SupportTier
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.time.SystemClock
import app.selfwatch.service.device.WatchStatus
import app.selfwatch.shell.MainActivity
import app.selfwatch.shell.Tab
import app.selfwatch.ui.components.ListRow
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast

/** Robolectric: several devices on Devices, Device order (reorder persists) and Other devices. */
@RunWith(RobolectricTestRunner::class)
class SeveralDevicesTest {
    private lateinit var app: SelfWatchApp

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

    private fun MainActivity.dump(): String = texts(window.decorView).joinToString(" | ")

    private fun MainActivity.shows(text: String): Boolean = texts(window.decorView).any { it.contains(text) }

    private fun main(): MainActivity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

    private fun info(kind: DeviceKind, n: Int, name: String) =
        DeviceInfo(0, kind, "AA:BB:CC:DD:EE:0$n", name, name, null, null, null, SupportTier.UNVERIFIED, emptySet(), null)

    /** A watch, a second watch, a scale and a strap; returns their ids in that order. */
    private fun fourDevices(): List<Long> = runBlocking {
        val db = app.graph.db
        val watch = db.devices.upsert(info(DeviceKind.GARMIN_WATCH, 1, "vívoactive 5").copy(features = setOf(Feature.NOTIFICATIONS)))
        val second = db.devices.upsert(info(DeviceKind.GARMIN_WATCH, 2, "Forerunner 265"))
        val scale = db.devices.upsert(info(DeviceKind.WEIGHT_SCALE, 3, "Body scale").copy(features = setOf(Feature.HEALTH_WEIGHT)))
        val strap = db.devices.upsert(info(DeviceKind.HEART_RATE_SENSOR, 4, "Chest strap").copy(features = setOf(Feature.HEALTH_HR)))
        db.settings.set(WatchStatus.stateKey(watch), "ready")
        db.settings.set(WatchStatus.stateKey(strap), "ready")
        db.settings.set(WatchStatus.batteryKey(scale), "80")
        db.metrics.insert(listOf(MetricSample(strap, MetricKind.HEART_RATE, SystemClock.nowSec(), 72)))
        listOf(watch, second, scale, strap)
    }

    private fun allFragments(fm: FragmentManager): List<Fragment> =
        fm.fragments.flatMap { listOf(it) + allFragments(it.childFragmentManager) }

    private fun MainActivity.detail(): Fragment? = supportFragmentManager.findFragmentById(R.id.detail_container)

    private fun noComingSoon() {
        val toast = ShadowToast.getTextOfLatestToast()
        assertFalse("coming soon toast: $toast", toast?.contains("coming soon", ignoreCase = true) == true)
    }

    @Test
    fun devicesTabShowsEveryDevice() {
        fourDevices()
        val a = main()
        a.select(Tab.DEVICES)
        MeTestSupport.waitFor("all devices", { a.dump() }) { a.shows("Body scale") && a.shows("Chest strap") }
        assertTrue(a.shows("vívoactive 5")) // the primary watch's card
        assertTrue(a.shows("Forerunner 265"))
        assertTrue(a.shows("Battery 80%"))
        assertTrue(a.shows("Heart rate 72 bpm"))
        assertTrue(a.shows("Not connected. It connects by itself when it wakes up."))
    }

    @Test
    fun otherDevicesRowOpensTheScreen() {
        fourDevices()
        val a = main()
        a.select(Tab.DEVICES)
        MeTestSupport.waitFor("devices tab") { a.shows("Other devices") }
        a.findViewById<ListRow>(R.id.row_other).performClick()
        MeTestSupport.waitFor("other devices screen", { a.dump() }) { a.detail() is OtherDevicesFragment && a.shows("Supported devices") }
        noComingSoon()
        fun paired(): String = texts(a.findViewById(R.id.other_devices_paired)).joinToString(" ")
        MeTestSupport.waitFor("paired sensors", { paired() }) { paired().contains("Body scale") && paired().contains("Chest strap") }
        assertTrue(a.shows("Weight scale"))
        assertTrue(a.shows("Heart rate strap"))
        assertTrue(a.shows("Blood pressure monitor"))
        assertTrue(a.shows("Not supported yet"))
        assertFalse(paired().contains("Forerunner 265")) // watches are not listed here
    }

    @Test
    fun otherDevicesWithNothingPaired() {
        val a = main()
        a.supportFragmentManager.beginTransaction().replace(R.id.detail_container, OtherDevicesFragment()).commitNow()
        MeTestSupport.waitFor("empty other devices", { a.dump() }) { a.shows("None paired yet.") }
    }

    @Test
    fun deviceOrderMenuOpensTheScreenAndAMovePersists() {
        val ids = fourDevices()
        val a = main()
        a.select(Tab.DEVICES)
        MeTestSupport.waitFor("devices tab") { a.shows("vívoactive 5") }
        val devices = allFragments(a.supportFragmentManager).filterIsInstance<DevicesFragment>().first()
        val toolbar = devices.requireView().findViewById<MaterialToolbar>(R.id.toolbar)
        assertTrue(toolbar.menu.performIdentifierAction(R.id.menu_device_order, 0))
        MeTestSupport.waitFor("device order screen", { a.dump() }) { a.detail() is DeviceOrderFragment && a.shows("Primary · Garmin watch") }
        noComingSoon()

        // TalkBack's "Move down" on the first row: the second watch becomes primary.
        val list = a.findViewById<RecyclerView>(R.id.device_order_list)
        MeTestSupport.waitFor("rows") { list.childCount == 4 }
        val first = list.getChildAt(0)
        val node: AccessibilityNodeInfo = first.createAccessibilityNodeInfo()
        val moveDown = node.actionList.first { it.label == app.getString(R.string.edit_cards_move_down) }
        assertTrue(first.performAccessibilityAction(moveDown.id, null))
        MeTestSupport.waitFor("order saved") { runBlocking { app.graph.db.devices.active().map { it.id } } == listOf(ids[1], ids[0], ids[2], ids[3]) }
    }

    @Test
    fun deviceOrderWithNothingPaired() {
        val a = main()
        a.supportFragmentManager.beginTransaction().replace(R.id.detail_container, DeviceOrderFragment()).commitNow()
        MeTestSupport.waitFor("empty order", { a.dump() }) { a.shows("No devices paired yet.") }
    }

    @Test
    fun makePrimaryAndSensorInfo() {
        val ids = fourDevices()
        val list = app.graph.deviceList
        runBlocking {
            list.makePrimary(ids[3])
            assertEquals(listOf(ids[3], ids[0], ids[1], ids[2]), app.graph.db.devices.active().map { it.id })
        }
        val i = DbDeviceList.sensorInfo(PairedDevice("aa:00:00:00:00:09", null, 4), DeviceKind.BLOOD_PRESSURE_MONITOR, "Blood pressure monitor")
        assertEquals("AA:00:00:00:00:09", i.address)
        assertEquals("Blood pressure monitor", i.name)
        assertEquals(setOf(Feature.HEALTH_BLOOD_PRESSURE), i.features)
        assertEquals(SensorType.BLOOD_PRESSURE, SensorType.of(DeviceKind.BLOOD_PRESSURE_MONITOR))
        assertEquals(R.string.devices_kind_watch, SensorType.label(DeviceKind.GARMIN_WATCH))
        assertEquals(2, SensorType.SCALE.filter.serviceUuids.size)
    }
}
