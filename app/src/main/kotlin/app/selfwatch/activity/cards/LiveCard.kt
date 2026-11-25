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
import app.selfwatch.body.HeartCardLoader
import app.selfwatch.body.cards.HeartRateNowCard
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.model.time.SystemClock
import app.selfwatch.service.live.LiveReading
import app.selfwatch.shell.Views
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Fmt
import app.selfwatch.watch.LiveVitals
import app.selfwatch.ui.R as UiR

/**
 * Live (optional, Activity): heart rate and steps right now. While the card is on screen and a watch is
 * connected it asks for live readings ([LiveVitals.follow], the same lease as the Heart rate card; they
 * stop when the card scrolls away or the screen closes). A connected heart-rate strap's reading wins
 * ([LiveReading.hrSourceId]) and the card names it. Otherwise it shows the newest stored heart rate and
 * today's steps, with how old they are.
 */
object LiveCard : Card<LiveCard.Data>("live", CardTab.ACTIVITY, R.string.card_live, R.string.card_live_summary) {

    /**
     * The newest stored heart rate ([bpm] at [bpmSec]), today's stored [steps] and the devices' names by
     * id (to name the source of a live reading).
     */
    data class Data(val bpm: Int?, val bpmSec: Long?, val steps: Int?, val names: Map<Long, String>)

    override val tables: Set<Table> = setOf(Table.METRIC, Table.DAY_SUMMARY, Table.DEVICE)

    override val needs: Set<DataKind> = setOf(DataKind.LIVE)

    override suspend fun load(env: CardEnv): Data {
        val heart = HeartCardLoader(env.db, env.clock).now()
        val steps = env.db.summaries.day(env.clock.today())?.steps
        return Data(heart.latestBpm, heart.latestSec, steps, env.db.devices.all().associate { it.id to it.name })
    }

    override fun small(ctx: Context, data: Data): SmallFace? {
        val bpm = data.bpm ?: return null
        val now = SystemClock.nowSec()
        return SmallFace(
            value = bpm.toString(),
            unit = ctx.getString(R.string.unit_bpm),
            label = HeartRateNowCard.ageText(ctx, data.bpmSec ?: now, now, live = false),
            note = data.steps?.let { ctx.getString(R.string.live_steps_note, Fmt.number(it.toLong())) },
        )
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    /** What the large card shows at [nowSec] from the stored [data] and the newest [live] reading. */
    data class Shown(val bpm: Int?, val steps: Int?, val live: Boolean, val bpmSec: Long?, val source: String?, val strap: Boolean)

    /**
     * Merges [data] with [live] (used while it is at most [HeartRateNowCard.LIVE_SEC] old): live values
     * win; a strap's heart rate is named as the source.
     */
    fun shown(data: Data, live: LiveReading?, nowSec: Long): Shown {
        val l = live?.takeIf { nowSec - it.tSec in 0..HeartRateNowCard.LIVE_SEC }
        val liveBpm = l?.bpm
        val strapId = l?.hrSourceId?.takeIf { liveBpm != null }
        return Shown(
            bpm = liveBpm ?: data.bpm,
            steps = l?.steps ?: data.steps,
            live = l != null && (liveBpm != null || l.steps != null),
            bpmSec = if (liveBpm != null) l.tSec else data.bpmSec,
            source = l?.let { data.names[strapId ?: it.deviceId] },
            strap = strapId != null,
        )
    }

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<Data>(SwCard(parent.context)) {
        private val ctx: Context = parent.context
        private val heart = StatTile(ctx)
        private val stepsTile = StatTile(ctx)
        private val status = Views.caption(ctx)
        private val empty = EmptyState(ctx)
        private val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        private var stored: Data? = null
        private var live: LiveReading? = null

        init {
            val card = root as SwCard
            card.title = ctx.getString(R.string.card_live)
            heart.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
            stepsTile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
            row.addView(heart, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(stepsTile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row, Views.matchWrap(ctx))
            card.addView(status, Views.matchWrap(ctx, 8))
            empty.message = ctx.getString(R.string.live_empty)
            card.addView(empty, Views.matchWrap(ctx))
            for (v in listOf(heart, stepsTile, status)) v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            // Live readings only while this card is on screen (the gallery has no screen to follow).
            if (runCatching { host.fragment }.isSuccess) {
                LiveVitals.follow(host, root) { r ->
                    live = r
                    show()
                }
            }
        }

        override fun bind(data: Data) {
            stored = data
            show()
        }

        private fun show() {
            val data = stored ?: return
            val now = SystemClock.nowSec()
            val s = shown(data, live, now)
            val hasAny = s.bpm != null || s.steps != null
            row.visibility = if (hasAny) View.VISIBLE else View.GONE
            status.visibility = row.visibility
            empty.visibility = if (hasAny) View.GONE else View.VISIBLE
            if (!hasAny) {
                root.contentDescription = ctx.getString(R.string.live_empty)
                return
            }
            val dash = ctx.getString(R.string.dash)
            heart.value = s.bpm?.toString() ?: dash
            heart.unit = ctx.getString(R.string.unit_bpm)
            heart.label = ctx.getString(R.string.live_heart_rate)
            stepsTile.value = s.steps?.let { Fmt.number(it.toLong()) } ?: dash
            stepsTile.unit = null
            stepsTile.label = ctx.getString(R.string.live_steps_today)
            status.text = when {
                s.live && s.strap && s.source != null -> ctx.getString(R.string.live_from_strap, s.source)
                s.live && s.source != null -> ctx.getString(R.string.live_from_device, s.source)
                s.live -> ctx.getString(R.string.hrn_live)
                else -> ctx.getString(R.string.live_stored, HeartRateNowCard.ageText(ctx, s.bpmSec ?: now, now, live = false))
            }
            root.contentDescription = ctx.getString(
                R.string.live_description,
                listOf(heart.value, heart.unit, heart.label).joinToString(" "),
                listOf(stepsTile.value, stepsTile.label).joinToString(" "),
                status.text,
            )
        }
    }
}
