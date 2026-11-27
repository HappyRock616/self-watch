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
import app.selfwatch.R
import app.selfwatch.body.BodyLoader
import app.selfwatch.body.IntensityInfo
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardStatusColors
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.CardVisuals
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.shell.Tab
import app.selfwatch.ui.charts.LevelColors
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.RingView
import app.selfwatch.ui.format.Fmt

/**
 * Vigorous minutes over the last 7 days against 75 a week (card design standard, an Activity card): a
 * ring with the minutes inside, met / short in color. Small form: the same ring. Opens Activity. Null
 * data: no activity this week.
 */
object IntensityCard : Card<IntensityInfo?>("intensity", CardTab.ACTIVITY, R.string.card_intensity, R.string.card_intensity_summary) {
    override val tables: Set<Table> = setOf(Table.ACTIVITY)

    override suspend fun load(env: CardEnv): IntensityInfo? = BodyLoader(env.db, env.clock).intensity()

    override val needs: Set<DataKind> = setOf(DataKind.STEPS, DataKind.ACTIVE_TIME)

    override fun small(ctx: Context, data: IntensityInfo?): SmallFace? {
        if (data == null) return null
        return SmallFace(
            value = Fmt.number(data.weekMin.toLong()),
            label = ctx.getString(R.string.activity_of_goal, Fmt.number(data.weekGoalMin.toLong())),
            unit = ctx.getString(R.string.unit_min),
            status = CardStatusColors.ofProgress(data.weekMin.toDouble(), data.weekGoalMin.toDouble()),
            progress = if (data.weekGoalMin > 0) data.weekMin.toFloat() / data.weekGoalMin else null,
        )
    }

    override fun createSmallGraphic(ctx: Context): View = CardVisuals.smallRing(ctx)

    override val smallGraphicShowsValue: Boolean = true

    override fun bindSmallGraphic(graphic: View, data: IntensityInfo?) {
        if (data != null) ring(graphic as RingView, data)
    }

    /** Vigorous minutes of the weekly goal on [view]. */
    private fun ring(view: RingView, data: IntensityInfo) {
        val c = LevelColors.of(view.context)
        view.progress = if (data.weekGoalMin > 0) data.weekMin.toFloat() / data.weekGoalMin else 0f
        view.color = if (data.weekGoalMin > 0 && data.weekMin >= data.weekGoalMin) c.good else c.close
        view.centerText = Fmt.number(data.weekMin.toLong())
        view.subText = view.context.getString(R.string.activity_of_goal, Fmt.number(data.weekGoalMin.toLong()))
    }

    override fun openDetail(host: CardHost): Boolean {
        host.openTab(Tab.ACTIVITY)
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<IntensityInfo?> = Holder(host, parent)

    private class Holder(host: CardHost, parent: ViewGroup) :
        CardView<IntensityInfo?>(CardView.inflate(parent, R.layout.card_activity_intensity)) {
        private val ringView = find<RingView>(R.id.iv_ring)
        private val empty = find<EmptyState>(R.id.iv_empty)

        init {
            root.setOnClickListener { host.openTab(Tab.ACTIVITY) }
        }

        override fun bind(data: IntensityInfo?) {
            val ctx = root.context
            ringView.visibility = if (data == null) View.GONE else View.VISIBLE
            empty.visibility = if (data == null) View.VISIBLE else View.GONE
            if (data == null) {
                root.contentDescription = empty.message
                return
            }
            ring(ringView, data)
            root.contentDescription = ctx.getString(
                R.string.design_intensity_description, Fmt.number(data.weekMin.toLong()), Fmt.number(data.weekGoalMin.toLong()),
            )
        }
    }
}
