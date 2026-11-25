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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.selfwatch.R
import app.selfwatch.activity.ActivityLoader
import app.selfwatch.activity.Sports
import app.selfwatch.activity.Trend
import app.selfwatch.activity.TrendMetric
import app.selfwatch.activity.TrendRange
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.ChartChoice
import app.selfwatch.cards.ChartId
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.graph
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.charts.TrendChartView
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.ToggleChipGroup
import app.selfwatch.ui.components.ToggleItem
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import app.selfwatch.ui.R as UiR

/**
 * The long-trend bars (Week · Month · Year · All) with chips for distance, active minutes, steps and
 * active kcal. The chosen range is a setting ([TrendRange.KEY]), so the Activity menu can change it too;
 * the chips start from the main goal's defaults and the user's choice is kept in settings ([CHOICE]).
 */
object TrendsCard : Card<TrendsCard.Data>("trends", CardTab.ACTIVITY, R.string.card_trends, R.string.card_trends_summary) {

    /** The trend bars in display [units], and the metrics the user switched off. */
    data class Data(val trend: Trend, val units: Units, val hidden: Set<String>)

    /** The chips' stored choice. */
    val CHOICE: ChartChoice by lazy { ChartChoice(ChartId.ACTIVITY_TRENDS) }

    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY, Table.GOAL, Table.SETTING)

    override suspend fun load(env: CardEnv): Data {
        val loader = ActivityLoader(env.db, env.clock)
        return Data(loader.trend(loader.trendRange()), env.units, CHOICE.load(env.db))
    }

    override val needs: Set<DataKind> = setOf(DataKind.STEPS, DataKind.DISTANCE, DataKind.ACTIVE_TIME, DataKind.ACTIVE_CALORIES)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override fun small(ctx: Context, data: Data): SmallFace? {
        val t = data.trend
        if (t.isEmpty) return null
        // Every bar is a day or a daily average, so their mean is steps a day over the range.
        val steps = t.values[TrendMetric.STEPS].orEmpty().filter { it >= 0 }
        if (steps.isEmpty()) return null
        val range = when (t.range) {
            TrendRange.WEEK -> R.string.small_range_week
            TrendRange.MONTH -> R.string.small_range_month
            TrendRange.YEAR -> R.string.small_range_year
            TrendRange.ALL -> R.string.small_range_all
        }
        return SmallFace(
            value = Fmt.number(steps.average().roundToInt().toLong()),
            label = ctx.getString(R.string.small_steps_a_day),
            note = ctx.getString(range),
        )
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    /** Saves [range] as the Trends card's range. */
    fun setRange(host: CardHost, range: TrendRange) {
        val settings = host.context.graph.settings
        host.launch { settings.set(TrendRange.KEY, range.id) }
    }

    /** Chip label of [m]. */
    fun label(m: TrendMetric): Int = when (m) {
        TrendMetric.DISTANCE -> R.string.activity_distance
        TrendMetric.ACTIVE_MIN -> R.string.activity_active_min
        TrendMetric.STEPS -> R.string.activity_steps
        TrendMetric.ACTIVE_KCAL -> R.string.activity_active_kcal
    }

    /** Each metric is one series of the color theme's chart palette, in chip order. */
    private fun color(ctx: Context, m: TrendMetric): Int = ChartPalette.of(ctx).series(m.ordinal)

    /** Saves the metrics switched off. */
    fun saveHidden(host: CardHost, hidden: Set<String>) {
        val settings = host.context.graph.settings
        host.launch { CHOICE.save(settings, hidden) }
    }

    private class Holder(private val host: CardHost, parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_activity_trends)) {
        private val ctx: Context = root.context
        private val charts = find<LinearLayout>(R.id.trend_charts)
        private val chips = find<ToggleChipGroup>(R.id.trend_chips)
        private val segments = Views.segments(
            ctx,
            listOf(ctx.getString(R.string.range_week), ctx.getString(R.string.range_month), ctx.getString(R.string.range_year), ctx.getString(R.string.range_all)),
        ) { i ->
            // Checking the stored range on bind also lands here: only a different range is saved.
            val range = TrendRange.entries[i]
            if (range != last?.trend?.range) setRange(host, range)
        }
        private var last: Data? = null

        init {
            find<FrameLayout>(R.id.trend_segments).addView(segments)
            chips.onChange = { _, _, on ->
                last?.let { bindTrend(it, on) }
                saveHidden(host, TrendMetric.entries.map { it.id }.filter { it !in on }.toSet())
            }
        }

        override fun bind(data: Data) {
            last = data
            bindChips(data.hidden)
            Views.selectSegment(segments, data.trend.range.ordinal)
            bindTrend(data, chips.onIds)
            find<EmptyState>(R.id.trend_empty).visibility = if (data.trend.isEmpty) View.VISIBLE else View.GONE
            charts.visibility = if (data.trend.isEmpty) View.GONE else View.VISIBLE
        }

        /** Builds the chips from the stored choice when it differs from what they show. */
        private fun bindChips(hidden: Set<String>) {
            val on = TrendMetric.entries.map { it.id }.filter { it !in hidden }.toSet()
            if (chips.items.isNotEmpty() && chips.onIds == on) return
            chips.items = TrendMetric.entries.map { m -> ToggleItem(m.id, ctx.getString(label(m)), color(ctx, m), m.id in on) }
        }

        private fun bindTrend(data: Data, on: Set<String>) {
            val t = data.trend
            val loc = Locale.getDefault()
            val labels = t.starts.map { d ->
                val date = d.toLocalDate()
                when {
                    !t.daily -> date.month.getDisplayName(TextStyle.SHORT, loc)
                    t.range == TrendRange.WEEK -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, loc)
                    else -> date.dayOfMonth.toString()
                }
            }
            val longLabels = t.starts.map { d ->
                if (t.daily) {
                    Fmt.day(ctx, d.yyyymmdd)
                } else {
                    ctx.getString(R.string.activity_month_average, d.toLocalDate().month.getDisplayName(TextStyle.FULL, loc) + " " + d.year)
                }
            }
            // Reuse chart views by metric so their selection survives refreshes.
            val wanted = TrendMetric.entries.filter { it.id in on }
            val existing = (0 until charts.childCount).map { charts.getChildAt(it) }.associateBy { it.tag }
            charts.removeAllViews()
            for (m in wanted) {
                val holder = (existing[m.id] as? LinearLayout) ?: newHolder(m)
                val caption = holder.getChildAt(0) as TextView
                val chart = holder.getChildAt(1) as TrendChartView
                val (values, scale, unit) = display(m, t.values[m].orEmpty(), data.units)
                chart.values = values.toIntArray()
                chart.scale = scale
                chart.unit = unit
                chart.labels = labels
                chart.longLabels = longLabels
                chart.goal = t.goals[m]
                val present = values.filter { it >= 0 }
                val avg = if (present.isEmpty()) null else present.average()
                caption.text = ctx.getString(
                    if (t.daily) R.string.activity_trend_caption_daily else R.string.activity_trend_caption_monthly,
                    ctx.getString(label(m)),
                    avg?.let { Fmt.number(it / scale, if (scale > 1) 1 else 0) + if (unit.isEmpty()) "" else " $unit" } ?: ctx.getString(R.string.dash),
                )
                charts.addView(holder, Views.matchWrap(ctx, 8))
            }
        }

        private fun newHolder(m: TrendMetric): LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            tag = m.id
            addView(Views.caption(ctx))
            addView(
                TrendChartView(ctx).apply { barColor = color(ctx, m) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.resources.getDimensionPixelSize(R.dimen.trend_chart_height)),
            )
        }

        /** Raw values to display values, scale and unit (distance in tenths of km or mi). */
        private fun display(m: TrendMetric, raw: List<Int>, units: Units): Triple<List<Int>, Int, String> = when (m) {
            TrendMetric.DISTANCE -> {
                val per = if (units == Units.METRIC) 1000.0 else Sports.METERS_PER_MILE
                val unit = ctx.getString(if (units == Units.METRIC) UiR.string.sw_unit_km else UiR.string.sw_unit_mi)
                Triple(raw.map { if (it < 0) -1 else (it * 10 / per).roundToInt() }, 10, unit)
            }
            TrendMetric.ACTIVE_MIN -> Triple(raw, 1, ctx.getString(R.string.unit_min))
            TrendMetric.STEPS -> Triple(raw, 1, "")
            TrendMetric.ACTIVE_KCAL -> Triple(raw, 1, ctx.getString(UiR.string.sw_unit_kcal))
        }
    }
}
