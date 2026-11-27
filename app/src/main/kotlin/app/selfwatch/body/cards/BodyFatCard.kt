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
import app.selfwatch.body.BodyFatInfo
import app.selfwatch.body.BodyTrendLoader
import app.selfwatch.body.GarminBands
import app.selfwatch.body.MeasureDetailFragment
import app.selfwatch.body.MeasureEditor
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.PictureFrame
import app.selfwatch.cards.SmallFace
import app.selfwatch.cards.TrendPicture
import app.selfwatch.cards.TrendPictures
import app.selfwatch.db.Table
import app.selfwatch.model.food.Sex
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import app.selfwatch.ui.charts.LevelColors
import app.selfwatch.ui.components.Band
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import java.time.ZoneId
import kotlin.math.abs
import app.selfwatch.ui.R as UiR

/**
 * C6 Body fat (card design standard): the smoothed trend as the number tagged with its ACE band (as Garmin
 * Index scales name it, by sex), a range bar of the ACE bands with the trend marked, and a mini trend line
 * of 90 days with dated ends. Small form: the number and the range bar. Opens the body fat history with
 * the protein and strength overlay (O3).
 */
object BodyFatCard : Card<BodyFatCard.Data>("body_fat", CardTab.BODY, R.string.card_body_fat, R.string.card_body_fat_summary) {

    /**
     * The trend (null with no readings), the display [units] (for logging), [sex] (for the ACE band) and
     * [startDay], the day of the first reading on the line.
     */
    data class Data(val fat: BodyFatInfo?, val units: Units, val sex: Sex = Sex.UNSPECIFIED, val startDay: Day? = null)

    override val tables: Set<Table> = setOf(Table.BODY)

    override suspend fun load(env: CardEnv): Data {
        val fat = BodyTrendLoader(env.db, env.clock).bodyFat()
        val zone = env.clock.zone()
        val today = env.clock.today()
        val start = fat?.let {
            env.db.body.series(BodyKind.BODY_FAT, DayRange(today.plusDays(-LINE_DAYS), today), zone).minByOrNull { m -> m.tSec }?.let { m -> Day.of(m.tSec, zone) }
        }
        return Data(fat, env.units, env.db.settings.profile().sex, start)
    }

    override val needs: Set<DataKind> = setOf(DataKind.BODY_FAT)

    /** ACE edges (lower edges of athletes, fitness, acceptable, obese), as [GarminBands.bodyFat] uses them. */
    fun edges(sex: Sex): FloatArray = when (sex) {
        Sex.MALE -> floatArrayOf(6f, 14f, 18f, 26f)
        Sex.FEMALE -> floatArrayOf(14f, 21f, 25f, 32f)
        else -> floatArrayOf(10f, 17.5f, 21.5f, 29f)
    }

    /** The ACE range bar with [pct] marked. */
    fun range(ctx: Context, pct: Double, sex: Sex): TrendPicture.Range {
        val e = edges(sex)
        val c = LevelColors.of(ctx)
        val max = e[3] + RANGE_PAD_TOP
        return TrendPicture.Range(
            listOf(Band(e[0], c.scaleClose), Band(e[1], c.scaleGood), Band(e[2], c.scaleGood), Band(e[3], c.scaleClose), Band(max, c.scaleOff)),
            (e[0] - RANGE_PAD_BOTTOM).coerceAtLeast(0f), max, pct.toFloat(),
            e.map { it to Fmt.number(it.toDouble(), if (it % 1f == 0f) 0 else 1) },
        )
    }

    override fun small(ctx: Context, data: Data): SmallFace? {
        val f = data.fat ?: return null
        return SmallFace(
            value = Fmt.number(f.trendX100 / 100.0, 1),
            label = ctx.getString(GarminBands.bodyFat(f.trendX100 / 100.0, data.sex).name),
            unit = ctx.getString(R.string.unit_percent),
            status = GarminBands.bodyFat(f.trendX100 / 100.0, data.sex).status,
            note = change(ctx, f),
        )
    }

    /** "−0.8 % in 90 days", or null. */
    private fun change(ctx: Context, f: BodyFatInfo): String? = f.change90X100?.let {
        val sign = if (it < 0) "−" else if (it > 0) "+" else ""
        ctx.getString(R.string.small_change_90, sign + Fmt.number(abs(it) / 100.0, 1) + " " + ctx.getString(R.string.unit_percent))
    }

    override fun createSmallGraphic(ctx: Context): View = PictureFrame(ctx, small = true)

    override fun bindSmallGraphic(graphic: View, data: Data) {
        val f = data.fat
        (graphic as PictureFrame).show(f?.let { range(graphic.context, it.trendX100 / 100.0, data.sex) })
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(MeasureDetailFragment.newInstance(BodyKind.BODY_FAT, null))
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    private const val LINE_DAYS = 90
    private const val RANGE_PAD_TOP = 10f
    private const val RANGE_PAD_BOTTOM = 4f

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<Data>(CardView.inflate(parent, R.layout.card_body_fat)) {
        private var units = Units.METRIC
        private val tile = find<StatTile>(R.id.body_fat_tile)
        private val range = find<PictureFrame>(R.id.body_fat_range)
        private val line = find<PictureFrame>(R.id.body_fat_line)
        private val empty = find<EmptyState>(R.id.body_fat_empty)

        init {
            root.setOnClickListener { host.open(MeasureDetailFragment.newInstance(BodyKind.BODY_FAT, null)) }
            empty.apply {
                buttonText = context.getString(R.string.body_log)
                onButton = { MeasureEditor.log(host.fragment, BodyKind.BODY_FAT, units) }
            }
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
        }

        override fun bind(data: Data) {
            val ctx = root.context
            units = data.units
            val f = data.fat
            empty.visibility = if (f == null) View.VISIBLE else View.GONE
            tile.visibility = if (f == null) View.GONE else View.VISIBLE
            if (f == null) {
                range.show(null)
                line.show(null)
                root.contentDescription = ctx.getString(R.string.body_fat_empty)
                return
            }
            val pct = f.trendX100 / 100.0
            val band = ctx.getString(GarminBands.bodyFat(pct, data.sex).name)
            tile.value = Fmt.number(pct, 1)
            tile.unit = ctx.getString(R.string.unit_percent)
            tile.label = band
            range.show(range(ctx, pct, data.sex))
            line.show(
                if (f.spark.size >= 2) {
                    TrendPicture.Line(
                        f.spark.map { it / 100f }.toFloatArray(),
                        TrendPictures.dayLabel(ctx, data.startDay) ?: ctx.getString(R.string.d2_trend_first),
                        TrendPictures.dayLabel(ctx, Day.of(f.latestSec, ZoneId.systemDefault())),
                    )
                } else {
                    null
                },
            )
            val text = listOfNotNull(ctx.getString(R.string.body_fat_band, band), change(ctx, f)).joinToString(" · ")
            root.contentDescription = ctx.getString(R.string.body_fat_description, tile.value.toString() + tile.unit, text)
        }
    }
}
