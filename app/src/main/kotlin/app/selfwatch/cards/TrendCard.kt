/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.cards

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import app.selfwatch.R
import app.selfwatch.model.time.Day
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.components.ArcGaugeView
import app.selfwatch.ui.components.Band
import app.selfwatch.ui.components.BarSeries
import app.selfwatch.ui.components.BarsView
import app.selfwatch.ui.components.DotsView
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.RangeBarView
import app.selfwatch.ui.components.RingItem
import app.selfwatch.ui.components.RingRowView
import app.selfwatch.ui.components.RingView
import app.selfwatch.ui.components.SplitBarView
import app.selfwatch.ui.components.SplitPart
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.components.TrendLineView
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import app.selfwatch.ui.R as UiR

/**
 * One number over time: the [latest] value (null: none yet), the [series] behind the picture (oldest
 * first), the [change] over the card's comparison window (null: too little history) and an optional
 * [extras] numbers some cards use (a second value, a count). Values are in the card's own unit.
 * [startDay] and [endDay] (when known) are the days of the first and last points, for the trend line's
 * end labels.
 */
data class TrendData(
    val latest: Double?,
    val series: List<Double> = emptyList(),
    val change: Double? = null,
    val units: Units = Units.METRIC,
    val extras: List<Double?> = emptyList(),
    val startDay: Day? = null,
    val endDay: Day? = null,
)

/**
 * The one picture of a card (card design standard, docs/CARD_DESIGN.md): what [PictureFrame] draws.
 * Gauge, ring, rings and tiles show the number themselves ([showsValue]).
 */
sealed class TrendPicture {
    /** True when the picture shows the card's number (the separate number is then hidden). */
    open val showsValue: Boolean get() = false

    /** A trend line with both ends labeled, an optional shaded [band] and a dashed [goal]. */
    class Line(
        val values: FloatArray,
        val startLabel: String?,
        val endLabel: String?,
        @param:ColorInt val color: Int? = null,
        val band: Pair<Float, Float>? = null,
        val goal: Float? = null,
    ) : TrendPicture()

    /** Where a reading sits among named ranges, with labeled edges ([ticks]; dropped on small cards). */
    class Range(
        val bands: List<Band>,
        val min: Float,
        val max: Float,
        val value: Float?,
        val ticks: List<Pair<Float, String>> = emptyList(),
    ) : TrendPicture()

    /** An arc gauge with the number ([center]) and level ([sub]) inside. */
    class Gauge(
        val bands: List<Band>,
        val value: Float,
        val center: String,
        val sub: String?,
        val min: Float = 0f,
        val max: Float = 100f,
    ) : TrendPicture() {
        override val showsValue: Boolean get() = true
    }

    /** Bars with a label under each ([labels]: day letters, meal letters) and an optional goal line. */
    class Bars(
        val labels: List<String>,
        val values: IntArray,
        val goal: Float? = null,
        @param:ColorInt val color: Int? = null,
        val colorFor: ((group: Int, value: Int) -> Int?)? = null,
        val description: String? = null,
        val series: List<BarSeries>? = null,
    ) : TrendPicture()

    /** A goal ring with the number inside. */
    class Ring(
        val progress: Float,
        @param:ColorInt val color: Int,
        val center: String,
        val sub: String?,
    ) : TrendPicture() {
        override val showsValue: Boolean get() = true
    }

    /** A row of small goal rings, each with its value inside and a label under it. */
    class Rings(val items: List<RingItem>) : TrendPicture() {
        override val showsValue: Boolean get() = true
    }

    /** Day dots, filled when done, with labels under them. */
    class Dots(val filled: BooleanArray, val labels: List<String>, @param:ColorInt val color: Int? = null) : TrendPicture()

    /** A split bar (time or amount by part), with an optional reference [mark] (0–1). */
    class Split(val parts: List<SplitPart>, val mark: Float? = null) : TrendPicture()

    /** Numbers with icons side by side (no sentence). */
    class Tiles(val items: List<Tile>) : TrendPicture() {
        override val showsValue: Boolean get() = true
    }

