/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body.cards

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.selfwatch.R
import app.selfwatch.body.ChartScreens
import app.selfwatch.body.HeartCardLoader
import app.selfwatch.body.HeartDetailFragment
import app.selfwatch.body.HeartTrendInfo
import app.selfwatch.body.HeartTrendLayers
import app.selfwatch.body.OverlayText
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
import app.selfwatch.ui.charts.ChartLayer
import app.selfwatch.ui.charts.LineLayer
import app.selfwatch.ui.charts.OverlayChartView
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.ToggleChipGroup
import java.time.ZoneId

/**
 * Heart rate trends: resting heart rate and the daily average heart rate over the last 30 days, as two
 * lines on one chart with chips (both on by default, [ChartId.HEART_RATE_TRENDS]). Opens Heart.
 */
object HeartRateTrendsCard : Card<HeartRateTrendsCard.Data>(
    "heart_rate_trends", CardTab.BODY, R.string.card_heart_rate_trends, R.string.card_heart_rate_trends_summary,
) {
    /** The trend and the layers the user switched off. */
    data class Data(val trend: HeartTrendInfo, val hidden: Set<String>)

    /** The chips' stored choice. */
    val CHOICE: ChartChoice by lazy { ChartChoice(ChartId.HEART_RATE_TRENDS) }

    override val tables: Set<Table> = setOf(Table.METRIC, Table.DAY_SUMMARY, Table.SETTING)

    override suspend fun load(env: CardEnv): Data = Data(HeartCardLoader(env.db, env.clock).trend(), CHOICE.load(env.db))

    override val needs: Set<DataKind> = setOf(DataKind.HEART_RATE, DataKind.RESTING_HR)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override fun small(ctx: Context, data: Data): SmallFace? {
        val t = data.trend
        if (t.isEmpty) return null
        val bpm = ctx.getString(R.string.unit_bpm)
        val resting = t.restingMean
        val average = t.averageMean
        return if (resting != null) {
            SmallFace(
                value = resting.toString(),
                label = ctx.getString(R.string.small_hrt_resting),
                unit = bpm,
                note = average?.let { ctx.getString(R.string.small_hrt_daily_note, it.toString()) },
            )
        } else {
            SmallFace(
                value = average?.toString() ?: ctx.getString(R.string.dash),
                label = ctx.getString(R.string.small_hrt_daily),
                unit = average?.let { bpm },
            )
        }
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(HeartDetailFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    private const val DAY_SEC = 86_400L

    private class Holder(private val host: CardHost, parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_body_heart_rate_trends)) {
        private val summary = find<TextView>(R.id.hrt_summary)
        private val chart = find<OverlayChartView>(R.id.hrt_chart)
        private val chips = find<ToggleChipGroup>(R.id.hrt_chips)
        private val readout = find<TextView>(R.id.hrt_readout)
        private val note = find<TextView>(R.id.hrt_note)
        private val empty = find<EmptyState>(R.id.hrt_empty)
        private var applied = false

        init {
            root.setOnClickListener { host.open(HeartDetailFragment()) }
            readout.setText(R.string.chart_scrub_hint)
            note.setText(R.string.hrt_note)
            chart.onScrub = { t, values -> readout.text = OverlayText.readout(root.context, t, values) }
        }

        override fun bind(data: Data) {
            val ctx = root.context
            val t = data.trend
            for (v in listOf(summary, chart, chips, readout, note)) v.visibility = if (t.isEmpty) View.GONE else View.VISIBLE
            empty.visibility = if (t.isEmpty) View.VISIBLE else View.GONE
            if (t.isEmpty) {
                root.contentDescription = ctx.getString(R.string.hrt_empty)
                return
            }
            val dash = ctx.getString(R.string.dash)
            summary.text = ctx.getString(R.string.hrt_summary, t.restingMean?.toString() ?: dash, t.averageMean?.toString() ?: dash)
            val zone = ZoneId.systemDefault()
            val bpm = ctx.getString(R.string.unit_bpm)
            fun line(id: String, label: Int, color: Int, values: List<Int?>): LineLayer? {
                val idx = values.indices.filter { values[it] != null }
                if (idx.isEmpty()) return null
                return LineLayer(
                    id, ctx.getString(label), color, Axis.LEFT,
                    LongArray(idx.size) { ChartScreens.noon(t.days[idx[it]], zone) },
                    IntArray(idx.size) { values[idx[it]] ?: 0 },
                    gapSec = 2 * DAY_SEC, unit = bpm,
                )
            }
            val layers = listOfNotNull<ChartLayer>(
                line(HeartTrendLayers.RESTING, R.string.hrt_layer_resting, ChartScreens.series(ctx, 0), t.resting),
                line(HeartTrendLayers.AVERAGE, R.string.hrt_layer_average, ChartScreens.series(ctx, 1), t.average),
            )
            if (!applied) {
                for (id in HeartTrendLayers.ALL) chart.setVisible(id, id !in data.hidden)
                applied = true
            }
            chart.zone = zone
            val range = TimeRange(t.days.first().startSec(zone), t.days.last().plusDays(1).startSec(zone))
            if (chart.range != range) chart.range = range
            chart.layers = layers
            chart.bindChips(chips)
            chips.onChange = { id, on, _ ->
                chart.setVisible(id, on)
                val hidden = HeartTrendLayers.ALL.filterNot { chart.isLayerVisible(it) }.toSet()
                val settings = host.context.graph.settings
                host.launch { CHOICE.save(settings, hidden) }
            }
            root.contentDescription = ctx.getString(R.string.hrt_description, summary.text)
        }
    }
}
