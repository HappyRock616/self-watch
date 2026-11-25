/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.me

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.selfwatch.SelfWatchApp
import app.selfwatch.cards.CardStore
import app.selfwatch.cards.MainGoal
import app.selfwatch.food.cards.BurnSource
import app.selfwatch.food.cards.WeightGoalMath
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.food.Profile
import app.selfwatch.model.food.Sex
import app.selfwatch.model.food.UnitSystem
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.net.NetFeature
import app.selfwatch.model.time.Day
import app.selfwatch.shell.MainActivity
import app.selfwatch.shell.Nav
import app.selfwatch.ui.components.ListRow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Me page audit: the Privacy center rows and the weight loss plan on the Goals screen. */
@RunWith(RobolectricTestRunner::class)
class MePageScreensTest {

    private lateinit var app: SelfWatchApp

    @Before
    fun setUp() {
        app = MeTestSupport.freshApp()
    }

    @After
    fun tearDown() = MeTestSupport.resetDb()

    private fun texts(v: View): List<String> = when {
        v.visibility != View.VISIBLE -> emptyList()
        v is TextView -> listOf(v.text.toString())
        v is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun privacyCenterHidesTheRecipeBookAndEveryPermissionRowActs() = privacyCenter()

    @Test
    @Config(fontScale = 1.5f)
    fun privacyCenterAt150Percent() = privacyCenter()

    @Test
    @Config(qualifiers = "w360dp-h640dp", fontScale = 2.0f)
    fun privacyCenterAtDoubleFontOnASmallPhone() = privacyCenter()

    private fun privacyCenter() {
        val a = Robolectric.buildActivity(PrivacyActivity::class.java).setup().get()
        val root = a.window.decorView
        MeTestSupport.waitFor("permissions", { texts(root).joinToString(" | ") }) { root.findViewWithTag<View>("perm_camera") != null }
        val shown = texts(root)
        assertFalse("recipe book row hidden", shown.any { it.contains("Starter recipe book") })
        assertTrue(shown.any { it.contains("Barcode lookups") })
        assertTrue(shown.any { it.contains("Sends a scanned barcode") })

        // Not granted, never asked: the row asks with the system dialog.
        val camera = root.findViewWithTag<ListRow>("perm_camera")
        assertTrue(texts(camera).any { it.contains("Tap to allow") })
        camera.performClick()
        val asked = shadowOf(a).lastRequestedPermission
        assertNotNull(asked)
        assertTrue(asked.requestedPermissions.contains(Manifest.permission.CAMERA))

        // Special access opens its Android page.
        val listener = root.findViewWithTag<ListRow>("perm_notification_access")
        if (listener != null) {
            listener.performClick()
            val next: Intent? = shadowOf(a).nextStartedActivity
            assertTrue(next?.action, next?.action == Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS || next?.action == Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        root.findViewWithTag<ListRow>("perm_battery_optimization")?.let { row ->
            row.performClick()
            assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, shadowOf(a).nextStartedActivity?.action)
        }
        assertFalse(NetFeature.RECIPE_BOOK.defaultOn)
    }

    @Test
    fun grantedPermissionOpensTheAppPageToTakeItBack() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        val a = Robolectric.buildActivity(PrivacyActivity::class.java).setup().get()
        val root = a.window.decorView
        MeTestSupport.waitFor("contacts row") { root.findViewWithTag<View>("perm_contacts") != null }
        val row = root.findViewWithTag<ListRow>("perm_contacts")
        assertTrue(texts(row).any { it.contains("Allowed") })
        row.performClick()
        val next = shadowOf(a).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, next?.action)
        assertEquals(app.packageName, next?.data?.schemeSpecificPart)
    }

    @Test
    fun weightLossPlanWorksOutTheCalorieGoal() = weightLossPlan()

    @Test
    @Config(fontScale = 1.5f)
    fun weightLossPlanAt150Percent() = weightLossPlan()

    @Test
    @Config(qualifiers = "w360dp-h640dp", fontScale = 2.0f)
    fun weightLossPlanAtDoubleFontOnASmallPhone() = weightLossPlan()

    private fun weightLossPlan() {
        val today = Day.of(LocalDate.now())
        runBlocking {
            val g = app.graph
            g.settings.setProfile(Profile(180, today.year - 46, Sex.MALE, UnitSystem.METRIC))
            CardStore(g.settings).setMainGoal(MainGoal.WEIGHT_LOSS)
            g.db.body.insert(BodyMeasure(0, System.currentTimeMillis() / 1000, BodyKind.WEIGHT, null, 90_00, DataSource.MANUAL, null, null))
            // A goal date too soon for a safe pace.
            g.settings.setInt(WeightGoalMath.DATE_KEY, today.plusDays(14).yyyymmdd)
        }
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        Nav.open(a.supportFragmentManager.fragments.first(), GoalsFragment())
        val root = a.window.decorView
        MeTestSupport.waitFor("plan card", { texts(root).joinToString(" | ") }) { texts(root).any { it.contains("Your weight loss plan") } }
        MeTestSupport.waitFor("current weight filled") { root.findViewWithTag<TextView>("current_weight")?.text?.toString() == "90" }
        assertTrue(texts(root).any { it.contains("Next: set your goal weight") })

        root.findViewWithTag<TextView>("goal_weight").text = "86"
        MeTestSupport.waitFor("too soon", { texts(root).joinToString(" | ") }) { texts(root).any { it.contains("too soon for a safe pace") } }
        val earliest = today.plusDays(47)
        val expectedFloor = WeightLossPlanner.advise(90_00, 86_00, today, today.plusDays(14), 2_160, BurnSource.ESTIMATED, Sex.MALE)
        assertEquals(earliest, expectedFloor.earliestSafe)
        assertEquals("1500", root.findViewWithTag<TextView>("goal_kcal").text.toString())

        // Take the proposed date: on time, recalculated.
        root.findViewWithTag<View>("plan_use_date").performClick()
        MeTestSupport.waitFor("on time") { texts(root).none { it.contains("too soon for a safe pace") } }
        val explain = root.findViewWithTag<TextView>("plan_explain").text.toString()
        assertTrue(explain, explain.contains("estimated from your profile") && explain.contains("kcal"))

        // The calorie goal can still be changed by hand, and is saved with the plan.
        root.findViewWithTag<TextView>("goal_kcal").text = "1650"
        clickSave(root)
        MeTestSupport.waitFor("saved") { runBlocking { app.graph.db.goals.allOn(today) }[GoalKind.KCAL_IN]?.value == 1_650 }
        MeTestSupport.waitFor("date saved") { runBlocking { app.graph.settings.getInt(WeightGoalMath.DATE_KEY) } == earliest.yyyymmdd }
        assertEquals(86_00, runBlocking { app.graph.db.goals.allOn(today) }[GoalKind.WEIGHT_KG_X100]?.value)
    }

    private fun clickSave(root: View) {
        fun find(v: View): View? = when {
            v is TextView && v.text?.toString() == "Save" && v.isShown -> v
            v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        (find(root) ?: throw AssertionError("no Save button")).performClick()
    }
}
