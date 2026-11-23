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
import app.selfwatch.model.health.SleepStage
import app.selfwatch.ui.SwTheme
import app.selfwatch.ui.ThemeColors
import app.selfwatch.ui.components.SwContrast

/**
 * The one place charts, rings, bars, sparklines, chips and card accents get their colors from. Every
 * color comes from the current [SwTheme.Theme] and look (dark or light); nothing is fixed per metric.
 *
 * Rules:
 * - Series 1 is the theme's primary, series 2 its secondary.
 * - Series 3 and 4 are tints of primary and secondary (dark look: 45% towards white; light look: 35%
 *   towards black). Series 5 and 6 are shades (dark look: 35% towards black, pulled back to 3:1; light
 *   look: 60% towards black). From series 7 the list repeats.
 * - Every series reaches 3:1 on the card surface and the background.
 * - Sleep stages: deep = the darker secondary, light = the lighter secondary, REM = primary, awake =
 *   neutral gray, unmeasurable = track gray. (Dark look: deep = secondary, light = secondary tint.
 *   Light look: deep = secondary 45% towards black, light = secondary.)
 * - Neutrals (text, muted, track) are Just Go 5K's grays. Red ([alarm]) is only for alarms and errors.
 */
class ChartPalette private constructor(
    /** The theme's colors in this look. */
    val colors: ThemeColors,
) {
    /** The theme. */
    val theme: SwTheme.Theme get() = colors.theme

    /** True for the dark look. */
    val night: Boolean get() = colors.night

    /** Series 1: the theme's primary. */
    @ColorInt
    val primary: Int = colors.primary

    /** Series 2: the theme's secondary. */
    @ColorInt
    val secondary: Int = colors.secondary

    private val seriesList: IntArray = if (colors.night) {
        intArrayOf(
            primary,
            secondary,
            colors.fill(SwContrast.mix(primary, WHITE, DARK_TINT)),
            colors.fill(SwContrast.mix(secondary, WHITE, DARK_TINT)),
            colors.fill(SwContrast.mix(primary, BLACK, DARK_SHADE)),
            colors.fill(SwContrast.mix(secondary, BLACK, DARK_SHADE)),
        )
    } else {
        intArrayOf(
            primary,
            secondary,
            SwContrast.mix(primary, BLACK, LIGHT_TINT),
            SwContrast.mix(secondary, BLACK, LIGHT_TINT),
            SwContrast.mix(primary, BLACK, LIGHT_SHADE),
            SwContrast.mix(secondary, BLACK, LIGHT_SHADE),
        )
    }

    /** Number of distinct series colors before the list repeats. */
    val seriesCount: Int get() = seriesList.size

    /** Color of series [index] (0 = primary, 1 = secondary, then tints and shades; repeats after [seriesCount]). */
    @ColorInt
    fun series(index: Int): Int = seriesList[Math.floorMod(index, seriesList.size)]

    /** Series 1 as small text (labels, legends): at least 4.5:1 on every surface. */
    @ColorInt
    val primaryText: Int = colors.accentText

    /** Series 2 as small text. */
    @ColorInt
    val secondaryText: Int = colors.secondaryText

    /** Main text on charts. */
    @ColorInt
    val text: Int = colors.neutrals.text

    /** Axis labels, captions and neutral marks (weight dots, normal bands, goal lines). */
    @ColorInt
    val muted: Int = colors.neutrals.muted

    /** Ring and bar tracks. */
    @ColorInt
    val track: Int = colors.neutrals.track

    /** Alarm and error red (the same in every theme). Never a series color. */
    @ColorInt
    val alarm: Int = ThemeColors.ALARM

    /** A muted, theme-tinted pill fill for tags ([primary] = true: primary tint, else secondary tint). */
    @ColorInt
    fun tagFill(primary: Boolean): Int =
        SwContrast.mix(if (primary) colors.theme.primary else colors.theme.secondary, colors.neutrals.surface, TAG_FILL)

    /** Text on [tagFill], at least 4.5:1. */
    @ColorInt
    fun tagText(primary: Boolean): Int =
        ThemeColors.ensureAll(if (primary) colors.theme.primary else colors.theme.secondary, intArrayOf(tagFill(primary)), SwContrast.TEXT_MIN)

    /** Neutral pill fill (database tags). */
    @ColorInt
    val neutralTagFill: Int = SwContrast.mix(colors.neutrals.muted, colors.neutrals.surface, TAG_FILL)

    /** Text on [neutralTagFill]. */
    @ColorInt
    val neutralTagText: Int = ThemeColors.ensureAll(colors.neutrals.text, intArrayOf(neutralTagFill), SwContrast.TEXT_MIN)

    /** Color of a sleep stage. */
    @ColorInt
    fun stage(stage: SleepStage): Int = when (stage) {
        SleepStage.DEEP -> if (night) secondary else SwContrast.mix(secondary, BLACK, LIGHT_DEEP)
        SleepStage.LIGHT -> if (night) colors.fill(SwContrast.mix(secondary, WHITE, DARK_TINT)) else secondary
        SleepStage.REM -> primary
        SleepStage.AWAKE -> if (night) muted else AWAKE_LIGHT
        SleepStage.UNMEASURABLE -> track
    }

    /** Lookup. */
    companion object {
        /** The palette of [theme] in the dark ([night]) or light look. */
        fun of(theme: SwTheme.Theme, night: Boolean): ChartPalette = ChartPalette(ThemeColors.of(theme, night))

        /**
         * Text on a filled series [color] (filled chips): black or white, whichever reads better. Pure black
         * (not the near-black text color) so mid-tone series still reach 4.5:1.
         */
        @ColorInt
        fun textOn(@ColorInt color: Int): Int = SwContrast.textOn(color, dark = BLACK)

        /** The palette [context] renders with: its theme overlay and its night mode. */
        fun of(context: Context): ChartPalette = of(SwTheme.themeOf(context), SwTheme.isNight(context))

        private const val BLACK: Int = 0xFF000000.toInt()
        private const val WHITE: Int = 0xFFFFFFFF.toInt()
        private const val DARK_TINT: Double = 0.45
        private const val DARK_SHADE: Double = 0.35
        private const val LIGHT_TINT: Double = 0.35
        private const val LIGHT_SHADE: Double = 0.6
        private const val LIGHT_DEEP: Double = 0.45
        private const val TAG_FILL: Double = 0.75

        // Light look's awake gray: Just Go 5K's neutral, a shade darker to reach 3:1 on white.
        private const val AWAKE_LIGHT: Int = 0xFF8A8983.toInt()
    }
}
