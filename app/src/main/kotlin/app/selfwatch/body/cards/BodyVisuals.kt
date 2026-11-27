/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body.cards

import android.content.Context
import androidx.annotation.StringRes
import app.selfwatch.R
import app.selfwatch.body.RangeStatus
import app.selfwatch.ui.charts.LevelColors
import app.selfwatch.ui.components.Band
import app.selfwatch.ui.components.RangeBarView
import app.selfwatch.ui.components.TrendLineView

/**
 * Pictures shared by the Body cards of the card design standard: "your usual" range bars, Garmin's
 * readiness and energy bands, and the end labels of trend lines.
 */
object BodyVisuals {
    /**
     * A range bar's scale and bands around a usual range [low]..[high] that also shows [value]: below the
     * range in [below], inside in `good`, above in [above]. Returns the bands with the scale's ends.
     */
    fun usual(ctx: Context, low: Float, high: Float, value: Float, below: Int? = null, above: Int? = null): Triple<List<Band>, Float, Float> {
        val c = LevelColors.of(ctx)
        val width = (high - low).coerceAtLeast(1f)
        val min = minOf(low - width, value - width * MARGIN)
        val max = maxOf(high + width, value + width * MARGIN)
        return Triple(listOf(Band(low, below ?: c.scaleClose), Band(high, c.scaleGood), Band(max, above ?: c.scaleNeutral)), min, max)
    }

    /** Shows [value] against the usual [low]..[high] on [bar], with the range's ends as ticks [lowText], [highText]. */
    fun bindUsual(bar: RangeBarView, low: Float, high: Float, value: Float, lowText: String?, highText: String?, below: Int? = null, above: Int? = null) {
        val (bands, min, max) = usual(bar.context, low, high, value, below, above)
        bar.min = min
        bar.max = max
        bar.bands = bands
        bar.value = value
        bar.ticks = if (lowText == null || highText == null) emptyList() else listOf(low to lowText, high to highText)
    }

    /** The short tag for a reading against the usual range. */
    @StringRes
    fun usualTag(status: RangeStatus): Int = when (status) {
        RangeStatus.BELOW -> R.string.body1_below_usual
        RangeStatus.WITHIN -> R.string.body1_usual
        RangeStatus.ABOVE -> R.string.body1_above_usual
    }

    /** Garmin's training readiness bands: Poor, Low, Moderate, High, Prime. */
    fun readinessBands(ctx: Context): List<Band> = LevelColors.of(ctx).let {
        listOf(Band(25f, it.scaleOff), Band(50f, it.stressMedium), Band(75f, it.scaleClose), Band(95f, it.scaleNeutral), Band(100f, it.scaleGood))
    }

    /** Garmin's energy (reserve) bands: very low, low, medium, high. */
    fun energyBands(ctx: Context): List<Band> = LevelColors.of(ctx).let {
        listOf(Band(25f, it.scaleOff), Band(50f, it.scaleClose), Band(75f, it.scaleNeutral), Band(100f, it.scaleGood))
    }

    /** The color of an energy level 0–100 in [energyBands]. */
    fun energyColor(ctx: Context, level: Int): Int = energyBands(ctx).let { b -> (b.firstOrNull { level <= it.upTo } ?: b.last()).color }

    /** "12 days ago". */
    fun daysAgo(ctx: Context, days: Int): String = ctx.resources.getQuantityString(R.plurals.body1_days_ago, days, days)

    /** A small range bar for a small card. */
    fun smallRange(ctx: Context): RangeBarView = RangeBarView(ctx)

    /** A small trend line for a small card. */
    fun smallTrend(ctx: Context): TrendLineView = TrendLineView(ctx)

    private const val MARGIN = 0.3f
}
