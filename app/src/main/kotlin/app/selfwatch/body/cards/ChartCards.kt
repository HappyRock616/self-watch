/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body.cards

import android.content.Context
import android.view.ViewGroup
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import app.selfwatch.R
import app.selfwatch.body.BodyCardMath
import app.selfwatch.body.BodyUnits
import app.selfwatch.body.ChartCard
import app.selfwatch.body.HeartChart
import app.selfwatch.body.HeartDetailFragment
import app.selfwatch.body.HeartUiState
import app.selfwatch.body.HeartViewModel
import app.selfwatch.body.RangeStatus
import app.selfwatch.body.SleepChart
import app.selfwatch.body.SleepDetailFragment
import app.selfwatch.body.SleepLayers
import app.selfwatch.body.SleepNightData
import app.selfwatch.body.SleepViewModel
import app.selfwatch.body.WeightChart
import app.selfwatch.body.WeightDetailFragment
import app.selfwatch.body.WeightLayers
import app.selfwatch.body.WeightRange
import app.selfwatch.body.WeightUiState
import app.selfwatch.body.WeightViewModel
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.ChartChoice
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.graph
import app.selfwatch.model.health.BodyKind
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import app.selfwatch.ui.format.Units
import java.util.Locale

/**
 * A chart card's large form (card design rule 8): the detail screen's overlay chart, toggle chips and
 * read-out, unchanged, in a card whose title row opens [detail] (the chart itself scrubs and zooms). [show]
 * binds the data the same way the detail screen does.
 */
class ChartCardView<T> private constructor(
    /** The chart card ([root] is its card). */
    val chart: ChartCard,
    private val show: (ChartCard, T) -> Unit,
) : CardView<T>(chart.card) {

    override fun bind(data: T) = show(chart, data)

    /** Construction. */
    companion object {
        /** A chart card view with root id [rootId], titled [title], showing [empty] without data; the title row opens [detail]. */
        operator fun <T> invoke(
            host: CardHost,
            parent: ViewGroup,
            @IdRes rootId: Int,
            @StringRes title: Int,
            @StringRes empty: Int,
            detail: () -> Fragment,
            show: (ChartCard, T) -> Unit,
        ): ChartCardView<T> {
            val ctx = parent.context
            val c = ChartCard.onTab(ctx, ctx.getString(title), ctx.getString(empty)) { host.open(detail()) }
            c.card.id = rootId
            return ChartCardView(c, show)
        }

        /** Saves [hidden] as [choice] from a chip change on a tab card. */
        fun save(host: CardHost, choice: ChartChoice, hidden: Set<String>) {
            val db = host.context.graph.db
            host.launch { choice.save(db, hidden) }
        }
    }
}

/** "7 h 40 min". */
private fun duration(ctx: Context, minutes: Int): String = Fmt.duration(minutes * 60L, Locale.getDefault(), FmtLabels.from(ctx))

/** The Sleep chart card's data: last night (null when none), the stored chip choice and the units. */
data class SleepChartData(val night: SleepNightData?, val hidden: Set<String>, val units: Units)

/**
 * Sleep chart: the Sleep screen's Night chart (stages with heart rate, HRV, breathing, SpO2 and skin
 * temperature chips) for last night, as a large card. Chips share the Night view's stored choice.
 */
object SleepChartCard : Card<SleepChartData>("sleep_chart", CardTab.BODY, R.string.card_sleep_chart, R.string.card_sleep_chart_summary) {

    override val tables: Set<Table> = SleepViewModel.TABLES + Table.SETTING

    override val needs: Set<DataKind> = setOf(DataKind.SLEEP)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override suspend fun load(env: CardEnv): SleepChartData =
        SleepChartData(SleepViewModel.loadNight(env.db, env.day, env.clock.zone()), SleepLayers.NIGHT_CHOICE.load(env.db), env.units)

