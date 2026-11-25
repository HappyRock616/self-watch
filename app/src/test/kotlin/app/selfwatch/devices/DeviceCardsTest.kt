/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.devices

import android.content.DialogInterface
import android.os.Looper
import android.os.SystemClock as AndroidClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import app.selfwatch.R
import app.selfwatch.SelfWatchApp
import app.selfwatch.db.SettingKeys
import app.selfwatch.me.MeTestSupport
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.device.Feature
import app.selfwatch.model.device.SupportTier
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.SystemClock
import app.selfwatch.model.time.TimeRange
import app.selfwatch.service.device.DeviceManager
import app.selfwatch.service.device.WatchStatus
import app.selfwatch.shell.MainActivity
import app.selfwatch.shell.Tab
import app.selfwatch.ui.components.SwCard
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.time.Duration
import java.time.ZoneOffset

/** Robolectric: the Devices page cards (connect, disconnect, Exclusive, hold to reset, remove with data). */
@RunWith(RobolectricTestRunner::class)
class DeviceCardsTest {
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

    private fun info(kind: DeviceKind, n: Int, name: String) =
        DeviceInfo(0, kind, "AA:BB:CC:DD:EE:0$n", name, name, null, null, null, SupportTier.UNVERIFIED, emptySet(), null)

    private val db get() = app.graph.db

    /** A watch, a second watch, a scale and a strap (with one heart rate); ids in that order. */
    private fun devices(): List<Long> = runBlocking {
        val watch = db.devices.upsert(info(DeviceKind.GARMIN_WATCH, 1, "vívoactive 5").copy(features = setOf(Feature.NOTIFICATIONS)))
        val second = db.devices.upsert(info(DeviceKind.GARMIN_WATCH, 2, "Forerunner 265"))
        val scale = db.devices.upsert(info(DeviceKind.WEIGHT_SCALE, 3, "Body scale"))
        val strap = db.devices.upsert(info(DeviceKind.HEART_RATE_SENSOR, 4, "Chest strap"))
        db.settings.set(WatchStatus.stateKey(watch), "ready")
        db.settings.set(WatchStatus.stateKey(strap), "ready")
        db.metrics.insert(listOf(MetricSample(strap, MetricKind.HEART_RATE, SystemClock.nowSec(), 72)))
        listOf(watch, second, scale, strap)
    }

    private fun MainActivity.cards(): LinearLayout = findViewById(R.id.device_cards)

    /** The tappable top row of card [i]. */
    private fun MainActivity.body(i: Int): ViewGroup = (cards().getChildAt(i) as SwCard).content.getChildAt(0) as ViewGroup

