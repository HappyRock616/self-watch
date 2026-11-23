/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body.cards

import android.view.View
import android.view.ViewGroup
import app.selfwatch.R
import app.selfwatch.body.BodyLoader
import app.selfwatch.body.HeartDetailFragment
import app.selfwatch.body.Recovery
import app.selfwatch.body.SleepDetailFragment
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.db.Table
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import java.util.Locale

/** Sleep, resting heart rate and HRV side by side; open Sleep and Heart. */
object RecoveryCard : Card<Recovery>("recovery", CardTab.BODY, R.string.card_recovery, R.string.card_recovery_summary) {

    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY, Table.METRIC, Table.SLEEP_SESSION)

    override suspend fun load(env: CardEnv): Recovery = BodyLoader(env.db, env.clock).recovery()

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Recovery> = Holder(host, parent)

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<Recovery>(CardView.inflate(parent, R.layout.card_body_recovery)) {
        init {
            find<View>(R.id.sleep_card).setOnClickListener { host.open(SleepDetailFragment()) }
            find<View>(R.id.rest_hr_card).setOnClickListener { host.open(HeartDetailFragment()) }
            find<View>(R.id.hrv_card).setOnClickListener { host.open(HeartDetailFragment()) }
        }

        override fun bind(data: Recovery) {
            val ctx = root.context
            val sleep = find<StatTile>(R.id.sleep_tile)
            sleep.value = data.sleepMin?.let { Fmt.duration(it * 60L, Locale.getDefault(), FmtLabels.from(ctx)) } ?: ctx.getString(R.string.dash)
            val hr = find<StatTile>(R.id.rest_hr_tile)
            hr.value = data.restingHr?.toString() ?: ctx.getString(R.string.dash)
            hr.unit = if (data.restingHr != null) ctx.getString(R.string.unit_bpm) else null
            val hrv = find<StatTile>(R.id.hrv_tile)
            hrv.value = data.hrvMs?.toString() ?: ctx.getString(R.string.dash)
            hrv.unit = if (data.hrvMs != null) ctx.getString(R.string.unit_ms) else null
        }
    }
}
