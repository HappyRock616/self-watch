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
import androidx.annotation.StringRes
import app.selfwatch.R
import app.selfwatch.body.BodySignalsDetailFragment
import app.selfwatch.body.BodyTrendLoader
import app.selfwatch.body.SignalKind
import app.selfwatch.body.SignalsInfo
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import app.selfwatch.ui.format.Units
import kotlin.math.abs

/**
 * C29 Body signals: last night's skin temperature change, resting heart rate and breathing rate, each
 * against the user's own baseline (the 28 nights before). Only when two or three rise together does it
 * say "several signals are up; take it easy"; never diagnostic. Opens the body signals chart (O7).
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
        init {
            root.setOnClickListener { host.open(BodySignalsDetailFragment()) }
        }

        override fun bind(data: SignalsInfo) {
            val ctx = root.context
            val r = data.result
            val tile = find<StatTile>(R.id.body_signals_tile)
            val status = find<TextView>(R.id.body_signals_status)
            val rows = find<TextView>(R.id.body_signals_rows)
            val note = find<TextView>(R.id.body_signals_note)
            val empty = find<EmptyState>(R.id.body_signals_empty)
            for (v in listOf(tile, status, rows, note)) v.visibility = if (r == null) View.GONE else View.VISIBLE
            empty.visibility = if (r == null) View.VISIBLE else View.GONE
            if (r == null) {
                empty.message = if (data.nights == 0 || data.nights >= data.needed) {
                    ctx.getString(R.string.signals_card_empty)
                } else {
                    ctx.getString(R.string.signals_card_learning, data.nights, data.needed)
                }
                root.contentDescription = empty.message
                return
            }
            // The tile is a count, "0 of 3 up": a plain fact rather than a score.
            tile.value = ctx.getString(R.string.signals_card_up_count, r.upCount, r.readings.size)
            tile.label = Fmt.day(ctx, r.day.yyyymmdd)
            status.setText(
                when {
                    r.flagged -> R.string.signals_card_flag
                    r.upCount == 1 -> R.string.signals_card_one
                    else -> R.string.signals_card_calm
                },
            )
            rows.text = r.readings.joinToString("\n") { s ->
                ctx.getString(if (s.up) R.string.signals_row_up else R.string.signals_row, ctx.getString(label(s.kind)), deviation(ctx, s.kind, s.deviation, data.units))
            }
            note.setText(R.string.signals_card_note)
            root.contentDescription = listOf(status.text, rows.text).joinToString(". ")
        }
    }
}
