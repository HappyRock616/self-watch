/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import app.selfwatch.R
import app.selfwatch.SelfWatchApp
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.TestDb
import app.selfwatch.fit.encode.ActivityFileWriter
import app.selfwatch.me.MeTestSupport
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.TrackPoint
import app.selfwatch.model.health.Workout
import app.selfwatch.shell.MainActivity
import app.selfwatch.shell.Nav
import app.selfwatch.ui.charts.OverlayChartView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The workout screens: trim (slider screen, save, undo) and the per-sport dashboard in All activities
 * (chart, layer chips remembered per sport, remove and bring back, tap a workout), in the default dark
 * theme, in light with another accent at 1.5× font and at 2.0× font on a small phone.
 */
@RunWith(RobolectricTestRunner::class)
class WorkoutScreensTest {

    private lateinit var app: SelfWatchApp
    private val t0 = 1_795_000_000L

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as SelfWatchApp
        TestDb.wipeShared(app)
    }

    @After
    fun tearDown() = MeTestSupport.resetDb()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun texts(v: View): List<String> = when {
        v.visibility != View.VISIBLE -> emptyList()
        v is TextView -> listOf(v.text?.toString().orEmpty())
        v is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun MainActivity.shows(text: String): Boolean = texts(window.decorView).any { it.contains(text) }

    private fun MainActivity.top(): Fragment = requireNotNull(supportFragmentManager.findFragmentById(R.id.detail_container))

    private fun MainActivity.toolbar(): MaterialToolbar = top().requireView().findViewById(R.id.toolbar)

    private fun waitFor(a: MainActivity, what: String, cond: () -> Boolean) = MeTestSupport.waitFor(what, { texts(a.window.decorView).toString() }, cond)

    /** A one-hour recording: a 30-minute run, then 30 minutes of driving home. */
    private fun points(): List<TrackPoint> = (0..360).map { i ->
        val running = i <= 180
        val lat = if (running) 476_000_000 + i * 2_700 else 476_000_000 + 180 * 2_700 + (i - 180) * 13_500
        TrackPoint(t0 + i * 10L, lat, -1_223_000_000, 10_000, if (running) 150 else 80, null, null, if (running) 3_000 else 15_000)
    }

    private fun seedRun(): Long = runBlocking {
        val w = Workout(
            0, 1, Sports.RUNNING, null, t0, t0 + 3600, 3600, 32_000, 900, 115, 150, 5, 5, true, "workouts/test-run.fit", null, null, DataSource.WATCH,
        )
        val f = File(app.filesDir, "workouts/test-run.fit")
        f.parentFile?.mkdirs()
        f.outputStream().use { ActivityFileWriter.write(it, w, points()) }
        app.graph.db.workouts.insert(w)
    }

    private fun seedRides() = runBlocking {
        for (i in 1..4) {
            val start = t0 - i * 86_400L
            app.graph.db.workouts.insert(
                Workout(0, 1, Sports.CYCLING, null, start, start + 3600, 3600, 30_000 + i * 500, 700, 130 + i, 165, 200, 200, false, null, null, null, DataSource.WATCH),
            )
            // The watch's cycling VO2 max, updated as each ride is saved.
            app.graph.db.metrics.insert(listOf(MetricSample(1, MetricKind.VO2_MAX_CYCLING, start + 3660, 500 + (4 - i) * 3)))
        }
    }

    private fun trimAndUndo() {
        val id = seedRun()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        Nav.open(a.supportFragmentManager.fragments.first(), WorkoutDetailFragment.newInstance(id))
        waitFor(a, "workout") { a.shows("19.9") && a.shows("Route") }
        assertTrue(a.toolbar().menu.findItem(R.id.menu_trim_workout).isVisible)
        assertFalse(a.toolbar().menu.findItem(R.id.menu_undo_trim).isVisible)

        // Trim: the whole recording to start with, nothing to save yet.
        a.toolbar().menu.performIdentifierAction(R.id.menu_trim_workout, 0)
        idle()
        waitFor(a, "trim screen") { a.top() is WorkoutTrimFragment && a.shows("Nothing cut yet") && a.shows("Starts ") }
        assertTrue(a.shows("Drag the ends"))
        val save = a.top().requireView().findViewById<View>(R.id.trim_save)
        assertFalse(save.isEnabled)
        assertNotNull(a.top().requireView().findViewById<View>(R.id.trim_slider))
        // Cut the drive: the summary follows.
        val vm = ViewModelProvider(a.top())[WorkoutTrimViewModel::class.java]
        vm.setWindow(t0, t0 + 1800)
        waitFor(a, "preview") { a.shows("30 min cut") && a.shows("150") }
        assertTrue(save.isEnabled)
        save.performClick()
        waitFor(a, "back on the workout, trimmed") { a.top() is WorkoutDetailFragment && a.shows("Trimmed: 30 min cut") }
        val trimmed = runBlocking { app.graph.db.workouts.get(id)!! }
        assertEquals(t0 + 1800, trimmed.endSec)
        assertEquals(5_330.0, trimmed.distanceM!!.toDouble(), 60.0)
        assertEquals(150, trimmed.avgHr)
        assertEquals("workouts/test-run.fit", trimmed.fitPath)
        assertTrue(File(app.filesDir, "workouts/test-run.fit").isFile)
        waitFor(a, "undo shown") { a.toolbar().menu.findItem(R.id.menu_undo_trim).isVisible }
        assertEquals("Export FIT (trimmed)", a.toolbar().menu.findItem(R.id.menu_export_fit).title.toString())

        // Undo trim: everything back.
        a.toolbar().menu.performIdentifierAction(R.id.menu_undo_trim, 0)
        waitFor(a, "undone") { !a.shows("Trimmed:") && runBlocking { app.graph.db.workouts.get(id)!!.endSec } == t0 + 3600 }
        assertEquals(32_000, runBlocking { app.graph.db.workouts.get(id)!!.distanceM })
    }

    private fun sportDashboard() {
        seedRides()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        Nav.open(a.supportFragmentManager.fragments.first(), WorkoutListFragment.newInstance(Sports.CYCLING))
        waitFor(a, "bike dashboard") { a.shows("Bike trends") && a.shows("Your last 4 workouts") }
        val root = a.top().requireView()
        val chips = chips(root)
        assertTrue(chips.toString(), chips.keys.containsAll(listOf("Avg heart rate", "Distance", "Speed", "Calories", "Elevation gain")))
        assertTrue(chips.getValue("Avg heart rate").isChecked)
        assertFalse(chips.getValue("Calories").isChecked)
        // VO2 max is a layer too (off until picked), read from the estimate after each ride.
        assertFalse(chips.getValue("VO2 max").isChecked)
        chips.getValue("VO2 max").performClick()
        idle()
        val chart = root.findViewById<OverlayChartView>(R.id.sport_dashboard_chart)
        val newest = t0 - 86_400L
        val vo2 = chart.valuesAt(newest + 600).first { it.layerId == "vo2_max" }
        assertEquals(50.9, vo2.value!!, 1e-9)
        assertTrue(vo2.text, vo2.text.startsWith("50.9") && vo2.text.endsWith("ml/kg/min"))
        // A chip switches its layer and the choice is kept for this sport.
        chips.getValue("Calories").performClick()
        idle()
        MeTestSupport.waitFor("saved") { runBlocking { app.graph.db.settings.get(SportDashboardPrefs.layersKey(Sports.CYCLING)) }?.contains("kcal") == false }
        // Remove, then bring back from the menu.
        clickText(root, "Remove")
        val card = root.findViewById<View>(R.id.sport_dashboard_card)
        waitFor(a, "removed") { card.visibility == View.GONE && a.toolbar().menu.findItem(R.id.menu_show_sport_dashboard).isVisible }
        assertEquals("Show Bike trends", a.toolbar().menu.findItem(R.id.menu_show_sport_dashboard).title.toString())
        a.toolbar().menu.performIdentifierAction(R.id.menu_show_sport_dashboard, 0)
        waitFor(a, "back") { card.visibility == View.VISIBLE }
        // The list stays below; tapping a workout opens it.
        val row = findDescribed(root, "Bike, ")
        row.performClick()
        waitFor(a, "detail") { a.top() is WorkoutDetailFragment }
    }

    private fun chips(v: View): Map<String, Chip> = when (v) {
        is Chip -> mapOf(v.text.toString() to v)
        is ViewGroup -> (0 until v.childCount).fold(emptyMap()) { acc, i -> acc + chips(v.getChildAt(i)) }
        else -> emptyMap()
    }

    private fun findDescribed(v: View, prefix: String): View {
        fun find(x: View): View? = when {
            x.contentDescription?.toString()?.startsWith(prefix) == true && x.isShown -> x
            x is ViewGroup -> (0 until x.childCount).firstNotNullOfOrNull { find(x.getChildAt(it)) }
            else -> null
        }
        return requireNotNull(find(v)) { "nothing described as $prefix" }
    }

    private fun clickText(root: View, text: String) {
        fun find(v: View): View? = when {
            v is TextView && v.text?.toString() == text && v.isShown -> v
            v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        requireNotNull(find(root)) { "no view shows $text" }.performClick()
        idle()
    }

    @Test
    fun trimInDarkTheme() = trimAndUndo()

    @Test
    fun sportDashboardInDarkTheme() = sportDashboard()

    @Test
    @Config(fontScale = 1.5f)
    fun screensInLightThemeWithAnotherAccentAt150Percent() {
        runBlocking {
            app.graph.db.settings.set(SettingKeys.THEME, "light")
            app.graph.db.settings.set(SettingKeys.ACCENT, "#2E9E5B")
        }
        trimAndUndo()
        sportDashboard()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp", fontScale = 2.0f)
    fun screensAtDoubleFontOnASmallPhone() {
        trimAndUndo()
        sportDashboard()
    }
}
