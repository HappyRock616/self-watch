/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity.cards

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import app.selfwatch.R
import app.selfwatch.activity.ActivityLoader
import app.selfwatch.activity.Sports
import app.selfwatch.activity.WorkoutDetailFragment
import app.selfwatch.activity.WorkoutListFragment
import app.selfwatch.activity.WorkoutSummaryRow
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.Day
import app.selfwatch.shell.Views
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import app.selfwatch.ui.format.Units
import java.util.Locale

/**
 * Recent activities (a tall card on Activity): the last 5 workouts, each with its sport icon, name, date
 * and summary stats (duration, distance, pace or speed, average heart rate, calories). Tap one for the
 * full workout screen; "All activities" opens every workout, filterable by sport.
 */
object WorkoutsCard : Card<WorkoutsCard.Data>("workouts", CardTab.ACTIVITY, R.string.card_workouts, R.string.card_workouts_summary) {

    /** The workouts, newest first, in display [units]. */
    data class Data(val workouts: List<Workout>, val units: Units)

    override val tables: Set<Table> = setOf(Table.WORKOUT)

    override val needs: Set<DataKind> = setOf(DataKind.WORKOUTS)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override suspend fun load(env: CardEnv): Data = Data(ActivityLoader(env.db, env.clock).workouts(), env.units)

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    override fun small(ctx: Context, data: Data): SmallFace? {
        val w = data.workouts.firstOrNull() ?: return null
        val dist = w.distanceM?.takeIf { it > 0 }
        return SmallFace(
            value = dist?.let { Fmt.distance(it.toDouble(), data.units, Locale.getDefault(), FmtLabels.from(ctx)) }
                ?: Fmt.duration(w.durationSec.toLong(), Locale.getDefault(), FmtLabels.from(ctx)),
            label = ctx.getString(Sports.label(w.sport)),
            note = Fmt.day(ctx, Day.of(w.startSec, java.time.ZoneId.systemDefault()).yyyymmdd),
        )
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(WorkoutListFragment())
        return true
    }

    private class Holder(private val host: CardHost, parent: ViewGroup) :
        CardView<Data>(CardView.inflate(parent, R.layout.card_activity_workouts)) {
        init {
            val sw = root as SwCard
            sw.actionText = root.context.getString(R.string.activity_all_activities)
            sw.onAction = { host.open(WorkoutListFragment()) }
        }

        override fun bind(data: Data) {
            val ctx = root.context
            val list = find<LinearLayout>(R.id.recent_list)
            list.removeAllViews()
            for (w in data.workouts) {
                list.addView(WorkoutSummaryRow.create(ctx, w, data.units) { host.open(WorkoutDetailFragment.newInstance(w.id)) }, Views.matchWrap(ctx))
            }
            find<EmptyState>(R.id.recent_empty).visibility = if (data.workouts.isEmpty()) View.VISIBLE else View.GONE
        }
    }
}
