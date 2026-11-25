/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.cards

import android.content.Context
import android.view.ViewGroup
import app.selfwatch.R
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.food.SleepCaloriesFragment

/**
 * Logging days (C4): which days of the week holding the day shown have diary entries, as dots, with
 * gentle wording ("4 days logged this week, nice"; never a broken-streak alarm). Its link opens the
 * sleep and calories chart (O2).
 */
object LoggingDaysCard : Card<LoggingWeek>(
    "logging_days",
    CardTab.FOOD,
    R.string.card_logging_days,
    R.string.card_logging_days_summary,
) {
    /** Days logged from which the line adds "nice". */
    const val NICE_FROM: Int = 3

    override val tables: Set<Table> = setOf(Table.DIARY)

    override suspend fun load(env: CardEnv): LoggingWeek = FoodCardLoader(env.db, env.clock).loggingWeek(env.day)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override fun small(ctx: Context, data: LoggingWeek): SmallFace {
        val n = data.count
        // Gentle: logging some days is "close", never "off" (no broken-streak alarm).
        return SmallFace(
            value = n.toString(),
            label = ctx.getString(R.string.small_logging_label),
            status = when {
                n >= NICE_FROM -> CardStatus.GOOD
                n > 0 -> CardStatus.CLOSE
                else -> CardStatus.NONE
            },
            note = ctx.getString(
                when {
                    n >= NICE_FROM -> R.string.small_logging_nice
                    n > 0 -> R.string.small_logging_some
                    else -> R.string.small_logging_start
                },
            ),
        )
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<LoggingWeek> = Holder(host, parent)

    private class Holder(host: CardHost, parent: ViewGroup) :
        FoodMetricView<LoggingWeek>(parent, R.id.food_logging_days_card, R.string.card_logging_days) {

        init {
            link.id = R.id.sleep_calories_link
            link.text = ctx.getString(R.string.sleep_calories_link)
            link.setOnClickListener { host.open(SleepCaloriesFragment()) }
            show(link, true)
            show(tile, false)
        }

        override fun bind(data: LoggingWeek) {
            val n = data.count
            val res = ctx.resources
            val line = when {
                n >= NICE_FROM -> res.getQuantityString(R.plurals.logging_days_nice, n, n)
                n > 0 -> res.getQuantityString(R.plurals.logging_days_count, n, n)
                data.ahead.first() || data.ahead.count { !it } <= 1 -> ctx.getString(R.string.logging_days_fresh)
                else -> ctx.getString(R.string.logging_days_resume)
            }
            show(empty, false)
            show(tile, false)
            setDots(data.logged, data.ahead)
            text(note, line)
            text(coverage, if (n > 0) ctx.getString(R.string.logging_days_note) else null)
            card.contentDescription = line
        }
    }
}
