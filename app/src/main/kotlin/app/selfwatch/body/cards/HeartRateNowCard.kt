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
import app.selfwatch.body.HeartCardLoader
import app.selfwatch.body.HeartDetailFragment
import app.selfwatch.body.HeartNowInfo
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.SystemClock
import app.selfwatch.service.live.LiveReading
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.Sparkline
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import app.selfwatch.watch.LiveVitals
import app.selfwatch.ui.R as UiR

/**
 * Heart rate now: live from the watch while this card is on screen and the watch is connected (the live
 * readings are turned on only then, see [LiveVitals]); otherwise the newest stored reading and how long
 * ago it was taken ("62 bpm · 3 minutes ago"). Below: today's low, high and resting heart rate and a
 * sparkline of the last 24 hours. Opens Heart (the full day with a day picker).
 */
object HeartRateNowCard : Card<HeartNowInfo>("heart_rate_now", CardTab.BODY, R.string.card_heart_rate_now, R.string.card_heart_rate_now_summary) {

    override val tables: Set<Table> = setOf(Table.METRIC, Table.DAY_SUMMARY)

    override suspend fun load(env: CardEnv): HeartNowInfo = HeartCardLoader(env.db, env.clock).now()

    override val needs: Set<DataKind> = setOf(DataKind.HEART_RATE, DataKind.RESTING_HR)

    override fun small(ctx: Context, data: HeartNowInfo): SmallFace? {
        val bpm = data.latestBpm ?: return null
        val now = SystemClock.nowSec()
        return SmallFace(
            value = bpm.toString(),
            label = ageText(ctx, data.latestSec ?: now, now, live = false),
            unit = ctx.getString(R.string.unit_bpm),
            note = data.restingBpm?.let { ctx.getString(R.string.small_hr_resting, it.toString()) },
        )
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(HeartDetailFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<HeartNowInfo> = Holder(host, parent)

    /** A live reading this old or newer counts as live. */
    const val LIVE_SEC: Long = 20L

    /**
     * The line under the number: live, "just now", minutes or hours ago, else the date and time of
     * the reading at [tSec] (now is [nowSec]).
     */
    fun ageText(ctx: Context, tSec: Long, nowSec: Long, live: Boolean): String {
        val ago = (nowSec - tSec).coerceAtLeast(0)
        return when {
            live -> ctx.getString(R.string.hrn_live)
            ago < MINUTE -> ctx.getString(R.string.hrn_just_now)
            ago < HOUR -> ctx.resources.getQuantityString(R.plurals.hrn_minutes_ago, (ago / MINUTE).toInt(), (ago / MINUTE).toInt())
            ago < HOURS_SHOWN * HOUR -> ctx.resources.getQuantityString(R.plurals.hrn_hours_ago, (ago / HOUR).toInt(), (ago / HOUR).toInt())
            else -> ctx.getString(R.string.hrn_at, Fmt.day(ctx, Fmt.dateToDay(java.time.Instant.ofEpochSecond(tSec).atZone(java.time.ZoneId.systemDefault()).toLocalDate())) + " " + Fmt.time(ctx, tSec))
        }
    }

    private const val MINUTE = 60L
    private const val HOUR = 3_600L
    private const val HOURS_SHOWN = 12L

    private class Holder(host: CardHost, parent: ViewGroup, private val clock: Clock = SystemClock) :
        CardView<HeartNowInfo>(CardView.inflate(parent, R.layout.card_body_heart_rate_now)) {
        private val tile = find<StatTile>(R.id.hrn_tile)
        private val today = find<TextView>(R.id.hrn_today)
        private val spark = find<Sparkline>(R.id.hrn_spark)
        private val sparkCaption = find<TextView>(R.id.hrn_spark_caption)
        private val empty = find<EmptyState>(R.id.hrn_empty)
        private var stored: HeartNowInfo? = null
        private var live: LiveReading? = null

        init {
            root.setOnClickListener { host.open(HeartDetailFragment()) }
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
            spark.color = ChartPalette.of(root.context).primary
            LiveVitals.follow(host, root) { r ->
                live = r
                show()
            }
        }

        override fun bind(data: HeartNowInfo) {
            stored = data
            show()
        }

        private fun show() {
            val ctx = root.context
            val data = stored ?: return
            val now = clock.nowSec()
            val l = live?.takeIf { it.bpm != null && now - it.tSec <= LIVE_SEC }
            val bpm = l?.bpm ?: data.latestBpm
            val hasAny = bpm != null
            for (v in listOf(tile, today, spark, sparkCaption)) v.visibility = if (hasAny) View.VISIBLE else View.GONE
            empty.visibility = if (hasAny) View.GONE else View.VISIBLE
            if (bpm == null) {
                root.contentDescription = ctx.getString(R.string.hrn_empty)
                return
            }
            tile.value = bpm.toString()
            tile.unit = ctx.getString(R.string.unit_bpm)
            tile.label = ageText(ctx, l?.tSec ?: data.latestSec ?: now, now, live = l != null)
            val dash = ctx.getString(R.string.dash)
            val resting = data.restingBpm ?: l?.restingBpm
            today.text = ctx.getString(
                R.string.hrn_today,
                data.lowBpm?.toString() ?: dash,
                data.highBpm?.toString() ?: dash,
                resting?.toString() ?: dash,
            )
            spark.values = data.spark.toIntArray()
            val showSpark = data.spark.size >= 2
            spark.visibility = if (showSpark) View.VISIBLE else View.GONE
            sparkCaption.visibility = spark.visibility
            sparkCaption.setText(R.string.hrn_spark_caption)
            root.contentDescription = ctx.getString(R.string.hrn_description, tile.value, tile.label, today.text)
        }
    }
}