    private fun <T : View> find(v: View, type: Class<T>): T? {
        if (type.isInstance(v)) return type.cast(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), type)?.let { return it }
        return null
    }

    private fun dialog(): AlertDialog = requireNotNull(ShadowDialog.getLatestDialog() as? AlertDialog) { "no dialog" }

    private fun open(): MainActivity {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.select(Tab.DEVICES)
        MeTestSupport.waitFor("cards", { a.dump() }) { a.cards().childCount == 4 && a.shows("Chest strap") }
        return a
    }

    private fun setting(key: String): Boolean = runBlocking { db.settings.getBool(key) }

    @Test
    fun oneCardPerDeviceAndTapConnectsOrDisconnects() {
        val ids = devices()
        val a = open()
        assertTrue(a.shows("Not in use. Tap to use this watch."))
        assertTrue(a.shows("Primary · Garmin watch"))
        assertTrue(a.shows("Heart rate 72 bpm"))
        assertTrue(a.shows("Heart rate comes from Chest strap while it's connected."))
        // Tap the strap: it is turned off and stays off.
        a.body(3).performClick()
        MeTestSupport.waitFor("strap off", { a.dump() }) { setting(SettingKeys.deviceOff(ids[3])) && a.shows("Off. Tap to connect.") }
        // Tap it again: back on.
        a.body(3).performClick()
        MeTestSupport.waitFor("strap on") { !setting(SettingKeys.deviceOff(ids[3])) }
        // Tap the second watch: it becomes the watch in use.
        a.body(1).performClick()
        MeTestSupport.waitFor("second watch in use") { runBlocking { db.settings.get(DeviceManager.ACTIVE_WATCH_KEY) } == ids[1].toString() }
        MeTestSupport.waitFor("first watch not in use", { a.dump() }) {
            texts(a.cards().getChildAt(0)).any { it.contains("Not in use") }
        }
    }

    @Test
    fun exclusiveIsOffByDefaultAndStored() {
        val ids = devices()
        val a = open()
        val sw = requireNotNull(find(a.cards().getChildAt(0), MaterialSwitch::class.java))
        assertEquals(false, sw.isChecked)
        assertEquals(null, find(a.cards().getChildAt(3), MaterialSwitch::class.java)) // sensors have none
        sw.performClick()
        MeTestSupport.waitFor("exclusive stored") { setting(SettingKeys.deviceExclusive(ids[0])) }
    }

    @Test
    fun holdingThreeSecondsAsksToResetAndKeepsWorkouts() {
        val ids = devices()
        runBlocking { db.workouts.insert(Workout(0, ids[3], 1, null, 1_000, 2_000, 1_000, null, null, null, null, null, null, false, null, null, null, DataSource.WATCH)) }
        val a = open()
        val body = a.body(3)
        val t = AndroidClock.uptimeMillis()
        body.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DeviceCardsView.HOLD_MS + 200))
        MeTestSupport.waitFor("reset question", { a.dump() }) { ShadowDialog.getLatestDialog()?.isShowing == true }
        dialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        MeTestSupport.waitFor("readings gone") {
            runBlocking { db.metrics.series(MetricKind.HEART_RATE, TimeRange(0, Long.MAX_VALUE), ids[3]).isEmpty() }
        }
        assertEquals(1, runBlocking { db.workouts.all().size })
    }

    @Test
    fun resetIsATalkBackActionToo() {
        devices()
        val a = open()
        val body = a.body(2)
        val action = body.createAccessibilityNodeInfo().actionList.first { it.label == app.getString(R.string.devices_reset_action) }
        assertTrue(body.performAccessibilityAction(action.id, null))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ShadowDialog.getLatestDialog()?.isShowing == true)
    }

    @Test
    fun trashRemovesTheDeviceAndOptionallyItsData() {
        val ids = devices()
        val a = open()
        val trash = requireNotNull(find(a.body(3), ImageButton::class.java))
        assertEquals("Remove Chest strap", trash.contentDescription)
        trash.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val d = dialog()
        requireNotNull(find(d.window!!.decorView, MaterialCheckBox::class.java)).isChecked = true
        d.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        MeTestSupport.waitFor("strap removed", { a.dump() }) {
            runBlocking { db.devices.active().none { it.id == ids[3] } } && a.cards().childCount == 3
        }
        MeTestSupport.waitFor("its data deleted") {
            runBlocking { db.metrics.series(MetricKind.HEART_RATE, TimeRange(0, Long.MAX_VALUE), ids[3]).isEmpty() }
        }
    }

    @Test
    fun summariesToRebuild() {
        val today = Day(20261125)
        val utc = ZoneOffset.UTC
        assertEquals(emptyList<Day>(), DbDeviceList.refreshDays(null, today, utc))
        val start = Day(20261123).startSec(utc) + 3_600
        // Rows from the 23rd to the 24th evening: the 23rd, 24th and the morning after (25th).
        val days = DbDeviceList.refreshDays(start to Day(20261124).startSec(utc) + 20 * 3_600, today, utc)
        assertEquals(listOf(Day(20261125), Day(20261124), Day(20261123)), days)
        val long = DbDeviceList.refreshDays(0L to Day(20261124).startSec(utc), today, utc)
        assertEquals(DbDeviceList.MAX_REFRESH_DAYS, long.size)
        assertEquals(Day(20261124), long.first())
    }
}
