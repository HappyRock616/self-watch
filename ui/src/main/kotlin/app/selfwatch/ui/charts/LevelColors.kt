/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.charts

import android.content.Context
import androidx.annotation.ColorInt

/**
 * Colors that carry a meaning on cards (card design standard, "color is the meaning").
 *
 * - Goal colors ([good], [close], [neutral]) follow the theme palette (primary, secondary, a tint): rings,
 *   week bars, dots and status dots (owner, 2026-11-26: "use the color palette on data cards when possible").
 * - Scale colors ([scaleGood], [scaleClose], [scaleOff], [scaleNeutral]) are the same in every theme, for
 *   gauges and range bars where a color scale reads better (Pixel 5 test: with the Orange theme a
 *   theme-colored scale read backwards). Stress levels use Garmin's scheme.
 */
class LevelColors private constructor(p: ChartPalette) {
    /** Goal met / on track (theme primary). */
    @ColorInt val good: Int = p.primary

    /** Short of goal / close (theme secondary). */
    @ColorInt val close: Int = p.secondary

    /** Off (alarm red). */
    @ColorInt val off: Int = p.alarm

    /** Neutral, no judgment (a tint of primary). */
    @ColorInt val neutral: Int = p.series(2)

    /** Scale: good band (green). */
    @ColorInt val scaleGood: Int = GOOD

    /** Scale: close band (amber). */
    @ColorInt val scaleClose: Int = CLOSE

    /** Scale: off band (red). */
    @ColorInt val scaleOff: Int = OFF

    /** Scale: neutral band (blue). */
    @ColorInt val scaleNeutral: Int = NEUTRAL

    /** Track gray behind rings and bars. */
    @ColorInt val track: Int = p.track

    /** Garmin's stress level colors: rest, low, medium, high. */
    @ColorInt val stressRest: Int = STRESS_REST
    @ColorInt val stressLow: Int = STRESS_LOW
    @ColorInt val stressMedium: Int = STRESS_MED
    @ColorInt val stressHigh: Int = STRESS_HIGH

    /** Color of a stress value 0–100 (0–25 rest, 26–50 low, 51–75 medium, 76–100 high). */
    @ColorInt
    fun stress(v: Int): Int = when {
        v <= 25 -> stressRest
        v <= 50 -> stressLow
        v <= 75 -> stressMedium
        else -> stressHigh
    }

    /** Lookup. */
    companion object {
        private const val GOOD = 0xFF34B37A.toInt()
        private const val CLOSE = 0xFFF2B33D.toInt()
        private const val OFF = 0xFFE5534B.toInt()
        private const val NEUTRAL = 0xFF4AA9F5.toInt()
        private const val STRESS_REST = 0xFF3D8FE0.toInt()
        private const val STRESS_LOW = 0xFFF5B94F.toInt()
        private const val STRESS_MED = 0xFFEF8A2B.toInt()
        private const val STRESS_HIGH = 0xFFD9531E.toInt()

        /** The colors for [context]'s theme. */
        fun of(context: Context): LevelColors = LevelColors(ChartPalette.of(context))
    }
}
