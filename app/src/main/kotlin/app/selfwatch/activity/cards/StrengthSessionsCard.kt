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
import app.selfwatch.activity.ActivityStatsLoader
import app.selfwatch.activity.ActivityWeek
import app.selfwatch.activity.StrengthRules
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardStatusColors
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.ui.format.Fmt

/**
 * Strength (and balance) days this week (C13), from the workouts' FIT sport types: strength training
 * counts; from age 65 yoga, pilates and flexibility sessions count too and the aim is 3 days instead of
 * 2 (WHO 2020). Empty when no counting session was recorded in the last 3 months.
 */
object StrengthSessionsCard : Card<StrengthSessionsCard.Data>(
    "strength_sessions", CardTab.ACTIVITY, R.string.card_strength_sessions, R.string.card_strength_sessions_summary,
) {
    /**
     * @property days distinct days this week (Monday to today) with a counting session.
     * @property target days a week.
     * @property withBalance true from age 65 (balance sessions count).
     * @property lastDay the day of the latest counting session in the last 3 months, or null (empty state).
     */
    data class Data(val days: Int, val target: Int, val withBalance: Boolean, val lastDay: Day?)

    override val tables: Set<Table> = setOf(Table.WORKOUT, Table.SETTING)

    override suspend fun load(env: CardEnv): Data {
        val loader = ActivityStatsLoader(env.db, env.clock)
        val age = loader.age()
        val withBalance = age != null && age >= StrengthRules.OLDER_AGE
        val zone = env.clock.zone()
        val recent = loader.workouts(DayRange(env.day.plusDays(-HISTORY_DAYS), env.day)).filter { StrengthRules.counts(it, withBalance) }
        val week = recent.filter { Day.of(it.startSec, zone) >= ActivityWeek.start(env.day) }
        return Data(
            StrengthRules.days(week, withBalance, zone),
            if (withBalance) StrengthRules.OLDER_TARGET else StrengthRules.TARGET,
            withBalance,
            recent.maxByOrNull { it.startSec }?.let { Day.of(it.startSec, zone) },
        )
    }

    override val needs: Set<DataKind> = setOf(DataKind.WORKOUTS)

    override fun small(ctx: Context, data: Data): SmallFace? {
        if (data.lastDay == null) return null
        return SmallFace(
            value = Fmt.number(data.days.toLong()),
            label = ctx.getString(R.string.small_days_aim, Fmt.number(data.target.toLong())),
            status = CardStatusColors.ofProgress(data.days.toDouble(), data.target.toDouble()),
            note = if (data.days >= data.target) ctx.getString(R.string.ss_caption_done) else null,
            progress = if (data.target > 0) data.days.toFloat() / data.target else null,
        )
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(parent)

    private const val HISTORY_DAYS = 90

    private class Holder(parent: ViewGroup) : StatCardView<Data>(
        parent, R.layout.card_activity_strength,
        StatIds(R.id.ss_tile, R.id.ss_bar, 0, R.id.ss_caption, R.id.ss_note, R.id.ss_empty),
    ) {
        override fun bind(data: Data) {
            val ctx = root.context
            val last = data.lastDay
            showEmpty(last == null)
            if (last == null) return
            val target = Fmt.number(data.target.toLong())
            tile.value = Fmt.number(data.days.toLong())
            tile.unit = null
            tile.label = ctx.getString(R.string.ss_label, target)
            progress(data.days, data.target)
            caption.text = when {
                data.days >= data.target -> ctx.getString(R.string.ss_caption_done)
                data.withBalance -> ctx.getString(R.string.ss_caption_balance, target)
                else -> ctx.getString(R.string.ss_caption_strength, target)
            }
            note.text = ctx.getString(R.string.ss_note_last, Fmt.day(ctx, last.yyyymmdd))
            root.contentDescription = ctx.getString(R.string.ss_description, tile.value, target)
        }
    }
}