    override fun small(ctx: Context, data: SleepChartData): SmallFace? {
        val n = data.night ?: return null
        val status = when (BodyCardMath.sleepStatus(n.asleepMin)) {
            RangeStatus.WITHIN -> CardStatus.GOOD
            RangeStatus.BELOW, RangeStatus.ABOVE -> CardStatus.CLOSE
        }
        return SmallFace(value = duration(ctx, n.asleepMin), label = ctx.getString(R.string.sleep_card_last_night), status = status)
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(SleepDetailFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<SleepChartData> =
        ChartCardView(host, parent, R.id.sleep_chart_card, R.string.card_sleep_chart, R.string.sleep_empty, ::SleepDetailFragment) { c, d ->
            c.setTag(d.night?.let { duration(c.card.context, it.asleepMin) })
            SleepChart.bindNight(c, d.night, d.hidden, d.units) { ChartCardView.save(host, SleepLayers.NIGHT_CHOICE, it) }
        }
}

/** Heart chart: the Heart screen's Day chart (heart rate, stress and HRV chips) for today, as a large card. */
object HeartChartCard : Card<HeartUiState>("heart_chart", CardTab.BODY, R.string.card_heart_chart, R.string.card_heart_chart_summary) {

    override val tables: Set<Table> = HeartViewModel.TABLES

    override val needs: Set<DataKind> = setOf(DataKind.HEART_RATE, DataKind.STRESS, DataKind.HRV)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override suspend fun load(env: CardEnv): HeartUiState = HeartViewModel.loadDay(env.db, env.day, env.clock.zone())

    override fun small(ctx: Context, data: HeartUiState): SmallFace? {
        if (data.isEmpty) return null
        val rest = data.restingHr
        val avg = data.hr.average()
        return when {
            rest != null -> SmallFace(value = rest.toString(), unit = ctx.getString(R.string.unit_bpm), label = ctx.getString(R.string.chart_card_resting_hr))
            avg != null -> SmallFace(value = avg.toString(), unit = ctx.getString(R.string.unit_bpm), label = ctx.getString(R.string.chart_card_avg_hr))
            else -> null
        }
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(HeartDetailFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<HeartUiState> =
        ChartCardView(host, parent, R.id.heart_chart_card, R.string.card_heart_chart, R.string.heart_empty, ::HeartDetailFragment) { c, s ->
            c.setTag(s.restingHr?.let { c.card.context.getString(R.string.chart_card_resting, it) })
            HeartChart.bindDay(c, s)
        }
}

/**
 * Weight chart: the Weight screen's chart (weigh-ins, trend and goal with energy balance, activity, calories
 * and steps chips) over the last 90 days, as a large card. Chips share the Weight screen's stored choice.
 */
object WeightChartCard : Card<WeightUiState>("weight_chart", CardTab.BODY, R.string.card_weight_chart, R.string.card_weight_chart_summary) {

    override val tables: Set<Table> = setOf(Table.BODY, Table.GOAL, Table.SETTING, Table.DIARY, Table.DAY_SUMMARY)

    override val needs: Set<DataKind> = setOf(DataKind.WEIGHT)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override suspend fun load(env: CardEnv): WeightUiState = WeightViewModel.load(env.db, env.clock, WeightRange.D90, env.units)

    override fun small(ctx: Context, data: WeightUiState): SmallFace? {
        val latest = data.entries.lastOrNull() ?: return null
        return SmallFace(
            value = BodyUnits.number(BodyKind.WEIGHT, latest.valueX100, data.units),
            unit = BodyUnits.unit(BodyKind.WEIGHT, data.units),
            label = ctx.getString(R.string.chart_card_latest),
        )
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(WeightDetailFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<WeightUiState> =
        ChartCardView(host, parent, R.id.weight_chart_card, R.string.card_weight_chart, R.string.weight_empty, ::WeightDetailFragment) { c, s ->
            c.setTag(s.entries.lastOrNull()?.let { BodyUnits.format(BodyKind.WEIGHT, it.valueX100, s.units) })
            WeightChart.bind(c, s) { ChartCardView.save(host, WeightLayers.CHOICE, it) }
        }
}
