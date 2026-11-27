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
import androidx.annotation.StringRes
import app.selfwatch.R
import app.selfwatch.body.BodyTrendLoader
import app.selfwatch.body.HeartDetailFragment
import app.selfwatch.body.StressInfo
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.model.time.Day
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.cards.CardVisuals
import app.selfwatch.ui.charts.LevelColors
import app.selfwatch.ui.components.ArcGaugeView
import app.selfwatch.ui.components.DayStripView
import app.selfwatch.ui.components.SplitBarView
import app.selfwatch.ui.components.SplitPart
import app.selfwatch.ui.format.Fmt

/**
 * C28 Stress (card design standard, the reference card): an arc gauge with today's average and Garmin's
 * level, a 24-hour strip of 15-minute stress colored by level (faded while asleep) and a split bar of the
 * time at each level. Small form: the gauge. Context only, never about mood. Opens Heart.
 */
object BodyStressCard : Card<BodyStressCard.Data>("body_stress", CardTab.BODY, R.string.card_body_stress, R.string.card_body_stress_summary) {

    /** The day's numbers (null with no readings this week) and [today], to name the day. */
    data class Data(val stress: StressInfo?, val today: Day)

    override val tables: Set<Table> = setOf(Table.METRIC, Table.SLEEP_SESSION)

    override suspend fun load(env: CardEnv): Data = Data(BodyTrendLoader(env.db, env.clock).stress(), env.clock.today())

    override val needs: Set<DataKind> = setOf(DataKind.STRESS)

    override fun small(ctx: Context, data: Data): SmallFace? {
        val s = data.stress ?: return null
        return SmallFace(value = s.avg.toString(), label = ctx.getString(level(s.avg)))
    }

    override fun createSmallGraphic(ctx: Context): View = CardVisuals.smallGauge(ctx, CardVisuals.stressBands(ctx))

    override val smallGraphicShowsValue: Boolean = true

    override fun bindSmallGraphic(graphic: View, data: Data) {
        val s = data.stress ?: return
        (graphic as ArcGaugeView).apply {
            value = s.avg.toFloat()
            centerText = s.avg.toString()
            subText = context.getString(level(s.avg))
        }
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(HeartDetailFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    /** Garmin's level name for a stress value (0–25 rest, 26–50 low, 51–75 medium, 76–100 high). */
    @StringRes
    fun level(avg: Int): Int = when {
        avg <= REST_MAX -> R.string.band_rest
        avg <= LOW_MAX -> R.string.band_low
        avg <= MID_MAX -> R.string.band_medium
        else -> R.string.band_high
    }

    private const val REST_MAX = 25
    private const val LOW_MAX = 50
    private const val MID_MAX = 75

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_body_stress)) {
        private val gauge = find<ArcGaugeView>(R.id.body_stress_gauge)
        private val strip = find<DayStripView>(R.id.body_stress_strip)
        private val split = find<SplitBarView>(R.id.body_stress_split)
        private val empty = find<EmptyState>(R.id.body_stress_empty)

        init {
            root.setOnClickListener { host.open(HeartDetailFragment()) }
            val ctx = root.context
            val levels = LevelColors.of(ctx)
            gauge.bands = CardVisuals.stressBands(ctx)
            strip.colorFor = { levels.stress(it) }
        }

        override fun bind(data: Data) {
            val ctx = root.context
            val s = data.stress
            for (v in listOf(gauge, strip, split)) CardVisuals.show(v, s != null)
            CardVisuals.show(empty, s == null)
            if (s == null) {
                root.contentDescription = ctx.getString(R.string.stress_card_empty)
                return
            }
            val levels = LevelColors.of(ctx)
            val dayText = if (s.day == data.today) ctx.getString(R.string.stress_card_today) else Fmt.day(ctx, s.day.yyyymmdd)
            gauge.value = s.avg.toFloat()
            gauge.centerText = s.avg.toString()
            gauge.subText = ctx.getString(level(s.avg))
            strip.values = s.slots
            strip.dim = s.asleep
            CardVisuals.show(strip, s.slots.any { it >= 0 })
            val m = s.levelMin
            split.parts = listOf(
                SplitPart(m[0].toFloat(), levels.stressRest, ctx.getString(R.string.stress_split, ctx.getString(R.string.band_rest), CardVisuals.minutes(ctx, m[0]))),
                SplitPart(m[1].toFloat(), levels.stressLow, ctx.getString(R.string.stress_split, ctx.getString(R.string.band_low), CardVisuals.minutes(ctx, m[1]))),
                SplitPart(m[2].toFloat(), levels.stressMedium, ctx.getString(R.string.stress_split, ctx.getString(R.string.band_medium), CardVisuals.minutes(ctx, m[2]))),
                SplitPart(m[3].toFloat(), levels.stressHigh, ctx.getString(R.string.stress_split, ctx.getString(R.string.band_high), CardVisuals.minutes(ctx, m[3]))),
            )
            CardVisuals.show(split, m.sum() > 0)
            root.contentDescription = ctx.getString(
                R.string.stress_card_description, dayText, gauge.centerText, CardVisuals.minutes(ctx, s.restMin), gauge.subText,
            )
        }
    }
}
