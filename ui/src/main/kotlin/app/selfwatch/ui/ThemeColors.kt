/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui

import androidx.annotation.ColorInt
import app.selfwatch.ui.components.SwContrast

/**
 * The colors one [SwTheme.Theme] gives in one look (dark or light), worked out from the theme's two
 * Just Go 5K colors. Pure maths, no Android types, so it runs in JVM tests; the theme overlays in
 * `themes.xml` hold the same values (a test checks they match).
 *
 * Rules:
 * - Fills ([primary], [secondary]) are the theme colors, mixed towards black (light look) or white (dark
 *   look) only as far as needed to reach 3:1 on the card surface and the background. In the dark look the
 *   five themes need no change, so they are exactly Just Go 5K's colors.
 * - Text on a fill is Just Go 5K's text color when it reaches 4.5:1, else a dark (or light) shade of the
 *   fill's own hue that does.
 * - [accentText] is the primary as small text: 4.5:1 on the background, surface and surface variant.
 */
class ThemeColors private constructor(
    /** The theme. */
    val theme: SwTheme.Theme,
    /** True for the dark look. */
    val night: Boolean,
) {
    /** The neutrals of this look. */
    val neutrals: Neutrals = if (night) Neutrals.DARK else Neutrals.LIGHT

    /** Primary as a fill (buttons, rings, series 1). */
    @ColorInt
    val primary: Int = fill(theme.primary)

    /** Secondary as a fill (series 2). */
    @ColorInt
    val secondary: Int = fill(theme.secondary)

    /** Text and icons on [primary]. */
    @ColorInt
    val onPrimary: Int = textOnFill(theme.onPrimary, primary)

    /** Text and icons on [secondary]. */
    @ColorInt
    val onSecondary: Int = textOnFill(theme.onSecondary, secondary)

    /** Primary as small text on the screen's surfaces (text buttons, links, focused labels). */
    @ColorInt
    val accentText: Int = ensureAll(
        theme.primary,
        intArrayOf(neutrals.background, neutrals.surface, neutrals.surfaceVariant),
        SwContrast.TEXT_MIN,
    )

    /** Secondary as small text on the screen's surfaces (labels of series 2). */
    @ColorInt
    val secondaryText: Int = ensureAll(
        theme.secondary,
        intArrayOf(neutrals.background, neutrals.surface, neutrals.surfaceVariant),
        SwContrast.TEXT_MIN,
    )

    /** Primary as text on the inverse surface (snackbar action). */
    @ColorInt
    val accentTextInverse: Int = ensureAll(theme.primary, intArrayOf(neutrals.inverseSurface), SwContrast.TEXT_MIN)

    /** [c] as a graphic: at least 3:1 on the surface and the background. */
    @ColorInt
    fun fill(@ColorInt c: Int): Int =
        ensureAll(c, intArrayOf(neutrals.surface, neutrals.background), SwContrast.GRAPHIC_MIN)

    /**
     * The neutral colors of one look. Background, surfaces and text match `colors.xml`; muted, track and
     * the chart text grays are Just Go 5K's.
     */
    class Neutrals private constructor(
        /** Screen background. */
        @get:ColorInt val background: Int,
        /** Card surface. */
        @get:ColorInt val surface: Int,
        /** Surface variant (chips, filled fields). */
        @get:ColorInt val surfaceVariant: Int,
        /** Inverse surface (snackbars). */
        @get:ColorInt val inverseSurface: Int,
        /** Main text. */
        @get:ColorInt val text: Int,
        /** Secondary text and neutral chart marks (axis labels, dots, bands). */
        @get:ColorInt val muted: Int,
        /** Ring and bar tracks (done / empty gray). */
        @get:ColorInt val track: Int,
    ) {
        /** The two looks. */
        companion object {
            /** Dark look (default). */
            val DARK: Neutrals = Neutrals(
                background = 0xFF0B0B0B.toInt(), surface = 0xFF16324A.toInt(), surfaceVariant = 0xFF1F4766.toInt(),
                inverseSurface = 0xFFF1EFE8.toInt(), text = 0xFFF1EFE8.toInt(), muted = 0xFFB4B2A9.toInt(),
                track = 0xFF3A3A38.toInt(),
            )

            /** Light look. */
            val LIGHT: Neutrals = Neutrals(
                background = 0xFFF7F6F2.toInt(), surface = 0xFFFFFFFF.toInt(), surfaceVariant = 0xFFE9EEF2.toInt(),
                inverseSurface = 0xFF16202A.toInt(), text = 0xFF16202A.toInt(), muted = 0xFF5F5E5A.toInt(),
                track = 0xFFE2E1DB.toInt(),
            )
        }
    }

    /** Lookup. */
    companion object {
        /** Red, kept for destructive, alarm and error states in every theme (Just Go 5K's "missed"). */
        @ColorInt
        val ALARM: Int = 0xFFFF453A.toInt()

        private val cache = HashMap<Pair<SwTheme.Theme, Boolean>, ThemeColors>()

        /** The colors of [theme] in the dark ([night]) or light look. */
        fun of(theme: SwTheme.Theme, night: Boolean): ThemeColors =
            synchronized(cache) { cache.getOrPut(theme to night) { ThemeColors(theme, night) } }

        /**
         * [fg] if it reaches [min] contrast on every one of [bgs]; otherwise [fg] mixed towards black (light
         * backgrounds; decided by the first) or white, in 1/40 steps, until it does.
         */
        @ColorInt
        fun ensureAll(@ColorInt fg: Int, bgs: IntArray, min: Double): Int {
            val opaque = fg or OPAQUE
            if (bgs.all { SwContrast.ratio(opaque, it) >= min }) return opaque
            val target = if (SwContrast.luminance(bgs[0]) > MID_LUMINANCE) SwContrast.DARK_TEXT else SwContrast.LIGHT_TEXT
            for (step in 1..STEPS) {
                val mixed = SwContrast.mix(opaque, target, step.toDouble() / STEPS)
                if (bgs.all { SwContrast.ratio(mixed, it) >= min }) return mixed
            }
            return target
        }

        /** [base] if it reaches 4.5:1 on [fill]; else a dark (or, on a dark fill, light) shade of the fill's hue. */
        @ColorInt
        fun textOnFill(@ColorInt base: Int, @ColorInt fill: Int): Int {
            if (SwContrast.ratio(base, fill) >= SwContrast.TEXT_MIN) return base or OPAQUE
            val start = if (SwContrast.luminance(fill) > MID_LUMINANCE) {
                SwContrast.mix(fill, BLACK, DARK_SHADE)
            } else {
                SwContrast.mix(fill, WHITE, LIGHT_TINT)
            }
            return ensureAll(start, intArrayOf(fill), SwContrast.TEXT_MIN)
        }

        private const val OPAQUE: Int = 0xFF shl 24
        private const val STEPS: Int = 40
        private const val BLACK: Int = 0xFF000000.toInt()
        private const val WHITE: Int = 0xFFFFFFFF.toInt()
        private const val DARK_SHADE: Double = 0.8
        private const val LIGHT_TINT: Double = 0.9

        // Luminance where black and white text contrast equally: (L + 0.05)² = 1.05 × 0.05.
        private const val MID_LUMINANCE: Double = 0.1791
    }
}
