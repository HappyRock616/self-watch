/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every text/background pair the ui components and the five color themes use reaches 4.5:1 (labels on a
 * theme fill: Just Go 5K's own text colors, 3:1 as large bold text), in both looks. The theme fills are
 * the exact Just Go 5K brand colors, so they are not held to 3:1 on the light surfaces; the alarm red is.
 * Values come from colors.xml.
 */
class ColorTokenContrastTest {
    private val themes = listOf("turquoise", "purple", "orange", "yellow", "forest")
    private val surfaces = listOf("sw_background", "sw_surface", "sw_surface_variant", "sw_surface_high")

    /** (text, background) token pairs that carry text. */
    private val textPairs: List<Pair<String, String>> = buildList {
        // Body text and muted captions, on every surface level (cards, chips, filled fields, bubbles).
        for (bg in surfaces) {
            add("sw_on_surface" to bg)
            add("sw_muted" to bg)
        }
        for (t in themes) {
            // Primary as text: text buttons, dialog buttons, focused field labels, good deltas.
            for (bg in listOf("sw_background", "sw_surface", "sw_surface_variant")) add("sw_theme_${t}_accent_text" to bg)
            // Snackbar action on the inverse surface (colorPrimaryInverse).
            add("sw_theme_${t}_accent_text_inverse" to "sw_inverse_surface")
        }
        add("sw_on_inverse_surface" to "sw_inverse_surface")
        add("sw_on_error" to "sw_error")
        add("sw_error" to "sw_surface")
        add("sw_error" to "sw_background")
    }

    /** Labels on a theme fill (filled buttons, colorOnPrimary/OnSecondary/OnTertiary): large, bold text. */
    private val fillLabelPairs: List<Pair<String, String>> = themes.flatMap { t ->
        listOf("sw_theme_${t}_on_primary" to "sw_theme_${t}_primary", "sw_theme_${t}_on_secondary" to "sw_theme_${t}_secondary")
    }

    /** Graphic tokens that must stand out from the card surface and the background (not the brand fills). */
    private val graphics: List<String> = listOf("sw_bad")

    @Test
    fun fillLabelsReachThree() {
        val failures = ArrayList<String>()
        for (look in ColorTokens.THEMES) {
            val t = ColorTokens.load(look)
            for ((fg, bg) in fillLabelPairs) {
                val r = SwContrast.ratio(t.getValue(fg), t.getValue(bg))
                if (r < SwContrast.GRAPHIC_MIN) failures += "$look: $fg on $bg is ${"%.2f".format(r)}:1"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun darkLookFillsReachThree() {
        // In the dark look the exact brand colors already stand out from the surfaces.
        val t = ColorTokens.load(ColorTokens.DARK)
        for (theme in themes) {
            for (g in listOf("sw_theme_${theme}_primary", "sw_theme_${theme}_secondary")) {
                for (bg in listOf("sw_surface", "sw_background")) {
                    assertTrue("$g on $bg", SwContrast.ratio(t.getValue(g), t.getValue(bg)) >= SwContrast.GRAPHIC_MIN)
                }
            }
        }
    }

    @Test
    fun textPairsReachFourPointFive() {
        val failures = ArrayList<String>()
        for (look in ColorTokens.THEMES) {
            val t = ColorTokens.load(look)
            for ((fg, bg) in textPairs) {
                val r = SwContrast.ratio(t.getValue(fg), t.getValue(bg))
                if (r < SwContrast.TEXT_MIN) failures += "$look: $fg on $bg is ${"%.2f".format(r)}:1"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun graphicsReachThreeOnTheSurfaceAndBackground() {
        val failures = ArrayList<String>()
        for (look in ColorTokens.THEMES) {
            val t = ColorTokens.load(look)
            for (g in graphics) {
                for (bg in listOf("sw_surface", "sw_background")) {
                    val r = SwContrast.ratio(t.getValue(g), t.getValue(bg))
                    if (r < SwContrast.GRAPHIC_MIN) failures += "$look: $g on $bg is ${"%.2f".format(r)}:1"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
