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
import app.selfwatch.body.BodyCardMath
import app.selfwatch.body.BodyTrendLoader
import app.selfwatch.body.RangeStatus
import app.selfwatch.body.SleepDetailFragment
import app.selfwatch.body.SleepInfo
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.CardVisuals
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.model.health.SleepStage
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.SplitBarView
import app.selfwatch.ui.components.SplitPart
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import java.util.Locale
import app.selfwatch.ui.R as UiR

/**
 * C16 Sleep (card design standard): last night's time asleep as one big number, the night's stages as a
 * timeline (awake, REM, light, deep) with bedtime and wake time, and a split bar of the time in each
 * stage. Small form: the duration and the split bar. Opens Sleep. (The older `recovery` card stays
 * registered for saved layouts.)
 */
object SleepCard : Card<SleepInfo>("sleep", CardTab.BODY, R.string.card_sleep, R.string.card_sleep_summary) {

    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY, Table.SLEEP_SESSION, Table.SLEEP_STAGE)

    override suspend fun load(env: CardEnv): SleepInfo = BodyTrendLoader(env.db, env.clock).sleep()

    override val needs: Set<DataKind> = setOf(DataKind.SLEEP)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override fun small(ctx: Context, data: SleepInfo): SmallFace? {
        if (data.isEmpty) return null
        val last = data.lastMin
        if (last == null) {
            val avg = data.avgMin ?: return null
            return SmallFace(value = duration(ctx, avg), label = ctx.getString(R.string.small_seven_night_avg))
        }
        val status = when (BodyCardMath.sleepStatus(last)) {
            RangeStatus.WITHIN -> CardStatus.GOOD
            RangeStatus.BELOW, RangeStatus.ABOVE -> CardStatus.CLOSE
        }
        return SmallFace(value = duration(ctx, last), label = nightLabel(ctx, data), status = status)
    }

    override fun createSmallGraphic(ctx: Context): View = SplitBarView(ctx)

    override fun bindSmallGraphic(graphic: View, data: SleepInfo) {
        val parts = stageParts(graphic.context, data, labels = false)
        (graphic as SplitBarView).parts = parts
        CardVisuals.show(graphic, data.lastMin != null && parts.isNotEmpty())
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(SleepDetailFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<SleepInfo> = Holder(host, parent)

    /** "7 h 40 min". */
    fun duration(ctx: Context, minutes: Int): String = Fmt.duration(minutes * 60L, Locale.getDefault(), FmtLabels.from(ctx))

    /** "Last night", or the day the night ended when that is not today. */
    private fun nightLabel(ctx: Context, data: SleepInfo): String {
        val today = Fmt.dateToDay(java.time.LocalDate.now())
        return data.lastDay?.takeIf { it.yyyymmdd != today }?.let { Fmt.day(ctx, it.yyyymmdd) } ?: ctx.getString(R.string.sleep_card_last_night)
    }

    /** The latest night's stages as split-bar parts (deep, light, REM, awake), empty without stages. */
    fun stageParts(ctx: Context, data: SleepInfo, labels: Boolean): List<SplitPart> {
        val s = data.stageMin
        if (s.values.sum() <= 0) return emptyList()
        val palette = ChartPalette.of(ctx)
        return listOf(
            SleepStage.DEEP to R.string.body1_stage_deep,
            SleepStage.LIGHT to R.string.body1_stage_light,
            SleepStage.REM to R.string.body1_stage_rem,
            SleepStage.AWAKE to R.string.body1_stage_awake,
        ).map { (stage, label) ->
            val min = s[stage] ?: 0
            SplitPart(min.toFloat(), palette.stage(stage), if (labels && stage != SleepStage.AWAKE) ctx.getString(label, duration(ctx, min)) else null)
        }
    }

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<SleepInfo>(CardView.inflate(parent, R.layout.card_body_sleep)) {
        private val tile = find<StatTile>(R.id.sleep_night_tile)
        private val timeline = find<SleepStagesView>(R.id.sleep_night_timeline)
        private val split = find<SplitBarView>(R.id.sleep_night_split)
        private val empty = find<EmptyState>(R.id.sleep_night_empty)

        init {
            root.setOnClickListener { host.open(SleepDetailFragment()) }
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
        }

        override fun bind(data: SleepInfo) {
            val ctx = root.context
            val isEmpty = data.isEmpty
            CardVisuals.show(empty, isEmpty)
            for (v in listOf(tile, timeline, split)) CardVisuals.show(v, !isEmpty)
            if (isEmpty) {
                root.contentDescription = ctx.getString(R.string.sleep_card_empty)
                return
            }
            val last = data.lastMin
            if (last != null) {
                tile.value = duration(ctx, last)
                tile.label = nightLabel(ctx, data)
            } else {
                tile.value = data.avgMin?.let { duration(ctx, it) } ?: ctx.getString(R.string.dash)
                tile.label = ctx.getString(R.string.small_seven_night_avg)
            }
            val start = data.startSec
            val end = data.endSec
            val hasTimeline = last != null && data.timeline.isNotEmpty() && start != null && end != null && end > start
            CardVisuals.show(timeline, hasTimeline)
            if (hasTimeline) {
                timeline.stages = data.timeline
                timeline.startSec = start
                timeline.endSec = end
                timeline.startLabel = Fmt.time(ctx, start)
                timeline.endLabel = Fmt.time(ctx, end)
            }
            val parts = if (last == null) emptyList() else stageParts(ctx, data, labels = true)
            split.parts = parts
            CardVisuals.show(split, parts.isNotEmpty())
            val stagesText = if (parts.isEmpty()) {
                ""
            } else {
                val s = data.stageMin
                ctx.getString(
                    R.string.sleep_card_stages,
                    duration(ctx, s[SleepStage.DEEP] ?: 0),
                    duration(ctx, s[SleepStage.LIGHT] ?: 0),
                    duration(ctx, s[SleepStage.REM] ?: 0),
                    duration(ctx, s[SleepStage.AWAKE] ?: 0),
                )
            }
            root.contentDescription = ctx.getString(R.string.body1_sleep_description, tile.value, tile.label, stagesText)
        }
    }
}
