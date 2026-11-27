/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body.cards

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import app.selfwatch.R
import app.selfwatch.body.BodyLoader
import app.selfwatch.body.HeartDetailFragment
import app.selfwatch.body.Recovery
import app.selfwatch.body.SleepDetailFragment
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.model.health.SleepStage
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.charts.LevelColors
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import java.util.Locale
import app.selfwatch.ui.R as UiR

/**
 * Recovery (card design standard): three icon tiles side by side, numbers only: last night's sleep,
 * resting heart rate and HRV. Small form: the same three icons and numbers in a row. Sleep opens Sleep;
 * resting heart rate and HRV open Heart.
 */
object RecoveryCard : Card<Recovery>("recovery", CardTab.BODY, R.string.card_recovery, R.string.card_recovery_summary) {

    override val tables: Set<Table> = setOf(Table.DAY_SUMMARY, Table.METRIC, Table.SLEEP_SESSION)

    override suspend fun load(env: CardEnv): Recovery = BodyLoader(env.db, env.clock).recovery()

    override val needs: Set<DataKind> = setOf(DataKind.SLEEP, DataKind.HRV, DataKind.RESTING_HR)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override fun small(ctx: Context, data: Recovery): SmallFace? {
        if (data.sleepMin == null && data.restingHr == null && data.hrvMs == null) return null
        val note = listOfNotNull(
            data.restingHr?.let { ctx.getString(R.string.small_hr_resting, it.toString()) },
            data.hrvMs?.let { ctx.getString(R.string.small_recovery_hrv, it.toString()) },
        ).joinToString(" · ").ifEmpty { null }
        return SmallFace(
            value = data.sleepMin?.let { duration(ctx, it) } ?: ctx.getString(R.string.dash),
            label = ctx.getString(R.string.small_recovery_sleep),
            note = note,
        )
    }

    /** The three tiles' icons, in order: sleep, resting heart rate, HRV. */
    private val ICONS = intArrayOf(R.drawable.body1_ic_sleep, R.drawable.body1_ic_heart, R.drawable.body1_ic_hrv)

    /** The icon colors: the deep-sleep color, the heart orange and the HRV primary. */
    fun iconColors(ctx: Context): IntArray {
        val p = ChartPalette.of(ctx)
        return intArrayOf(p.stage(SleepStage.DEEP), LevelColors.of(ctx).stressMedium, p.primary)
    }

    override fun createSmallGraphic(ctx: Context): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        val colors = iconColors(ctx)
        for (i in ICONS.indices) {
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            cell.addView(icon(ctx, ICONS[i], colors[i]), LinearLayout.LayoutParams(Views.dp(ctx, ICON_SMALL_DP), Views.dp(ctx, ICON_SMALL_DP)))
            cell.addView(
                Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body_Number).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    maxLines = 1
                },
                Views.matchWrap(ctx, 2),
            )
            addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    override val smallGraphicShowsValue: Boolean = true

    override fun bindSmallGraphic(graphic: View, data: Recovery) {
        val ctx = graphic.context
        val dash = ctx.getString(R.string.dash)
        val values = listOf(
            data.sleepMin?.let { String.format(Locale.getDefault(), "%d:%02d", it / MINUTES, it % MINUTES) } ?: dash,
            data.restingHr?.toString() ?: dash,
            data.hrvMs?.toString() ?: dash,
        )
        val row = graphic as LinearLayout
        for (i in 0 until row.childCount) ((row.getChildAt(i) as LinearLayout).getChildAt(1) as TextView).text = values[i]
    }

    private fun icon(ctx: Context, @DrawableRes res: Int, color: Int): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun duration(ctx: Context, min: Int): String = Fmt.duration(min * 60L, Locale.getDefault(), FmtLabels.from(ctx))

    private const val MINUTES = 60
    private const val ICON_SMALL_DP = 20

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Recovery> = Holder(host, parent)

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<Recovery>(CardView.inflate(parent, R.layout.card_body_recovery)) {
        private val cards = listOf(find<View>(R.id.sleep_card), find<View>(R.id.rest_hr_card), find<View>(R.id.hrv_card))
        private val tiles = listOf(find<StatTile>(R.id.sleep_tile), find<StatTile>(R.id.rest_hr_tile), find<StatTile>(R.id.hrv_tile))

        init {
            cards[0].setOnClickListener { host.open(SleepDetailFragment()) }
            cards[1].setOnClickListener { host.open(HeartDetailFragment()) }
            cards[2].setOnClickListener { host.open(HeartDetailFragment()) }
            val colors = iconColors(root.context)
            listOf(R.id.sleep_icon, R.id.rest_hr_icon, R.id.hrv_icon).forEachIndexed { i, id ->
                find<ImageView>(id).imageTintList = ColorStateList.valueOf(colors[i])
            }
        }

        override fun bind(data: Recovery) {
            val ctx = root.context
            val dash = ctx.getString(R.string.dash)
            val (sleep, hr, hrv) = tiles
            sleep.value = data.sleepMin?.let { duration(ctx, it) } ?: dash
            hr.value = data.restingHr?.toString() ?: dash
            hr.unit = if (data.restingHr != null) ctx.getString(R.string.unit_bpm) else null
            hrv.value = data.hrvMs?.toString() ?: dash
            hrv.unit = if (data.hrvMs != null) ctx.getString(R.string.unit_ms) else null
            val names = listOf(R.string.body_sleep, R.string.body_rest_hr, R.string.body_hrv)
            tiles.forEachIndexed { i, t ->
                cards[i].contentDescription = ctx.getString(R.string.body1_tile_description, ctx.getString(names[i]), listOfNotNull(t.value, t.unit).joinToString(" "))
            }
        }
    }
}
