/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity.cards

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorInt
import app.selfwatch.R
import app.selfwatch.body.GarminBands
import app.selfwatch.model.food.Sex
import app.selfwatch.ui.charts.LevelColors
import app.selfwatch.ui.components.BarSeries
import app.selfwatch.ui.components.BarsView
import app.selfwatch.ui.components.DotsView
import app.selfwatch.ui.components.RingRowView
import app.selfwatch.ui.components.SplitBarView
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.R as UiR

/**
 * Pieces the Activity cards share under the card design standard (docs/CARD_DESIGN.md): goal colors,
 * week bars with a goal line, small pictures for the half-width form and the VO2 max rating edges.
 */
internal object ActivityVisuals {
    /** Goal met = good (primary), short = close (secondary). */
    @ColorInt
    fun goalColor(ctx: Context, value: Int, goal: Int?): Int {
        val c = LevelColors.of(ctx)
        return if (goal != null && goal > 0 && value >= goal) c.good else c.close
    }

    /**
     * Shows [values] as one bar series on [chart] with [labels] under the bars and a dashed [goal] line;
     * with a goal each bar is colored met / short, without one [color] (or neutral) is used.
     */
    fun weekBars(chart: BarsView, name: String, values: List<Int>, labels: List<String>, goal: Int?, color: ((Int, Int) -> Int?)? = null) {
        val ctx = chart.context
        val c = LevelColors.of(ctx)
        chart.labels = labels
        chart.series = listOf(BarSeries(name, c.neutral, values.toIntArray()))
        chart.goalLine = goal?.takeIf { it > 0 }?.toFloat()
        chart.barColor = color ?: if (goal != null && goal > 0) { _, v -> goalColor(ctx, v, goal) } else null
    }

    /** The big number of a card: value and unit, with a short [tag] (at most a few words). */
    fun number(tile: StatTile, value: CharSequence, unit: CharSequence?, tag: CharSequence?) {
        tile.value = value
        tile.unit = unit
        tile.label = tag
    }

    /** A StatTile with the display number style (for cards built in code). */
    fun tile(ctx: Context): StatTile = StatTile(ctx).apply { setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number) }

    private fun dp(ctx: Context, v: Int): Int = (ctx.resources.displayMetrics.density * v).toInt()

    /** Small week bars for a half-width card. */
    fun smallBars(ctx: Context): BarsView = BarsView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, SMALL_BARS_DP))
    }

    /** Small day dots for a half-width card. */
    fun smallDots(ctx: Context): DotsView = DotsView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** A small split bar for a half-width card. */
    fun smallSplit(ctx: Context): SplitBarView = SplitBarView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** A small ring row for a half-width card. */
    fun smallRingRow(ctx: Context): RingRowView = RingRowView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** Shows [views] when [show], else hides them. */
    fun show(show: Boolean, vararg views: View) {
        for (v in views) v.visibility = if (show) View.VISIBLE else View.GONE
    }

    /**
     * The lower edges of Fair, Good, Excellent and Superior VO2 max for [age] and [sex] (ml/kg/min),
     * read from [GarminBands.vo2Max] so the bar and the rating name always agree.
     */
    fun vo2Edges(age: Int, sex: Sex): FloatArray {
        val edges = FloatArray(VO2_BANDS)
        var prev = GarminBands.vo2Max(VO2_SCAN_FROM / VO2_STEPS, age, sex).name
        var found = 0
        var i = VO2_SCAN_FROM + 1
        while (i <= VO2_SCAN_TO && found < VO2_BANDS) {
            val v = i / VO2_STEPS
            val name = GarminBands.vo2Max(v, age, sex).name
            if (name != prev) {
                edges[found++] = v.toFloat()
                prev = name
            }
            i++
        }
        return edges
    }

    /** Rating names from Poor to Superior, matching [vo2Edges]. */
    val VO2_NAMES: List<Int> = listOf(R.string.band_poor, R.string.band_fair, R.string.band_good, R.string.band_excellent, R.string.band_superior)

    private const val SMALL_BARS_DP = 72
    private const val VO2_BANDS = 4
    private const val VO2_STEPS = 100.0
    private const val VO2_SCAN_FROM = 1_000 // 10.00
    private const val VO2_SCAN_TO = 9_000 // 90.00
}
