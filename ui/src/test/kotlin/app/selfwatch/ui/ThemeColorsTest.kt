/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui

import app.selfwatch.ui.components.ColorTokens
import app.selfwatch.ui.components.SwContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The five color themes: Just Go 5K's exact colors in both looks, colors.xml and themes.xml in step
 * with [ThemeColors], and the readable-text rules.
 */
class ThemeColorsTest {
    private fun hex(c: Int): String = "#%06X".format(c and 0xFFFFFF)

    @Test
    fun themesAreJustGo5KsInItsOrder() {
        // Source of truth: JustGo5K/source/Theme.mc (primary, secondary, text on primary, text on secondary).
        val expected = listOf(
            listOf("#19D3C5", "#FFD84D", "#04342C", "#412402"),
            listOf("#BD93F9", "#7CC8FF", "#26215C", "#042C53"),
            listOf("#FF8A1F", "#4DABF7", "#412402", "#042C53"),
            listOf("#F5B841", "#2E9E5B", "#412402", "#E8F5EC"),
            listOf("#2E9E5B", "#F5B841", "#E8F5EC", "#412402"),
        )
        assertEquals(listOf("TURQUOISE", "PURPLE", "ORANGE", "YELLOW", "FOREST"), SwTheme.Theme.entries.map { it.name })
        for ((i, t) in SwTheme.Theme.entries.withIndex()) {
            assertEquals(t.name, expected[i], listOf(t.primary, t.secondary, t.onPrimary, t.onSecondary).map(::hex))
        }
        assertEquals(SwTheme.Theme.ORANGE, SwTheme.DEFAULT_THEME)
    }

    @Test
    fun bothLooksUseTheExactThemeColors() {
        for (t in SwTheme.Theme.entries) {
            for (night in listOf(false, true)) {
                val c = ThemeColors.of(t, night)
                assertEquals("$t night=$night", hex(t.primary), hex(c.primary))
                assertEquals("$t night=$night", hex(t.secondary), hex(c.secondary))
                assertEquals("$t night=$night", hex(t.onPrimary), hex(c.onPrimary))
                assertEquals("$t night=$night", hex(t.onSecondary), hex(c.onSecondary))
            }
        }
    }

