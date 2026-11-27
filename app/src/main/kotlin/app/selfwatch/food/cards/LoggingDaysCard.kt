/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.cards

import android.content.Context
import android.view.View
import android.view.ViewGroup
import app.selfwatch.R
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.PictureFrame
import app.selfwatch.cards.SmallFace
import app.selfwatch.cards.TrendPicture
import app.selfwatch.db.Table
import app.selfwatch.food.SleepCaloriesFragment
import app.selfwatch.ui.format.Fmt
import java.time.format.TextStyle
import java.util.Locale

/**
 * Logging days (C4, card design standard): the days of the week holding the day shown as seven day dots,
 * filled when the diary has entries, with the count as the number. Gentle: never a broken-streak alarm.
 * Tap opens the sleep and calories chart (O2). Small form: the number and the dots.
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

    /** The week as seven day dots with day letters. */
    fun picture(data: LoggingWeek): TrendPicture = TrendPicture.Dots(
        data.logged.toBooleanArray(),
        data.days.map { d -> Fmt.dayToDate(d.yyyymmdd).dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) },
    )

    override fun createSmallGraphic(ctx: Context): View = PictureFrame(ctx, small = true)

    override fun bindSmallGraphic(graphic: View, data: LoggingWeek) {
        (graphic as PictureFrame).show(picture(data))
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(SleepCaloriesFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<LoggingWeek> = Holder(host, parent)

    private class Holder(host: CardHost, parent: ViewGroup) :
        FoodMetricView<LoggingWeek>(parent, R.id.food_logging_days_card, R.string.card_logging_days) {

        init {
            card.isClickable = true
            card.setOnClickListener { host.open(SleepCaloriesFragment()) }
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
            tile.value = n.toString()
            tile.unit = ctx.getString(R.string.goal_lines_of_count, data.days.size)
            tile.label = ctx.getString(R.string.d2_tag_days_logged)
            showContent(picture(data))
            card.contentDescription = line
        }
    }
}
