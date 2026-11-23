/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.shell

import android.content.Intent
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import app.selfwatch.R
import app.selfwatch.activity.ActivityExportFragment
import app.selfwatch.cards.EditCardsFragment
import app.selfwatch.graph
import app.selfwatch.me.AboutActivity
import app.selfwatch.me.GoalsFragment
import app.selfwatch.me.HelpFragment
import app.selfwatch.me.HelpTopic
import app.selfwatch.me.MeTestSupport
import app.selfwatch.onboarding.OnboardingActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import kotlin.reflect.KClass

/**
 * Taps every overflow item on Body, Activity, Devices and Me: none may say "coming soon", and the
 * ones this branch built open their screen or system intent. Food's menu and Devices › Device order
 * belong to other work and are left out.
 */
@RunWith(RobolectricTestRunner::class)
class MenuSmokeTest {

    private lateinit var main: MainActivity

    @Before
    fun setUp() {
        MeTestSupport.freshApp()
        main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
    }

    @After
    fun tearDown() = MeTestSupport.resetDb()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun toolbar(tab: Tab): MaterialToolbar {
        main.select(tab)
        idle()
        val f = main.supportFragmentManager.findFragmentByTag(tab.tag)
        return requireNotNull(f?.view?.findViewById(R.id.toolbar)) { "toolbar of $tab" }
    }

    private fun detail(): Fragment? = main.supportFragmentManager.findFragmentById(R.id.detail_container)

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun button(root: View, label: String): MaterialButton? = when (root) {
        is MaterialButton -> root.takeIf { it.text.toString() == label }
        is ViewGroup -> (0 until root.childCount).firstNotNullOfOrNull { button(root.getChildAt(it), label) }
        else -> null
    }

    private fun nextActivity(): Intent? {
        var next = shadowOf(main).nextStartedActivity
        while (next != null && next.component?.className == OnboardingActivity::class.java.name) next = shadowOf(main).nextStartedActivity
        return next
    }

    private fun assertNoComingSoon(what: String) {
        val soon = main.getString(R.string.coming_soon).lowercase()
        val toast = ShadowToast.getTextOfLatestToast()?.lowercase().orEmpty()
        assertFalse("$what showed \"$toast\"", toast.contains(soon))
    }

    /** Taps [itemId] on [tab]'s toolbar and checks it did not say "coming soon". */
    private fun tap(tab: Tab, itemId: Int) {
        val bar = toolbar(tab)
        ShadowToast.reset()
        assertTrue(bar.menu.performIdentifierAction(itemId, 0))
        idle()
        assertNoComingSoon("$tab ${main.resources.getResourceEntryName(itemId)}")
    }

    private fun reset() {
        ShadowDialog.getLatestDialog()?.dismiss()
        main.supportFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        idle()
        do {
            val drained = nextActivity()
        } while (drained != null)
    }

    private fun assertDetail(cls: KClass<out Fragment>) {
        val d = detail()
        assertTrue("expected ${cls.simpleName}, got ${d?.javaClass?.simpleName}", cls.isInstance(d))
    }

    @Test
    fun everyMenuItemOutsideFoodWorks() {
        val excluded = setOf(R.id.menu_device_order)
        var tapped = 0
        for (tab in listOf(Tab.BODY, Tab.ACTIVITY, Tab.DEVICES, Tab.ME)) {
            val menu = toolbar(tab).menu
            val ids = (0 until menu.size()).map { menu.getItem(it) }.filter { it.isVisible && it.itemId !in excluded }.map { it.itemId }
            for (id in ids) {
                tap(tab, id)
                tapped++
                reset()
            }
        }
        assertTrue("tapped $tapped items", tapped >= 10)
    }

