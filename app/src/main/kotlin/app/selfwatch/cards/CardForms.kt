/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.cards

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import app.selfwatch.R
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.components.SwCard
import com.google.android.material.progressindicator.LinearProgressIndicator
import app.selfwatch.ui.R as UiR

/** Theme colors for [CardStatus] (the theme's primary, secondary and muted; never hard-coded). */
object CardStatusColors {
    /** The color of [status] in [ctx]'s theme, or null for [CardStatus.NONE]. */
    @ColorInt
    fun of(ctx: Context, status: CardStatus): Int? {
        val p = ChartPalette.of(ctx)
        return when (status) {
            CardStatus.GOOD -> p.primary
            CardStatus.CLOSE -> p.secondary
            CardStatus.OFF -> p.muted
            CardStatus.NONE -> null
        }
    }

    /** How [status] is read out. */
    fun spoken(ctx: Context, status: CardStatus): String? = when (status) {
        CardStatus.GOOD -> ctx.getString(R.string.card_status_good)
        CardStatus.CLOSE -> ctx.getString(R.string.card_status_close)
        CardStatus.OFF -> ctx.getString(R.string.card_status_off)
        CardStatus.NONE -> null
    }

    /** [done] of [goal] as a status: reached, at least [closeShare] of the way, or off. */
    fun ofProgress(done: Double, goal: Double, closeShare: Double = 0.6): CardStatus = when {
        goal <= 0 -> CardStatus.NONE
        done >= goal -> CardStatus.GOOD
        done >= goal * closeShare -> CardStatus.CLOSE
        else -> CardStatus.OFF
    }
}

/**
 * The small form of [card] (half width): the card's name with a status dot, one big number, a label, an
 * optional note and a thin progress bar. Tap opens the card's detail ([Card.openDetail]) or, for cards
 * without one, the large form on its own screen.
 */
class SmallCardView<T>(private val card: Card<T>, host: CardHost, parent: ViewGroup) : CardView<T>(build(parent.context)) {

    private val ctx: Context = parent.context
    private val dot: View = root.findViewById(R.id.small_card_dot)
    private val name: TextView = root.findViewById(R.id.small_card_name)
    private val tile: StatTile = root.findViewById(R.id.small_card_tile)
    private val note: TextView = root.findViewById(R.id.small_card_note)
    private val bar: LinearProgressIndicator = root.findViewById(R.id.small_card_bar)

    private val graphic: View? = card.createSmallGraphic(ctx)

    init {
        name.text = ctx.getString(card.title)
        root.setOnClickListener { if (!card.openDetail(host)) host.open(CardDetailFragment.newInstance(card.id)) }
        graphic?.let { g ->
            g.visibility = View.GONE
            g.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            (root as ViewGroup).addView(g, 1, Views.matchWrap(ctx, 8))
        }
        show(null)
    }

    override fun bind(data: T) {
        val face = card.small(ctx, data)
        graphic?.let { g ->
            g.visibility = if (face == null) View.GONE else View.VISIBLE
            if (face != null) card.bindSmallGraphic(g, data)
        }
        show(face)
    }

    /** Shows [face]; null shows a dash and "No data yet". */
    fun show(face: SmallFace?) {
        val f = face ?: SmallFace(ctx.getString(R.string.dash), ctx.getString(R.string.card_no_data))
        tile.value = f.value
        tile.unit = f.unit
        tile.label = f.label
        note.text = f.note
        note.visibility = if (f.note.isNullOrEmpty()) View.GONE else View.VISIBLE
        val color = CardStatusColors.of(ctx, f.status)
        if (color == null) {
            dot.visibility = View.GONE
        } else {
            dot.visibility = View.VISIBLE
            (dot.background as GradientDrawable).setColor(color)
        }
        val p = f.progress
        if (p == null) {
            bar.visibility = View.GONE
        } else {
            bar.visibility = View.VISIBLE
            bar.setIndicatorColor(color ?: ChartPalette.of(ctx).primary)
            bar.trackColor = ChartPalette.of(ctx).track
            bar.setProgressCompat((p.coerceIn(0f, 1f) * BAR_MAX).toInt(), false)
        }
        root.contentDescription = listOfNotNull(
            name.text,
            listOfNotNull(f.value, f.unit, f.label).joinToString(" "),
            f.note,
            CardStatusColors.spoken(ctx, f.status),
            ctx.getString(R.string.card_open_hint),
        ).joinToString(". ")
    }

    /** Builds the view. */
    companion object {
        private const val BAR_MAX = 1000

        /** The small card's view tree (ids `small_card_*`). */
        fun build(ctx: Context): SwCard {
            val card = SwCard(ctx).apply {
                isClickable = true
                isFocusable = true
            }
            val top = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val size = ctx.resources.getDimensionPixelSize(R.dimen.card_status_dot)
            val dot = View(ctx).apply {
                id = R.id.small_card_dot
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            top.addView(dot, LinearLayout.LayoutParams(size, size).apply { marginEnd = ctx.resources.getDimensionPixelSize(UiR.dimen.sw_space_1) })
            val name = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body).apply {
                id = R.id.small_card_name
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            top.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(top, Views.matchWrap(ctx))
            val tile = StatTile(ctx).apply {
                id = R.id.small_card_tile
                setValueAppearance(UiR.style.TextAppearance_SelfWatch_Headline_Number)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            card.addView(tile, Views.matchWrap(ctx, 4))
            val note = Views.caption(ctx).apply {
                id = R.id.small_card_note
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            card.addView(note, Views.matchWrap(ctx, 4))
            val bar = LinearProgressIndicator(ctx).apply {
                id = R.id.small_card_bar
                max = BAR_MAX
                isIndeterminate = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            card.addView(bar, Views.matchWrap(ctx, 8))
            return card
        }
    }
}

/**
 * A card whose data none of the user's devices records: its name and "Your devices don't record this",
 * in either size. It turns into the real card as soon as a device records the data.
 */
object WaitingCardView {
    /** The view for [card] in [size]. */
    fun create(card: Card<*>, size: CardSize, ctx: Context): View {
        val sw = SwCard(ctx)
        val message = ctx.getString(R.string.card_not_recorded)
        if (size == CardSize.LARGE) {
            sw.title = ctx.getString(card.title)
            sw.addView(Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, message), Views.matchWrap(ctx))
            sw.addView(Views.caption(ctx, ctx.getString(R.string.card_not_recorded_help)), Views.matchWrap(ctx, 4))
        } else {
            sw.addView(Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, ctx.getString(card.title)), Views.matchWrap(ctx))
            sw.addView(Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Headline_Number, ctx.getString(R.string.dash)), Views.matchWrap(ctx, 4))
            sw.addView(Views.caption(ctx, message), Views.matchWrap(ctx, 4))
        }
        sw.contentDescription = ctx.getString(R.string.card_not_recorded_description, ctx.getString(card.title))
        sw.tag = WAITING_TAG
        return sw
    }

    /** Tag on waiting views (tests). */
    const val WAITING_TAG: String = "card_waiting"
}
