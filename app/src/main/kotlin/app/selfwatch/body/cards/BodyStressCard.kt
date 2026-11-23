/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body.cards

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import app.selfwatch.db.Table
import app.selfwatch.model.time.Day
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.Sparkline
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.R as UiR

/**
 * C28 Body stress: the watch's body stress score for today (average) and the rest time, with the week's
 * daily averages. Context only, no target, never about mood ("body stress", "rest time", "your body was
 * working hard"). Opens Heart.
 */
object BodyStressCard : Card<BodyStressCard.Data>("body_stress", CardTab.BODY, R.string.card_body_stress, R.string.card_body_stress_summary) {

    /** The day's numbers (null with no readings this week) and [today], to name the day. */
    data class Data(val stress: StressInfo?, val today: Day)

    override val tables: Set<Table> = setOf(Table.METRIC)

    override suspend fun load(env: CardEnv): Data = Data(BodyTrendLoader(env.db, env.clock).stress(), env.clock.today())

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    /** The wording for an average score (0–25 rest, 26–50 light, 51–75 moderate, 76–100 hard). */
    @StringRes
    fun level(avg: Int): Int = when {
        avg <= REST_MAX -> R.string.stress_card_rest_level
        avg <= LOW_MAX -> R.string.stress_card_low_level
        avg <= MID_MAX -> R.string.stress_card_mid_level
        else -> R.string.stress_card_high_level
    }

    private const val REST_MAX = 25
    private const val LOW_MAX = 50
    private const val MID_MAX = 75

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_body_stress)) {
        init {
            root.setOnClickListener { host.open(HeartDetailFragment()) }
        }

        override fun bind(data: Data) {
            val ctx = root.context
            val s = data.stress
            val tile = find<StatTile>(R.id.body_stress_tile)
            val rest = find<TextView>(R.id.body_stress_rest)
            val level = find<TextView>(R.id.body_stress_level)
            val spark = find<Sparkline>(R.id.body_stress_spark)
            val note = find<TextView>(R.id.body_stress_note)
            for (v in listOf(tile, rest, level, spark, note)) v.visibility = if (s == null) View.GONE else View.VISIBLE
            find<EmptyState>(R.id.body_stress_empty).visibility = if (s == null) View.VISIBLE else View.GONE
            if (s == null) {
                root.contentDescription = ctx.getString(R.string.stress_card_empty)
                return
            }
            val dayText = if (s.day == data.today) ctx.getString(R.string.stress_card_today) else Fmt.day(ctx, s.day.yyyymmdd)
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
            tile.value = s.avg.toString()
            tile.label = ctx.getString(R.string.card_line_pair, ctx.getString(R.string.stress_card_avg), dayText)
            val restText = SleepCard.duration(ctx, s.restMin)
            rest.text = ctx.getString(R.string.card_line_pair, ctx.getString(R.string.stress_card_rest), restText)
            level.setText(level(s.avg))
            note.setText(R.string.stress_card_note)
            val days = s.daily.filter { it >= 0 }
            spark.values = days.toIntArray()
            spark.visibility = if (days.size >= 2) View.VISIBLE else View.GONE
            root.contentDescription = ctx.getString(R.string.stress_card_description, dayText, tile.value, restText, level.text)
        }
    }
}