    /** One icon tile: an icon, a value and a one-word label. */
    data class Tile(@param:DrawableRes val icon: Int, val value: String, val label: String)
}

/**
 * Draws a [TrendPicture] (builds the right view for its kind and reuses it). [small] draws the small-card
 * version: smaller gauge and ring, no range ticks. Hidden for null.
 */
class PictureFrame @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
    private val small: Boolean = false,
) : FrameLayout(ctx, attrs) {
    /** In code: [small] for the small-card version. */
    constructor(ctx: Context, small: Boolean) : this(ctx, null, small)

    private var kind: Class<*>? = null
    private var child: View? = null

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    /** The view now drawn (for tests), or null. */
    val content: View? get() = child

    /** Shows [p]; null hides the frame. */
    fun show(p: TrendPicture?) {
        visibility = if (p == null) View.GONE else View.VISIBLE
        if (p == null) return
        if (kind != p.javaClass) {
            removeAllViews()
            val v = create(p)
            child = v
            kind = p.javaClass
            addView(v)
        }
        bind(child ?: return, p)
    }

    private fun dim(id: Int): Int = context.resources.getDimensionPixelSize(id)

    private fun create(p: TrendPicture): View {
        val ctx = context
        val d = ctx.resources.displayMetrics.density
        val wrap = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        return when (p) {
            is TrendPicture.Line -> TrendLineView(ctx).apply { layoutParams = wrap }
            is TrendPicture.Range -> RangeBarView(ctx).apply { layoutParams = wrap }
            is TrendPicture.Gauge -> ArcGaugeView(ctx).apply {
                if (small) arcWidth = d * SMALL_STROKE_DP
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dim(if (small) R.dimen.card_gauge_small_height else R.dimen.card_gauge_height))
            }
            is TrendPicture.Bars -> BarsView(ctx).apply {
                layoutParams = if (small) LayoutParams(LayoutParams.MATCH_PARENT, (d * SMALL_BARS_DP).toInt()) else wrap
            }
            is TrendPicture.Ring -> RingView(ctx).apply {
                if (small) ringWidth = d * SMALL_STROKE_DP
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dim(if (small) R.dimen.card_ring_small_height else R.dimen.card_ring_height))
            }
            is TrendPicture.Rings -> RingRowView(ctx).apply { layoutParams = wrap }
            is TrendPicture.Dots -> DotsView(ctx).apply { layoutParams = wrap }
            is TrendPicture.Split -> SplitBarView(ctx).apply { layoutParams = wrap }
            is TrendPicture.Tiles -> LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = wrap
            }
        }
    }

    private fun bind(v: View, p: TrendPicture) {
        when (p) {
            is TrendPicture.Line -> (v as TrendLineView).apply {
                values = p.values
                startLabel = p.startLabel
                endLabel = p.endLabel
                color = p.color ?: ChartPalette.of(context).primary
                band = p.band
                goal = p.goal
            }
            is TrendPicture.Range -> (v as RangeBarView).apply {
                bands = p.bands
                min = p.min
                max = p.max
                value = p.value
                ticks = if (small) emptyList() else p.ticks
            }
            is TrendPicture.Gauge -> (v as ArcGaugeView).apply {
                bands = p.bands
                min = p.min
                max = p.max
                value = p.value
                centerText = p.center
                subText = p.sub
            }
            is TrendPicture.Bars -> (v as BarsView).apply {
                labels = p.labels
                goalLine = p.goal
                barColor = p.colorFor
                series = p.series ?: listOf(BarSeries("", p.color ?: ChartPalette.of(context).primary, p.values))
                contentDescription = p.description
            }
            is TrendPicture.Ring -> (v as RingView).apply {
                progress = p.progress.coerceAtLeast(0f)
                color = p.color
                centerText = p.center
                subText = p.sub
            }
            is TrendPicture.Rings -> (v as RingRowView).items = p.items
            is TrendPicture.Dots -> (v as DotsView).apply {
                filled = p.filled
                labels = p.labels
                color = p.color ?: ChartPalette.of(context).primary
            }
            is TrendPicture.Split -> (v as SplitBarView).apply {
                parts = p.parts
                mark = p.mark
            }
            is TrendPicture.Tiles -> bindTiles(v as LinearLayout, p.items)
        }
    }

    private fun bindTiles(row: LinearLayout, items: List<TrendPicture.Tile>) {
        val ctx = row.context
        row.removeAllViews()
        val iconSize = Views.dp(ctx, if (small) SMALL_ICON_DP else ICON_DP)
        val tint = ChartPalette.of(ctx).primary
        for (t in items) {
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            col.addView(
                ImageView(ctx).apply {
                    setImageResource(t.icon)
                    setColorFilter(tint)
                },
                LinearLayout.LayoutParams(iconSize, iconSize),
            )
            col.addView(
                Views.text(ctx, if (small) UiR.style.TextAppearance_SelfWatch_Body_Number else UiR.style.TextAppearance_SelfWatch_Display_Number, t.value)
                    .apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            col.addView(
                Views.caption(ctx, t.label).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private companion object {
        const val SMALL_STROKE_DP = 8f
        const val SMALL_BARS_DP = 64f
        const val ICON_DP = 24
        const val SMALL_ICON_DP = 18
    }
}

/** Shared bits for building pictures. */
object TrendPictures {
    /** Narrow weekday letters for the days ending on [end] (oldest first), "M T W …". */
    fun dayLetters(end: Day, days: Int, locale: Locale = Locale.getDefault()): List<String> =
        (days - 1 downTo 0).map { Fmt.dayToDate(end.plusDays(-it).yyyymmdd).dayOfWeek.getDisplayName(TextStyle.NARROW, locale) }

    /** "Sep 3"-style label for [day], or null. */
    fun dayLabel(ctx: Context, day: Day?): String? = day?.let { Fmt.day(ctx, it.yyyymmdd) }

    /** Bar colors for a goal: met = good, short = close (null keeps the series color). */
    fun goalColors(ctx: Context, goal: Double?): ((Int, Int) -> Int?)? {
        val g = goal ?: return null
        val good = app.selfwatch.ui.charts.LevelColors.of(ctx).good
        val close = app.selfwatch.ui.charts.LevelColors.of(ctx).close
        return { _, v -> if (v >= g) good else close }
    }
}

/**
 * Base for the simple catalog cards (card design standard): one number with its unit and a short tag,
 * and one picture under it (a trend line with labeled ends by default; a card can return a range bar,
 * gauge, bars… from [picture]). The small form is the number and the same picture. A subclass loads
 * [TrendData] and says how to format it.
 */
abstract class TrendCard(id: String, tab: CardTab, title: Int, summary: Int) : Card<TrendData>(id, tab, title, summary) {

    /** [value] as text (number only, in [units]). */
    abstract fun format(ctx: Context, value: Double, units: Units): String

    /** The unit after the number, or null. */
    abstract fun unit(ctx: Context, units: Units): String?

    /** The short tag under the number ("latest", "last night"…), at most four words. */
    abstract fun label(ctx: Context, data: TrendData): String

    /** The card's number as shown (the latest value by default; for example "128/79" for blood pressure). */
    open fun valueText(ctx: Context, data: TrendData): String = data.latest?.let { format(ctx, it, data.units) } ?: ctx.getString(R.string.dash)

    /**
     * The change over time or a range, read by TalkBack (and shown in the small form when the card has no
     * picture). Not drawn on the large card: the standard keeps sentences off cards.
     */
    open fun note(ctx: Context, data: TrendData): String? = data.change?.let { c ->
        val text = format(ctx, kotlin.math.abs(c), data.units)
        when {
            (c * CHANGE_SCALE).roundToInt() == 0 -> ctx.getString(R.string.trend_note_steady)
            c > 0 -> ctx.getString(R.string.trend_note_up, text)
            else -> ctx.getString(R.string.trend_note_down, text)
        }
    }

    /** How the number stands (no judgment by default). */
    open fun status(data: TrendData): CardStatus = CardStatus.NONE

    /** The message when there is no value yet. */
    open fun emptyMessage(ctx: Context): String = ctx.getString(R.string.trend_empty)

    /** Opens the detail screen for this number; false when there is none. */
    open fun open(host: CardHost): Boolean = false

    /**
     * The card's picture for [data] ([small]: the small form), or null for none. By default a trend line
     * of [TrendData.series] with both ends labeled ([lineLabels]), colored by [status].
     */
    open fun picture(ctx: Context, data: TrendData, small: Boolean): TrendPicture? {
        if (data.latest == null || data.series.size < 2) return null
        val (start, end) = lineLabels(ctx, data)
        return TrendPicture.Line(
            data.series.map { it.toFloat() }.toFloatArray(),
            start,
            end,
            CardStatusColors.of(ctx, status(data)) ?: ChartPalette.of(ctx).primary,
            band = lineBand(data),
            goal = lineGoal(data),
        )
    }

    /** The trend line's end labels: the first and last days when known, else "First" and "Latest". */
    open fun lineLabels(ctx: Context, data: TrendData): Pair<String?, String?> =
        (TrendPictures.dayLabel(ctx, data.startDay) ?: ctx.getString(R.string.d2_trend_first)) to
            (TrendPictures.dayLabel(ctx, data.endDay) ?: ctx.getString(R.string.d2_trend_latest))

    /** A shaded range on the trend line (your usual), or null. */
    open fun lineBand(data: TrendData): Pair<Float, Float>? = null

    /** A dashed goal on the trend line, or null. */
    open fun lineGoal(data: TrendData): Float? = null

    /**
     * True when the card's picture always shows the number (gauge, ring): the small form then hides the
     * separate number.
     */
    open val pictureShowsValue: Boolean = false

    override fun openDetail(host: CardHost): Boolean = open(host)

    override fun small(ctx: Context, data: TrendData): SmallFace? {
        if (data.latest == null) return null
        return SmallFace(valueText(ctx, data), label(ctx, data), unit(ctx, data.units), status(data), note(ctx, data))
    }

    override fun createSmallGraphic(ctx: Context): View? = PictureFrame(ctx, small = true)

    override val smallGraphicShowsValue: Boolean get() = pictureShowsValue

    override fun bindSmallGraphic(graphic: View, data: TrendData) {
        (graphic as? PictureFrame)?.show(picture(graphic.context, data, small = true))
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<TrendData> = Holder(this, host, parent)

    private class Holder(private val card: TrendCard, host: CardHost, parent: ViewGroup) : CardView<TrendData>(SwCard(parent.context)) {
        private val ctx = parent.context
        private val tile = StatTile(ctx).apply { setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number) }
        private val picture = PictureFrame(ctx, small = false)
        private val empty = EmptyState(ctx)

        init {
            val sw = root as SwCard
            sw.title = ctx.getString(card.title)
            sw.addView(tile, Views.matchWrap(ctx))
            sw.addView(picture, Views.matchWrap(ctx, 8))
            sw.addView(empty, Views.matchWrap(ctx))
            sw.isClickable = true
            sw.isFocusable = true
            sw.setOnClickListener { card.open(host) }
        }

        override fun bind(data: TrendData) {
            val v = data.latest
            empty.visibility = if (v == null) View.VISIBLE else View.GONE
            if (v == null) {
                tile.visibility = View.GONE
                picture.show(null)
                empty.message = card.emptyMessage(ctx)
                root.contentDescription = listOf(ctx.getString(card.title), empty.message).joinToString(". ")
                return
            }
            tile.value = card.valueText(ctx, data)
            tile.unit = card.unit(ctx, data.units)
            tile.label = card.label(ctx, data)
            val p = card.picture(ctx, data, small = false)
            picture.show(p)
            tile.visibility = if (p?.showsValue == true) View.GONE else View.VISIBLE
            val n = card.note(ctx, data)
            root.contentDescription = listOfNotNull(ctx.getString(card.title), "${tile.value} ${tile.unit ?: ""}".trim(), tile.label, n).joinToString(". ")
        }
    }

    private companion object {
        const val CHANGE_SCALE = 10.0
    }
}
