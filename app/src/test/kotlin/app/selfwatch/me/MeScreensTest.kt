/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.me

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.selfwatch.SelfWatchApp
import app.selfwatch.R
import app.selfwatch.data.DataActivity
import app.selfwatch.data.DataViewModel
import app.selfwatch.data.ImportActivity
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.dao.NetLogEntry
import app.selfwatch.interop.api.InputSource
import app.selfwatch.interop.api.ImportChoices
import app.selfwatch.model.net.NetFeature
import app.selfwatch.model.result.Outcome
import app.selfwatch.onboarding.OnboardingActivity
import app.selfwatch.shell.MainActivity
import app.selfwatch.shell.Tab
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

/** Robolectric smoke tests: every Me screen and onboarding open with empty and with sample data. */
@RunWith(RobolectricTestRunner::class)
class MeScreensTest {

    private lateinit var app: SelfWatchApp

    @Before
    fun setUp() {
        app = MeTestSupport.freshApp()
    }

    @After
    fun tearDown() = MeTestSupport.resetDb()

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun findToolbar(v: View): com.google.android.material.appbar.MaterialToolbar? = when (v) {
        is com.google.android.material.appbar.MaterialToolbar -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findToolbar(v.getChildAt(it)) }
        else -> null
    }

    private fun Activity.shows(text: String): Boolean = texts(window.decorView).any { it.contains(text) }

    private fun <T : Activity> open(cls: Class<T>, mustShow: String): T {
        val c = Robolectric.buildActivity(cls).setup()
        val a = c.get()
        MeTestSupport.waitFor("$mustShow on ${cls.simpleName}", { texts(a.window.decorView).joinToString(" | ") }) { a.shows(mustShow) }
        return a
    }

    private fun sampleData() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        val db = app.graph.db
        db.netLog.insert(NetLogEntry(0, now, NetFeature.FOOD_DATA, "fooddata.example", "GET", 100, 5_000, 200))
        db.netLog.insert(NetLogEntry(0, now, NetFeature.OPEN_FOOD_FACTS, "world.openfoodfacts.org", "GET", 0, 0, NetLogEntry.BLOCKED))
        val importer = app.graph.interop.importers.first { it.id == "loseit" }
        val plan = (importer.plan(InputSource.ofBytes("loseit.zip", MeTestSupport.zipOfDir("imports-synthetic/loseit"))) as Outcome.Ok).value
        importer.commit(plan, ImportChoices("MM/dd/yyyy", "lb"))
        app.graph.settings.set(DataViewModel.LAST_BACKUP_SEC, now.toString())
    }

    @Test
    fun screensOpenWithEmptyData() {
        open(PrivacyActivity::class.java, "Internet switches")
        open(NetworkLogActivity::class.java, "No requests yet.")
        open(SettingsActivity::class.java, "Always connected")
        open(AboutActivity::class.java, "GNU Affero General Public License")
        open(FoodDataActivity::class.java, "Not installed")
        open(DataActivity::class.java, "No backup yet")
        open(ImportActivity::class.java, "No imports yet.")
    }

    @Test
    fun screensOpenWithSampleData() {
        sampleData()
        open(PrivacyActivity::class.java, "2 internet requests today · 1 blocked")
        open(NetworkLogActivity::class.java, "world.openfoodfacts.org")
        open(DataActivity::class.java, "Last backup")
        open(ImportActivity::class.java, "Lose It! · loseit.zip")
    }

    @Test
    fun onboardingStartsWithThePromiseAndCanBeSkipped() {
        val a = open(OnboardingActivity::class.java, "Your body. Your data. Your business.")
        assertTrue(a.shows("Step 1 of 7"))
        assertTrue(a.shows("No account, no ads"))
        findToolbar(a.window.decorView)?.menu?.performIdentifierAction(1, 0)
        MeTestSupport.waitFor("finished") { a.isFinishing }
        assertTrue(runBlocking { app.graph.settings.getBool(SettingKeys.ONBOARDING_DONE) })
    }

    @Test
    fun firstLaunchOpensOnboardingOnlyUntilDone() {
        val first = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        MeTestSupport.waitFor("onboarding started") { shadowOf(first).peekNextStartedActivity() != null }
        assertEquals(OnboardingActivity::class.java.name, shadowOf(first).nextStartedActivity.component?.className)

        runBlocking { app.graph.settings.setBool(SettingKeys.ONBOARDING_DONE, true) }
        val second = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(10) { MeTestSupport.waitFor("idle") { true } }
        assertEquals(null, shadowOf(second).peekNextStartedActivity())
    }

    @Test
    fun meTabShowsRequestsAndOpensPrivacyCenter() {
        sampleData()
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        main.select(Tab.ME)
        MeTestSupport.waitFor("requests on Me") { main.shows("2 internet requests today") }
        main.findViewById<View>(R.id.privacy_card).performClick()
        var next = shadowOf(main).nextStartedActivity
        while (next != null && next.component?.className == OnboardingActivity::class.java.name) next = shadowOf(main).nextStartedActivity
        assertEquals(PrivacyActivity::class.java.name, next?.component?.className)
    }
}
