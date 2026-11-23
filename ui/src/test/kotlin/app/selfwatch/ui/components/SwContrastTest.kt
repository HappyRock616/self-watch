/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.components

import app.selfwatch.model.health.SleepStage
import app.selfwatch.ui.SwTheme
import app.selfwatch.ui.charts.ChartPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwContrastTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun ratioMatchesWcagReferenceValues() {
        assertEquals(21.0, SwContrast.ratio(black, white), 1e-9)
        assertEquals(1.0, SwContrast.ratio(white, white), 1e-9)
        // #777777 on white is the classic 4.48:1 just-fails case.
        assertEquals(4.48, SwContrast.ratio(0xFF777777.toInt(), white), 0.01)
        // The light accent that prompted the text-accent token: about 3:1.
        assertEquals(3.06, SwContrast.ratio(0xFFE67400.toInt(), white), 0.01)
    }

    @Test
    fun textOnPicksTheReadableSide() {
        assertEquals(SwContrast.DARK_TEXT, SwContrast.textOn(white))
        assertEquals(SwContrast.LIGHT_TEXT, SwContrast.textOn(black))
        // The light HRV (violet) chip: dark text was about 3.2:1, white is over 5:1.
        assertEquals(SwContrast.LIGHT_TEXT, SwContrast.textOn(0xFF6B5BD6.toInt()))
        // The dark theme's light series colours keep near-black text.
        assertEquals(SwContrast.DARK_TEXT, SwContrast.textOn(0xFF9B8CFF.toInt()))
        assertEquals(SwContrast.DARK_TEXT, SwContrast.textOn(0xFFFF8A1F.toInt()))
    }

    @Test
    fun chipTextReachesTextContrastOnEverySeriesColorInEveryTheme() {
        for (theme in SwTheme.Theme.entries) {
            for (night in listOf(false, true)) {
                val p = ChartPalette.of(theme, night)
                val fills = (0 until p.seriesCount).map { p.series(it) } + SleepStage.entries.map { p.stage(it) }
                for (fill in fills) {
                    val r = SwContrast.ratio(ChartPalette.textOn(fill), fill)
                    assertTrue("$theme night=$night ${Integer.toHexString(fill)}: chip text ${"%.2f".format(r)}:1", r >= SwContrast.TEXT_MIN)
                }
            }
        }
    }

    @Test
    fun ensureLeavesGoodColoursAndFixesWeakOnes() {
        val navy = 0xFF16202A.toInt()
        assertEquals(navy, SwContrast.ensure(navy, white))
        val fixed = SwContrast.ensure(0xFFE67400.toInt(), white)
        assertTrue(SwContrast.ratio(fixed, white) >= SwContrast.TEXT_MIN)
        // Still orange-ish: red channel above green above blue.
        val r = (fixed shr 16) and 0xFF
        val g = (fixed shr 8) and 0xFF
        val b = fixed and 0xFF
        assertTrue(r > g && g > b)
        // On a dark surface a dark colour is lightened.
        val surface = 0xFF1F4766.toInt()
        val lifted = SwContrast.ensure(0xFF1C5C99.toInt(), surface)
        assertTrue(SwContrast.ratio(lifted, surface) >= SwContrast.TEXT_MIN)
        assertTrue(SwContrast.luminance(lifted) > SwContrast.luminance(0xFF1C5C99.toInt()))
    }

    @Test
    fun mixEnds() {
        assertEquals(0xFF102030.toInt(), SwContrast.mix(0xFF102030.toInt(), white, 0.0))
        assertEquals(white, SwContrast.mix(0xFF102030.toInt(), white, 1.0))
    }
}
