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
import android.widget.TextView
import app.selfwatch.R
import app.selfwatch.activity.TimelineInfo
import app.selfwatch.activity.TimelineLayers
import app.selfwatch.activity.TodayLoader
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
import app.selfwatch.model.time.TimeRange
import app.selfwatch.ui.charts.Axis
import app.selfwatch.ui.charts.BarLayer
import app.selfwatch.ui.charts.ChartLayer
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.charts.LineLayer
import app.selfwatch.ui.charts.OverlayChartView
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.ToggleChipGroup
import app.selfwatch.ui.format.Fmt
import java.time.ZoneId

/**
 * Today hour by hour: steps per hour as bars with the heart-rate line on top, on one chart with chips
 * (both on by default, [ChartId.TODAY_TIMELINE]). Tap the chart to read an hour.
 */
object TodayTimelineCard : Card<TodayTimelineCard.Data>("today_timeline", CardTab.ACTIVITY, R.string.card_today_timeline, R.string.card_today_timeline_summary) {

    /** Today hour by hour and the layers the user switched off. */
    data class Data(val timeline: TimelineInfo, val hidden: Set<String>)

    /** The chips' stored choice. */
    val CHOICE: ChartChoice by lazy { ChartChoice(ChartId.TODAY_TIMELINE) }

    override val tables: Set<Table> = setOf(Table.ACTIVITY, Table.METRIC, Table.SETTING)

    override suspend fun load(env: CardEnv): Data = Data(TodayLoader(env.db, env.clock).timeline(env.day), CHOICE.load(env.db))

    override val needs: Set<DataKind> = setOf(DataKind.STEPS, DataKind.HEART_RATE)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override fun small(ctx: Context, data: Data): SmallFace? {
        val t = data.timeline
        if (t.isEmpty) return null
        return SmallFace(
            value = Fmt.number(t.totalSteps.toLong()),
            label = ctx.getString(R.string.small_steps_so_far),
            note = t.busiestHour?.let { ctx.getString(R.string.small_busiest_hour, Fmt.time(ctx, t.dayStartSec + it * HOUR_SEC)) },
        )
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    private const val HOUR_SEC = 3_600L
    private const val DAY_SEC = 86_400L

    private class Holder(private val host: CardHost, parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_activity_today_timeline)) {
        private val caption = find<TextView>(R.id.tt_caption)
        private val chart = find<OverlayChartView>(R.id.tt_chart)
        private val chips = find<ToggleChipGroup>(R.id.tt_chips)
        private val readout = find<TextView>(R.id.tt_readout)
        private val empty = find<EmptyState>(R.id.tt_empty)
        private var applied = false

        init {
            readout.setText(R.string.chart_scrub_hint)
            chart.onScrub = { t, values ->
                val parts = values.filter { it.value != null }.joinToString(" · ") { "${it.label} ${it.text}" }
                readout.text = root.context.getString(R.string.card_line_pair, Fmt.time(root.context, t), parts.ifEmpty { root.context.getString(R.string.dash) })
            }
        }

        override fun bind(data: Data) {
            val ctx = root.context
            val t = data.timeline
            for (v in listOf(caption, chart, chips, readout)) v.visibility = if (t.isEmpty) View.GONE else View.VISIBLE
            empty.visibility = if (t.isEmpty) View.VISIBLE else View.GONE
            if (t.isEmpty) {
                root.contentDescription = ctx.getString(R.string.tt_empty)
                return
            }
            val palette = ChartPalette.of(ctx)
            val busiest = t.busiestHour?.let { Fmt.time(ctx, t.dayStartSec + it * HOUR_SEC) } ?: ctx.getString(R.string.dash)
            caption.text = ctx.getString(R.string.tt_caption, Fmt.number(t.totalSteps.toLong()), busiest)
            val layers = ArrayList<ChartLayer>()
            layers += BarLayer(
                TimelineLayers.STEPS, ctx.getString(R.string.tt_layer_steps), palette.series(1), Axis.RIGHT,
                t.dayStartSec, HOUR_SEC, t.stepsPerHour,
            )
            if (t.hrT.isNotEmpty()) {
                layers += LineLayer(
                    TimelineLayers.HR, ctx.getString(R.string.tt_layer_hr), palette.series(0), Axis.LEFT,
                    t.hrT, t.hrV, unit = ctx.getString(R.string.unit_bpm),
                )
            }
            if (!applied) {
                for (id in TimelineLayers.ALL) chart.setVisible(id, id !in data.hidden)
                applied = true
            }
            val zone = ZoneId.systemDefault()
            chart.zone = zone
            val range = TimeRange(t.dayStartSec, t.dayStartSec + DAY_SEC)
            if (chart.range != range) chart.range = range
            chart.layers = layers
            chart.bindChips(chips)
            chips.onChange = { id, on, _ ->
                chart.setVisible(id, on)
                val hidden = TimelineLayers.ALL.filterNot { chart.isLayerVisible(it) }.toSet()
                val settings = host.context.graph.settings
                host.launch { CHOICE.save(settings, hidden) }
            }
            root.contentDescription = ctx.getString(R.string.tt_description, caption.text)
        }
    }
}