    @Test
    fun paletteXmlHoldsTheThemeMcValuesInBothLooks() {
        val failures = ArrayList<String>()
        for (look in ColorTokens.THEMES) {
            val tokens = ColorTokens.load(look)
            for (t in SwTheme.Theme.entries) {
                val name = t.name.lowercase()
                val want = mapOf("primary" to t.primary, "secondary" to t.secondary, "on_primary" to t.onPrimary, "on_secondary" to t.onSecondary)
                for ((k, v) in want) {
                    val got = tokens["sw_theme_${name}_$k"]
                    if (got == null || hex(got) != hex(v)) failures += "$look sw_theme_${name}_$k: ${got?.let(::hex)} != ${hex(v)}"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun lightLookTextIsNeverADarkenedThemeColor() {
        // On light surfaces, accent text is the theme color itself, Just Go 5K's text-on color or the text color.
        for (t in SwTheme.Theme.entries) {
            val c = ThemeColors.of(t, night = false)
            assertTrue("$t accentText ${hex(c.accentText)}", c.accentText in intArrayOf(c.primary, c.onPrimary, c.neutrals.text))
            assertTrue("$t secondaryText ${hex(c.secondaryText)}", c.secondaryText in intArrayOf(c.secondary, c.onSecondary, c.neutrals.text))
            val dark = ThemeColors.of(t, night = true)
            assertTrue("$t inverse ${hex(dark.accentTextInverse)}", dark.accentTextInverse in intArrayOf(dark.primary, dark.onPrimary, dark.neutrals.inverseText))
        }
        assertEquals("#412402", hex(ThemeColors.of(SwTheme.Theme.YELLOW, night = false).accentText))
        assertEquals("#16202A", hex(ThemeColors.of(SwTheme.Theme.FOREST, night = false).accentText))
    }

    @Test
    fun colorsXmlMatchesThemeColors() {
        val failures = ArrayList<String>()
        for (look in ColorTokens.THEMES) {
            val tokens = ColorTokens.load(look)
            val night = look == ColorTokens.DARK
            for (t in SwTheme.Theme.entries) {
                val c = ThemeColors.of(t, night)
                val name = t.name.lowercase()
                val want = mapOf(
                    "primary" to c.primary, "on_primary" to c.onPrimary, "secondary" to c.secondary,
                    "on_secondary" to c.onSecondary, "accent_text" to c.accentText, "accent_text_inverse" to c.accentTextInverse,
                )
                for ((k, v) in want) {
                    val token = "sw_theme_${name}_$k"
                    val got = tokens[token]
                    if (got == null || hex(got) != hex(v)) failures += "$look $token: xml ${got?.let(::hex)}, ThemeColors ${hex(v)}"
                }
            }
            // Neutrals in ThemeColors are the colors.xml surfaces.
            val n = if (night) ThemeColors.Neutrals.DARK else ThemeColors.Neutrals.LIGHT
            val neutral = mapOf(
                "sw_background" to n.background, "sw_surface" to n.surface, "sw_surface_variant" to n.surfaceVariant,
                "sw_inverse_surface" to n.inverseSurface, "sw_on_surface" to n.text, "sw_track" to n.track,
            )
            for ((token, v) in neutral) if (hex(tokens.getValue(token)) != hex(v)) failures += "$look $token differs"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun everyOverlaySetsItsOrdinalAndItsOwnColors() {
        val f = File("src/main/res/values/themes.xml").takeIf { it.exists() } ?: File("ui/src/main/res/values/themes.xml")
        val xml = f.readText()
        for (t in SwTheme.Theme.entries) {
            val styleName = "ThemeOverlay.SelfWatch.Theme." + t.name.lowercase().replaceFirstChar { it.uppercase() }
            val body = Regex("""<style name="${Regex.escape(styleName)}"[^>]*>(.*?)</style>""", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1) ?: error("missing $styleName")
            assertTrue(styleName, body.contains("""<item name="swThemeId">${t.ordinal}</item>"""))
            val name = t.name.lowercase()
            for (role in listOf("colorPrimary", "colorOnPrimary", "colorSecondary", "colorOnSecondary", "swColorAccentText", "colorPrimaryInverse")) {
                val item = Regex("""<item name="$role">@color/([a-z_]+)</item>""").find(body)?.groupValues?.get(1)
                assertTrue("$styleName $role -> $item", item != null && item.startsWith("sw_theme_${name}_"))
            }
        }
    }

    @Test
    fun smallTextReachesFourPointFive() {
        for (t in SwTheme.Theme.entries) {
            for (night in listOf(false, true)) {
                val c = ThemeColors.of(t, night)
                val n = c.neutrals
                val pairs = listOf(
                    "accentText/bg" to SwContrast.ratio(c.accentText, n.background),
                    "accentText/surface" to SwContrast.ratio(c.accentText, n.surface),
                    "accentText/variant" to SwContrast.ratio(c.accentText, n.surfaceVariant),
                    "secondaryText/surface" to SwContrast.ratio(c.secondaryText, n.surface),
                    "accentTextInverse" to SwContrast.ratio(c.accentTextInverse, n.inverseSurface),
                )
                for ((what, r) in pairs) assertTrue("$t night=$night $what ${"%.2f".format(r)}", r >= SwContrast.TEXT_MIN)
                // Button labels on a fill are Just Go 5K's own text colors: at least 3:1 (large, bold text).
                assertTrue("$t onPrimary", SwContrast.ratio(c.onPrimary, c.primary) >= SwContrast.GRAPHIC_MIN)
                assertTrue("$t onSecondary", SwContrast.ratio(c.onSecondary, c.secondary) >= SwContrast.GRAPHIC_MIN)
            }
        }
    }

    @Test
    fun justGo5KTextOnColorsAreKept() {
        val orange = ThemeColors.of(SwTheme.Theme.ORANGE, night = true)
        assertEquals("#412402", hex(orange.onPrimary))
        assertEquals("#042C53", hex(orange.onSecondary))
        assertEquals("#E8F5EC", hex(ThemeColors.of(SwTheme.Theme.FOREST, night = true).onPrimary))
        assertEquals("#E8F5EC", hex(ThemeColors.of(SwTheme.Theme.FOREST, night = false).onPrimary))
    }
}
