/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.charts

import android.content.Context
import androidx.appcompat.view.ContextThemeWrapper
import app.selfwatch.model.health.SleepStage
import app.selfwatch.ui.R
import app.selfwatch.ui.SwTheme
import app.selfwatch.ui.ThemeColors
import app.selfwatch.ui.components.SwContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChartPaletteTest {
    private val looks = listOf(false, true)

    private fun distance(a: Int, b: Int): Double {
        var sum = 0.0
        for (shift in intArrayOf(16, 8, 0)) {
            val d = ((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)
            sum += (d * d).toDouble()
        }
        return sqrt(sum)
    }

    private fun palettes(): List<ChartPalette> = SwTheme.Theme.entries.flatMap { t -> looks.map { ChartPalette.of(t, it) } }

    @Test
    fun seriesOneAndTwoArePrimaryAndSecondary() {
        for (p in palettes()) {
            val c = ThemeColors.of(p.theme, p.night)
            assertEquals(c.primary, p.series(0))
            assertEquals(c.secondary, p.series(1))
            // Exactly Just Go 5K's colors (Theme.mc) in both looks.
            assertEquals(p.theme.primary, p.series(0))
            assertEquals(p.theme.secondary, p.series(1))
        }
    }

    @Test
    fun everySeriesIndexHasADistinctColor() {
        for (p in palettes()) {
            val colors = (0 until p.seriesCount).map { p.series(it) }
            assertEquals("${p.theme} night=${p.night}", colors.size, colors.toSet().size)
            for (i in colors.indices) {
                for (j in i + 1 until colors.size) {
                    val d = distance(colors[i], colors[j])
                    assertTrue("${p.theme} night=${p.night} series ${i + 1} and ${j + 1} too close ($d)", d >= MIN_DISTANCE)
                }
            }
            // The list repeats after seriesCount, also for negative indices.
            assertEquals(p.series(0), p.series(p.seriesCount))
            assertEquals(p.series(p.seriesCount - 1), p.series(-1))
        }
    }

    @Test
    fun derivedSeriesReachThreeToOneOnSurfaceAndBackground() {
        // Series 1 and 2 are the exact brand colors; the gray steps (5, 6) and the dark look's tints reach 3:1.
        // The light look's tints (3, 4) stay pale on purpose: pulling them to 3:1 on white darkens them to brown.
        for (p in palettes()) {
            val n = p.colors.neutrals
            for (i in (if (p.night) 2 else 4) until p.seriesCount) {
                for (bg in intArrayOf(n.surface, n.background)) {
                    val r = SwContrast.ratio(p.series(i), bg)
                    assertTrue("${p.theme} night=${p.night} series ${i + 1}: ${"%.2f".format(r)}", r >= SwContrast.GRAPHIC_MIN)
                }
            }
        }
    }

    @Test
    fun derivedSeriesAreNeverBrownOrOlive() {
        // Owner's decision: no shade towards black. Series 3 and 4 are tints of primary and secondary (every
        // channel at least the source's, so lighter in the same hue); series 5 and 6 are neutral grays.
        for (p in palettes()) {
            for ((i, source) in listOf(2 to p.primary, 3 to p.secondary)) {
                val c = p.series(i)
                for (shift in intArrayOf(16, 8, 0)) {
                    val ch = (c shr shift) and 0xFF
                    val src = (source shr shift) and 0xFF
                    assertTrue("${p.theme} night=${p.night} series ${i + 1} darker than its source", ch >= src)
                }
                assertTrue(SwContrast.luminance(c) > SwContrast.luminance(source))
            }
            for (i in 4..5) {
                val c = p.series(i)
                val ch = intArrayOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
                assertTrue("${p.theme} night=${p.night} series ${i + 1} is not a gray", ch.max() - ch.min() <= GRAY_SPREAD)
            }
            // Deep sleep is the exact secondary, never a darkened one.
            assertEquals(p.secondary, p.stage(SleepStage.DEEP))
        }
    }

    @Test
    fun redIsOnlyTheAlarm() {
        for (p in palettes()) {
            assertEquals(0xFFFF453A.toInt(), p.alarm)
            for (i in 0 until p.seriesCount) assertTrue(distance(p.series(i), p.alarm) > MIN_DISTANCE)
        }
    }

    @Test
    fun sleepStagesAreThemeShadesAndDistinct() {
        val stages = listOf(SleepStage.DEEP, SleepStage.LIGHT, SleepStage.REM, SleepStage.AWAKE)
        for (p in palettes()) {
            val colors = stages.map { p.stage(it) }
            for (i in colors.indices) {
                for (j in i + 1 until colors.size) {
                    assertTrue("${p.theme} night=${p.night} ${stages[i]}/${stages[j]}", distance(colors[i], colors[j]) >= MIN_DISTANCE)
                }
            }
            // REM is the primary; deep is darker than light.
            assertEquals(p.primary, p.stage(SleepStage.REM))
            assertTrue(SwContrast.luminance(p.stage(SleepStage.DEEP)) < SwContrast.luminance(p.stage(SleepStage.LIGHT)))
            val n = p.colors.neutrals
            for ((s, c) in stages.zip(colors)) {
                if (c == p.primary || c == p.secondary) continue // exact brand colors
                if (!p.night && s == SleepStage.LIGHT) continue // a pale tint on white, on purpose (never darkened)
                val r = SwContrast.ratio(c, n.surface)
                assertTrue("${p.theme} night=${p.night} $s on surface ${"%.2f".format(r)}", r >= SwContrast.GRAPHIC_MIN)
            }
        }
    }

    @Test
    fun neutralsAndTagsAreReadable() {
        for (p in palettes()) {
            val n = p.colors.neutrals
            assertTrue(SwContrast.ratio(p.text, n.surface) >= SwContrast.TEXT_MIN)
            assertTrue(SwContrast.ratio(p.muted, n.surface) >= SwContrast.TEXT_MIN)
            assertTrue(SwContrast.ratio(p.primaryText, n.surface) >= SwContrast.TEXT_MIN)
            assertTrue(SwContrast.ratio(p.secondaryText, n.surface) >= SwContrast.TEXT_MIN)
            assertTrue(SwContrast.ratio(p.tagText(true), p.tagFill(true)) >= SwContrast.TEXT_MIN)
            assertTrue(SwContrast.ratio(p.tagText(false), p.tagFill(false)) >= SwContrast.TEXT_MIN)
            assertTrue(SwContrast.ratio(p.neutralTagText, p.neutralTagFill) >= SwContrast.TEXT_MIN)
        }
        assertEquals(0xFF3A3A38.toInt(), ChartPalette.of(SwTheme.Theme.ORANGE, true).track)
    }

    @Test
    fun paletteFollowsTheContextsOverlay() {
        val base: Context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_SelfWatch)
        // No overlay: the base theme is Orange.
        assertEquals(SwTheme.Theme.ORANGE, SwTheme.themeOf(base))
        for (t in SwTheme.Theme.entries) {
            val ctx = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_SelfWatch)
            SwTheme.applyTheme(ctx, t)
            assertEquals(t, SwTheme.themeOf(ctx))
            val p = ChartPalette.of(ctx)
            assertEquals(t, p.theme)
            assertFalse(p.night) // Robolectric's default configuration is the light look
            // The overlay's colorPrimary is the palette's series 1.
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true)
            assertEquals(p.primary, tv.data)
        }
    }

    private companion object {
        // Euclidean RGB distance; about the smallest step two lines can still be told apart by.
        const val MIN_DISTANCE: Double = 24.0

        // Largest channel spread that still reads as a neutral gray.
        const val GRAY_SPREAD: Int = 12
    }
}
