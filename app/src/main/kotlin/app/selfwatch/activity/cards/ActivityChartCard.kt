/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity.cards

import android.content.Context
import android.view.ViewGroup
import app.selfwatch.R
import app.selfwatch.activity.OverlayLayers
import app.selfwatch.activity.TrainingDetailFragment
import app.selfwatch.activity.TrainingOverlay
import app.selfwatch.body.cards.ChartCardView
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.ui.format.Fmt

/** The Activity chart card's data: the 12-week training overlay and the stored chip choice. */
class ActivityChartData(val overlay: TrainingOverlay, val hidden: Set<String>) {
    /** Training load over the last 7 days. */
    val weekLoad: Int get() = overlay.load.takeLast(DAYS).sum()

    private companion object {
        const val DAYS = 7
    }
}

/**
 * Activity chart: the Training and recovery chart (load and intensity bars with HRV, its usual band and
 * resting heart rate chips, 12 weeks) as a large card. Chips share the Training screen's stored choice.
 */
object ActivityChartCard : Card<ActivityChartData>("activity_chart", CardTab.ACTIVITY, R.string.card_activity_chart, R.string.card_activity_chart_summary) {

    override val tables: Set<Table> = setOf(Table.WORKOUT, Table.METRIC, Table.ACTIVITY, Table.DAY_SUMMARY, Table.SETTING)

    override val needs: Set<DataKind> = setOf(DataKind.WORKOUTS, DataKind.HRV, DataKind.RESTING_HR)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override suspend fun load(env: CardEnv): ActivityChartData =
        ActivityChartData(TrainingOverlay.load(env.db, env.clock), OverlayLayers.TRAINING.load(env.db))

    override fun small(ctx: Context, data: ActivityChartData): SmallFace? {
        if (!data.overlay.hasData) return null
        return SmallFace(value = Fmt.number(data.weekLoad.toLong()), label = ctx.getString(R.string.chart_card_week_load))
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(TrainingDetailFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<ActivityChartData> =
        ChartCardView(host, parent, R.id.activity_chart_card, R.string.card_activity_chart, R.string.training_empty, ::TrainingDetailFragment) { c, d ->
            TrainingDetailFragment.bind(c, d.overlay, d.hidden) { ChartCardView.save(host, OverlayLayers.TRAINING, it) }
        }
}
