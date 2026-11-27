/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.cards

import android.content.Context
import android.view.View
import android.view.ViewGroup
import app.selfwatch.R
import app.selfwatch.ui.charts.LevelColors
import app.selfwatch.ui.components.ArcGaugeView
import app.selfwatch.ui.components.Band
import app.selfwatch.ui.components.RingView
import app.selfwatch.ui.format.Fmt
import java.util.Locale
import app.selfwatch.ui.format.FmtLabels

/**
 * Shared pieces of the card design standard (docs/CARD_DESIGN.md): the band sets each kind of score uses,
 * small-card pictures and short time text. Every card draws its picture from here so they match.
 */
object CardVisuals {
    /** Garmin's stress bands: rest, low, medium, high. */
    fun stressBands(ctx: Context): List<Band> = LevelColors.of(ctx).let {
        listOf(Band(25f, it.stressRest), Band(50f, it.stressLow), Band(75f, it.stressMedium), Band(100f, it.stressHigh))
    }

    /** A 0–100 score where higher is better, with band edges [edges] (ascending; e.g. 60, 80, 90 for sleep score). */
    fun scoreBands(ctx: Context, vararg edges: Float): List<Band> {
        val c = LevelColors.of(ctx)
        val colors = when (edges.size) {
            1 -> listOf(c.scaleOff, c.scaleGood)
            2 -> listOf(c.scaleOff, c.scaleClose, c.scaleGood)
            3 -> listOf(c.scaleOff, c.scaleClose, c.scaleNeutral, c.scaleGood)
            else -> listOf(c.scaleOff, c.scaleOff, c.scaleClose, c.scaleNeutral, c.scaleGood)
        }
        val out = ArrayList<Band>()
        edges.forEachIndexed { i, e -> out += Band(e, colors[i]) }
        out += Band(100f, colors.last())
        return out
    }

    /** A small gauge for a small card ([Card.createSmallGraphic]). */
    fun smallGauge(ctx: Context, bands: List<Band>): ArcGaugeView = ArcGaugeView(ctx).apply {
        this.bands = bands
        arcWidth = ctx.resources.displayMetrics.density * 8f
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.resources.getDimensionPixelSize(R.dimen.card_gauge_small_height))
    }

    /** A small ring for a small card. */
    fun smallRing(ctx: Context): RingView = RingView(ctx).apply {
        ringWidth = ctx.resources.displayMetrics.density * 8f
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.resources.getDimensionPixelSize(R.dimen.card_ring_small_height))
    }

    /** Minutes as short time ("9h 12m", "45 min"). */
    fun minutes(ctx: Context, min: Int): String = Fmt.duration(min * 60L, Locale.getDefault(), FmtLabels.from(ctx))

    /** Hides [v] when [show] is false. */
    fun show(v: View, show: Boolean) {
        v.visibility = if (show) View.VISIBLE else View.GONE
    }
}
