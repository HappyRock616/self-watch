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
import app.selfwatch.body.BodyLoader
import app.selfwatch.body.IntensityInfo
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardStatusColors
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.shell.Tab
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import com.google.android.material.progressindicator.LinearProgressIndicator
import app.selfwatch.ui.R as UiR

/** Vigorous minutes today and over 7 days vs 75 a week; opens Activity. Null data: no activity this week. */
object IntensityCard : Card<IntensityInfo?>("intensity", CardTab.ACTIVITY, R.string.card_intensity, R.string.card_intensity_summary) {

    override val tables: Set<Table> = setOf(Table.ACTIVITY)

    override suspend fun load(env: CardEnv): IntensityInfo? = BodyLoader(env.db, env.clock).intensity()

    override val needs: Set<DataKind> = setOf(DataKind.STEPS, DataKind.ACTIVE_TIME)

    override fun small(ctx: Context, data: IntensityInfo?): SmallFace? {
        if (data == null) return null
        return SmallFace(
            value = Fmt.number(data.weekMin.toLong()),
            label = ctx.getString(R.string.intensity_week_label),
            unit = ctx.getString(R.string.unit_min),
            status = CardStatusColors.ofProgress(data.weekMin.toDouble(), data.weekGoalMin.toDouble()),
            note = ctx.getString(R.string.intensity_today, Fmt.number(data.todayMin.toLong())),
            progress = if (data.weekGoalMin > 0) data.weekMin.toFloat() / data.weekGoalMin else null,
        )
    }

    override fun openDetail(host: CardHost): Boolean {
        host.openTab(Tab.ACTIVITY)
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<IntensityInfo?> = Holder(host, parent)

    private class Holder(host: CardHost, parent: ViewGroup) :
        CardView<IntensityInfo?>(CardView.inflate(parent, R.layout.card_body_intensity)) {
        init {
            root.setOnClickListener { host.openTab(Tab.ACTIVITY) }
        }

        override fun bind(data: IntensityInfo?) {
            val ctx = root.context
            val empty = data == null
            find<EmptyState>(R.id.intensity_empty).visibility = if (empty) View.VISIBLE else View.GONE
            for (id in intArrayOf(R.id.intensity_week, R.id.intensity_bar, R.id.intensity_today)) {
                find<View>(id).visibility = if (empty) View.GONE else View.VISIBLE
            }
            if (data == null) return
            val tile = find<StatTile>(R.id.intensity_week)
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
            tile.value = Fmt.number(data.weekMin.toLong())
            tile.unit = ctx.getString(R.string.unit_min)
            tile.label = ctx.getString(R.string.intensity_week_goal, Fmt.number(data.weekGoalMin.toLong()))
            find<LinearProgressIndicator>(R.id.intensity_bar).let { bar ->
                bar.visibility = if (data.weekGoalMin <= 0) View.GONE else View.VISIBLE
                bar.max = maxOf(data.weekGoalMin, 1)
                bar.setProgressCompat(data.weekMin.coerceIn(0, bar.max), false)
            }
            val today = Fmt.number(data.todayMin.toLong())
            find<TextView>(R.id.intensity_today).text =
                ctx.getString(if (data.weekMin >= data.weekGoalMin) R.string.intensity_done else R.string.intensity_today, today)
            root.contentDescription =
                ctx.getString(R.string.intensity_description, tile.value, Fmt.number(data.weekGoalMin.toLong()), today)
        }
    }
}
