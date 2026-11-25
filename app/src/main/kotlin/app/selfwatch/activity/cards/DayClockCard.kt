/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity.cards

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.selfwatch.R
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.SystemClock
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.R as UiR

/**
 * Day clock (optional, Activity): the day shown as a 24-hour circle of sleep, rest, light movement and
 * active time, from the watch's minute data and sleep ([DayClockMath]). The small form is the ring with
 * the active-hours count.
 */
object DayClockCard : Card<DayClockCard.Data>("day_clock", CardTab.ACTIVITY, R.string.card_day_clock, R.string.card_day_clock_summary) {

    /** The [clock] and when it was loaded ([nowSec], for the "now" mark). */
    data class Data(val clock: DayClock, val nowSec: Long)

    override val tables: Set<Table> = setOf(Table.ACTIVITY, Table.SLEEP_SESSION)

    override val needs: Set<DataKind> = setOf(DataKind.STEPS, DataKind.SLEEP, DataKind.ACTIVE_TIME)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override suspend fun load(env: CardEnv): Data {
        val zone = env.clock.zone()
        val range = env.day.toTimeRange(zone)
        val minutes = env.db.activity.minutes(range)
        // Sessions that end today or tomorrow cover every one that overlaps today.
        val sleep = env.db.sleep.sessions(DayRange(env.day, env.day.plusDays(1)), zone).filter { it.startSec < range.endSec && it.endSec > range.startSec }
        val now = env.clock.nowSec()
        return Data(DayClockMath.build(range, minutes, sleep, now), now)
    }

    override fun small(ctx: Context, data: Data): SmallFace? {
        val c = data.clock
        if (c.isEmpty) return null
        return SmallFace(
            value = Fmt.number(c.activeHours.toLong()),
            label = ctx.resources.getQuantityString(R.plurals.day_clock_active_hours, c.activeHours),
            note = if (c.sleepMin > 0) ctx.getString(R.string.day_clock_sleep_note, Fmt.duration(c.sleepMin * MINUTE)) else null,
        )
    }

    override fun createSmallGraphic(ctx: Context): View = DayClockView(ctx, compact = true)

    override fun bindSmallGraphic(graphic: View, data: Data) {
        val v = graphic as DayClockView
        v.clock = data.clock
        v.nowSec = data.nowSec
        v.centerText = null
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(parent)

    /** "Active 1 h 10 min · Light 3 h · Rest 9 h · Sleep 7 h 20 min" for TalkBack. */
    fun describe(ctx: Context, c: DayClock): String = ctx.getString(
        R.string.day_clock_description,
        ctx.resources.getQuantityString(R.plurals.day_clock_active_hours_count, c.activeHours, c.activeHours),
        Fmt.duration(c.activeMin * MINUTE),
        Fmt.duration(c.lightMin * MINUTE),
        Fmt.duration(c.restMin * MINUTE),
        Fmt.duration(c.sleepMin * MINUTE),
    )

    private const val MINUTE = 60L

    private class Holder(parent: ViewGroup) : CardView<Data>(SwCard(parent.context)) {
        private val ctx: Context = parent.context
        private val clockView = DayClockView(ctx)
        private val legend = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        private val empty = EmptyState(ctx)
        private val rows = HashMap<ClockPart, TextView>()

        init {
            val card = root as SwCard
            card.title = ctx.getString(R.string.card_day_clock)
            clockView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            card.addView(clockView, Views.matchWrap(ctx, 8))
            val palette = ChartPalette.of(ctx)
            for (part in LEGEND) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val size = ctx.resources.getDimensionPixelSize(R.dimen.card_status_dot)
                row.addView(
                    View(ctx).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(DayClockView.colorOf(palette, part) ?: palette.track)
                        }
                    },
                    LinearLayout.LayoutParams(size, size).apply { marginEnd = ctx.resources.getDimensionPixelSize(UiR.dimen.sw_space_1) },
                )
                val text = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body)
                row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                rows[part] = text
                legend.addView(row, Views.matchWrap(ctx, 4))
            }
            card.addView(legend, Views.matchWrap(ctx, 8))
            empty.message = ctx.getString(R.string.day_clock_empty)
            card.addView(empty, Views.matchWrap(ctx))
            importantForAccessibility(legend)
        }

        private fun importantForAccessibility(v: View) {
            v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        override fun bind(data: Data) {
            val c = data.clock
            val none = c.isEmpty
            clockView.visibility = if (none) View.GONE else View.VISIBLE
            legend.visibility = clockView.visibility
            empty.visibility = if (none) View.VISIBLE else View.GONE
            if (none) {
                root.contentDescription = ctx.getString(R.string.day_clock_empty)
                return
            }
            clockView.clock = c
            clockView.nowSec = if (data.nowSec > 0) data.nowSec else SystemClock.nowSec()
            clockView.centerText = Fmt.number(c.activeHours.toLong())
            clockView.subText = ctx.resources.getQuantityString(R.plurals.day_clock_active_hours, c.activeHours)
            rows[ClockPart.ACTIVE]?.text = ctx.getString(R.string.day_clock_active, Fmt.duration(c.activeMin * MINUTE))
            rows[ClockPart.LIGHT]?.text = ctx.getString(R.string.day_clock_light, Fmt.duration(c.lightMin * MINUTE))
            rows[ClockPart.REST]?.text = ctx.getString(R.string.day_clock_rest, Fmt.duration(c.restMin * MINUTE))
            rows[ClockPart.SLEEP]?.text = ctx.getString(R.string.day_clock_sleep, Fmt.duration(c.sleepMin * MINUTE))
            root.contentDescription = describe(ctx, c)
        }
    }

    private val LEGEND = listOf(ClockPart.ACTIVE, ClockPart.LIGHT, ClockPart.REST, ClockPart.SLEEP)
}
