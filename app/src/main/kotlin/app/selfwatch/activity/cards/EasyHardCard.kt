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
import app.selfwatch.R
import app.selfwatch.activity.ActivityStatsLoader
import app.selfwatch.activity.HrZones
import app.selfwatch.activity.WorkoutListFragment
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.model.time.DayRange
import app.selfwatch.ui.charts.LevelColors
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.SplitBarView
import app.selfwatch.ui.components.SplitPart
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import java.util.Locale
import kotlin.math.roundToInt
import app.selfwatch.ui.R as UiR

/**
 * Easy/hard split (C21, card design standard): the share of workout time over the last 4 weeks under
 * zone 4 ([HrZones.easySec]) as a split bar, easy then hard, with a mark at the usual 75–80% easy.
 * Workouts without heart rate are left out. Small form: the split bar. Opens Workouts.
 */
object EasyHardCard : Card<EasyHardCard.Data>("easy_hard", CardTab.ACTIVITY, R.string.card_easy_hard, R.string.card_easy_hard_summary) {

    /** Days looked at. */
    const val DAYS: Int = 28

    /**
     * @property easySec time below zone 4 ([zone4Bpm], else 80% of [maxHrBpm]); [hardSec] in zone 4 and up.
     * @property workouts workouts with heart rate counted.
     */
    data class Data(val easySec: Int, val hardSec: Int, val workouts: Int, val maxHrBpm: Int, val zone4Bpm: Int? = null) {
        /** Easy share in percent, or null without time. */
        val easyPct: Int? get() = (easySec + hardSec).takeIf { it > 0 }?.let { (easySec * 100.0 / it).roundToInt() }

        /** Heart rate below which time is easy. */
        val easyBelowBpm: Int get() = zone4Bpm ?: (maxHrBpm * HrZones.EASY_BELOW_PCT / 100)
    }

    /** The mark on the split bar: the middle of the usual 75–80% easy. */
    val USUAL_MARK: Float = (HrZones.EASY_REF_LOW_PCT + HrZones.EASY_REF_HIGH_PCT) / 200f

    override val tables: Set<Table> = setOf(Table.WORKOUT, Table.METRIC, Table.SETTING)

    override suspend fun load(env: CardEnv): Data {
        val loader = ActivityStatsLoader(env.db, env.clock)
        val zones = loader.zoneSet()
        val sessions = loader.zones(loader.workouts(DayRange(env.day.plusDays(-(DAYS - 1)), env.day)), zones).filter { it.zoneSec != null }
        return Data(
            sessions.sumOf { s -> s.zoneSec?.let { HrZones.easySec(it) } ?: 0 },
            sessions.sumOf { s -> s.zoneSec?.let { HrZones.hardSec(it) } ?: 0 },
            sessions.size,
            zones.maxHr,
            zones.bounds[3],
        )
    }

    override val needs: Set<DataKind> = setOf(DataKind.WORKOUTS)

    override fun small(ctx: Context, data: Data): SmallFace? {
        val pct = data.easyPct ?: return null
        // Close to the usual mix is on track; much harder or almost all easy is close.
        val mix = pct >= HrZones.EASY_REF_LOW_PCT - MARGIN_PCT && pct <= ALL_EASY_PCT
        return SmallFace(
            value = Fmt.percent(pct / 100.0, 0, Locale.getDefault()),
            label = ctx.getString(R.string.tag_easy),
            status = if (mix) CardStatus.GOOD else CardStatus.CLOSE,
        )
    }

    override fun createSmallGraphic(ctx: Context): View = ActivityVisuals.smallSplit(ctx)

    override fun bindSmallGraphic(graphic: View, data: Data) = split(graphic as SplitBarView, data, labels = false)

    /** Easy (goal color) then hard (Garmin's orange), with the usual-mix mark; labeled with their time when [labels]. */
    private fun split(bar: SplitBarView, data: Data, labels: Boolean) {
        val ctx = bar.context
        val c = LevelColors.of(ctx)
        val loc = Locale.getDefault()
        val fmt = FmtLabels.from(ctx)
        fun label(name: Int, sec: Int) = if (labels) ctx.getString(R.string.part_value, ctx.getString(name), Fmt.duration(sec.toLong(), loc, fmt)) else null
        bar.parts = listOf(
            SplitPart(data.easySec.toFloat(), c.good, label(R.string.part_easy, data.easySec)),
            SplitPart(data.hardSec.toFloat(), c.stressMedium, label(R.string.part_hard, data.hardSec)),
        )
        bar.mark = USUAL_MARK
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(WorkoutListFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    private const val ALL_EASY_PCT = 90
    private const val MARGIN_PCT = 5

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_activity_easy_hard)) {
        private val tile = find<StatTile>(R.id.eh_tile)
        private val bar = find<SplitBarView>(R.id.eh_split)
        private val empty = find<EmptyState>(R.id.eh_empty)

        init {
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
            root.setOnClickListener { host.open(WorkoutListFragment()) }
        }

        override fun bind(data: Data) {
            val ctx = root.context
            val pct = data.easyPct
            ActivityVisuals.show(pct != null, tile, bar)
            ActivityVisuals.show(pct == null, empty)
            if (pct == null) {
                root.contentDescription = empty.message
                return
            }
            val loc = Locale.getDefault()
            val fmt = FmtLabels.from(ctx)
            ActivityVisuals.number(tile, Fmt.percent(pct / 100.0, 0, loc), null, ctx.getString(R.string.tag_easy))
            split(bar, data, labels = true)
            root.contentDescription = ctx.getString(
                R.string.design_easy_hard_description, tile.value,
                Fmt.duration(data.easySec.toLong(), loc, fmt), Fmt.duration(data.hardSec.toLong(), loc, fmt),
                Fmt.number(HrZones.EASY_REF_LOW_PCT.toLong()), Fmt.number(HrZones.EASY_REF_HIGH_PCT.toLong()),
            )
        }
    }
}
