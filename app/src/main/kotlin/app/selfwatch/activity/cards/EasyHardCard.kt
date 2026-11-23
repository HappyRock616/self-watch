/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity.cards

import android.view.ViewGroup
import app.selfwatch.R
import app.selfwatch.activity.ActivityStatsLoader
import app.selfwatch.activity.HrZones
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.db.Table
import app.selfwatch.model.time.DayRange
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Easy/hard split (C21): the share of workout time over the last 4 weeks under 80% of maximum heart
 * rate ([HrZones.easySec]) against the usual 75–80% easy. Workouts without heart rate are left out.
 */
object EasyHardCard : Card<EasyHardCard.Data>("easy_hard", CardTab.ACTIVITY, R.string.card_easy_hard, R.string.card_easy_hard_summary) {

    /** Days looked at. */
    const val DAYS: Int = 28

    /**
     * @property easySec time under 80% of [maxHrBpm]; [hardSec] at or above it.
     * @property workouts workouts with heart rate counted.
     */
    data class Data(val easySec: Int, val hardSec: Int, val workouts: Int, val maxHrBpm: Int) {
        /** Easy share in percent, or null without time. */
        val easyPct: Int? get() = (easySec + hardSec).takeIf { it > 0 }?.let { (easySec * 100.0 / it).roundToInt() }

        /** Heart rate below which time is easy. */
        val easyBelowBpm: Int get() = maxHrBpm * HrZones.EASY_BELOW_PCT / 100
    }

    override val tables: Set<Table> = setOf(Table.WORKOUT, Table.METRIC, Table.SETTING)

    override suspend fun load(env: CardEnv): Data {
        val loader = ActivityStatsLoader(env.db, env.clock)
        val maxHr = loader.maxHr()
        val sessions = loader.zones(loader.workouts(DayRange(env.day.plusDays(-(DAYS - 1)), env.day)), maxHr.bpm).filter { it.zoneSec != null }
        return Data(
            sessions.sumOf { s -> s.zoneSec?.let { HrZones.easySec(it) } ?: 0 },
            sessions.sumOf { s -> s.zoneSec?.let { HrZones.hardSec(it) } ?: 0 },
            sessions.size,
            maxHr.bpm,
        )
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(parent)

    private const val ALL_EASY_PCT = 90

    private class Holder(parent: ViewGroup) : StatCardView<Data>(
        parent, R.layout.card_activity_easy_hard,
        StatIds(R.id.eh_tile, R.id.eh_bar, 0, R.id.eh_caption, R.id.eh_note, R.id.eh_empty),
    ) {
        override fun bind(data: Data) {
            val ctx = root.context
            val pct = data.easyPct
            showEmpty(pct == null)
            if (pct == null) return
            val loc = Locale.getDefault()
            val labels = FmtLabels.from(ctx)
            tile.value = Fmt.percent(pct / 100.0, 0, loc)
            tile.unit = null
            tile.label = ctx.getString(R.string.eh_label, Fmt.number(HrZones.EASY_REF_LOW_PCT.toLong()), Fmt.number(HrZones.EASY_REF_HIGH_PCT.toLong()))
            progress(pct, PERCENT)
            val hint = ctx.getString(
                when {
                    pct < HrZones.EASY_REF_LOW_PCT - MARGIN_PCT -> R.string.eh_hint_hard
                    pct > ALL_EASY_PCT -> R.string.eh_hint_easy
                    else -> R.string.eh_hint_mix
                },
            )
            val counts = ctx.resources.getQuantityString(
                R.plurals.eh_caption, data.workouts,
                Fmt.duration(data.easySec.toLong(), loc, labels), Fmt.duration(data.hardSec.toLong(), loc, labels), Fmt.number(data.workouts.toLong()),
            )
            caption.text = ctx.getString(R.string.eh_caption_hint, counts, hint)
            note.text = ctx.getString(R.string.eh_note, Fmt.number(data.easyBelowBpm.toLong()))
            root.contentDescription = ctx.getString(R.string.eh_description, tile.value)
        }

        companion object {
            const val PERCENT = 100
            const val MARGIN_PCT = 5
        }
    }
}
