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
import app.selfwatch.cards.PictureFrame
import app.selfwatch.cards.SmallFace
import app.selfwatch.cards.TrendPicture
import app.selfwatch.db.Table
import app.selfwatch.me.ProfileFragment
import app.selfwatch.model.health.BodyKind
import app.selfwatch.ui.charts.LevelColors
import app.selfwatch.ui.components.Band
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import app.selfwatch.ui.R as UiR

/**
 * C5 Waist to height (card design standard): the latest waist ÷ the profile height as the number, tagged
 * with its NICE band (under 0.5 healthy, 0.5–0.59 raised, 0.6 and over high), on a range bar of those
 * bands. Without a height the card asks for it and opens Me › Profile; without a waist it offers to log
 * one. Small form: the number and the range bar. Opens the waist history.
 */
object WaistHeightCard : Card<WaistHeightCard.Data>(
    "waist_height", CardTab.BODY, R.string.card_waist_height, R.string.card_waist_height_summary,
) {
    /** The numbers and the display [units]. */
    data class Data(val info: WaistHeightInfo, val units: Units)

    override val tables: Set<Table> = setOf(Table.BODY, Table.SETTING)

    override suspend fun load(env: CardEnv): Data = Data(BodyTrendLoader(env.db, env.clock).waistHeight(), env.units)

    override val needs: Set<DataKind> = setOf(DataKind.MEASUREMENTS)

    /** The band's status and short name. */
    private fun band(ratioX100: Int): Pair<CardStatus, Int> = when (BodyCardMath.waistBand(ratioX100)) {
        WaistBand.UNDER -> CardStatus.GOOD to R.string.d2_wh_healthy
        WaistBand.RAISED -> CardStatus.CLOSE to R.string.d2_wh_raised
        WaistBand.HIGH -> CardStatus.OFF to R.string.d2_wh_high
    }

    /** The NICE range bar with [ratioX100] marked. */
    fun range(ctx: Context, ratioX100: Int): TrendPicture.Range {
        val c = LevelColors.of(ctx)
        return TrendPicture.Range(
            listOf(Band(0.5f, c.scaleGood), Band(0.6f, c.scaleClose), Band(MAX, c.scaleOff)),
            MIN, MAX, ratioX100 / 100f,
            listOf(0.5f to Fmt.number(0.5, 1), 0.6f to Fmt.number(0.6, 1)),
        )
    }

    override fun small(ctx: Context, data: Data): SmallFace? {
        val i = data.info
        val ratio = i.ratioX100 ?: return null
        if (i.waistX100 == null) return null
        val (status, name) = band(ratio)
        return SmallFace(
            value = Fmt.number(ratio / 100.0, 2),
            label = ctx.getString(name),
            status = status,
        )
    }

    override fun createSmallGraphic(ctx: Context): View = PictureFrame(ctx, small = true)

    override fun bindSmallGraphic(graphic: View, data: Data) {
        val r = data.info.ratioX100
        (graphic as PictureFrame).show(if (r == null || data.info.waistX100 == null) null else range(graphic.context, r))
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(MeasureDetailFragment.newInstance(BodyKind.WAIST, null))
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    private const val MIN = 0.35f
    private const val MAX = 0.8f

    private class Holder(private val host: CardHost, parent: ViewGroup) :
        CardView<Data>(CardView.inflate(parent, R.layout.card_body_waist_height)) {
        private var units = Units.METRIC
        private val tile = find<StatTile>(R.id.waist_height_tile)
        private val range = find<PictureFrame>(R.id.waist_height_range)
        private val empty = find<EmptyState>(R.id.waist_height_empty)

        init {
            root.setOnClickListener { host.open(MeasureDetailFragment.newInstance(BodyKind.WAIST, null)) }
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
        }

        override fun bind(data: Data) {
            val ctx = root.context
            units = data.units
            val i = data.info
            val ratio = i.ratioX100
            if (ratio == null || i.waistX100 == null) {
                tile.visibility = View.GONE
                range.show(null)
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
            tile.visibility = View.VISIBLE
            empty.visibility = View.GONE
            tile.value = Fmt.number(ratio / 100.0, 2)
            tile.unit = null
            tile.label = ctx.getString(band(ratio).second)
            range.show(range(ctx, ratio))
            val waist = ctx.getString(
                R.string.waist_height_detail,
                BodyUnits.format(BodyKind.WAIST, i.waistX100, units),
                BodyUnits.format(BodyKind.WAIST, (i.heightCm ?: 0) * 100, units),
            )
            root.contentDescription = ctx.getString(R.string.waist_height_description, tile.value, tile.label) + ". " + waist
        }
    }
}
