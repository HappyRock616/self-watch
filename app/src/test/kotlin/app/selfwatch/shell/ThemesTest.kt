/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.shell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import app.selfwatch.Appearance
import app.selfwatch.body.WeightChart
import app.selfwatch.body.WeightUiState
import app.selfwatch.db.SettingKeys
import app.selfwatch.graph
import app.selfwatch.me.MeTestSupport
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.time.TimeRange
import app.selfwatch.ui.SwTheme
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.charts.OverlayChartView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.ZoneId

/**
 * The color themes: migration of the old accent values, the setting reaching every screen, and the main
 * tabs and an overlay chart rendering in each theme.
 */
@RunWith(RobolectricTestRunner::class)
class ThemesTest {

    @Before
    fun setUp() {
        MeTestSupport.freshApp()
        SwTheme.resetForTests()
    }

    @After
    fun tearDown() {
        SwTheme.resetForTests()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Applies [theme] the way the app does when the setting changes (SelfWatchApp.watchAppearance). */
    private fun choose(theme: SwTheme.Theme) {
        // Store it too: the app's appearance observer re-applies the stored theme on its own thread, and
        // with nothing stored it would put Orange back (that race made this test fail in full runs).
        kotlinx.coroutines.runBlocking {
            app.selfwatch.db.SelfWatchDb.get(org.robolectric.RuntimeEnvironment.getApplication())
                .settings.set(app.selfwatch.db.SettingKeys.ACCENT, app.selfwatch.Appearance.storedValue(theme))
        }
        SwTheme.setCurrent(theme)
        // Let the observer catch up (it reads on an IO thread) so it can't put an older theme back later.
        val until = System.currentTimeMillis() + 3_000
        do {
            idle()
            Thread.sleep(20)
            idle()
        } while (SwTheme.current != theme && System.currentTimeMillis() < until)
        if (SwTheme.current != theme) SwTheme.setCurrent(theme)
        idle()
    }

    @Test
    fun oldAccentValuesMigrateToTheNearestTheme() {
        // Orange stays Orange; blue, green and violet become Turquoise, Forest and Purple (both palettes).
        val cases = mapOf(
            "#FF8A1F" to SwTheme.Theme.ORANGE, "#E67400" to SwTheme.Theme.ORANGE,
            "#4DABF7" to SwTheme.Theme.TURQUOISE, "#1c7fc4" to SwTheme.Theme.TURQUOISE,
            "#5BC08A" to SwTheme.Theme.FOREST, "#2E8B57" to SwTheme.Theme.FOREST,
            "#9B8CFF" to SwTheme.Theme.PURPLE, " #6B5BD6 " to SwTheme.Theme.PURPLE,
        )
        for ((old, theme) in cases) assertEquals(old, theme, Appearance.themeOf(old))
        assertEquals("#19D3C5", Appearance.migratedValue("#4DABF7"))
        assertEquals("#2E9E5B", Appearance.migratedValue("#5BC08A"))
        assertEquals("#BD93F9", Appearance.migratedValue("#9B8CFF"))
        assertEquals("#FF8A1F", Appearance.migratedValue("#E67400"))
        assertEquals("#FF8A1F", Appearance.migratedValue("rubbish"))
        // Current values and the default need nothing.
        assertNull(Appearance.migratedValue("#FF8A1F"))
        assertNull(Appearance.migratedValue(null))
        for (t in SwTheme.Theme.entries) {
            assertNull(Appearance.migratedValue(Appearance.storedValue(t)))
            assertEquals(t, Appearance.themeOf(Appearance.storedValue(t)))
        }
        // Unknown or missing: Orange, the default.
        assertEquals(SwTheme.Theme.ORANGE, Appearance.themeOf(null))
        assertEquals(SwTheme.Theme.ORANGE, Appearance.themeOf("#123456"))
    }

    @Test
    fun anOldStoredValueIsRewrittenAndReadsAsItsTheme() {
        val settings = RuntimeEnvironment.getApplication().graph.settings
        runBlocking { settings.set(SettingKeys.ACCENT, "#9B8CFF") }
        // What the app does once at start (SelfWatchApp.watchAppearance).
        runBlocking { Appearance.migratedValue(settings.get(SettingKeys.ACCENT))?.let { settings.set(SettingKeys.ACCENT, it) } }
        val stored = runBlocking { settings.get(SettingKeys.ACCENT) }
        assertEquals("#BD93F9", stored)
        assertEquals(SwTheme.Theme.PURPLE, Appearance.parse(null, stored).theme)
        // A fresh install keeps the default, which is already a current value.
        assertNull(Appearance.migratedValue(SettingKeys.defaultOf(SettingKeys.ACCENT)))
    }

    @Test
    fun mainTabsAndAnOverlayChartRenderInEveryTheme() {
        for (theme in SwTheme.Theme.entries) {
            choose(theme)
            val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            idle()
            assertEquals(theme, SwTheme.themeOf(main))
            for (tab in listOf(Tab.BODY, Tab.ACTIVITY, Tab.FOOD, Tab.ME)) {
                main.select(tab)
                idle()
                val view = main.supportFragmentManager.findFragmentByTag(tab.tag)?.view
                assertNotNull("$theme $tab", view)
                draw(view!!)
            }
            // The Weight overlay chart, colored from the theme's palette.
            val zone = ZoneId.systemDefault()
            val t0 = 1_763_852_400L
            val entries = (0 until 30).map { BodyMeasure(it.toLong(), t0 + it * 86_400L, BodyKind.WEIGHT, null, 8_000 + it * 5, DataSource.MANUAL, null, null) }
            val state = WeightUiState(entries = entries, trend = entries.map { it.valueX100 }, loading = false)
            val layers = WeightChart.layers(main, state, zone)
            assertEquals(ChartPalette.of(main).series(0), layers.first().color)
            val chart = OverlayChartView(main).apply {
                range = TimeRange(t0, t0 + 30 * 86_400L)
                this.layers = layers
            }
            draw(chart)
            main.finish()
        }
    }

    @Test
    fun anOpenScreenRecreatesWhenTheThemeChanges() {
        choose(SwTheme.Theme.ORANGE)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val first = controller.get()
        assertEquals(SwTheme.Theme.ORANGE, SwTheme.themeOf(first))
        choose(SwTheme.Theme.FOREST)
        idle()
        val now = controller.get()
        assertTrue("recreated", now !== first)
        assertEquals(SwTheme.Theme.FOREST, SwTheme.themeOf(now))
    }

    private fun draw(v: View) {
        val w = 1080
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val h = v.measuredHeight.coerceIn(1, 4000)
        v.layout(0, 0, w, h)
        v.draw(Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)))
    }
}
