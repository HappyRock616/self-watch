/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.shell

import android.os.Looper
import android.view.View
import androidx.fragment.app.Fragment
import app.selfwatch.R
import app.selfwatch.SelfWatchApp
import app.selfwatch.ui.components.ListRow
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    private fun launch() = Robolectric.buildActivity(MainActivity::class.java).setup()

    private fun MainActivity.tab(tab: Tab): Fragment? = supportFragmentManager.findFragmentByTag(tab.tag)

    @Test
    fun opensOnBodyWithOnlyTheBodyFragment() {
        val activity = launch().get()
        val body = activity.tab(Tab.BODY)
        assertNotNull(body)
        assertTrue(body!!.isVisible)
        for (t in Tab.entries - Tab.BODY) assertNull(activity.tab(t))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.add_fab).visibility)
    }

    @Test
    fun everyTabCanBeSelectedAndKeepsItsFragment() {
        val activity = launch().get()
        val nav = activity.findViewById<BottomNavigationView>(R.id.bottom_nav)
        val firstFragments = HashMap<Tab, Fragment>()
        for (t in Tab.entries) {
            nav.selectedItemId = t.itemId
            shadowOf(Looper.getMainLooper()).idle()
            val f = activity.tab(t)
            assertNotNull("fragment for $t", f)
            assertTrue("$t visible", f!!.isVisible)
            assertNotNull("$t has a toolbar", f.requireView().findViewById<View>(R.id.toolbar))
            for (other in Tab.entries - t) assertFalse("$other hidden", activity.tab(other)?.isVisible ?: false)
            firstFragments[t] = f
        }
        // Back to Body: the same fragment instance, shown again (show/hide, not replace).
        nav.selectedItemId = Tab.BODY.itemId
        assertTrue(firstFragments[Tab.BODY] === activity.tab(Tab.BODY))
        assertTrue(activity.tab(Tab.BODY)!!.isVisible)
    }

    @Test
    fun backFromAnotherTabReturnsToBody() {
        val activity = launch().get()
        activity.select(Tab.DEVICES)
        assertTrue(activity.onBackPressedDispatcher.hasEnabledCallbacks())
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(Tab.BODY.itemId, activity.findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun addButtonOpensTheSheetWithAllSevenItems() {
        val activity = launch().get()
        activity.findViewById<View>(R.id.add_fab).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val sheet = activity.supportFragmentManager.findFragmentByTag(AddSheet.TAG) as AddSheet
        val rows = AddSheet.Item.entries.mapNotNull { sheet.requireView().findViewWithTag<ListRow>(it.name) }
        assertEquals(7, rows.size)
        assertEquals(4, AddSheet.items(foodEnabled = false).size)
    }

    @Test
    fun addButtonOnlyOnBodyAndFood() {
        val activity = launch().get()
        val fab = activity.findViewById<View>(R.id.add_fab)
        for (t in Tab.entries) {
            activity.select(t)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("fab on $t", t.hasAddButton, fab.visibility == View.VISIBLE)
        }
    }

    @Test
    fun visitingEveryTabButFoodNeverBuildsTheFoodGraph() {
        // The Food tab is a food screen: it builds the food graph (when food tracking is on).
        val activity = launch().get()
        for (t in Tab.entries - Tab.FOOD) activity.select(t)
        shadowOf(Looper.getMainLooper()).idle()
        val app = RuntimeEnvironment.getApplication() as SelfWatchApp
        assertFalse(app.graph.isFoodBuilt)
        assertFalse(app.graph.isInteropBuilt)
    }

    @Test
    fun survivesRecreationOnTheSelectedTab() {
        val controller = launch()
        controller.get().select(Tab.ME)
        controller.recreate()
        val activity = controller.get()
        assertEquals(Tab.ME.itemId, activity.findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId)
        assertTrue(activity.tab(Tab.ME)!!.isVisible)
        assertFalse(activity.tab(Tab.BODY)!!.isVisible)
    }
}
