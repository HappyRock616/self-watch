/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body.cards

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.StringRes
import app.selfwatch.R
import app.selfwatch.body.BodySignalsDetailFragment
import app.selfwatch.body.BodyTrendLoader
import app.selfwatch.body.SignalKind
import app.selfwatch.body.SignalReading
import app.selfwatch.body.SignalsInfo
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.CardVisuals
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.LevelColors
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.RangeBarView
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import app.selfwatch.ui.format.Units
import kotlin.math.abs
import app.selfwatch.ui.R as UiR

/**
 * C29 Body signals (card design standard): last night's skin temperature change, resting heart rate and
 * breathing rate, each as a mini range bar around the user's own baseline (the 28 nights before) with a
 * marker at last night and a short "↑ up" or "ok". Never diagnostic. Small form: the count up and the three
 * bars. Opens the body signals chart (O7).
 */
object BodySignalsCard : Card<SignalsInfo>("body_signals", CardTab.BODY, R.string.card_body_signals, R.string.card_body_signals_summary) {

    override val tables: Set<Table> = setOf(Table.METRIC, Table.DAY_SUMMARY, Table.SLEEP_SESSION)

    override suspend fun load(env: CardEnv): SignalsInfo = BodyTrendLoader(env.db, env.clock).signals().copy(units = env.units)

    override val needs: Set<DataKind> = setOf(DataKind.SKIN_TEMP, DataKind.RESPIRATION, DataKind.RESTING_HR)

    override fun small(ctx: Context, data: SignalsInfo): SmallFace? {
        val r = data.result
        if (r == null) {
            if (data.nights == 0 || data.nights >= data.needed) return null
            return SmallFace(
                value = ctx.getString(R.string.dash),
                label = ctx.getString(R.string.small_signals_label),
                note = ctx.getString(R.string.small_learning_nights, data.nights, data.needed),
            )
        }
        val (status, note) = when {
            r.flagged -> CardStatus.OFF to R.string.small_signals_flag
            r.upCount == 1 -> CardStatus.CLOSE to R.string.small_signals_one
            else -> CardStatus.GOOD to R.string.small_signals_calm
        }
        return SmallFace(
            value = r.upCount.toString(),
            label = ctx.getString(R.string.small_signals_up, r.readings.size),
            status = status,
            note = ctx.getString(note),
        )
    }

    override fun createSmallGraphic(ctx: Context): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        repeat(SignalKind.entries.size) { addView(RangeBarView(ctx), Views.matchWrap(ctx, 2)) }
    }

    override fun bindSmallGraphic(graphic: View, data: SignalsInfo) {
        val group = graphic as LinearLayout
        val readings = data.result?.readings.orEmpty()
        for (i in 0 until group.childCount) {
            val bar = group.getChildAt(i) as RangeBarView
            val s = readings.getOrNull(i)
            CardVisuals.show(bar, s != null)
            if (s != null) bindBar(bar, s)
        }
        CardVisuals.show(group, readings.isNotEmpty())
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(BodySignalsDetailFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<SignalsInfo> = Holder(host, parent)

    /** The name of [kind]. */
    @StringRes
    fun label(kind: SignalKind): Int = when (kind) {
        SignalKind.SKIN_TEMP -> R.string.signals_skin
        SignalKind.RESTING_HR -> R.string.signals_rhr
        SignalKind.RESPIRATION -> R.string.signals_resp
    }

    /** The short name of [kind] on the card ("Skin temp"). */
    @StringRes
    fun shortLabel(kind: SignalKind): Int = when (kind) {
        SignalKind.SKIN_TEMP -> R.string.body1_signal_skin
        SignalKind.RESTING_HR -> R.string.body1_signal_rhr
        SignalKind.RESPIRATION -> R.string.body1_signal_resp
    }

    /**
     * Shows [s] on [bar]: the usual band around the baseline (mean ± the rise that counts, or one standard
     * deviation when wider), lower in neutral, higher in the "watch" color, and the marker at last night.
     */
    fun bindBar(bar: RangeBarView, s: SignalReading) {
        val c = LevelColors.of(bar.context)
        val mean = (s.value - s.deviation).toFloat()
        val half = maxOf(s.kind.minRise.toDouble(), s.sd).toFloat()
        BodyVisuals.bindUsual(bar, mean - half, mean + half, s.value.toFloat(), null, null, below = c.scaleNeutral, above = c.scaleOff)
    }

    /** A signed deviation of [kind] in display units: "+0.4 °C" (or "+0.7 °F"), "−2 bpm", "+1.1 br/min". */
    fun deviation(ctx: Context, kind: SignalKind, stored: Int, units: Units): String {
        val decimals = if (kind.scale == 1) 0 else 1
        val unit = when (kind) {
            SignalKind.SKIN_TEMP -> Fmt.temperatureUnit(units, FmtLabels.from(ctx))
            SignalKind.RESTING_HR -> ctx.getString(R.string.unit_bpm)
            SignalKind.RESPIRATION -> ctx.getString(R.string.unit_breaths)
        }
        val value = abs(stored).toDouble() / kind.scale
        val n = Fmt.number(if (kind == SignalKind.SKIN_TEMP) Fmt.temperatureDelta(value, units) else value, decimals)
        val sign = when {
            stored > 0 -> "+"
            stored < 0 -> "−"
            else -> ""
        }
        return "$sign$n $unit"
    }

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<SignalsInfo>(CardView.inflate(parent, R.layout.card_body_signals)) {
        private val rows = find<LinearLayout>(R.id.body_signals_rows)
        private val empty = find<EmptyState>(R.id.body_signals_empty)

        init {
            root.setOnClickListener { host.open(BodySignalsDetailFragment()) }
        }

        override fun bind(data: SignalsInfo) {
            val ctx = root.context
            val r = data.result
            CardVisuals.show(rows, r != null)
            CardVisuals.show(empty, r == null)
            if (r == null) {
                empty.message = if (data.nights == 0 || data.nights >= data.needed) {
                    ctx.getString(R.string.signals_card_empty)
                } else {
                    ctx.getString(R.string.signals_card_learning, data.nights, data.needed)
                }
                root.contentDescription = empty.message
                return
            }
            rows.removeAllViews()
            val off = LevelColors.of(ctx).off
            for (s in r.readings) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(
                    Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, ctx.getString(shortLabel(s.kind))).apply { maxLines = 2 },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, LABEL_WEIGHT),
                )
                val bar = RangeBarView(ctx)
                bindBar(bar, s)
                row.addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, BAR_WEIGHT).apply { marginStart = Views.dp(ctx, 8) })
                val tag = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, ctx.getString(if (s.up) R.string.body1_signal_up else R.string.body1_signal_ok)).apply {
                    gravity = Gravity.END
                    if (s.up) setTextColor(off)
                }
                row.addView(tag, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, TAG_WEIGHT).apply { marginStart = Views.dp(ctx, 8) })
                rows.addView(row, Views.matchWrap(ctx, if (rows.childCount == 0) 4 else 10))
            }
            val spoken = r.readings.map { s ->
                ctx.getString(if (s.up) R.string.signals_row_up else R.string.signals_row, ctx.getString(label(s.kind)), deviation(ctx, s.kind, s.deviation, data.units))
            }
            root.contentDescription = (listOf(ctx.getString(R.string.signals_card_up_count, r.upCount, r.readings.size)) + spoken).joinToString(". ")
        }

        private companion object {
            const val LABEL_WEIGHT = 1.1f
            const val BAR_WEIGHT = 1.6f
            const val TAG_WEIGHT = 0.6f
        }
    }
}
