/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.cards

import android.content.Context
import android.view.View
import android.view.ViewGroup
import app.selfwatch.cards.PictureFrame
import app.selfwatch.cards.TrendPicture
import app.selfwatch.R
import app.selfwatch.activity.Sports
import app.selfwatch.activity.WorkoutDetailFragment
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardStatusColors
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.model.time.Day
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.FmtLabels
import java.time.ZoneId
import java.util.Locale

/**
 * Workout fuel (C22): for the latest workout of an hour or more on the day shown or the day before,
 * the carbs logged from 30 minutes before it to 30 minutes after, per hour, against 30–60 g/h (up to
 * 90 g/h beyond 2.5 h). Without such a workout it says quietly what it will show. Opens the workout.
 */
object WorkoutFuelCard : Card<WorkoutFuel>(
    "workout_fuel",
    CardTab.FOOD,
    R.string.card_workout_fuel,
    R.string.card_workout_fuel_summary,
) {
    override val tables: Set<Table> = setOf(Table.DIARY, Table.WORKOUT)

    override suspend fun load(env: CardEnv): WorkoutFuel = FoodCardLoader(env.db, env.clock).workoutFuel(env.day)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override fun small(ctx: Context, data: WorkoutFuel): SmallFace? {
        val w = data.workout ?: return null
        val low = data.band.first
        return SmallFace(
            value = Fmt.number(data.gPerHour.toLong()),
            label = ctx.getString(R.string.small_aim_band, Fmt.number(low.toLong()), Fmt.number(data.band.last.toLong())),
            unit = ctx.getString(R.string.food_card_unit_g_per_h),
            status = CardStatusColors.ofProgress(data.gPerHour.toDouble(), low.toDouble()),
            note = ctx.getString(
                R.string.card_line_pair,
                ctx.getString(Sports.label(w.sport)),
                Fmt.duration(w.durationSec.toLong(), Locale.getDefault(), FmtLabels.from(ctx)),
            ),
            progress = if (low > 0) data.gPerHour.toFloat() / low else null,
        )
    }

    /** Carbs per hour on a range bar with the aim band marked; null without a workout. */
    fun picture(ctx: Context, data: WorkoutFuel): TrendPicture? {
        if (data.workout == null) return null
        val low = data.band.first.toDouble()
        val high = data.band.last.toDouble()
        return FoodPictures.target(
            ctx, data.gPerHour.toDouble(), low, high,
            listOf(low.toFloat() to Fmt.number(low.toLong()), high.toFloat() to Fmt.number(high.toLong())),
        )
    }

    override fun createSmallGraphic(ctx: Context): View = PictureFrame(ctx, small = true)

    override fun bindSmallGraphic(graphic: View, data: WorkoutFuel) {
        (graphic as PictureFrame).show(picture(graphic.context, data))
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<WorkoutFuel> = Holder(host, parent)

    private class Holder(private val host: CardHost, parent: ViewGroup) :
        FoodMetricView<WorkoutFuel>(parent, R.id.food_workout_fuel_card, R.string.card_workout_fuel) {

        private var workoutId: Long? = null

        init {
            card.setOnClickListener { workoutId?.let { host.open(WorkoutDetailFragment.newInstance(it)) } }
        }

        override fun bind(data: WorkoutFuel) {
            val w = data.workout
            workoutId = w?.id
            card.isClickable = w != null
            if (w == null) {
                showEmpty(ctx.getString(R.string.workout_fuel_none))
                return
            }
            val labels = FmtLabels.from(ctx)
            tile.value = Fmt.number(data.gPerHour.toLong())
            tile.unit = ctx.getString(R.string.food_card_unit_g_per_h)
            val aim = ctx.getString(R.string.workout_fuel_aim, Fmt.number(data.band.first.toLong()), Fmt.number(data.band.last.toLong()))
            tile.label = aim
            showContent(picture(ctx, data))
            val what = ctx.getString(
                R.string.workout_fuel_workout,
                ctx.getString(Sports.label(w.sport)),
                Fmt.duration(w.durationSec.toLong(), Locale.getDefault(), labels),
                Fmt.day(ctx, Day.of(w.startSec, ZoneId.systemDefault()).yyyymmdd),
            )
            val carbs = if (data.entries == 0) {
                ctx.getString(R.string.workout_fuel_nothing)
            } else {
                ctx.getString(R.string.workout_fuel_carbs, Fmt.number(data.carbsG.toLong()))
            }
            val cov = if (data.unknown > 0) {
                ctx.resources.getQuantityString(R.plurals.workout_fuel_coverage, data.entries, data.entries - data.unknown, data.entries)
            } else {
                null
            }
            card.contentDescription = ctx.getString(
                R.string.food_card_description,
                ctx.getString(R.string.card_workout_fuel),
                "${tile.value} ${tile.unit}, $aim",
                listOfNotNull(what, carbs, cov).joinToString(". "),
            )
        }
    }
}
