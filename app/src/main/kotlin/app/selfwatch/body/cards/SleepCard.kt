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
import app.selfwatch.body.BodyCardMath
import app.selfwatch.body.BodyTrendLoader
import app.selfwatch.body.RangeStatus
import app.selfwatch.body.SleepDetailFragment
import app.selfwatch.body.SleepInfo
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.model.health.SleepStage
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.Sparkline
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import java.util.Locale
import app.selfwatch.ui.R as UiR

/**
 * C16 Sleep: last night's time asleep against the 7–9 h guide, the 7-night average, the stages as
 * detail and 14 nights; opens Sleep. (The older `recovery` card stays registered for saved layouts.)
 */
object SleepCard : Card<SleepInfo>("sleep", CardTab.BODY, R.string.card_sleep, R.string.card_sleep_summary) {

    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY, Table.SLEEP_SESSION, Table.SLEEP_STAGE)

    override suspend fun load(env: CardEnv): SleepInfo = BodyTrendLoader(env.db, env.clock).sleep()

    override val needs: Set<DataKind> = setOf(DataKind.SLEEP)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override fun small(ctx: Context, data: SleepInfo): SmallFace? {
        if (data.isEmpty) return null
        val last = data.lastMin
        if (last == null) {
            val avg = data.avgMin ?: return null
            return SmallFace(value = duration(ctx, avg), label = ctx.getString(R.string.small_seven_night_avg))
        }
        val today = Fmt.dateToDay(java.time.LocalDate.now())
        val label = data.lastDay?.takeIf { it.yyyymmdd != today }?.let { Fmt.day(ctx, it.yyyymmdd) }
            ?: ctx.getString(R.string.sleep_card_last_night)
        val (status, note) = when (BodyCardMath.sleepStatus(last)) {
            RangeStatus.WITHIN -> CardStatus.GOOD to R.string.sleep_card_within
            RangeStatus.BELOW -> CardStatus.CLOSE to R.string.sleep_card_below
            RangeStatus.ABOVE -> CardStatus.CLOSE to R.string.sleep_card_above
        }
        return SmallFace(value = duration(ctx, last), label = label, status = status, note = ctx.getString(note))
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(SleepDetailFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<SleepInfo> = Holder(host, parent)

    /** "7 h 40 min". */
    fun duration(ctx: Context, minutes: Int): String = Fmt.duration(minutes * 60L, Locale.getDefault(), FmtLabels.from(ctx))

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<SleepInfo>(CardView.inflate(parent, R.layout.card_body_sleep)) {
        init {
            root.setOnClickListener { host.open(SleepDetailFragment()) }
        }

        override fun bind(data: SleepInfo) {
            val ctx = root.context
            val tile = find<StatTile>(R.id.sleep_night_tile)
            val status = find<TextView>(R.id.sleep_night_status)
            val stages = find<TextView>(R.id.sleep_night_stages)
            val spark = find<Sparkline>(R.id.sleep_night_spark)
            val empty = data.isEmpty
            find<EmptyState>(R.id.sleep_night_empty).visibility = if (empty) View.VISIBLE else View.GONE
            for (v in listOf(tile, status, stages, spark)) v.visibility = if (empty) View.GONE else View.VISIBLE
            if (empty) {
                root.contentDescription = ctx.getString(R.string.sleep_card_empty)
                return
            }
            val last = data.lastMin
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
            tile.value = last?.let { duration(ctx, it) } ?: ctx.getString(R.string.dash)
            val today = Fmt.dateToDay(java.time.LocalDate.now())
            tile.label = data.lastDay?.takeIf { it.yyyymmdd != today }?.let { Fmt.day(ctx, it.yyyymmdd) }
                ?: ctx.getString(R.string.sleep_card_last_night)
            val statusText = last?.let {
                ctx.getString(
                    when (BodyCardMath.sleepStatus(it)) {
                        RangeStatus.BELOW -> R.string.sleep_card_below
                        RangeStatus.WITHIN -> R.string.sleep_card_within
                        RangeStatus.ABOVE -> R.string.sleep_card_above
                    },
                )
            }
            val avgText = data.avgMin?.let { ctx.getString(R.string.sleep_card_avg, duration(ctx, it)) }
            status.text = listOfNotNull(statusText, avgText).joinToString(" · ")
            status.visibility = if (status.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            val s = data.stageMin
            if (s.isEmpty()) {
                stages.visibility = View.GONE
            } else {
                stages.text = ctx.getString(
                    R.string.sleep_card_stages,
                    duration(ctx, s[SleepStage.DEEP] ?: 0),
                    duration(ctx, s[SleepStage.LIGHT] ?: 0),
                    duration(ctx, s[SleepStage.REM] ?: 0),
                    duration(ctx, s[SleepStage.AWAKE] ?: 0),
                )
            }
            val nights = data.nights.filter { it >= 0 }
            spark.values = nights.toIntArray()
            spark.visibility = if (nights.size >= 2) View.VISIBLE else View.GONE
            root.contentDescription = ctx.getString(R.string.sleep_card_description, tile.value, status.text, if (s.isEmpty()) "" else stages.text)
        }
    }
}