    @Test
    fun builtItemsOpenTheirScreens() {
        tap(Tab.ACTIVITY, R.id.menu_export_activities)
        assertDetail(ActivityExportFragment::class)
        reset()
        tap(Tab.ACTIVITY, R.id.menu_activity_goals)
        assertDetail(GoalsFragment::class)
        reset()
        tap(Tab.ACTIVITY, R.id.menu_edit_cards)
        assertDetail(EditCardsFragment::class)
        reset()
        tap(Tab.ME, R.id.menu_help)
        assertDetail(HelpFragment::class)
        reset()
        tap(Tab.ME, R.id.menu_about)
        assertEquals(AboutActivity::class.java.name, nextActivity()?.component?.className)
        reset()
        tap(Tab.DEVICES, R.id.menu_bluetooth_help)
        assertDetail(HelpFragment::class)
        assertEquals(main.getString(R.string.menu_bluetooth_help), detail()?.view?.findViewById<MaterialToolbar>(R.id.toolbar)?.title)
    }

    @Test
    fun helpShowsEveryTopic() {
        tap(Tab.ME, R.id.menu_help)
        val shown = texts(requireNotNull(detail()?.view))
        for (t in HelpTopic.entries) assertTrue(t.name, shown.contains(main.getString(t.title)))
        assertTrue(shown.any { it.contains("another watch app on this phone may be connected") })
    }

    @Test
    fun bluetoothHelpOpensSettingsAndPairsAgain() {
        tap(Tab.DEVICES, R.id.menu_bluetooth_help)
        val root = requireNotNull(detail()?.view)
        val shown = texts(root)
        for (t in HelpFragment.BLUETOOTH_TOPICS) assertTrue(t.name, shown.contains(main.getString(t.title)))
        assertFalse("focused on Bluetooth", shown.contains(main.getString(HelpTopic.FOOD.title)))

        requireNotNull(button(root, main.getString(R.string.help_open_bluetooth))).performClick()
        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, nextActivity()?.action)

        requireNotNull(button(root, main.getString(R.string.help_open_battery))).performClick()
        val battery = nextActivity()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, battery?.action)
        assertEquals("package:${main.packageName}", battery?.data?.toString())

        requireNotNull(button(root, main.getString(R.string.help_pair_again))).performClick()
        idle()
        assertFalse("help closed", detail() is HelpFragment)
        // Devices starts pairing: on API 31+ it first explains the Nearby devices permission.
        MeTestSupport.waitFor("pairing reason") { ShadowDialog.getLatestDialog()?.isShowing == true }
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        val dialogTexts = texts(requireNotNull(dialog.window?.decorView))
        assertTrue(dialogTexts.toString(), dialogTexts.any { it == main.getString(R.string.devices_bt_reason) })
    }

    @Test
    fun aboutShowsVersionLicencesAndCredits() {
        val about = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        idle()
        val shown = texts(about.window.decorView).joinToString("\n")
        assertTrue(shown, shown.contains("Version "))
        assertTrue(shown.contains("GNU Affero General Public License"))
        assertTrue(shown.contains("Open Food Facts contributors"))
        assertTrue(shown.contains("Open Database License (ODbL) 1.0"))
        assertTrue(shown.contains("no code was copied"))
    }

    @Test
    fun exportActivitiesAsksWhereToSaveAZip() {
        val now = System.currentTimeMillis() / 1000 - 3_600
        kotlinx.coroutines.runBlocking {
            main.graph.db.workouts.insert(
                app.selfwatch.model.health.Workout(
                    id = 0, deviceId = null, sport = 1, subSport = null, startSec = now, endSec = now + 600, durationSec = 600,
                    distanceM = 1_000, kcal = 80, avgHr = null, maxHr = null, ascentM = null, descentM = null, hasTrack = false,
                    fitPath = null, trackPath = null, routePreview = null, source = app.selfwatch.model.health.DataSource.MANUAL,
                ),
            )
        }
        tap(Tab.ACTIVITY, R.id.menu_export_activities)
        val root = requireNotNull(detail()?.view)
        val export = requireNotNull(button(root, main.getString(R.string.export_act_button)))
        MeTestSupport.waitFor("export enabled", { texts(root).toString() }) { export.isEnabled }
        export.performClick()
        val create = nextActivity()
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, create?.action)
        assertEquals("application/zip", create?.type)
        assertTrue(create?.getStringExtra(Intent.EXTRA_TITLE).orEmpty().startsWith("selfwatch-workouts-gpx-"))
    }
}
