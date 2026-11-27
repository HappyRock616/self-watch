/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.cards

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.annotation.IdRes
import app.selfwatch.R
import app.selfwatch.cards.CardView
import app.selfwatch.cards.PictureFrame
import app.selfwatch.cards.TrendPicture
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.charts.LevelColors
import app.selfwatch.ui.components.Band
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.R as UiR

/**
 * The shared view of the Food number cards (layout `card_food_metric`, card design standard): one number
 * with a short tag ([tile]), one picture under it ([picture]) and an empty state. Each card gives the root
 * its own id ([rootId]) and fills the parts in [bind].
 */
abstract class FoodMetricView<T>(parent: ViewGroup, @IdRes rootId: Int, title: Int) :
    CardView<T>(CardView.inflate(parent, R.layout.card_food_metric)) {

    /** The card. */
    protected val card: SwCard = root as SwCard

    /** The main number. */
    protected val tile: StatTile = find(R.id.metric_tile)

    /** The card's one picture. */
    protected val picture: PictureFrame = find(R.id.metric_picture)

    /** Shown instead of the number when there is nothing yet. */
    protected val empty: EmptyState = find(R.id.metric_empty)

    /** The card's context. */
    protected val ctx: Context get() = root.context

    init {
        card.id = rootId
        card.title = parent.context.getString(title)
        tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
    }

    /** The color theme's chart colors. */
    protected val palette: ChartPalette get() = ChartPalette.of(ctx)

    /** Shows [message] instead of the number (the rest hidden); [description] is read by TalkBack. */
    protected fun showEmpty(message: CharSequence, description: CharSequence = message) {
        empty.message = message
        show(empty, true)
        show(tile, false)
        picture.show(null)
        card.contentDescription = description
    }

    /** Shows the number and [p] (and hides the empty state); a picture with the number inside hides the tile. */
    protected fun showContent(p: TrendPicture?) {
        show(empty, false)
        picture.show(p)
        show(tile, p?.showsValue != true)
    }

    /** Shows or hides [v]. */
    protected fun show(v: View, visible: Boolean) {
        v.visibility = if (visible) View.VISIBLE else View.GONE
    }
}

/** Pictures shared by the Food number cards (large and small forms draw the same one). */
object FoodPictures {
    /**
     * A value against a target band [low]..[high] (no top: the band runs to the end): under the band is
     * "close", in it "good", over its top "close". The bar ends a little past the band or the value.
     */
    fun target(ctx: Context, value: Double, low: Double, high: Double?, ticks: List<Pair<Float, String>>): TrendPicture.Range {
        val c = LevelColors.of(ctx)
        val top = high ?: low
        val max = maxOf(value, top * HEADROOM).toFloat()
        val bands = ArrayList<Band>()
        bands += Band(low.toFloat(), c.scaleClose)
        if (high != null) {
            bands += Band(high.toFloat(), c.scaleGood)
            bands += Band(max, c.scaleClose)
        } else {
            bands += Band(max, c.scaleGood)
        }
        return TrendPicture.Range(bands, 0f, max, value.toFloat(), ticks)
    }

    /** A value against a [limit]: under 90 % "good", up to the limit "close", over it "off". */
    fun limit(ctx: Context, value: Double, limit: Double, tick: String): TrendPicture.Range {
        val c = LevelColors.of(ctx)
        val max = maxOf(value, limit * HEADROOM).toFloat()
        return TrendPicture.Range(
            listOf(Band((limit * NEAR_LIMIT).toFloat(), c.scaleGood), Band(limit.toFloat(), c.scaleClose), Band(max, c.scaleOff)),
            0f, max, value.toFloat(), listOf(limit.toFloat() to tick),
        )
    }

    private const val HEADROOM = 1.25
    private const val NEAR_LIMIT = 0.9
}
