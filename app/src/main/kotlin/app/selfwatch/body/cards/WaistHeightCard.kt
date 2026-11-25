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
import app.selfwatch.body.BodyCardMath
import app.selfwatch.body.BodyTrendLoader
import app.selfwatch.body.BodyUnits
import app.selfwatch.body.MeasureDetailFragment
import app.selfwatch.body.MeasureEditor
import app.selfwatch.body.WaistBand
import app.selfwatch.body.WaistHeightInfo
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.me.ProfileFragment
import app.selfwatch.model.health.BodyKind
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import kotlin.math.abs
import app.selfwatch.ui.R as UiR

/**
 * C5 Waist to height: the latest waist ÷ the profile height against the 0.5 guide (0.5–0.59 raised,
 * 0.6 and over high), with the waist's 30-day change and BMI beneath. Without a height the card asks
 * for it and opens Me › Profile; without a waist it offers to log one. Opens the waist history.
 */
object WaistHeightCard : Card<WaistHeightCard.Data>(
    "waist_height", CardTab.BODY, R.string.card_waist_height, R.string.card_waist_height_summary,
) {
    /** The numbers and the display [units]. */
    data class Data(val info: WaistHeightInfo, val units: Units)

    override val tables: Set<Table> = setOf(Table.BODY, Table.SETTING)

    override suspend fun load(env: CardEnv): Data = Data(BodyTrendLoader(env.db, env.clock).waistHeight(), env.units)

    override val needs: Set<DataKind> = setOf(DataKind.MEASUREMENTS)

    override fun small(ctx: Context, data: Data): SmallFace? {
        val i = data.info
        val ratio = i.ratioX100 ?: return null
        if (i.waistX100 == null) return null
        val (status, note) = when (BodyCardMath.waistBand(ratio)) {
            WaistBand.UNDER -> CardStatus.GOOD to R.string.small_wh_under
            WaistBand.RAISED -> CardStatus.CLOSE to R.string.small_wh_raised
            WaistBand.HIGH -> CardStatus.OFF to R.string.small_wh_high
        }
        return SmallFace(
            value = Fmt.number(ratio / 100.0, 2),
            label = ctx.getString(R.string.small_waist_height_label),
            status = status,
            note = ctx.getString(note),
        )
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(MeasureDetailFragment.newInstance(BodyKind.WAIST, null))
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    private class Holder(private val host: CardHost, parent: ViewGroup) :
        CardView<Data>(CardView.inflate(parent, R.layout.card_body_waist_height)) {
        private var units = Units.METRIC

        init {
            root.setOnClickListener { host.open(MeasureDetailFragment.newInstance(BodyKind.WAIST, null)) }
        }

        override fun bind(data: Data) {
            val ctx = root.context
            units = data.units
            val i = data.info
            val tile = find<StatTile>(R.id.waist_height_tile)
            val band = find<TextView>(R.id.waist_height_band)
            val detail = find<TextView>(R.id.waist_height_detail)
            val bmi = find<TextView>(R.id.waist_height_bmi)
            val guide = find<TextView>(R.id.waist_height_guide)
            val empty = find<EmptyState>(R.id.waist_height_empty)
            val ratio = i.ratioX100
            val content = listOf(tile, band, detail, bmi, guide)
            if (ratio == null || i.waistX100 == null) {
                content.forEach { it.visibility = View.GONE }
                empty.visibility = View.VISIBLE
                if (i.heightCm == null) {
                    empty.message = ctx.getString(R.string.waist_height_need_height)
                    empty.buttonText = ctx.getString(R.string.waist_height_add_height)
                    empty.onButton = { host.open(ProfileFragment()) }
                } else {
                    empty.message = ctx.getString(R.string.waist_height_need_waist)
                    empty.buttonText = ctx.getString(R.string.waist_height_log_waist)
                    empty.onButton = { MeasureEditor.log(host.fragment, BodyKind.WAIST, units) }
                }
                root.contentDescription = empty.message
                return
            }
            content.forEach { it.visibility = View.VISIBLE }
            empty.visibility = View.GONE
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
            tile.value = Fmt.number(ratio / 100.0, 2)
            tile.unit = null
            band.setText(
                when (BodyCardMath.waistBand(ratio)) {
                    WaistBand.UNDER -> R.string.waist_height_under
                    WaistBand.RAISED -> R.string.waist_height_raised
                    WaistBand.HIGH -> R.string.waist_height_high
                },
            )
            val height = i.heightCm ?: 0
            val parts = ArrayList<String>()
            parts += ctx.getString(
                R.string.waist_height_detail,
                BodyUnits.format(BodyKind.WAIST, i.waistX100, units),
                BodyUnits.format(BodyKind.WAIST, height * 100, units),
            )
            i.changeX100?.takeIf { it != 0 }?.let { c ->
                val n = BodyUnits.toDisplay(BodyKind.WAIST, abs(c), units)
                val text = (if (c < 0) "−" else "+") + Fmt.number(n, 1) + " " + BodyUnits.unit(BodyKind.WAIST, units)
                parts += ctx.getString(R.string.waist_height_change, text)
            }
            detail.text = parts.joinToString(" · ")
            val b = i.bmi
            bmi.text = b?.let { ctx.getString(R.string.waist_height_bmi, Fmt.number(it, 1)) }
            bmi.visibility = if (b == null) View.GONE else View.VISIBLE
            root.contentDescription = ctx.getString(R.string.waist_height_description, tile.value, band.text)
        }
    }
}
